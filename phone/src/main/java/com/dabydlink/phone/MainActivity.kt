package com.dabydlink.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dabydlink.shared.Protocol

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scanner: CarScanner

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var btnStop: Button
    private lateinit var carList: LinearLayout
    private lateinit var carEmpty: TextView
    private lateinit var ipInput: EditText
    private lateinit var touchState: TextView
    private lateinit var btnTouch: Button
    private lateinit var touchHint: TextView
    private lateinit var btnAppInfo: TextView
    private lateinit var locationState: TextView
    private lateinit var btnLocation: Button
    private lateinit var notifState: TextView
    private lateinit var btnNotif: Button

    /** Car chosen by the user, waiting for the screen-capture permission result. */
    private var pending: CarScanner.Car? = null
    private var cars: List<CarScanner.Car> = emptyList()

    private val stateListener: () -> Unit = { renderStatus(); renderCars() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        prefs = Prefs(this)
        scanner = CarScanner(this) { list ->
            cars = list
            renderCars()
        }

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        btnStop = findViewById(R.id.btnStop)
        carList = findViewById(R.id.carList)
        carEmpty = findViewById(R.id.carEmpty)
        ipInput = findViewById(R.id.ipInput)
        touchState = findViewById(R.id.touchState)
        btnTouch = findViewById(R.id.btnTouch)
        touchHint = findViewById(R.id.touchHint)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        locationState = findViewById(R.id.locationState)
        btnLocation = findViewById(R.id.btnLocation)
        notifState = findViewById(R.id.notifState)
        btnNotif = findViewById(R.id.btnNotif)

        ipInput.setText(prefs.lastIp)

        btnStop.setOnClickListener {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
        }
        findViewById<Button>(R.id.btnConnectIp).setOnClickListener { connectManual() }
        btnTouch.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find DaBYDlink under Installed / Downloaded apps", Toast.LENGTH_LONG).show()
        }
        btnAppInfo.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        btnLocation.setOnClickListener {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(POST_NOTIFICATIONS), REQ_NOTIF)
        }
        if (Build.VERSION.SDK_INT < 33) findViewById<View>(R.id.notifRow).visibility = View.GONE

        val group = findViewById<RadioGroup>(R.id.qualityGroup)
        group.check(
            when (prefs.quality) {
                0 -> R.id.qSmooth
                2 -> R.id.qSharp
                else -> R.id.qBalanced
            },
        )
        group.setOnCheckedChangeListener { _, id ->
            prefs.quality = when (id) {
                R.id.qSmooth -> 0
                R.id.qSharp -> 2
                else -> 1
            }
        }
        val guard = findViewById<Switch>(R.id.guardSwitch)
        guard.isChecked = prefs.drivingGuard
        guard.setOnCheckedChangeListener { _, checked ->
            prefs.drivingGuard = checked
            renderSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        MirrorState.addListener(stateListener)
        scanner.start()
        renderSetup()
        renderStatus()
        renderCars()
    }

    override fun onPause() {
        MirrorState.removeListener(stateListener)
        scanner.stop()
        super.onPause()
    }

    // ---- connect flow ----

    private fun connectManual() {
        val host = ipInput.text.toString().trim()
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) {
            ipInput.error = "Enter the address shown on the car screen"
            return
        }
        prefs.lastIp = host
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(ipInput.windowToken, 0)
        connect(CarScanner.Car(host, host, Protocol.STREAM_PORT, 0))
    }

    private fun connect(car: CarScanner.Car) {
        if (!TouchService.isEnabled(this)) {
            Toast.makeText(this, "Tip: turn on touch control to use the car screen as a touchscreen", Toast.LENGTH_LONG).show()
        }
        pending = car
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val car = pending ?: return
        pending = null
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing is needed to show your phone on the car", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(MirrorService.startIntent(this, resultCode, data, car.host, car.port, car.name))
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

    // ---- rendering ----

    private fun renderStatus() {
        val color: Int
        when (MirrorState.phase) {
            MirrorState.Phase.IDLE -> {
                color = getColor(R.color.idle)
                statusTitle.text = "Not connected"
                statusDetail.text = MirrorState.message.ifEmpty { "Pick your car below to start." }
                btnStop.visibility = View.GONE
            }
            MirrorState.Phase.CONNECTING -> {
                color = getColor(R.color.warn)
                statusTitle.text = "Connecting…"
                statusDetail.text = "Reaching ${MirrorState.carName}"
                btnStop.visibility = View.VISIBLE
            }
            MirrorState.Phase.STREAMING -> {
                color = getColor(R.color.accent)
                statusTitle.text = "On the car screen"
                statusDetail.text = if (MirrorState.videoBlocked) {
                    "Connected to ${MirrorState.carName}. Video is hidden while the car is moving."
                } else {
                    "Connected to ${MirrorState.carName}. Open YouTube or Maps and turn the phone sideways."
                }
                btnStop.visibility = View.VISIBLE
            }
        }
        (statusDot.background.mutate() as? GradientDrawable)?.setColor(color)
    }

    private fun renderCars() {
        carList.removeAllViews()
        carEmpty.visibility = if (cars.isEmpty()) View.VISIBLE else View.GONE
        val busy = MirrorState.phase != MirrorState.Phase.IDLE
        val dp = resources.displayMetrics.density
        for ((i, car) in cars.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_car_row)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                isEnabled = !busy
                alpha = if (busy) 0.5f else 1f
                setOnClickListener { connect(car) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = car.name
                setTextColor(getColor(R.color.text))
                textSize = 16f
                paint.isFakeBoldText = true
            })
            texts.addView(TextView(this).apply {
                text = car.host
                setTextColor(getColor(R.color.muted))
                textSize = 13f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (MirrorState.phase == MirrorState.Phase.STREAMING && MirrorState.carName == car.name) "Connected" else "Connect"
                setTextColor(getColor(R.color.accent))
                textSize = 15f
                paint.isFakeBoldText = true
            })
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = (10 * dp).toInt()
            carList.addView(row, lp)
        }
    }

    private fun renderSetup() {
        val touchOn = TouchService.isEnabled(this)
        touchState.text = if (touchOn) "On — the car screen works as a touchscreen" else "Off — the car will only show the picture"
        btnTouch.text = if (touchOn) "Settings" else getString(R.string.turn_on)
        touchHint.visibility = if (touchOn) View.GONE else View.VISIBLE
        btnAppInfo.visibility = if (touchOn) View.GONE else View.VISIBLE

        val locOn = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationState.text = when {
            !prefs.drivingGuard -> "Driving guard is turned off in Settings"
            locOn -> "Allowed — video hides while the car is moving"
            else -> "Needed to know when the car is moving"
        }
        btnLocation.visibility = if (locOn || !prefs.drivingGuard) View.GONE else View.VISIBLE

        if (Build.VERSION.SDK_INT >= 33) {
            val notifOn = checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            notifState.text = if (notifOn) "Allowed" else "Shows a Stop button while mirroring"
            btnNotif.visibility = if (notifOn) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private const val REQ_CAPTURE = 10
        private const val REQ_LOCATION = 11
        private const val REQ_NOTIF = 12
        private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
