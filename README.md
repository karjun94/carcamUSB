# D-Max Cam

A small, ad-free USB (UVC) camera app for the Android head unit in an Isuzu D-Max (tested on a 2024 1.9 Auto Plus 4x2). Its main job is a front camera that you open and close with a steering-wheel button.

Built and tested on an **iMotor** head unit (Rockchip RK3326, Android 11, 32-bit, model `SL900CA_DTSSKL`). The steering-button part depends on how this firmware logs keys, so on other units it may need changes.

## Features
- Full-screen live picture from the first USB webcam. It prefers 1280×720, then 640×480.
- **Steering-button toggle.** One press opens the camera, the next press closes it. Call is the default button.
- **Settings** (separate "D-Max Cam Settings" launcher icon):
  - Pick the button from a list, or tap **Learn** and press any steering button.
  - Parking guide lines on or off: red, yellow and green distance bands.
  - **Adjust guide lines.** Drag each line's top and bottom ends separately to match the bumper edge and a camera that's off-centre or tilted. Saved on the unit, with Reset.
  - Mirror the picture left ↔ right.
- Tap the screen to close the camera.

## How the steering button works
On this unit the MCU injects steering-wheel keys straight into the window manager. Accessibility-based key mappers (Key Mapper, etc.) never see them. The vendor's `InputPolicy` does log every key to the **system** log buffer:

```
D InputPolicy: interceptKeyBeforeQueueing event = KeyEvent { action=ACTION_UP, keyCode=KEYCODE_CALL, ... }
```

`KeyWatchService` runs as a foreground service, follows that log with `logcat -b system`, and opens or closes `CameraActivity` when the chosen key comes up.

## Build
Requires JDK 17 and the Android SDK (platform 34).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It's signed with the debug key.

## Install on the head unit
The unit needs wireless ADB. iMotor firmware also **blocks all app installs by default**: see **[docs/HEADUNIT-UNLOCK.md](docs/HEADUNIT-UNLOCK.md)** for how to unlock that and get ADB connected.

Then one script unlocks installs, installs the app, grants the permissions only ADB can give, and pins ADB to port 5555:

```bash
scripts/headunit-setup.sh <ip>:<port> app/build/outputs/apk/release/app-release.apk
```

To do it by hand instead:

```bash
adb install -r app-release.apk
adb shell pm grant com.karjun.dmaxcam android.permission.READ_LOGS
adb shell pm grant com.karjun.dmaxcam android.permission.CAMERA
adb shell appops set com.karjun.dmaxcam SYSTEM_ALERT_WINDOW allow
```

- `READ_LOGS` lets the app see the steering keys.
- `SYSTEM_ALERT_WINDOW` lets it bring the camera to the front from the background.

Open the app once after installing so the button watcher starts.

## Choosing a camera
Any **USB UVC** camera with **MJPEG** works. On USB 2.0 that's needed for smooth 720p. A quick check: if it shows up in the Windows Camera app with no driver, it will work here. Tested with a USB microscope camera. For the front of the car, pick a waterproof (IP67+) wide-angle (120–170°) camera with a 3–5 m cable. Analog AHD/CVBS reverse cameras and Wi-Fi cameras won't work.

## Known limits
- The guide lines are straight lines you place by hand. They don't follow steering angle.
- The firmware may block the app from starting at boot. If the button stops working after a restart, open the app once.
- The button's own function still runs (Call opens the dialer). The camera is opened on top of it.

## Credits
USB camera support comes from [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) (Apache 2.0).
