#!/bin/sh
# Puts the TV's own home back: Google TV's home and its setup wizard on again, Home to Google TV, and
# the screensaver back to Google's ambient mode. Pane stays installed (pick it again with tv-default.sh).
# Run this before any factory reset: the setup wizard must be on for the TV's first-run setup.
. "$(dirname "$0")/tv-env.sh"
sh() { adb -s "$TV" shell "$@"; }
sh pm enable com.google.android.tungsten.setupwraith
sh pm enable com.google.android.apps.tv.launcherx
sh cmd package set-home-activity --user 0 "$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME com.google.android.apps.tv.launcherx | tail -n 1 | tr -d '\r')"
sh settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
sh input keyevent KEYCODE_HOME
