#!/bin/sh
# Builds the release APK and puts it on the TV. An update switches the Home button's
# accessibility service off, and a debug build is too slow on this TV, so every install goes
# through here: release build, compiled ahead of time, service and TCL auto start on again.
set -e
. "$(dirname "$0")/tv-env.sh"
PKG=com.dananz.window
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home}
./gradlew -q assembleRelease
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
adb -s "$TV" shell cmd package compile -m speed -f $PKG
adb -s "$TV" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
# Lets Window switch its Home button service back on by itself after the next update or force stop
adb -s "$TV" shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# TCL only: its TclAppBoot otherwise stops the service from binding
adb -s "$TV" shell appops set $PKG AUTO_START allow 2>/dev/null || true
adb -s "$TV" shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow
# Lets Window settings change when the screensaver starts
adb -s "$TV" shell appops set $PKG WRITE_SETTINGS allow
# Last: installing, compiling and force-stopping the app each take its service off again
adb -s "$TV" shell am start -S -n $PKG/.MainActivity >/dev/null
sleep 2
adb -s "$TV" shell settings put secure enabled_accessibility_services $PKG/$PKG.HomeButton
adb -s "$TV" shell settings put secure accessibility_enabled 1
