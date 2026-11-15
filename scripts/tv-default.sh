#!/bin/sh
# Makes Window the TV's real home screen, the way CleanYourTV does it: Google TV's home (launcherx, or
# tvlauncher on an Android TV set)
# and its setup wizard (setupwraith, which otherwise takes Home back) are switched off with
# `pm disable-user`. Nothing is uninstalled; scripts/tv-restore.sh puts both back.
# Also sets Window's clock as the screensaver.
set -e
. "$(dirname "$0")/tv-env.sh"
PKG=com.dananz.window
SETUP=com.google.android.tungsten.setupwraith
sh() { adb -s "$TV" shell "$@"; }

# One read of what's installed, trusted only when it lists Window itself: a dropped adb must not read as
# "no setup wizard", which would switch the home off with the wizard still on
PKGS="$(sh pm list packages --user 0 | tr -d '\r')"
has() { printf '%s\n' "$PKGS" | grep -qx "package:$1"; }
has $PKG || { echo "Window is not installed (or the TV did not answer); run scripts/tv-install.sh first." >&2; exit 1; }
STOCK=""
for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do has $p && { STOCK=$p; break; }; done
[ -n "$STOCK" ] || { echo "Neither Google TV's home nor Android TV's is installed on this TV; nothing changed." >&2; exit 1; }
# Some Android TV sets have no setupwraith; there only the stock home goes
WIZARD=""
has $SETUP && WIZARD=1
# A Wi-Fi network saved during the TV's first setup belongs to setupwraith, and Android deletes it the
# moment setupwraith is switched off: the TV drops off Wi-Fi, and adb with it. Only go on when the saved
# networks belong to the system (forget and re-join the network in Settings to move it there).
if [ -n "$WIZARD" ]; then
OWNERS="$(sh dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u)"
if [ -z "$OWNERS" ]; then echo "Could not read who owns the saved Wi-Fi networks; nothing changed." >&2; exit 1; fi
if printf '%s\n' "$OWNERS" | grep setupwraith >/dev/null; then
  echo "The saved Wi-Fi network belongs to the setup wizard: switching it off would take the TV off Wi-Fi." >&2
  echo "On the TV, forget the network and join it again in Settings > Network & Internet, then run this again." >&2
  exit 1
fi
fi

# What was disabled before, for the record
mkdir -p "$(dirname "$0")/../backup"
sh pm list packages -d > "$(dirname "$0")/../backup/disabled-before-$(date +%Y%m%d-%H%M%S).txt"

# The order matters (CleanYourTV's TROUBLESHOOTING): with Google TV's home off and the setup wizard still
# on, Home goes to the wizard's RecoveryActivity, and a reboot in that state has factory-reset TVs. So
# the wizard goes first, and Google TV's home only once the wizard is confirmed off.
off() { sh pm list packages --user 0 -d "$1" | tr -d '\r' | grep -qx "package:$1"; }
sh cmd package set-home-activity --user 0 $PKG/.MainActivity
[ -z "$WIZARD" ] || sh pm disable-user --user 0 $SETUP
[ -z "$WIZARD" ] || off $SETUP || { echo "The setup wizard did not switch off; Google TV's home was left on. Nothing else changed." >&2; exit 1; }
sh pm disable-user --user 0 $STOCK
sh cmd package set-home-activity --user 0 $PKG/.MainActivity
# The screensaver
sh settings put secure screensaver_components $PKG/.Screensaver
sh settings put secure screensaver_enabled 1

# Ground truth: what Home resolves to, and what has focus after Home
home="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
sh input keyevent KEYCODE_HOME
sleep 2
focus="$(sh dumpsys activity activities | tr -d '\r' | grep -m1 mFocusedApp || true)"
echo "Home: $home"
echo "$focus"
case "$home $focus" in
  *RecoveryActivity* | *FallbackHome*)
    echo "Home went to the setup wizard's recovery screen or a blank fallback, not Window. Do not reboot the TV." >&2
    echo "Run scripts/tv-restore.sh now (it switches Google TV's home on first, then the wizard)." >&2
    exit 1 ;;
esac
case "$home" in
  "$PKG/.MainActivity" | "$PKG/$PKG.MainActivity") ;;
  *) echo "Home is not Window ($home). Do not reboot the TV; run scripts/tv-restore.sh." >&2; exit 1 ;;
esac
