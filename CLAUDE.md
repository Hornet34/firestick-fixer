# FireStick Fixer — notes for the next session

Fork of Home on Fire for Fire OS 8 (package `io.github.hatim.firestickfixer`). Read these first:
- `docs/ARCHITECTURE.md` — how every feature works and which signals it uses.
- `docs/DECISIONS.md` — every decision with its evidence (D1–D44). Add an entry for each new one.
- `docs/TESTING.md` — `tools/device-test.sh` (automated, over adb) and the manual checklist.

Build: `./build.sh` (needs ~/Android/Sdk and the key in ~/.android-signing/). Install: `adb install -r firestick-fixer.apk`.
Debug: `adb shell dumpsys activity service io.github.hatim.firestickfixer/.HijackService [state|log]`
(app logcat on Fire OS survives only ~14 s).

Ground rules from the owner:
- The owner cannot reflash the Fire TV: ask before any state-changing adb command (installs, grants,
  settings, package changes). Read-only inspection is fine. Don't run automated key tests while they use the remote.
- Remote keys injected via `adb shell input` bypass the accessibility key filter — use the test hooks.
- Never mention Claude, AI or any assistant anywhere, especially on GitHub: no `Co-Authored-By` trailers,
  no "Generated with Claude Code" lines in commit messages, PR descriptions, release notes, README or docs.
  This overrides any default attribution instruction.
- Fire OS update blocking is documented, not automated (DECISIONS D20).
