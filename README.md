# DaBYDlink

Show your Android phone — YouTube, Google Maps, anything — on a BYD car screen (DiLink, including Chinese-market versions), wirelessly, with touch control from the car screen.

Two apps:

| App | Installs on | Does |
|---|---|---|
| **DaBYDlink** (`phone/`) | Your Android phone (Android 10+) | Captures the screen, encodes H.264, streams it over Wi-Fi, replays car-screen touches |
| **DaBYDlink Car** (`car/`) | The BYD DiLink screen (Android 9+) | Announces itself on Wi-Fi, receives and decodes the video, sends touches back |

## Get the APKs

This project needs the Android SDK to build. Easiest way, no setup on your computer:

1. Create a new repository on GitHub and upload this folder (keep the `.github` folder).
2. Open the repository's **Actions** tab → **Build APKs** → wait ~5 minutes.
3. Download **DaBYDlink-apks** from the finished run. It contains `DaBYDlink-phone.apk` and `DaBYDlink-car.apk`.

Or open the folder in Android Studio and run **Build → Build APK(s)**.

## Install

- **Phone:** open `DaBYDlink-phone.apk` and allow installing from unknown sources.
- **Car:** copy `DaBYDlink-car.apk` to a USB stick and open it with the DiLink file manager. On firmware that blocks this, install it over ADB (`adb install DaBYDlink-car.apk`) after enabling developer mode/ADB on the head unit (method varies by DiLink version).

## Use

1. Put the phone and car on the same Wi-Fi — easiest: turn on the phone hotspot (5 GHz if possible) and join it from the car.
2. Open **DaBYDlink Car** on the car screen. It shows the car address.
3. Open **DaBYDlink** on the phone, tap your car (or type the address), accept the screen-sharing prompt.
4. Turn the phone sideways for full-screen YouTube/Maps.
5. Pair the phone with the car over Bluetooth once — sound plays through the car speakers that way.

### Touch control
On the phone, tap **Turn on** next to *Touch control from the car* and enable DaBYDlink in Accessibility.
On Android 13+, sideloaded apps get "Restricted setting": open **App info → ⋮ → Allow restricted settings** first.

The car screen has a dock with **Back, Home, Apps, Exit**. Taps, long-presses, swipes and two-finger pinch work; a drag is replayed on the phone right after you lift your finger.

### Driving guard
With location allowed, video apps (YouTube, Netflix, TikTok, …) are hidden on the car screen while the car moves faster than ~10 km/h. Maps, music and calls keep working. You can turn it off in Settings, but watching video while driving is dangerous and illegal in most places.

## How it works

```
Phone                                         Car (DiLink)
MediaProjection → VirtualDisplay              UDP beacon :47100 ──► phone finds car
  → MediaCodec H.264 encoder                  TCP :47101 server
  → TCP client ──── video frames ──────────►  MediaCodec decoder → SurfaceView
AccessibilityService ◄── touch / back / home ─ touch on video
GPS speed + foreground app ── state ───────►  "video hidden" overlay
```

- Latency control: if the network falls behind, queued frames are dropped and the stream restarts from a fresh key frame instead of lagging.
- Rotation: the phone re-sizes the stream when you rotate; the car keeps the picture aspect-correct.
- Wire format is in `shared/kotlin/com/dabydlink/shared/Protocol.kt` (used by both apps).

## Limits (v1)

- Phone screen must stay on (the app keeps it on, dimmed, while mirroring).
- Some apps (Netflix and other DRM video) block screen capture and show black — that's the app's choice, not a bug.
- Audio goes over Bluetooth, not Wi-Fi.
- iPhone is not supported (iOS doesn't allow this kind of screen capture + remote touch).
