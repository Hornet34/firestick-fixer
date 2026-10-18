# Plan: set up a Fire TV from an Android phone (no computer)

Status: **proposed, not built**. Builds on Fast actions (DECISIONS D34–D43).

## Goal
The user installs FireStick Fixer **on their Android phone only**, taps "Set up a Fire TV", presses
**Allow once** on the TV, and is done: the app is installed on the TV, granted, switched on and Fast
actions work. Same APK on both devices; no computer, no commands.

## What the user still has to do (Fire OS requires it, no app can)
1. On the TV: switch on **Developer Options → ADB debugging** (and, if Developer Options are hidden,
   About → click the device name 7 times). The phone screen shows these steps with pictures.
2. Get the APK onto the phone (download from the GitHub release; Android asks once to allow installs
   from the browser).
3. Press **Allow** on the TV once, for the phone ("Allow USB debugging?", tick *Always allow*).
4. Only on a Wi-Fi the TV hasn't trusted for wireless debugging yet: possibly a second Allow (see below).

## How the phone does the rest
The phone is "another device", so adbd talks to it normally on `TV:5555` (the D36 block applies only to
connections from the TV itself). The phone uses the ADB client already in the app.

1. **Find the TV:** scan the phone's Wi-Fi subnet for port 5555 answering adb's `CNXN` with `AUTH`
   (~1–2 s for a /24); or the user types the address (TV: Settings → My Fire TV → About → Network).
2. **Connect** with the phone's own key (made on the phone, like the TV app's). The TV asks "Allow USB
   debugging?" → the user's one Allow.
3. **Install the app on the TV from the phone:** the phone streams its own APK (`getPackageCodePath()`,
   the same file) into `exec:cmd package install -S <size> -r`. Skipped if the TV already has this
   version or newer; if the TV has a copy signed with another key, stop and say so (never uninstall).
4. **Grants and settings:** `pm grant` WRITE_SECURE_SETTINGS and READ_LOGS;
   `settings put global adb_allowed_connection_time 0`; add `<pkg>/.HijackService` to
   `enabled_accessibility_services` (read first, append, never drop the others) and
   `accessibility_enabled 1`.
5. **Approve the TV app's own key without a relay or a second Allow:** the TV app prints its public
   key (new test hook `adb pubkey`, makes the key if needed). The phone opens a second connection to
   `TV:5555` and offers that public key (adb `AUTH RSAPUBLICKEY`; no private key is needed for that),
   which makes adbd raise its prompt for it; then, on the first connection (shell, which holds
   `MANAGE_DEBUGGING`), it answers the prompt with `service call adb 1 i32 1 s16 '<key>'`
   (`IAdbManager.allowDebugging(alwaysAllow=true, key)`). adbd only accepts that answer for the key it
   is prompting for (fingerprint check), so nothing else can be approved this way. The TV app's
   private key never leaves the TV and the phone never holds it.
   Fallback: the user presses Allow on that prompt too.
6. **Wireless debugging on this Wi-Fi:** the phone runs the TV app's `adb test`. On an untrusted Wi-Fi
   the TV shows "Allow wireless debugging on this network?". Shell can't read the BSSID (Fire OS shows
   `XXX`), so `allowWirelessDebugging(bssid)` can't be called directly. Instead the phone reads the
   dialog with `uiautomator dump`, checks the focused element before each key (tick "Always allow",
   move to Allow, press), the way D39 did it by hand, never blind. Fallback: the user presses Allow.
7. **Test and finish:** `adb test` must return the model; then `am start` opens FireStick Fixer on the
   TV so the user picks a launcher. The phone shows "Done".
8. **Disconnect:** the phone closes its connection. Its key stays approved (so a later "Update the TV
   app" or "Undo" works without a new Allow); the phone screen offers "Forget this phone on the TV"
   (`service call adb 3` clears all keys, so it warns that the TV app needs setup again; offered only as
   an explicit choice).

## Phone screen (same APK)
- `MainActivity` checks `UiModeManager` mode: not a TV → opens `PhoneSetupActivity` instead of the TV
  settings. Touch UI, plain Material-ish widgets, one screen:
  - checklist with live ticks: TV found → phone allowed → app installed → permissions → service on →
    fast actions → done; each with a short reason if it fails and a "Try again".
  - buttons: **Set up a Fire TV**, **Update the app on the TV** (re-streams the APK), **Undo setup**.
- The accessibility service, boot receiver and TV screens stay inert on a phone (never enabled there).

## Safety
- The phone side has its own fixed command list (`TvSetup`), only the steps above. No free-form shell,
  never `pm uninstall` / `pm clear` / `pm disable`, never key presses without reading the screen first.
- Every step is reversible; **Undo setup** runs: `dumpsys … adb forget`, remove our entry from
  `enabled_accessibility_services`, `pm revoke` both permissions, `settings delete global
  adb_allowed_connection_time`. (Uninstalling the TV app stays the user's choice in Fire OS settings.)

## Code
- Refactor: move the packet/stream code out of `LocalAdb` into `AdbProtocol` (packets, AUTH, shell and
  `exec:` streams with flow control for the APK upload); `LocalAdb` (TV → its own adbd, TLS) and new
  `RemoteAdb` (phone → TV:5555, plain) both use it.
- New: `PhoneSetupActivity`, `TvSetup` (the step list), `TvFinder` (subnet scan); test hook
  `adb pubkey`. `tools/setup-adb.sh` switches to the same no-relay approval (step 5).

## Checks before building
1. **No-relay approval** (step 5) from the laptop: offer an unapproved public key on a second connection,
   answer with `service call adb 1`, confirm the second connection gets `CNXN`. Side effect: one
   throwaway key ends up approved (its private key is deleted at once).
2. **APK streaming** to `cmd package install -S` works (laptop `adb exec-in`/raw stream).
3. **A phone to test on:** no emulator image is installed. Either the owner's Android phone (USB or
   wireless debugging to the laptop, to install the build), or an emulator image (~1.5 GB download).

## Limits
- Android phones only: iPhone apps can't open raw TCP to arbitrary LAN hosts in a usable way. iPhone or
  no phone: `tools/setup-adb.sh` stays.
- ADB debugging must stay on for Fast actions anyway; the phone setup can't switch it on.
- Phone and TV on the same Wi-Fi (no client isolation / guest network).
