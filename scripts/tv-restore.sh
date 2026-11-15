#!/bin/sh
# Puts the TV's own home back: Google TV's home and its setup wizard on again, Home to Google TV, and
# the screensaver back to Google's ambient mode. Window stays installed (pick it again with tv-default.sh).
# Run this before any factory reset: the setup wizard must be on for the TV's first-run setup.
. "$(dirname "$0")/tv-env.sh"
sh() { adb -s "$TV" shell "$@"; }
STOCK=com.google.android.apps.tv.launcherx
SETUP=com.google.android.tungsten.setupwraith
# Google TV's home first, and the setup wizard only once it is on again: with the wizard on and Google
# TV's home off, Home goes to the wizard's RecoveryActivity, and a reboot in that state has
# factory-reset TVs (CleanYourTV's TROUBLESHOOTING, #3578)
sh pm enable $STOCK
if ! sh pm list packages --user 0 -e $STOCK | tr -d '\r' | grep -qx "package:$STOCK"; then
  echo "Google TV's home did not switch on, so the setup wizard was left off. Do not reboot; run this again." >&2
  exit 1
fi
sh pm enable $SETUP
sh cmd package set-home-activity --user 0 "$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1 | tr -d '\r')"
sh settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
sh input keyevent KEYCODE_HOME
# Read back: both on, and Home on Google TV's home
for p in $STOCK $SETUP; do
  sh pm list packages --user 0 -d $p | tr -d '\r' | grep -qx "package:$p" && { echo "Still off: $p" >&2; failed=1; }
done
home="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
echo "Home: $home"
case "$home" in "$STOCK"/*) ;; *) echo "Home is not Google TV's home." >&2; failed=1 ;; esac
[ -z "$failed" ]
