# Sourced by the tv-* scripts: which TV to talk to. TV=<ip>:5555 on the command line wins, else the
# `tv=` line in local.properties.
TV=${TV:-$(sed -n 's/^tv=//p' "$(dirname "$0")/../local.properties" 2>/dev/null)}
if [ -z "$TV" ]; then
  echo "Which TV? Run with TV=<ip>:5555, or add tv=<ip>:5555 to local.properties." >&2
  exit 1
fi

# A TV listed as `nohomebutton=<serial>` in local.properties gets no Home button service: there Home
# reaches Window once Google TV's home is off, and the service's key filter stops held keys repeating
# (volume, arrows) on its remote (Sony BRAVIA)
no_home_button() {
  serial=$(adb -s "$TV" shell getprop ro.serialno | tr -d '\r')
  grep -qx "nohomebutton=$serial" "$(dirname "$0")/../local.properties" 2>/dev/null
}

# The TV's own home: Google TV's (launcherx) or, on older Android TV sets (Android 10), tvlauncher.
# Prints nothing when neither is installed.
stock_home() {
  pkgs=$(adb -s "$TV" shell pm list packages --user 0 | tr -d '\r')
  for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do
    if printf '%s\n' "$pkgs" | grep -qx "package:$p"; then echo "$p"; return 0; fi
  done
  return 0
}

# The TV's own ambient-mode screensaver, for tv-restore: Google TV's or Android TV's; nothing when neither
stock_saver() {
  pkgs=$(adb -s "$TV" shell pm list packages --user 0 | tr -d '\r')
  for c in com.google.android.apps.tv.dreamx/.service.Backdrop com.google.android.backdrop/.Backdrop; do
    if printf '%s\n' "$pkgs" | grep -qx "package:${c%%/*}"; then echo "$c"; return 0; fi
  done
  return 0
}
