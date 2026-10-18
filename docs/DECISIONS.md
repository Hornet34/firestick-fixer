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
  the adb route here, and building an in-app local-adb helper for it, were not done at the
  time.
- **Decision:** the app shows read-only updater status and the options (Settings → Fire OS
  updates); the user chooses DNS blocking (recommended) or runs the adb commands. The app
  re-verifies itself after any update (D7) and notifies the user.
- **Update (Oct 18, owner's device):** `pm disable-user` on `com.amazon.device.software.ota`
  is refused on Fire OS 8.1.8.5 ("Cannot disable a protected package"). `pm suspend --user 0`
  is accepted and survives a reboot, but it does NOT stop the updater: after the reboot the
  OTA process is running again (persistent), its `OtaService` is active and bound by
  `com.amazon.vizzini`. Suspend only blocks its UI and notifications, so it is not a
  verified block and the app does not present it as one. An app also cannot call suspend
  (needs a system or shell permission). Remaining candidates: DNS block (router) or an
  in-app local VPN that drops the update hosts (untested).
- **Hosts to block:** softwareupdates.amazon.com, updates.amazon.com,
  d1s31zyz7dcc2d.cloudfront.net, prod.ota-cloudfront.net, amzdigital-a.akamaihd.net.
  Never ftv-smtv.ntp-fireos.com (clock).

### D21 — Look and logo (Oct 17–18)
- Midnight navy + teal (kept, see D22), cards per section, one-line description under every setting.
- Logo: a home outline with an amber arrow leaving it — "Home, redirected", the app's core
  promise. Banner tagline: "Home goes where you want."

### D22 — Logo: a Fire TV Stick with a wrench, not Amazon's logo (Oct 18)
- **Request:** use the Amazon Fire TV logo, modified.
- **Decision:** an original drawing of the Fire TV Stick itself (orange HDMI dongle with
  its plug) with a wrench on it. Copying Amazon's logo or wordmark, even modified, would
  suggest Amazon made or endorses the app (trademark) and risks takedowns of the repo.
- **Revised the same day (owner's feedback):** the orange theme was replaced again by
  the earlier navy + teal, which reads calmer on a TV and doesn't look like an Amazon
  product. The stick is drawn in teal, and the wrench was enlarged and turned to cross
  the stick (it was too small to read at icon size); the HDMI plug stays visible.
  Amber for "attention", green for "all good"; tagline "Your Fire TV, your rules."

### D23 — Test every feature, even switched-off ones (Oct 18, owner's rule)
- **Decision:** `tools/device-test.sh` records the six feature switches, turns all on,
  tests, and restores them on exit (also on failure or Ctrl-C) via the `set` test hook.
- **Found by it right away:** two harness bugs, not app bugs. `dumpsys` runs on the app's
  main thread, so a hook that waited for the main thread deadlocked; and a check relied
  on a log line only written in diagnostic mode (now always logged).

### D24 — VPN toggle for any VPN app (Oct 18, user request)
- **Request:** a VPN switch in Amazon's top bar and long-press menu, for any VPN app (Proton on the owner's stick).
- **Facts:** those surfaces are Amazon system UI; an app cannot add icons to them. Android has no API to
  switch another app's VPN (one VPN at a time, consent per app). Proton publishes no intents.
- **Decision:** generic, no app names. VPN apps = packages with a `BIND_VPN_SERVICE` service for
  `android.net.VpnService`; state = any network with the VPN transport. Toggle = open the VPN app, click its
  connect/disconnect button found by label, confirm by the network state, press Back. Menu on the open
  long-press panel triggers it; a status chip (non-focusable overlay) shows the state. Settings: VPN card.
- **Verified on Proton TV 5.20 (device):** connect and disconnect both work through the toggle hook (~3 s, VPN off again
  afterwards). Proton's "Quick connect" is a heading; its focused tile is the button, so the click falls back to the
  focused clickable node. The explicit launch component is needed: Proton TV's launcher entry has no CATEGORY_DEFAULT.
- **Menu press on the long-press panel:** confirmed working by the owner on the real remote.

### D25 — Hold-Menu quick menu, background toggle cover, VPN status (Oct 18, user request)
- **Request:** VPN status on the home screen; a menu of our own on a held Menu key; the VPN switch done behind a
  progress screen instead of showing the VPN app.
- **Decision:** a 0.5 s Menu hold opens a non-focusable overlay menu driven by the key filter (Up/Down/OK/Back);
  items are a list (VPN, settings) so more can be added. The VPN switch runs under a full-screen cover
  ("VPN: connecting…", Back cancels). A status chip shows when a home screen (Amazon's or the launcher) opens and when a
  VPN connects or drops (NetworkCallback). Switches: quick menu, VPN status (both on).
- **Cross-check:** `dumpsys window policy` lists long-press behaviours only for Back, Home and Power: Fire OS has
  nothing on a held Menu. Other apps' own handling cannot be enumerated; a short press is untouched and the
  switch turns the menu off.
- **Tested over adb:** menu rendering (screenshot), OK on the VPN item disconnects through the cover, toggle both ways.
  Not tested: real held-Menu press, chip on home arrival, other apps' reaction.

### D26 — Quick menu restyled like Amazon's panel; all VPN badges removed (Oct 18, user request)
- **Request:** hold time longer; menu too big, should copy the launcher's panel (place, look, animation); no badges
  on single Menu / Home presses; no Menu-on-panel toggle. Only the held-Menu menu stays.
- **Decision:** hold = 1.5 s. Menu look measured from a capture of Amazon's long-press panel (opaque #2A2A2A card, top-right,
  40 px margin, 574 px wide, clock line, 94 px rows, #F0F0F0 pill on the selected row), scaled by screen height;
  slides in from the right with a fade (240 ms), out in 150 ms. Removed: status chip, home-arrival/changed
  notices (NetworkCallback), "Show VPN status" switch, Menu-on-panel toggle. The background-toggle cover stays.

### D27 — Menu hold = Home's long-press time; items switchable; more items (Oct 18, user request)
- **Evidence:** `settings get secure long_press_timeout` = 400 ms, which Android's key policy uses for Home. The menu
  itself builds in 15–30 ms (logged), so the earlier 1.5 s hold was the whole delay.
- **Decision:** hold time = `ViewConfiguration.getLongPressTimeout()`. Items: VPN, Free up memory (kills background
  processes of non-system apps except the one on screen, launcher, VPN app, this app), Amazon home, Fixer settings;
  each can be hidden in Settings → Quick menu.
- **Fix (same day):** the settings screen wires focus by hand (one explicit up/down chain); the new VPN and Quick menu rows
  were not in it, so the remote skipped them. They are in the chain now (checked over adb: Up/Down visits each row).

### D28 — Multi-select picker for "Apps that keep Alexa's results" (Oct 18, user request)
- **Problem:** the picker closed after every pick, so adding several apps meant reopening it each time, and nothing
  showed which apps were already on the list.
- **Decision:** `AppPicker.showMulti`: OK ticks/unticks and the list stays open; ticked rows get a check mark and a
  tinted background; every change saves at once; "Done" closes, "Clear all" unticks everything.
- **Model kept:** the list holds the exceptions (apps that keep Alexa's own results); every other app gets the
  in-app search. Reasons are in the owner conversation: the feature's promise is "search in the app I'm in", so
  that is the default; a new app works without setup; an exception list stays short.
- **Default (same day, owner's yes):** Amazon's own apps (`com.amazon.*`) keep Alexa's results unless the user unticks
  them. Stored as two sets: explicit ticks and explicit unticks (an unticked Amazon app stays unticked); the picker and the
  row count show the effective state. Checked on the device: 20 ticked by default, 19 after one untick, 20 after re-tick.

### D29 — Remember the way into an app's search; a clear card when it fails (Oct 18, user request)
- **Request:** remember what worked per app; if a remembered way fails, redo the whole search; no silent switch to
  Alexa's page for an app the user chose for in-app search; a proper message instead.
- **Decision (memory):** the way that reached the search box is saved per app as a sequence: `box` (already open),
  `label` (a Search button), `menu:N` (side-menu item N), or a chain such as `menu:0+label` (Nuvio from a cold start
  needs both). Saved in this app's own preferences (`shared_prefs/home_on_fire.xml`, key `voice_route2:<package>`), so it
  survives reboots and app updates. A box that was merely already on screen never replaces a known route.
- **Replay:** a remembered route is tried on its own. If it does not reach a box (no action by 7 s, or 5 s after its
  last click), it is forgotten and the full ladder runs again from where the app is, and whatever works is saved.
- **Give-up patience:** "no side menu" counts as an answer only after 7 s (cold Nuvio needs ~7 s to draw its menu;
  before this the ladder gave up at ~3.5 s and failed).
- **Failure:** no automatic switch to Alexa. Alexa's page cannot be reopened (D15) and the user chose in-app search for that
  app. A card (same style as the quick menu) says no search box was found and offers "Show Alexa's results for <app>"
  (adds it to the exceptions for next time), "Open Fixer settings" and "Dismiss"; it closes itself after 20 s.
- **Measured (Nuvio):** warm replay 0.3 s; cold start ~10 s in total, of which ~7 s is Nuvio starting. A wrong remembered
  route fell back and still reached search.

### D30 — One voice-search screen, shorter countdown; "Free up memory" removed (Oct 18, user request)
- **Back button check:** a short Back is held back and re-sent as a system Back (D18). Measured on the stick in TizenTube
  and Nuvio after voice searches: the same number of Backs leaves the app with our filter as with a real Back key
  (TizenTube: 2 stay, the 3rd leaves; Nuvio: 3 stay, the 4th leaves). Nothing in this session's work changes Back. A voice
  search opens the results as a link, which replaces the page, so earlier searches are not in the app's history.
- **Voice screens:** the countdown panel now turns into "Opening search in <app>…" and stays up until the app shows
  content (+2 s, cap 6 s), instead of being replaced by a black cover. Countdown was shortened to 1.0 s, then put back to 1.5 s at the owner's request.
- **Not done:** starting the in-app search during the countdown. Back would have to bring Alexa's page back, which cannot
  be reopened (D15), and it cannot be tested without a real Alexa search.
- **Free up memory removed:** `killBackgroundProcesses` called by this app ends nothing on Fire OS 8 (Nuvio, cached,
  stayed alive; `adb shell am kill` does kill it). The same call is used by hold-Back and remapped buttons (D12, D18) to
  stop the app left behind, so that stopping is best effort and may not happen.

### D31 — Can the query be handed to Alexa, or the in-app search start first? (Oct 18, user question)
- **Before Alexa:** no. The words are only known from Alexa's results page (D14); nothing lets an app stop Alexa or see the
  speech first.
- **Handing text back to Alexa:** no. From this app's uid, `SHOW_TEXT_SEARCH` and `SHOW_SEARCH_RESULTS` are refused with a
  SecurityException (both `signature|privileged`), Alexa's own permissions are `signature`, and `ASSIST` only opens
  Alexa's voice listening screen (voice only, no text).
- **Backing out to Alexa's page:** measured with Amazon Settings standing in for Alexa's page: Back from the app returned
  to the page beneath it, after 1 Back (app already running) or 4 Backs (cold start). So "cancel" could be done by
  pressing Back until Alexa's page is front again. Not verified on the real page (it may close itself); the gain from
  starting the app during the countdown is about one second.
- **Decision:** nothing built; the countdown + single panel from D30 stays. Test hook `startintent <action> [uri]`
  starts an intent from the app's own uid.

### D32 — "Free up memory" dropped for good (Oct 18, owner's decision)
- **Tried:** (1) `killBackgroundProcesses`: ends nothing when called by this app on Fire OS 8 (D30); (2) opening each app's
  Settings App info page and pressing "Force stop" by accessibility. The button works (no confirmation, a process
  vanished), and this app may open that page.
- **Why dropped:** the automatic run was unreliable (4 of 8 apps skipped: the info page was not recognised, ~6.5 s lost per
  skipped app); a full run would take 15-20 s behind a cover; it drives Fire OS Settings text that an update can change,
  next to "Clear data" and "Uninstall"; and the gain is small, because Android ends cached apps by itself when memory is
  needed and a force-stopped app costs a cold start on its next open.
- **Alternatives that remain:** hold Back leaves an app for the launcher; Amazon's Settings -> Applications has Force
  stop for one app. Removed: menu item, settings row, strings, icon, code and the `kill` test hook.

### D33 — Hold Back backs out of the app; stale-app voice search fixed; an uninstall incident (Oct 18)
- **Bug 1 (hold Back, app resumes where it was):** hold Back used to go to the launcher and "stop" the app with
  `killBackgroundProcesses`, which does nothing for this app (D30), so the app resumed on the same page. Now it presses
  Back until the app leaves (cover "Closing <app>…", up to 14 Backs, then falls back to the launcher), then continues to
  the user's launcher. Measured: TizenTube 4-5 Backs. TizenTube (a YouTube TV / Cobalt app) keeps its web state in a live
  process, so it still resumes on its last page; only ending the process resets it.
- **Force stop via Settings was tried and dropped:** an accessibility click on the "Force stop" row reported success but
  stopped nothing, and the rows next to it are "Uninstall" and "Clear data".
- **Bug 2 (Alexa result became TizenTube's):** after hold Back the last remembered app was still TizenTube, so a voice
  search from the home screen was sent to it. A home screen (the launcher, or Amazon's) now marks "no app" until a real app
  is in front again (`homeSinceApp`), so Alexa's own results stay.
- **Incident:** while checking the Force stop route by hand I sent blind D-pad presses on TizenTube's App info page over
  adb; the focus landed on "Uninstall", which acts without confirmation, and TizenTube was uninstalled from the owner's
  stick (its data too). Rule: never send blind key presses into Fire OS Settings; read the focused row first.

### D34 — Proposed: an in-app local ADB helper, restricted to a whitelist (Oct 18, owner's direction)
- **Clarification from the owner:** the earlier rule was about not running critical commands on the stick unattended; an
  app may have its own helper if it can never uninstall or wipe anything.
- **Why:** nothing an app can call ends another app's process on Fire OS 8 (D30, D33), and driving Settings is fragile and
  was dangerous (D33). `am force-stop <package>` over ADB is instant and exact. The stick already listens for ADB on
  `*:5555` (checked: `service.adb.tcp.port=5555`, `adb_enabled=1`), so the app could connect to itself on loopback.
- **Design limits to hold to:** off by default and switched on by the owner; one place that builds commands from fixed
  templates (`am force-stop <package>`, and the app's own two `pm grant`s); package names validated and never system,
  launcher, VPN app or this app; no free-form shell, no `pm uninstall`, `pm clear` or `pm disable`; every command logged.
- **Unknowns to test first:** whether Fire OS accepts the loopback connection and shows the one-time "Allow" prompt for the
  app's key; whether Amazon updates switch ADB debugging off. Not built yet.

### D35 — In-app ADB helper test: blocked on this Fire OS (Oct 18)
- **Built and tried:** a minimal ADB client (wire protocol, RSA key, Allow prompt) that connects to the stick's own ADB
  service and runs one read-only command (`getprop ro.product.model`) from a fixed list.
- **Result:** the client's messages are correct (from a computer the same service answered `CNXN` with an `AUTH` token), but
  from the app every connection, to `127.0.0.1:5555` and to the stick's own address, was reset at the first write. adbd
  logged nothing for it and there was no SELinux denial in the log, so the connection never reached adbd: something in
  Fire OS blocks apps from reaching the service. The cause could not be found without root.
- **Decision:** the helper, its `INTERNET` permission and the test command were removed; the app keeps having no
  `INTERNET` permission. D34 (the idea and its limits) stays on record as not achievable on this build.

### D36 — D35 re-checked: adbd itself drops connections from the stick (Oct 18)
- **Question from the owner:** was the test spoiled because the laptop already holds the connection on port 5555?
  **No:** while the laptop's `adb` session stayed connected, a second raw `CNXN` from the laptop to `:5555` got an
  `AUTH` answer at once. adbd accepts several TCP clients; the laptop's session doesn't block anyone.
- **Re-test without the app:** the same `CNXN` sent from the stick's own shell (`adb shell … | nc`, uid 2000, not an
  app) to `127.0.0.1`, the stick's IPv4 address, `::1`, both global IPv6 addresses and the IPv6 link-local address of
  `wlan0`: 0 bytes back every time.
- **Correction to D35:** the connection *does* reach adbd's socket. `/proc/net/tcp6` shows each one in `TIME_WAIT`
  on the `:5555` side, which means the server end closed first. So Amazon's adbd closes any connection that comes from
  the stick itself, whatever the uid or address. It isn't an app permission, a firewall or SELinux. Nothing on the
  device can get around it.
- **Consequence:** an ADB connection on the device is not possible on this build. The remaining route is a helper process
  started once from a computer (`adb shell`, a Shizuku-style server running as shell uid) that the app reaches through
  binder, not a socket. Feasibility test planned, not run.

### D37 — Shizuku feasibility, stage 1: the server runs on Fire OS 8.1.8.5 (Oct 18, owner approved)
- **Files:** Shizuku v13.6.0 (`shizuku-v13.6.0.r1086.2650830c-release.apk`, sha256 `6e273ab0…7b790f`) from the GitHub
  release; APK Signature Scheme v2 is valid, one signer `CN=Rikka`, cert SHA-256 `268b5590…30db30` (GitHub gives no
  checksum, so only the signature was checked). It has `lib/armeabi-v7a`, the only ABI the stick runs. Client libraries
  `dev.rikka.shizuku` api/provider/aidl/shared 13.1.5 from Maven Central (SHA-1s match), for stage 2. All in
  `../shizuku-test/dl/`. Pre-test app backed up as `firestick-fixer-before-shizuku.apk` (same sha256 as the installed one).
- **Done:** `adb install` of Shizuku, then started from the laptop with
  `adb shell '$(dirname $(pm path moe.shizuku.privileged.api | cut -d: -f2))/lib/arm/libshizuku.so'`.
- **Result:** `shizuku_server` runs as `shell` (parent init, so it outlives the adb command, RSS ~85 MB) and sent its
  binder to the Shizuku app. The only SELinux denials were harmless reads of `device_config` properties. No app has
  been granted access yet.
- **Next (stage 2, waits for the owner):** a test build of this app with Shizuku's provider and one test hook that runs
  `getprop ro.product.model` through Shizuku and measures the time. Stage 2 has no force-stop.
- **Rollback:** reboot, or `adb shell pkill -f shizuku_server` (stops the server); `adb uninstall
  moe.shizuku.privileged.api`; `adb shell rm -f /data/user_de/0/com.android.shell/shizuku.json` (the server's grant
  list, written once an app is allowed); `adb install -r firestick-fixer-before-shizuku.apk`.

### D38 — Wireless debugging works on Fire OS 8 and accepts connections from the stick itself (Oct 18, owner approved)
- **Why:** a helper started from the laptop dies at every reboot. The owner wants everything in one app, without a computer
  after setup. Android 11's wireless debugging (TLS adb) is the route that lets Shizuku start itself.
- **Test:** `settings put global adb_wifi_enabled 1`. Fire OS still has the AOSP dialog
  (`com.android.systemui/.wifi.WifiDebuggingActivity`, "Allow wireless debugging on this network?"). It opened behind the
  screensaver and the setting snapped back to 0 until answered. The owner ticked "Always allow on this network" and
  pressed Allow, so the home Wi-Fi is now trusted. adbd (uid 2000) then listened on a new port (34895 this time).
- **Result:** the plain `CNXN` got `STLS` (the switch to TLS) back from the laptop *and* from the stick's own shell, to
  `127.0.0.1`, the stick's IPv4 address and `::1`. The D36 block applies only to the classic `:5555` port; the wireless
  debugging port accepts connections from the stick itself.
- **Also found (read-only):** `cmd adb` exists. Shell holds `MANAGE_DEBUGGING` and `WRITE_SECURE_SETTINGS`, so a one-time
  setup step can start pairing through `IAdbManager` (the QR-pairing call takes a password the caller picks). This
  matters because Fire OS has no "Pair device with pairing code" screen. This app already holds `WRITE_SECURE_SETTINGS`,
  so it can switch wireless debugging back on after a reboot (no dialog now that the network is trusted).
- **Restored:** `adb_wifi_enabled` set back to 0; listening ports identical to before the test; the laptop's `:5555`
  connection never dropped. Shizuku's server from D37 still runs.
- **Not yet tested:** TLS connection from an *app* uid (it needs `INTERNET`, loopback only); pairing without a Settings
  screen; whether the trusted network survives a reboot or a Wi-Fi change.

### D39 — In-app TLS adb client: key approved, test build ready (Oct 18, owner granted full permission)
- **7-day key expiry found:** the stick rebooted (boot reason `reboot`) and the laptop came back `unauthorized`. Its key's
  `lastConnection` was Oct 10, and Android 11 drops an "always allow" key after 7 days without a fresh connection
  (`adb_allowed_connection_time`). Set `settings put global adb_allowed_connection_time 0` (never expire). Undo:
  `settings delete global adb_allowed_connection_time`. (The `/adb_keys ENOENT` error in the log came from `dumpsys adb`
  reading the read-only system key file; it's harmless.)
- **Codes confirmed:** `service call adb 12` = 1 (matches `cmd adb is-wifi-supported` true), `13` = 0 (QR pairing not
  supported). So pairing isn't used; the app's key is approved the classic way instead.
- **App key approved:** a second adb server with its own HOME (`-P 5038`) made a fresh RSA key (fingerprint
  `C2:57:FA:B8:…:D9:FB`) and connected to `:5555`. The prompt was read with `uiautomator dump`, the fingerprint
  checked against the key, then "Always allow" was ticked and Allow pressed, with the focus checked before each press. `dumpsys
  adb` lists 2 keys; `getprop` through that key worked. Key + cert (DER) are in `../shizuku-test/appkey/`.
- **Test build:** `AdbTls.java` (CNXN, STLS, TLS 1.3 with the app key as client cert, one `shell:` stream; commands only
  from a fixed list, now just `getprop ro.product.model`; port via mDNS `_adb-tls-connect._tcp`, restricted to the
  stick's own addresses) + hook `dumpsys … HijackService adbtls key|forget|wifi on|off|port|run <port|auto>`. Manifest
  gains `INTERNET` (loopback only). Same key, versionCode 300.
- **Owner's step:** installing it and storing the key in the app were left to the owner to run by hand. Shizuku's server died with the reboot (Shizuku itself is still installed).
- **Rollback:** `adb install -r firestick-fixer-before-shizuku.apk` (removes `INTERNET` and the hook; also run
  `adbtls forget` first to delete the key file); `settings put global adb_wifi_enabled 0`; revoke the app key with
  `service call adb 3` (clears ALL keys, the laptop then needs Allow again).

### D40 — The app runs adb commands on its own stick: it works (Oct 18, run by the owner)
- **Result:** with the port taken from `/proc/net/tcp6`, `adbtls run 45161` returned `AFTMA08C15`. Trace: connected 2 ms,
  `STLS` 3 ms, TLS 1.3 done 714 ms, adbd's `CNXN` (key accepted) 718 ms, output 761 ms. The app also switched wireless
  debugging on and off itself (`WRITE_SECURE_SETTINGS`), with no dialog. So a single app, set up once from a computer,
  can run whitelisted adb commands without a computer afterwards.
- **Cost:** ~0.7 s is the TLS handshake (RSA on this CPU). Keeping one session open would make later commands ~40 ms.
- **Open:** mDNS discovery from the app found nothing (`port=-1` after 5 s, twice). Apps can't read `/proc/net`. Next build
  traces the NSD callbacks and falls back to scanning 127.0.0.1 ports 32768–60999 for the one that answers CNXN with
  STLS. Also: the first hook call right after `install` failed ("No services match") because the accessibility service
  had not reconnected yet, so wait a few seconds after installing.

### D41 — Port discovery works; full in-app adb round trip under 0.1 s (Oct 18, run by the owner)
- **mDNS:** `_adb-tls-connect._tcp` found `adb-GT54TR0563230JRX-KY9ahU` (reported twice; the second resolve fails with
  code 3, already active, which is harmless), resolved to `<stick-ip>:34313` in 7–24 ms. The D40 failure came right
  after a reinstall, when wireless debugging hadn't been switched on yet.
- **Loopback scan fallback:** 700 ms, exactly 1 open port answered STLS (the right one). Kept as the fallback.
- **Round trip:** connect 1–2 ms, TLS 1.3 55–65 ms (the 711 ms in D40 was the first, cold run), command output 86–97 ms.
  An adb command from the app costs ~0.1 s, versus 1.5–6 s for the accessibility routes behind a cover.
- **Proven:** one app, set up once from a computer (approve its key, two grants), can switch wireless debugging on, find
  the port, run whitelisted adb commands on its own stick and switch it off again. Shizuku isn't needed.
- **Open for the real feature:** how setup approves the app's key without copying its private key to the laptop (the
  test key was made on the laptop); which commands go on the whitelist (D34); when wireless debugging is on (only for
  the moment a command runs, ~0.1 s, versus kept on).

### D42 — Fast actions built: the app's own adb, set up once (Oct 18, owner approved the plan)
- **Plan:** `docs/LOCAL-ADB-PLAN.md`. Pre-build checks: the port answers 75 ms after `adb_wifi_enabled=1`; no
  notification is posted while it is on; the trusted Wi-Fi survived the reboot (trusted 13:29, reboot ~13:53, enabled
  without a dialog at 14:01). So wireless debugging is on only around actions (20 s idle, off at screen-off).
- **Key (`AdbKey`):** RSA 2048 made by the app, kept in its private files (`files/adb/`); a hand-built self-signed v3
  certificate for TLS; the adb public-key format and the AUTH signature. Checked on the computer against adb itself:
  the public key is byte-identical to `adbkey.pub`, the fingerprint matches the TV prompt, the signature equals
  OpenSSL's `pkeyutl -sign -pkeyopt digest:sha1`. Not in the Android Keystore: TLS 1.3 client auth with Keystore
  keys needs RSA-PSS support in Conscrypt, which wasn't tested; the file route was (D40–D41).
- **Approval:** adbd shows its prompt only for connections from elsewhere (D36), so `tools/setup-adb.sh` relays the
  app's connection once (`adb reverse tcp:5560` → computer `socat`/Python on 5561 → stick `:5555`; if adb reverse
  isn't usable, the LAN address of the computer). The private key never leaves the stick.
- **Commands (`AdbActions`):** `cmd activity force-stop` (native `cmd`, not `am`, which starts a JVM per call),
  `ps -A -o NAME`, `dumpsys media_session`, `getprop ro.product.model`. Package checks: name pattern, installed, not
  system/updated-system, not `com.amazon.*`/`android*`/`com.android.*`, not this app, the launcher, the keyboard, an app
  with an enabled accessibility service or notification listener (a force-stop unbinds those for good), or the
  connected VPN app.
- **Uses:** hold Back → launcher, then force-stop (switch "Hold Back closes the app fully", on by default; system apps
  still back out). Quick menu **Free up memory** returns (D32 dropped it for lack of a working stop), shown only when Fast
  actions work; skips the app on screen and playing media.
- **Fallbacks:** not set up, ADB off, Wi-Fi not allowed (the system resets the setting at once and shows its dialog),
  key rejected, or any error → hold Back backs out as before (D33), the menu item hides, Settings → Fast actions says
  why. The test-build key (D39) is deleted from the app on first use; adbd still lists it until keys are cleared.
- **Status:** built and checked on the computer; not yet installed or run on the stick (the owner runs installs).

### D43 — Fast actions verified on the stick (Oct 18, run by the owner)
- `tools/setup-adb.sh <stick-ip>`: the app made its key (fingerprint `D7:E7:92:2E:…:7D:3D`); the relay went through
  `adb reverse` (the LAN fallback wasn't needed); the owner pressed Allow; `OK approved`. The Wi-Fi was already trusted.
  First command 1093 ms (cold: key load, TLS setup).
- `device-test.sh fast hold_back hold_back_force`: all PASS. `fast` (warm) 30 ms; the checks refuse the launcher, this
  app and Amazon home; hold Back without Fast actions still backs out; with them it force-stopped Downloader (no
  process left) and opened the launcher. Settings were restored.
- Cleanup: Shizuku uninstalled, the laptop copy of the test key deleted. The D39 test key is still approved in adbd
  (harmless; nothing holds its private key any more).
- **Full suite (run over adb, same day):** `tools/device-test.sh`: 20/20 PASS (all earlier features plus `fast`,
  `fast_checks`, `hold_back_force`); settings restored.
- **Free up memory (`adb free <front app>`):** running third-party apps were TizenTube, Nuvio, Amazon's YouTube app,
  the launcher and this app. It stopped Nuvio and TizenTube in 367 ms (one session, reused from the hold-Back test
  14 s earlier) and left YouTube (system), the launcher and this app running.
- **Idle close:** "session closed (idle)" exactly 20 s after the last command; `adb_wifi_enabled` back to 0, only the
  `:5555` listener left.
- **Not yet run:** the item in the real quick menu, and a real remote hold-Back press.

### D44 — Setup checklist on the TV; tested from a clean install (Oct 18, owner's request)
- **Why:** install with Downloader on the TV, then the app itself shows which `adb` commands are left; each disappears
  once done. Required first, optional boosts after.
- **Checklist (top of the settings screen):** the TV's own address (`adb connect <ip>:5555`); **1 Required**
  `pm grant … WRITE_SECURE_SETTINGS`; then **optional boosts**: **2** `pm grant … READ_LOGS`, **3** Fast actions as a
  one-liner (`curl -sL …/tools/setup-adb.sh | bash -s <ip>`, which also does 1 and 2). Re-checked every 2 s while
  the screen is open. When the required grant arrives and the service is off, the app switches its service on
  itself. The optional steps appear only after step 1: with all of them on screen at once, the focused service row
  scrolled step 1 off the top (seen on the first screenshot). Once only optional steps are left, OK on the card hides
  them (`setup_optional_hidden`); the Precise and Fast actions rows below still say how to add them. Step 3 ticks only
  after a Fast action has really worked once (`fast_actions/verified`), not just because a key exists.
- **setup-adb.sh as a one-liner:** under `curl | bash`, stdin is the script itself and `adb shell` swallowed the rest of
  it (the first run stopped after step 1); every adb call now reads `/dev/null`, and the Wi-Fi prompt reads `/dev/tty`.
  An empty `adb result` read (a dumpsys hiccup) made it give up during the Allow prompt; now it keeps waiting. It also
  switches the accessibility service on itself (appending to the list, keeping others).
- **Clean-install test:** settings exported with the new hook `prefs export` (22 values), app uninstalled, fresh install:
  checklist showed connect + step 1 (no grants, service off). `pm grant WRITE_SECURE_SETTINGS` → step 1 ticked and the
  service switched itself on within ~1 s; `pm grant READ_LOGS` → step 2 ticked; the piped script → the TV asked for the
  app's new key (fingerprint `61:3C:1D:…:01:E6`, checked against `adb status`), the owner pressed Allow, Fast actions
  `OK` in 63 ms → the whole card disappeared. `prefs import` brought every setting back (launcher, both button
  mappings, start-up app, switches). Full `device-test.sh`: 20/20 PASS.
- **Lesson:** `am force-stop` of this app removes its accessibility service from `enabled_accessibility_services`
  (Android does that for force-stopped packages). That's the reason AdbActions never stops apps with an enabled
  service; after restarting this app that way, the service has to be switched on again.
- **Downloader:** anyone can make a numeric code at go.aftvnews.com (captcha; submitted URLs are public). The release
  asset is named per version (`firestick-fixer-3.0.0.apk`), so a code pointing at it would go stale. Attach a
  fixed-name copy `firestick-fixer.apk` to each release and point the code at
  `https://github.com/Hornet34/firestick-fixer/releases/latest/download/firestick-fixer.apk`, which always serves the
  newest one. Done from v3.1.0 on: every release carries `firestick-fixer.apk` plus the versioned copy. The numeric
  code itself is the owner's to create (captcha).

## Known limits (by design, not bugs)
- Amazon's own home screen content (ads, rows) can't be changed without root.
- Apps that paint their own keyboard and have no side menu can't be searched by voice.
- Voice search matches the English word "Search" in step 4 of the ladder.
- An app button always starts its own app first (the system does it before any app can
  react); it is covered and replaced.
