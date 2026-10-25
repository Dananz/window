# Set up Window on a TV, with Claude

Open Claude Code in this repository's folder and paste the prompt below. Fill in the TV's address first
(Settings > Network & Internet > your network, on the TV).

---

```text
Set up Window, the Google TV home screen in this repository, on my TV at <TV-IP>. Read README.md first.

The TV is next to me. Before anything that changes the TV, tell me what you're about to do. Ask me to
look at the TV whenever a prompt might appear on it.

1. Tools. Check for adb, JDK 21 and the Android SDK (platform 37, build tools 37). Install what's missing
   with Homebrew (`brew install openjdk@21 android-platform-tools`, and the SDK with sdkmanager or Android
   Studio). Create local.properties from local.properties.example: sdk.dir, and tv=<TV-IP>:5555. Leave
   ha.url and ha.token out.

2. Connect. Run `adb connect <TV-IP>:5555`. If it's refused, tell me how to turn on Developer options and
   Network debugging on the TV (Settings > System > About, press the build 7 times; then Developer
   options). If it says unauthorized, ask me to accept the prompt on the TV with "Always allow". Then
   check `adb -s <TV-IP>:5555 shell getprop ro.build.version.sdk` is 31 or higher (Android 12), and tell me the
   model (`ro.product.model`).

3. Build and install: `scripts/tv-install.sh`. If the install fails with a signature error, Window was
   installed from another machine's build: ask me before running `adb uninstall com.dananz.window`.
   Afterwards, check the Home button service is on:
   `adb -s <TV-IP>:5555 shell settings get secure enabled_accessibility_services` lists
   com.dananz.window/com.dananz.window.HomeButton. Take a screenshot
   (`adb exec-out screencap -p > window.png`), look at it, and confirm Window is on screen.

4. Make it the home screen. Ask me first: this switches Google TV's own home and setup wizard off (they
   stay installed; scripts/tv-restore.sh brings them back). Then run `scripts/tv-default.sh`. If it stops
   because the saved Wi-Fi network belongs to the setup wizard, don't work around it: ask me to forget
   the network on the TV and join it again in Settings > Network & Internet, then run it again. Then
   press Home (`adb shell input keyevent KEYCODE_HOME`) and confirm from a screenshot that Window is up.

5. Home Assistant (skip if I don't use it). Window signs in by itself, with a QR code:
   on the remote, the gear at the top of the home screen > Window settings (bottom of the panel) > Smart home > Home Assistant > Connect Home Assistant.
   Window finds Home Assistant on the network, or asks for its address. I scan the QR with my phone and
   log in. Then Smart home > Tiles on the wall: I pick the lights and the AC. Guide me through it; don't
   drive the remote for me, and never press OK on a light or AC tile (they switch real devices).

6. Finish. Tell me what's set, what's left, and that after a TV firmware update I can ask you to run
   `scripts/tv-check.sh --fix`, and before a factory reset `scripts/tv-restore.sh`.
```

---

Notes for Claude, if things go sideways:

- An update, `cmd package compile -f` and a force stop each switch the Home button service off.
  `tv-install.sh` turns it on last; Window also turns it back on by itself when it starts (it has
  WRITE_SECURE_SETTINGS).
- `appops set ... AUTO_START` exists only on TCL TVs; elsewhere it's skipped.
- A TV with a second user: use `--user 0` with `pm`.
- Held volume or arrows on the remote step only once: the Home button service is in the way (its key
  filter stops keys repeating on some remotes, a Sony BRAVIA's). If Home already brings Window up with
  Google TV's home switched off, add `nohomebutton=<serial>` (`adb shell getprop ro.serialno`) to
  local.properties and install again: the scripts then leave the service off on that TV.
- On a 4K panel Window shows the landscape on a surface of the panel's own size. If the TV's log says
  `[4K/2K UI Coexist]` and the picture is black, the surface moved or was scaled: it must stay full screen.
- Keys over adb: 19 up, 20 down, 21 left, 22 right, 23 OK, 4 back, 3 home. Keys injected with
  `input keyevent 3` skip accessibility services, so Home from adb isn't the same as the remote's.
