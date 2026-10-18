#!/usr/bin/env bash
# Set up an iMotor (RK3326, Android 11) head unit over wireless ADB:
#   - allow sideloaded app installs (vendor switch clw_install_apps)
#   - pin ADB to a fixed TCP port so it stops changing
#   - optionally install D-Max Cam and grant the permissions only ADB can give
#
# Usage:
#   scripts/headunit-setup.sh <ip>:<port> [path/to/app-release.apk]
#   scripts/headunit-setup.sh <ip>:<port> --lock      # block installs again
#
# The first connection needs the port shown under Developer options >
# Wireless debugging. Runs in Git Bash on Windows as well as Linux/macOS.
set -euo pipefail

FIXED_PORT=5555
PKG=com.karjun.dmaxcam

if [ $# -lt 1 ]; then
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

TARGET=$1
HOST=${TARGET%%:*}
APK=${2:-}

command -v adb >/dev/null || { echo "adb not found: install Android platform-tools and add it to PATH" >&2; exit 1; }

echo "==> Connecting to $TARGET"
adb connect "$TARGET" | grep -q "connected" || { echo "Could not connect to $TARGET" >&2; exit 1; }
sh_() { adb -s "$TARGET" shell "$@"; }

echo "==> Device: Android $(sh_ getprop ro.build.version.release | tr -d '\r'), $(sh_ getprop ro.product.cpu.abi | tr -d '\r')"

if [ "$APK" = "--lock" ]; then
    echo "==> Blocking app installs again (clw_install_apps=0)"
    sh_ "content insert --uri content://isettings/global --bind name:s:clw_install_apps --bind value:s:0"
    exit 0
fi

# The vendor PackageManager refuses every install ("Install not allowed!")
# unless this flag in iMotor's own settings provider is 1. `content update`
# silently does nothing here; `insert` overwrites the value.
echo "==> Allowing app installs (clw_install_apps=1)"
sh_ "content insert --uri content://isettings/global --bind name:s:clw_install_apps --bind value:s:1"
sh_ "content query --uri content://isettings/global --where \"name='clw_install_apps'\""

if [ -n "$APK" ]; then
    echo "==> Installing $APK"
    adb -s "$TARGET" install -r "$APK"
    sh_ pm grant "$PKG" android.permission.READ_LOGS
    sh_ pm grant "$PKG" android.permission.CAMERA
    sh_ appops set "$PKG" SYSTEM_ALERT_WINDOW allow
    # Opening the app once starts the steering-button watcher.
    sh_ am start -n "$PKG/.SettingsActivity" >/dev/null
    echo "    installed and permissions granted"
fi

# Done last: restarting adbd drops this connection.
echo "==> Pinning ADB to port $FIXED_PORT"
sh_ setprop persist.adb.tcp.port "$FIXED_PORT"
adb -s "$TARGET" tcpip "$FIXED_PORT" >/dev/null
sleep 4
adb disconnect "$TARGET" >/dev/null 2>&1 || true
if adb connect "$HOST:$FIXED_PORT" | grep -q "connected"; then
    echo "    next time: adb connect $HOST:$FIXED_PORT"
else
    echo "    could not reconnect on $FIXED_PORT; use Wireless debugging's port next time" >&2
fi
