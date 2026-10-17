# Testing FireStick Fixer

## 1. Automated test (no remote needed)

With the Fire TV connected over adb and FireStick Fixer set up:

```sh
tools/device-test.sh            # everything (~3 minutes)
tools/device-test.sh --list     # test names
tools/device-test.sh voice      # one test
```

It presses Home, opens and closes apps, and reports PASS / FAIL / SKIP per check. It
changes no settings and ends on your launcher. Tests for switched-off features are
skipped, not failed.

| Test | What it proves |
|---|---|
| `service` | Service running, launcher set, precise detection state |
| `home_from_app` | ⌂ Home inside an app opens the launcher |
| `home_from_launcher` | ⌂ Home on the launcher stays there |
| `double_tap` | Double-tap stays on Amazon home for 7 s |
| `single_after_escape` | One Home press after that returns to the launcher |
| `back_on_launcher` | Back on the launcher's main screen is ignored |
| `hold_back` | Holding Back in an app closes it to the active home |
| `short_back` | A short Back still reaches the app |
| `buttons` | Per mapping: opening the app yourself is left alone; its button opens the mapped app |
| `voice` | Voice hand-off runs in TizenTube, YouTube and Nuvio (if installed) |

How it reaches what adb can't: remote keys injected with `adb shell input` never pass
through an accessibility service's key filter, and Alexa's page can't be opened by adb.
The app's test hooks (`TestHooks.java`, reachable only via `adb shell dumpsys`, which
needs the shell-only `DUMP` permission) feed those events into the same code paths.

Useful by hand:
```sh
adb shell dumpsys activity service io.github.hatim.firestickfixer/.HijackService          # recent log
adb shell dumpsys activity service io.github.hatim.firestickfixer/.HijackService state    # config + state
```

## 2. Manual checks (need the real remote)

Run after any change to key handling, and after a Fire OS update.

| # | Do | Expect |
|---|---|---|
| 1 | Restart the Fire TV | Start-up app opens ~25 s after power-on (no 1-minute delay, no second launch) |
| 2 | In any app press ⌂ Home | Launcher, no Amazon flash (with READ_LOGS) |
| 3 | Double-tap ⌂ Home, wait 10 s, then press Home once | Amazon home stays; one press → launcher |
| 4 | Hold ⌂ Home | Amazon's long-press panel, tiles work |
| 5 | Back on the launcher's main screen / inside its menu | Nothing / menu closes |
| 6 | Hold Back ~1 s inside an app | Launcher; app closed |
| 7 | YouTube button; Netflix button | TizenTube; Nuvio (or your mappings) |
| 8 | Open YouTube from the launcher | Real YouTube stays |
| 9 | In TizenTube hold Alexa, say a title | Panel, then TizenTube results; Back from them never shows Alexa's page |
| 10 | Same, press Back during the countdown | Alexa's results stay |
| 11 | In Nuvio's home screen, voice search | "Opening search in Nuvio…", then results (~4 s) |
| 12 | Add an app to "Apps that keep Alexa's results", voice search in it | Alexa's results |

## 3. After a Fire OS update
1. The app shows "Fire OS was updated (old → new)".
2. Run `tools/device-test.sh`, then manual checks 2, 3, 7 and 9.
3. Press ⌂ Home once so precise detection re-confirms the log format (state shows
   `precise=true` afterwards).
