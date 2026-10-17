# FireStick Fixer — architecture

How the app is put together and how each feature works. For *why* things are done this
way, see [DECISIONS.md](DECISIONS.md); for testing, [TESTING.md](TESTING.md).

## The one constraint everything follows from

On Fire OS 8, **no app can see the Home key or the remote's app buttons** (YouTube,
Netflix, Prime, Apps, …). Amazon's key policy consumes them before any accessibility
service. Measured on Fire OS 8.1.8.5: Back, OK, the D-pad and Menu reach the service's key
filter; Home and the app buttons never do. Keys injected with `adb shell input` never
reach the key filter either.

So the app reacts to what those keys *cause* instead. Three signal sources:

| Source | What it shows | Needs |
|---|---|---|
| Accessibility **window events** | Which app/activity came to the front | The accessibility service (always) |
| Accessibility **key filter** | Back, OK, D-pad, Menu presses | The accessibility service |
| System **activity-start log** (`LogWatcher`) | Every Home press (`cat=[HOME] … from uid 0`), every app button (`clickStreamReftag=r_btn_app_NN` via the Appstore, or a direct `MAIN … from uid 1000` start) | One-time `adb … pm grant … READ_LOGS` |

## Components

| File | Role |
|---|---|
| `HijackService.java` | The accessibility service. All runtime behaviour lives here: Home redirect, escape, Back handling, button remapping, voice search, boot launch, covers and panels. |
| `LogWatcher.java` | Runs `logcat -b system ActivityTaskManager:I` in a background thread and reports Home presses and app-button launches to the service. Only active with `READ_LOGS`. |
| `MainActivity.java` | The settings screen (built in code, no XML layouts). Remote-only navigation with explicit focus wiring. |
| `Prefs.java` | All settings (SharedPreferences). |
| `BootReceiver.java` | Fallback for the start-up app (Fire OS delivers `BOOT_COMPLETED` ~1 min late; the service normally handles boot first). |
| `TestHooks.java` | Commands reachable only via `adb shell dumpsys activity service …` (shell-only `DUMP` permission), used by `tools/device-test.sh`. |
| `RingLog.java` | In-memory copy of the service's log (Fire OS keeps app logcat for ~14 s). Read with `dumpsys` (no arguments). |
| `AppPicker`, `TargetPickerActivity`, `CustomMap`, `KeyBadges`, `InfoDialog`, `LogViewerActivity` | Pickers, mapping model, key-badge text, about dialog and on-device log viewer (inherited from Home on Fire, restyled). |
| `LoaderView`, `LoaderPreviewActivity` | Fire OS 7 loading animation (unused on Fire OS 8). |
| `tools/device-test.sh` | Automated end-to-end test over adb. |
| `build.sh` | aapt2 + javac + d8 build, no Gradle. |

## Features, step by step

### ⌂ Home opens your launcher
1. **Precise path (READ_LOGS):** `LogWatcher` sees the Home `START` line ~10 ms after the
   press, before Amazon home paints. The service shows a black cover (a pre-attached
   overlay window, made visible in one frame) and starts the launcher. Measured: cover
   3 ms and launcher start 40 ms after the press.
2. **Fallback path:** Amazon home's window event (`HomeActivity_vNext`) triggers the
   redirect. Amazon home flashes for ~250 ms. Repeat events from one press (Amazon home
   reports itself several times, up to ~1 s apart while a cold launcher starts) are
   filtered out.
3. Amazon-home events while another app owns the screen are ignored (Amazon's launcher
   reports itself from behind other apps).

### Double-tap ⌂ Home → Amazon home
- Precise: two Home log lines within 900 ms. Fallback: an Amazon-home arrival after the
  launcher has already appeared following the redirect.
- The escape **sticks** (`stayOnAmazonHome`) until another app opens; nothing
  redirects meanwhile (window events, focus inference, Back/OK on the Home tab). A single
  Home press brings the launcher back.

### Back
- **On the launcher's main screen: ignored.** The main screen is recognised by a
  fingerprint (class and bounds of its outermost scroll container), learned whenever
  the launcher comes up fresh. Back inside the launcher's own menus passes through,
  because Flutter/Compose menus remove that container from the accessibility tree.
