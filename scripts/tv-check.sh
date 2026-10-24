#!/bin/sh
# After a firmware update: is Window still the home screen? An update can switch Google TV's home or its
# setup wizard back on, or take Window's Home button service off. Prints what drifted; --fix puts it back.
. "$(dirname "$0")/tv-env.sh"
PKG=com.dananz.window
sh() { adb -s "$TV" shell "$@" | tr -d '\r'; }
drift=0
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  if sh pm list packages --user 0 -e "$p" | grep -q "$p"; then echo "on again: $p"; drift=1; fi
done
home="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
[ "$home" = "$PKG/.MainActivity" ] || { echo "Home is $home"; drift=1; }
sh settings get secure enabled_accessibility_services | grep -q "$PKG/$PKG.HomeButton" || { echo "Home button service is off"; drift=1; }
[ "$(sh settings get secure screensaver_components)" = "$PKG/.Screensaver" ] || { echo "Screensaver is not Window's"; drift=1; }
if [ $drift = 0 ]; then echo "Window is the home screen; nothing drifted."; exit 0; fi
[ "$1" = "--fix" ] && exec "$(dirname "$0")/tv-default.sh"
echo "Run with --fix to put it back."
exit 1
