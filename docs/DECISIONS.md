# FireStick Fixer — decision log

Every significant decision, with the reason and the evidence behind it, in the order
made. Dates are 2026. Test device throughout: Fire TV Stick AFTMA08C15, Fire OS 8.1.8.5
(Android 11). Each entry: **Decision**, **Why**, **Evidence**, **Consequences**.

---

### D1 — Fork Home on Fire under a new package id (Oct 17)
- **Decision:** fork toolicious/home-on-fire 1.5.2 (GPLv3) as `io.github.hatim.firestickfixer`.
- **Why:** installs side by side with the original, so rollback is one setting (switch
  the original's accessibility service back on). The device can't be reflashed, so every
  step had to be reversible.
- **Consequences:** settings start empty in the new app; GPLv3 obligations apply (source
  is public, original credited).

### D2 — Linux build with build.sh, not Gradle (Oct 17)
- **Decision:** keep upstream's aapt2 + javac + d8 pipeline; use the cmdline-tools d8.
- **Why:** smallest toolchain; build-tools 34's d8 crashes on classes from JDK 21/25.
- **Consequences:** signing key at `~/.android-signing/` (outside the repo). **Back it
  up**: updates must be signed with the same key.

### D3 — Accept that Home and app buttons are invisible to apps (Oct 17)
- **Evidence:** probe build logging every key with its scan code: Back/OK/D-pad/Menu
  arrive from the remote (device 8); Home and the YouTube button never do; logcat shows
  "KPM proceeds the key" in system_server instead.
- **Consequence:** all Home and button features react to effects (window events, system
  log), never to the keys themselves.

### D4 — Escape to Amazon home must stick (Oct 17)
- **Problem:** after a double-press or long-press escape, Amazon home's own focus events
  read as a new Home press and bounced the user back to the launcher.
- **Decision:** a sticky `stayOnAmazonHome` state that blocks every automatic redirect
  until another app opens; a single Home press ends it.

### D5 — Drop the long-press escape (Oct 17, user request)
- **Why:** taking over the long-press panel broke its tiles (profiles, sleep,
  mirroring). Off by default and removed from the UI; double-tap is the escape.

### D6 — Read the system activity log (READ_LOGS) (Oct 17)
- **Evidence:** `ActivityTaskManager` logs exactly one `START … cat=[HOME] … from uid 0`
  per Home press, and app buttons as `clickStreamReftag=r_btn_app_NN` (from uid 1000).
  Window events repeat for up to ~1 s per press, so double-press detection from window
  events alone was unreliable (sometimes worked, sometimes didn't).
- **Decision:** optional `LogWatcher` with a one-time `pm grant … READ_LOGS`.
- **Consequences:** exact double-tap, instant Home (D8), button-only remapping (D10).
  Without the grant, the window-event fallback still works.

### D7 — Only system-buffer logs; trust precise mode per Fire OS build (Oct 17)
- **Why:** the START lines are only in the `system` buffer (reading `main` doubled the
  work). A future Fire OS could change the log format; then precise mode would wait for
  lines that never come, with the fallback double-tap switched off.
- **Decision:** precise Home/button handling only after such a line was seen on the
  current `Build.DISPLAY`; otherwise fall back.

### D8 — Cover the screen on the Home log line (Oct 17)
- **Evidence:** the log line arrives ~10 ms after the press, before Amazon home paints.
  Cover visible after 3 ms, launcher started after 40 ms; Amazon home never reached the
  front. Creating the cover window the first time took ~200 ms, so it is attached once
  at start and only toggled.

### D9 — Back on the launcher's main screen does nothing (Oct 17, user request)
- **Problem:** LtvLauncher (Flutter) closes on Back at its root, dropping to Amazon home
  and then bouncing back.
- **Decision:** swallow Back only when the launcher shows its main screen, recognised by
  a layout fingerprint; menus remove that container from the tree, so Back still closes
  them. A launcher-agnostic rule, no app-specific code.

### D10 — Remap buttons only when the button was pressed (Oct 17, user request)
- **Problem:** remapping by window meant opening YouTube from the launcher also jumped to
  TizenTube.
- **Decision:** with READ_LOGS the mapping fires only for launches the log attributes to
  a button; without it, a recent OK press means "opened by the user".

### D11 — Netflix-style buttons: the direct start (Oct 18)
- **Problem:** Netflix → Nuvio did nothing; Netflix opened.
- **Evidence:** live capture of the Netflix press:
  `START u0 {act=MAIN cat=[LAUNCHER] … pkg=com.netflix.ninja … (has extras)} from uid 1000`,
  with no `r_btn_app` Appstore hop. YouTube goes Appstore → app.
- **Cause:** `LogWatcher` only recognised the Appstore form, so the Netflix window was
  treated as a manual open (D10) and left alone.
- **Decision:** also treat a `MAIN`/`LAUNCHER` start with extras from uid 1000 as a button
  launch. Launchers, Amazon home and Alexa start apps under their own uids, so a manual
  open never matches.

### D12 — Mapped app opens on top of the launcher; stop the original (Oct 17)
- **Problem:** Back from TizenTube went to YouTube (beneath it), which re-triggered the
  mapping: a loop.
- **Decision:** start the launcher, then the mapped app, so Back lands on the launcher;
  stop the original in the background after 2.5 s (it popped its own sign-in screen).

### D13 — Start-up app on Amazon home's first window (Oct 18)
- **Evidence:** service connects ~22 s after power-on, 0.2 s before Amazon home starts;
  `BOOT_COMPLETED` reaches apps after ~1 min (Fire OS delivers it app by app).
- **Decision:** open the start-up app on Amazon home's first window; `BootReceiver`
  stands down if the service already did. The start-up app may be any app.

### D14 — Alexa voice search goes to the app (Oct 17–18, user request)
- **Evidence:** Alexa logs that apps without `amazon.speech.permission.SEND_DATA_TO_ALEXA`
  don't get voice searches; it opens `DirectiveSearchResultsActivityDI` with the words in
  `id/search_text`. That field is "not important for accessibility": hidden from services
  unless `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` is set (only while reading).
  `findAccessibilityNodeInfosByViewId` returned nothing; a plain tree walk works.
- **Decision:** the ladder in ARCHITECTURE.md (link → ACTION_SEARCH → type → Search label
  → side menu). TizenTube verified with the YouTube link.

### D15 — Amazon's search page can't be reopened; decide before leaving it (Oct 18)
- **Evidence:** `amzn://com.amazon.tv.launcher/search` requires
  `com.amazon.tv.launcher.permission.SHOW_SEARCH_RESULTS`.
- **Consequences:** "show Alexa's results if the app has no search" must be decided
  before leaving the page. An up-front probe while Alexa listens was tried and dropped:
  Alexa's listening bar hides the app from accessibility, so the probe saw nothing
  (logged `this=-1` every time). A per-app failure lock-out was also dropped: one bad
  attempt disabled Nuvio permanently. Replaced by (a) the countdown panel with Back =
  Alexa's results, and (b) a user-controlled list "Apps that keep Alexa's results".

### D16 — Nuvio: no app-specific code; walk the side menu (Oct 18)
- **Evidence:** Nuvio has no search deep link (its source: `nuvio://` covers titles,
  add-ons, parties only). Its collapsed side menu exposes no labels; it refuses
  accessibility focus (`ACTION_FOCUS` returned false), so the menu can't be expanded to
  read "Search". A position-based click hit Library.
- **Decision:** generic: try side-menu items top to bottom, waiting for a search box after
  each (screens take up to ~3–4 s), and remember the item that worked per app.
  Measured: 6.7 s → 4.2 s from Nuvio home; 0.3 s from its search tab.

### D17 — Close Alexa's page before handing off (Oct 18)
- **Problem:** Back from the app later landed on Alexa's results.
- **Decision:** a Back on Alexa's page (returns to the app it opened over), then the
  search.

### D18 — Hold Back to close an app (Oct 18, user request)
- **Decision:** hold 0.7 s → active home + stop the app; short press re-sent as
  `GLOBAL_ACTION_BACK`. Not on the launcher or Amazon's own screens. Switchable.

### D19 — Logs in memory; test hooks via dumpsys (Oct 18)
- **Evidence:** Fire OS app logcat holds ~14 s (256 KB, filled by Amazon's apps); a file
  in `Android/data` is not readable over adb on Android 11.
- **Decision:** `RingLog` (400 lines) read via `dumpsys`; `TestHooks` behind the shell-only
  `DUMP` permission for automated tests.

### D20 — Fire OS update blocking: not built into the app (Oct 17–18)
- **Facts:** blocking requires disabling Amazon's updater packages (adb, `pm disable-user`)
  or DNS-blocking Amazon's update hosts. An app cannot disable system packages. Running
  the adb route here, and building an in-app local-adb helper for it, were stopped by the
  developer assistant's safety checks, so they were not done.
- **Decision:** the app shows read-only updater status and the options (Settings → Fire OS
  updates); the user chooses DNS blocking (recommended) or runs the adb commands. The app
  re-verifies itself after any update (D7) and notifies the user.
- **Hosts to block:** softwareupdates.amazon.com, updates.amazon.com,
  d1s31zyz7dcc2d.cloudfront.net, prod.ota-cloudfront.net, amzdigital-a.akamaihd.net.
  Never ftv-smtv.ntp-fireos.com (clock).

### D21 — Look and logo (Oct 17–18)
- Midnight navy + teal, cards per section, one-line description under every setting.
- Logo: a home outline with an amber arrow leaving it — "Home, redirected", the app's core
  promise. Banner tagline: "Home goes where you want."

## Known limits (by design, not bugs)
- Amazon's own home screen content (ads, rows) can't be changed without root.
- Apps that paint their own keyboard and have no side menu can't be searched by voice.
- Voice search matches the English word "Search" in step 4 of the ladder.
- An app button always starts its own app first (the system does it before any app can
  react); it is covered and replaced.