- **Hold Back (0.7 s) in an app:** go to the active home (launcher, or Amazon home if
  the redirect is off) and stop the app in the background. A short press is re-sent
  with `GLOBAL_ACTION_BACK`; our own re-sent Back is let through via `selfBackUntil`.

### Remote button remapping
- A mapping binds a *window* (the app a button opens, learned once in the settings) to
  an app to open instead.
- **Button vs. manual open:** with READ_LOGS, only a launch that the log attributes to a
  button fires the mapping (both forms: via the Appstore with `r_btn_app_NN`, or
  started directly by the system, as Netflix is). Without it, an OK press in the 3 s
  before means "opened by the user".
- The mapped app starts with the launcher underneath it, so Back returns to the
  launcher, not to the original app. The original app is covered and then stopped in the
  background (`killBackgroundProcesses`), so it can't show its own screens (YouTube's
  sign-in page) over the mapped app.

### Alexa voice search in the current app
1. Inside an app, Alexa answers a search with its own page
   (`DirectiveSearchResultsActivityDI`), unless the app has Alexa integration.
2. The service reads the spoken words from that page (`id/search_text`; Amazon marks it
   "not important for accessibility", so hidden views are included only while reading).
3. A panel covers the page: app icon, the words, a 1.5 s countdown. Back keeps Alexa's
   results; OK searches at once.
4. Alexa's page is closed (Back), then the search goes to the app, by the first route
   that works:
   1. YouTube search link (`https://www.youtube.com/results?search_query=…`) for YouTube
      and its clients;
   2. `ACTION_SEARCH` for apps that declare it;
   3. typing into a visible search box (`ACTION_SET_TEXT` + `ACTION_IME_ENTER`);
   4. clicking an element labelled "Search" first;
   5. walking the app's left-edge side menu (items tried top to bottom, waiting up to
      2.5 s each for a search box). The item that worked is remembered per app and tried
      first next time.
5. During steps 3–5 a waiting panel ("Opening search in …") covers the screen; Back
   cancels.
- Apps in "Apps that keep Alexa's results" are skipped entirely.

### Start-up app
- The service connects ~22 s after power-on, a fraction of a second before Amazon home
  starts. The start-up app is opened on Amazon home's first window (or at once if it is
  already up), under a cover. Amazon-home events in the next 8 s are ignored unless the
  user presses Home.
- `BootReceiver` only acts if the service has not, because `BOOT_COMPLETED` reaches
  apps ~1 minute after boot.

### Robustness against Fire OS updates
- Precise detection is trusted only once a Home (or button) log line has actually been
  seen on the current Fire OS build (stored per build). After an update it falls back to
  window events until the log format is confirmed again.
- Every Amazon-specific identifier (`HomeActivity_vNext`, `DirectiveSearchResultsActivityDI`,
  `search_text`, `r_btn_app_`, update package names) degrades gracefully: if Amazon
  renames it, that one feature stops, nothing breaks.
- The service notes the Fire OS build and tells the user when it changed.

## Overlay windows
All are `TYPE_ACCESSIBILITY_OVERLAY`, not focusable and not touchable, so keys still go
to the app underneath (the service sees them first in its key filter):
- **Cover:** plain black, attached once at start and only shown/hidden; capped at
  1.5–2.5 s so it can never stay on screen.
- **Voice panel:** decision countdown, then the "opening search" waiting screen.
- **Fire OS 7 loading mask** (inherited, unused on Fire OS 8).

## Permissions
| Permission | Why | How granted |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | The service itself | System |
| `WRITE_SECURE_SETTINGS` | Lets the in-app switch enable the service (Fire OS hides the Accessibility settings page) | `adb shell pm grant` once (required) |
| `READ_LOGS` | Precise Home/button detection | `adb shell pm grant` once (recommended) |
| `RECEIVE_BOOT_COMPLETED` | Start-up app fallback | Normal |
| `KILL_BACKGROUND_PROCESSES` | Stop a remapped app's original, and apps closed with hold-Back | Normal |

No `INTERNET` permission: nothing leaves the device.
