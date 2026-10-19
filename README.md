# CarHome and D-Max Cam

Two ad-free Android apps for the head unit in an Isuzu D-Max, tested on a 2024 1.9 Auto Plus 4x2. Both also run on an **old Android phone used as a head unit**.

| App | What it is |
|---|---|
| **CarHome** (`launcher/`) | A CarPlay-style **home screen**: clock, music, speed, compass, a **built-in USB front camera**, and swipeable app pages. |
| **D-Max Cam** (`app/`) | The **camera on its own**, for people who keep their existing launcher. |
| `camcore/` | Shared camera code used by both: steering-button watcher, guide lines, mirror, camera settings. |

Install one or both. If both are installed, **CarHome handles the steering button**, so one press never opens two cameras.

Both apps are free and ad-free. If they're useful to you, you can [buy me a coffee on Ko-fi](https://ko-fi.com/chopsign) ☕

Built and tested on an **iMotor** head unit (Rockchip RK3326, Android 11, 32-bit, model `SL900CA_DTSSKL`), and on a Pixel 3 phone.

## CarHome
- **Dashboard (page 1)**
  - Big clock and date.
  - Now playing: cover art, a scrolling title, and play/pause/skip for any music app. Uses notification access.
  - Six shortcut tiles. Long-press a tile to choose its app.
- **Left card: tap the swap button to cycle**
  - **Speed** from GPS.
  - **Camera**: a live USB camera picture.
  - **Compass**: GPS heading while moving, otherwise the compass sensor.
- **Full-screen camera**: tap the camera card, or press the steering button. It's the same live view, with:
  - parking guide lines and mirror (from camera settings)
  - a ⚙ button for camera settings
  - Back or a tap to close
- **Swipe left for app pages**: 4×2 on 16:9 screens, 4×3 on 4:3. Long-press an app to put it on a tile or **hide** it. Hidden apps can be brought back from the "Hidden apps" icon.
- **Matching icons** for the head unit's built-in apps (Radio, Music, Settings and others), letter tiles for apps without an icon, and separate CarPlay / Android Auto icons. "Choose default Home app" button in CarHome settings.
- **Automatic day/night** from sunrise and sunset at your location, offline. Or force Day or Night.
- **Fits the screen shape**: wide, 4:3, and nearly square, where the cards stack.

Set it as the default with **Settings → Apps → Default apps → Home app → CarHome**.

## D-Max Cam
- Full-screen USB camera with guide lines, mirror, and the steering-button toggle.
- "D-Max Cam Settings" icon: choose or learn the button, guide lines, mirror, and adjust guide lines.

## Camera settings (both apps)
- **Steering button**: pick it from a list, or tap **Learn** and press it. One press opens the camera, the next closes it.
- **Parking guide lines**: drag each line's top and bottom ends separately, to match the bumper edge and a camera that's off-centre or tilted.
- **Mirror**: flips the picture left ↔ right.

## How the steering button works
On this unit the MCU injects steering-wheel keys straight into the window manager. Accessibility-based key mappers never see them. The vendor's `InputPolicy` does log every key to the **system** log buffer:

```
D InputPolicy: interceptKeyBeforeQueueing event = KeyEvent { action=ACTION_UP, keyCode=KEYCODE_CALL, ... }
```

The shared `KeyWatchService` follows that log with `logcat -b system`. It only runs where `READ_LOGS` has been granted, so on a phone it simply stays off.

## Build
Requires JDK 17 and the Android SDK (platform 34).

```bash
./gradlew assembleRelease
```

The APKs are written to `launcher/build/outputs/apk/release/` and `app/build/outputs/apk/release/`. They're signed with the debug key.

## Install on the head unit
iMotor firmware **blocks app installs by default**. **[docs/HEADUNIT-UNLOCK.md](docs/HEADUNIT-UNLOCK.md)** explains how to unlock that and connect ADB. The setup script unlocks installs, installs D-Max Cam with its permissions, and pins ADB to port 5555:

```bash
scripts/headunit-setup.sh <ip>:<port> app/build/outputs/apk/release/app-release.apk
```

For **CarHome**, install it and grant the same permissions:

```bash
adb install -r launcher/build/outputs/apk/release/launcher-release.apk
adb shell pm grant com.karjun.dmaxlauncher android.permission.READ_LOGS
adb shell pm grant com.karjun.dmaxlauncher android.permission.CAMERA
adb shell pm grant com.karjun.dmaxlauncher android.permission.ACCESS_FINE_LOCATION
adb shell appops set com.karjun.dmaxlauncher SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener com.karjun.dmaxlauncher/com.karjun.dmaxlauncher.MediaListener
```

- `READ_LOGS` lets it see the steering keys.
- `SYSTEM_ALERT_WINDOW` lets it bring the camera to the front from the background.
- The notification listener is for the now-playing card.

## Using an old phone as a head unit
Install CarHome and set it as the Home app. Then:
- **Camera:** plug a USB camera into the phone through an **OTG adapter**.
- **Permissions:** the app asks for location and camera, and taps on the music card take you to notification access.
- **Steering button:** needs a head unit that logs its keys, so it isn't available on a phone.

## Choosing a camera
- **Any USB UVC camera with MJPEG works.** On USB 2.0, MJPEG is needed for smooth 720p.
- **Quick check:** if the camera shows up in the Windows Camera app with no driver, it will work here. Tested with a USB microscope camera.
- **For the front of the car:** pick a waterproof (IP67+), roughly 160° camera with a 3–5 m cable.
- **Won't work:** analog AHD/CVBS reverse cameras and Wi-Fi cameras.

## Known limits
- The guide lines are straight lines you place by hand. They don't follow steering angle.
- The firmware may block apps from starting at boot. If the steering button stops working after a restart, open the app once.
- The button's own function still runs (Call opens the dialer). The camera is opened on top of it.
- Compass heading from the sensor depends on the device. Many head units have none, so GPS heading is used while driving.

## Credits
USB camera support comes from [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) (Apache 2.0).

## Support
If CarHome or D-Max Cam is useful in your car, support the project at **[ko-fi.com/chopsign](https://ko-fi.com/chopsign)**. Thank you!
