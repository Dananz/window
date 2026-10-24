#!/bin/sh
# Makes Window the TV's real home screen, the way CleanYourTV does it: Google TV's home (launcherx)
# and its setup wizard (setupwraith, which otherwise takes Home back) are switched off with
# `pm disable-user`. Nothing is uninstalled; scripts/tv-restore.sh puts both back.
# Also sets Window's clock as the screensaver.
set -e
. "$(dirname "$0")/tv-env.sh"
PKG=com.dananz.window
STOCK=com.google.android.apps.tv.launcherx
SETUP=com.google.android.tungsten.setupwraith
sh() { adb -s "$TV" shell "$@"; }

# A Wi-Fi network saved during the TV's first setup belongs to setupwraith, and Android deletes it the
# moment setupwraith is switched off: the TV drops off Wi-Fi, and adb with it. Only go on when the saved
# networks belong to the system (forget and re-join the network in Settings to move it there).
OWNERS="$(sh dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u)"
if [ -z "$OWNERS" ]; then echo "Could not read who owns the saved Wi-Fi networks; nothing changed." >&2; exit 1; fi
if printf '%s\n' "$OWNERS" | grep setupwraith >/dev/null; then
  echo "The saved Wi-Fi network belongs to the setup wizard: switching it off would take the TV off Wi-Fi." >&2
  echo "On the TV, forget the network and join it again in Settings > Network & Internet, then run this again." >&2
  exit 1
fi
# Window must be installed before the stock home goes
sh pm list packages $PKG | grep -q "$PKG" || { echo "Window is not installed; run scripts/tv-install.sh first." >&2; exit 1; }

# What was disabled before, for the record
mkdir -p "$(dirname "$0")/../backup"
sh pm list packages -d > "$(dirname "$0")/../backup/disabled-before-$(date +%Y%m%d-%H%M%S).txt"

sh pm disable-user --user 0 $STOCK
sh pm disable-user --user 0 $SETUP
sh cmd package set-home-activity --user 0 $PKG/.MainActivity
# The screensaver
sh settings put secure screensaver_components $PKG/.Screensaver
sh settings put secure screensaver_enabled 1

# Ground truth: what actually has focus after Home
sh input keyevent KEYCODE_HOME
sleep 2
sh dumpsys activity activities | grep -m1 mFocusedApp
