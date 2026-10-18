# Plan: fast actions through the stick's own adb

Status: **built (D42), verified on the stick (D43)**. Evidence: DECISIONS D34–D42.
Changes from this plan: idle window 20 s instead of 2 min (the port is up 75 ms after switching on); apps with an
enabled accessibility service or notification listener are never stopped; the key lives in the app's private files,
not the Android Keystore (see D42).

## Goal
Let FireStick Fixer run a short, fixed list of adb commands on its own stick (~0.1 s each), without a
computer after a one-time setup. First use: **force-stop**, for hold Back and a returning "Free up memory".

## How it works at runtime
1. The app needs a command → sets `adb_wifi_enabled=1` (own `WRITE_SECURE_SETTINGS`; no dialog, the Wi-Fi is trusted).
2. Finds the port by mDNS `_adb-tls-connect._tcp` (7–24 ms), restricted to the stick's own addresses; falls back to the
   loopback scan (0.7 s).
3. Connects to `127.0.0.1:<port>`, TLS 1.3 with the app's key as client certificate, runs the commands on one session.
4. Leaves the session open and wireless debugging on for **2 minutes of idle**, then closes both (`adb_wifi_enabled=0`).
   Also closes them when the screen turns off. A burst of actions pays the ~0.1 s setup once.
5. If anything fails (Wi-Fi changed and needs approval, developer options switched off by an update, key revoked), the
   action falls back to today's behaviour, and Settings shows why. Nothing else breaks.

## One-time setup (computer needed once)
The app makes its own key; the private key never leaves the stick.
- **Key:** RSA 2048 in the Android Keystore (non-exportable). The Keystore also issues the self-signed certificate TLS
  needs. adb's AUTH signature = `NONEwithRSA` over SHA-1 DigestInfo + token; the public key goes out in adb's own format.
- **Approval:** adbd only shows "Allow USB debugging?" for connections that don't come from the stick itself (D36). So
  setup relays the app's connection through the computer:
  `app → 127.0.0.1:5560 (adb reverse) → computer → stick:5555`. adbd sees the computer's address, shows the prompt for
  the **app's** key; the owner ticks *Always allow* and presses Allow.
- **Script:** `tools/setup-adb.sh` (Linux/macOS; PowerShell version after) does it all and cleans up:
  1. the two existing grants (`WRITE_SECURE_SETTINGS`, `READ_LOGS`);
  2. `settings put global adb_allowed_connection_time 0` (approved keys never expire; D39);
  3. `adb reverse tcp:5560 tcp:5561` + a relay on the computer (`socat`, or a few lines of Python) to `stick:5555`;
  4. `dumpsys … HijackService adb setup` → the app connects through the relay; the TV shows the prompt;
  5. switches wireless debugging on once so the owner can allow the network, if it isn't trusted yet;
  6. tests one read-only command, removes the reverse and the relay, prints "Ready".
- **Settings:** a new section **Fast actions** with a status row: *Not set up* (shows the one command to run) / *Ready* /
  *Needs Wi-Fi approval* / *Not available (developer options off)*.

## The safe command list (one class, `AdbActions`)
Only these templates exist. No free-form shell, nothing reachable from other apps.

| Name | Command | Use |
|---|---|---|
| `forceStop(pkg)` | `cmd activity force-stop <pkg>` | hold Back, Free up memory |
| `running()` | `ps -A -o NAME` (read-only) | which apps have a process, for Free up memory |
| `model()` | `getprop ro.product.model` (read-only) | setup check |

`cmd` is native (binder, ~20 ms); `am` would start a JVM (~200 ms per call).
**Package checks before any force-stop** (all must pass): matches `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$`;
installed; not a system app (`FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP`), not `com.amazon.*`; not this app; not the chosen
launcher; not the current keyboard; not the VPN app while a VPN is up. Every command is written to the ring log.
Never in the list: `pm uninstall`, `pm clear`, `pm disable*`, `pm suspend`, `settings`, `reboot`, `rm`, `input`.

## First uses
**Hold Back (0.7 s) in an app**
- Ready: cover "Closing <app>…" → open the launcher → `forceStop(app)` → cover off. ~0.2 s instead of up to 14 Backs, and
  the app starts fresh next time (fixes TizenTube resuming on its last page, D33).
- Not ready: today's back-out (D33).
- Setting: "Hold Back closes the app fully" (on by default once Fast actions are ready).

**Free up memory (quick menu item, back from D32)**
- Shown only when Fast actions are ready; switchable in Settings → Quick menu like the others.
- `running()` → force-stop every running app that passes the checks, except the app on screen and apps playing audio.
  One session, ~20 ms per app; a small card shows "Closed N apps".

## Files
- New: `LocalAdb.java` (connection, TLS, session, idle close; from `AdbTls.java`), `AdbKey.java` (Keystore key, adb
  public-key format, AUTH signing), `AdbActions.java` (the list and the package checks), `tools/setup-adb.sh`.
- Changed: `HijackService` (hold Back, quick menu item, screen-off close), `MainActivity` (Fast actions section, focus
  chain), `Prefs`, `TestHooks` (`adb status|setup|forcestop <pkg>|free`; the `adbtls` test hooks go), manifest
  (`INTERNET` stays, comment: loopback only plus the setup relay), README, ARCHITECTURE, DECISIONS (D42+).

## Checks before building (read-only or reversible, ~5 min)
1. Time from `adb_wifi_enabled=1` to the port answering (decides the idle window).
2. Does Fire OS show a notification or toast while wireless debugging is on?
3. Does the trusted Wi-Fi survive a reboot?

## Cleanup of the experiment
- Uninstall Shizuku (not needed). Delete the test key from the app (`adbtls forget`) and `../shizuku-test/appkey/`.
- The test key stays approved in adbd. Removing it means `service call adb 3` (clears all keys), then the laptop and
  the app press Allow once more. Suggest doing it during setup, since setup asks for Allow anyway.

## Testing
`tools/device-test.sh` gains: setup status; force-stop of a harmless app (a test app we install, never the owner's);
hold Back with and without Fast actions; Free up memory exclusions (launcher, VPN app, on-screen app stay running);
fallback when wireless debugging is refused. The owner restores settings as usual (D23).

## Risks
- While wireless debugging is on (≤2 min idle), its port is open on the home network; only approved keys get in.
- An Amazon update could remove wireless debugging or turn developer options off: actions fall back, nothing breaks.
- Force-stop can't harm the system with the checks above; the worst case is an app restarting cold.
