#!/bin/sh
# Builds the release APK and puts it on the TV (or, with APK=<file>, puts that one on it: a release
# downloaded from GitHub). An update switches the Home button's
# accessibility service off, and a debug build is too slow on this TV, so every install goes
# through here: release build, compiled ahead of time, service and TCL auto start on again.
set -e
. "$(dirname "$0")/tv-env.sh"
PKG=com.dananz.window
cd "$(dirname "$0")/.."
if [ -z "$APK" ]; then
  export JAVA_HOME=${JAVA_HOME:-$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home}
  ./gradlew -q assembleRelease
  APK=app/build/outputs/apk/release/app-release.apk
fi
adb -s "$TV" install -r "$APK"
adb -s "$TV" shell cmd package compile -m speed -f $PKG
adb -s "$TV" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
# Lets Window switch its Home button service back on by itself after the next update or force stop
adb -s "$TV" shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# TCL only: its TclAppBoot otherwise stops the service from binding
adb -s "$TV" shell appops set $PKG AUTO_START allow 2>/dev/null || true
# Android 13 and up only: lets the service be switched on for a sideloaded app
adb -s "$TV" shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
# Lets Window settings change when the screensaver starts
adb -s "$TV" shell appops set $PKG WRITE_SETTINGS allow
# Lets the screensaver read what's playing (Window also switches this on by itself)
adb -s "$TV" shell cmd notification allow_listener $PKG/.NowPlaying 2>/dev/null || true
# Lets the sound bars follow the music (the spectrum of what the TV plays)
adb -s "$TV" shell pm grant $PKG android.permission.RECORD_AUDIO
# Last: installing, compiling and force-stopping the app each take its service off again
# Started as the home screen, or the next Home opens a second Window in the home task
adb -s "$TV" shell am start -S -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity >/dev/null
sleep 2
if no_home_button; then echo "Home button service left off on this TV (nohomebutton)"; exit 0; fi
adb -s "$TV" shell settings put secure enabled_accessibility_services $PKG/$PKG.HomeButton
adb -s "$TV" shell settings put secure accessibility_enabled 1
