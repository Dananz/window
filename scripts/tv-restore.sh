#!/bin/sh
# Puts the TV's own home back: Google TV's home and its setup wizard on again, Home to Google TV, and
# the screensaver back to Google's ambient mode. Window stays installed (pick it again with tv-default.sh).
# Run this before any factory reset: the setup wizard must be on for the TV's first-run setup.
. "$(dirname "$0")/tv-env.sh"
sh() { adb -s "$TV" shell "$@"; }
SETUP=com.google.android.tungsten.setupwraith
PKGS="$(sh pm list packages --user 0 | tr -d '\r')"
has() { printf '%s\n' "$PKGS" | grep -qx "package:$1"; }
has android || { echo "The TV did not answer; nothing changed." >&2; exit 1; }
STOCK=""
for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do has $p && { STOCK=$p; break; }; done
[ -n "$STOCK" ] || { echo "Neither Google TV's home nor Android TV's is installed on this TV; nothing changed." >&2; exit 1; }
# Google TV's home first, and the setup wizard only once it is on again: with the wizard on and Google
# TV's home off, Home goes to the wizard's RecoveryActivity, and a reboot in that state has
# factory-reset TVs (CleanYourTV's TROUBLESHOOTING, #3578)
sh pm enable $STOCK
if ! sh pm list packages --user 0 -e $STOCK | tr -d '\r' | grep -qx "package:$STOCK"; then
  echo "Google TV's home did not switch on, so the setup wizard was left off. Do not reboot; run this again." >&2
  exit 1
fi
has $SETUP && sh pm enable $SETUP
sh cmd package set-home-activity --user 0 "$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1 | tr -d '\r')"
sh settings put secure screensaver_components "$(stock_saver $STOCK)"
sh input keyevent KEYCODE_HOME
# Read back: both on, and Home on Google TV's home
failed=
has $SETUP || SETUP=""
for p in $STOCK $SETUP; do
  sh pm list packages --user 0 -d $p | tr -d '\r' | grep -qx "package:$p" && { echo "Still off: $p" >&2; failed=1; }
done
home="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
echo "Home: $home"
case "$home" in "$STOCK"/*) ;; *) echo "Home is not the TV's own home ($STOCK)." >&2; failed=1 ;; esac
[ -z "$failed" ]
