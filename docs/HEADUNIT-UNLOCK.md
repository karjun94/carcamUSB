# Unlocking app installs on an iMotor head unit

This guide is for the Android head unit fitted to an Isuzu D-Max. It may also apply to other cars with **iMotor** firmware.

The unit tested:

| | |
|---|---|
| Model | `SL900CA_DTSSKL` |
| Chip | Rockchip RK3326 (`RK3326_002_...`) |
| Android | 11, 32-bit (`armeabi-v7a`) |
| Firmware | iMotor (`com.imotor.*` system apps), MCU `IAP90` |

Out of the box, every APK install fails, both from the file manager and over ADB:

```
Failure [INSTALL_FAILED_INTERNAL_ERROR: Install not allowed!]
```

The "Unknown sources" setting has no effect. The block comes from iMotor's own settings store, not from Android's.

> **Use at your own risk.** This only changes a settings value, with no root and no firmware flashing, and it can be undone. It is still a dealer/vendor restriction, so check your warranty. Don't flash firmware from the internet onto these units: a wrong MCU or system image can brick the screen or break the CAN bus and steering controls.

## 1. Turn on wireless ADB
1. **Developer options:** Settings → About device → tap **Build number** 7 times. When it asks for a password, use **today's date followed by `OOO`**. The exact date format may vary between firmware versions.
2. Connect the head unit and your computer to the **same Wi-Fi** (a phone or laptop hotspot works).
3. Developer options → turn on **USB debugging** and **Wireless debugging**. Note the **IP address and port** shown under Wireless debugging.
4. On the computer, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) and run:
   ```bash
   adb connect <ip>:<port>
   ```
   Accept the **"Allow debugging?"** prompt on the head unit and tick **Always allow**.

For reference, the factory settings menu (Car settings → Factory) uses password `7890`. You don't need it for this guide.

## 2. Run the setup script
From the root of this repo:

```bash
scripts/headunit-setup.sh <ip>:<port>
```

To also install D-Max Cam and grant its permissions:

```bash
scripts/headunit-setup.sh <ip>:<port> app/build/outputs/apk/release/app-release.apk
```

The script:
1. **Allows app installs** by setting `clw_install_apps` to `1` (details below).
2. Optionally **installs D-Max Cam** and grants `READ_LOGS`, `CAMERA` and `SYSTEM_ALERT_WINDOW`, which only ADB can grant.
3. **Pins ADB to port 5555**, so you can reconnect with `adb connect <ip>:5555` instead of looking up a new port each time. It hasn't been confirmed yet that this survives a restart. If it doesn't, use the Wireless debugging port again.

To **block installs again**:

```bash
scripts/headunit-setup.sh <ip>:<port> --lock
```

## 3. Doing it by hand
```bash
# Allow installs. Note: `content update` silently does nothing here; `insert` overwrites the value.
adb shell "content insert --uri content://isettings/global --bind name:s:clw_install_apps --bind value:s:1"

# Check it (value=1 means allowed)
adb shell "content query --uri content://isettings/global --where \"name='clw_install_apps'\""

# Fixed ADB port (optional)
adb shell setprop persist.adb.tcp.port 5555
adb tcpip 5555
```

On the tested unit, the `clw_install_apps` value **survived a car restart**.

## How it was found
- `logcat` during a failed install shows `PackageManager: Cannot install apps` followed by `Install not allowed!`. Neither message is from stock Android.
- `services.jar` and `com.imotor.framework.jar` contain the key names `INSTALL_APPS = "clw_install_apps"` and `imt_install_apps_whitelist`, and the provider URIs `content://isettings/{global,system,secure}`.
- The `com.imotor.providers.settings` package provides `content://isettings`. Its `global` table had `clw_install_apps = 0`.
- Writing `imt_install_apps_whitelist` into Android's normal `settings` had no effect.

## After unlocking
- **App store:** [Aurora Store](https://f-droid.org/packages/com.aurora.store/) from F-Droid works with anonymous login. Old Aurora 3.x builds fail to log in, so use the current version.
- **No Google Play Services** on this unit. Official YouTube and YouTube Music won't run. [NewPipe](https://f-droid.org/packages/org.schabi.newpipe/) and [SimpMusic](https://f-droid.org/packages/com.maxrave.simpmusic/) work.
- **Pick 32-bit builds.** Choose `armeabi-v7a` or universal APKs, never arm64-only.
- **Steering-wheel keys** are injected by the MCU, so accessibility-based key mappers (Key Mapper and similar) don't see them. They do appear in `logcat -b system` under the `InputPolicy` tag, which is how D-Max Cam detects them.
