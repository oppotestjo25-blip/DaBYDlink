package com.dabydlink.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Foreground service that owns the screen capture and the connection to the car.
 * Also runs the driving guard: while the car is moving, video apps are hidden on the car screen.
 */
class MirrorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var session: StreamSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var guardEnabled = false
    private var moving = false
    private var lastFixAt = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("Stopped")
                return START_NOT_STICKY
            }
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return stopSelf()
        val port = intent.getIntExtra(EXTRA_PORT, 47101)
        val carName = intent.getStringExtra(EXTRA_NAME) ?: host
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val prefs = Prefs(this)
        guardEnabled = prefs.drivingGuard && hasLocationPermission()

        // Must be in the foreground before asking for the projection (Android 14+).
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (guardEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting to $carName…"), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            guardEnabled = false
            startForeground(
                NOTIFICATION_ID, buildNotification("Connecting to $carName…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }

        // Replace any previous connection without letting its callbacks tear down the new one.
        val oldSession = session
        session = null
        oldSession?.stop("Replaced")
        val oldProjection = projection
        projection = null
        try { oldProjection?.stop() } catch (_: Exception) {}

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = if (data != null) mpm.getMediaProjection(resultCode, data) else null
        if (proj == null) {
            shutdown("Screen sharing permission was not granted")
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { if (projection === proj) shutdown("Screen sharing stopped") }
            }
        }, main)

        MirrorState.set(MirrorState.Phase.CONNECTING, carName)
        var self: StreamSession? = null
        val s = StreamSession(this, proj, host, port, prefs.bitrate, object : StreamSession.Events {
            override fun onConnected(carName: String) {
                main.post {
                    if (session !== self) return@post
                    MirrorState.set(MirrorState.Phase.STREAMING, carName)
                    notify(buildNotification("Showing your screen on $carName"))
                    evaluateGuard()
                }
            }

            override fun onClosed(reason: String) {
                main.post { if (session === self) shutdown(reason) }
            }
        })
        self = s
        session = s
        s.start()

        acquireWakeLock()
        if (guardEnabled) startLocation()
    }

    // ---- driving guard ----

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        try {
            val lm = getSystemService(LocationManager::class.java)
            locationManager = lm
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            guardEnabled = false
        } catch (e: Exception) {
            Log.w(TAG, "GPS unavailable", e)
        }
    }

    private fun onLocation(l: Location) {
        if (!l.hasSpeed()) return
        lastFixAt = SystemClock.elapsedRealtime()
        val kmh = l.speed * 3.6f
        val nowMoving = if (moving) kmh > STOP_KMH else kmh > MOVE_KMH
        if (nowMoving != moving) {
            moving = nowMoving
            evaluateGuard()
        }
    }

    fun onForegroundAppChanged() {
        main.post { evaluateGuard() }
    }

    private fun evaluateGuard() {
        val pkg = TouchService.foregroundPackage
        val blocked = guardEnabled && moving && pkg != null && pkg in VIDEO_APPS
        session?.sendState(blocked)
        MirrorState.setVideoBlocked(blocked)
    }

    // ---- helpers ----

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        // Keeps the phone screen on (dimmed) so the mirrored image doesn't go black.
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "DaBYDlink:mirror").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun shutdown(reason: String) {
        session?.stop(reason)
        session = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        locationManager = null
        moving = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        MirrorState.set(MirrorState.Phase.IDLE, message = reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (session != null) shutdown("Stopped")
        instance = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Car mirroring", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DaBYDlink")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "DaBYDlink"
        const val ACTION_START = "com.dabydlink.phone.START"
        const val ACTION_STOP = "com.dabydlink.phone.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 1
        private const val MOVE_KMH = 10f
        private const val STOP_KMH = 5f

        /** Apps whose video is hidden on the car screen while driving. */
        val VIDEO_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "tv.twitch.android.app",
            "com.shahid.android",
            "com.osn.go",
            "com.vimeo.android.videoapp",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
        )

        @Volatile var instance: MirrorService? = null
            private set

        fun startIntent(context: Context, resultCode: Int, data: Intent, host: String, port: Int, name: String) =
            Intent(context, MirrorService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_NAME, name)
    }
}
