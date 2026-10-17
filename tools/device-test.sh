#!/usr/bin/env bash
# Automated end-to-end test of FireStick Fixer on a connected Fire TV (adb).
#
# It drives the device like a user would (Home presses, opening apps) and, where adb
# cannot reach (remote Back presses, remote app buttons, Alexa), uses the app's test
# hooks, which run the very same code paths (see src/.../TestHooks.java). Every check
# reads the real outcome: which app is in front, and what the service logged.
#
# Safe by design: changes no settings, installs nothing, reboots nothing. It opens and
# closes apps and presses Home; it ends on your launcher.
#
# Usage: tools/device-test.sh [-s <adb serial>] [test ...]   (no names = all tests)
#        tools/device-test.sh --list
set -uo pipefail

ADB=(adb)
if [[ "${1:-}" == "-s" ]]; then ADB=(adb -s "$2"); shift 2; fi
PKG=io.github.hatim.firestickfixer
SVC=$PKG/.HijackService
AMAZON_HOME=com.amazon.tv.launcher
PASS=0; FAIL=0; SKIP=0; RESULTS=()

sh_()   { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
hook()  { sh_ dumpsys activity service "$SVC" "$@" | sed -n '/Client:/,$p' | tail -n +2 | sed 's/^ *//'; }
fg()    { sh_ dumpsys activity activities | grep -m1 mResumedActivity | grep -oE 'u0 [^/ ]+' | cut -d' ' -f2; }
state() { hook state | sed -n "s/.*\b$1=\([^ ]*\).*/\1/p" | head -1; }
installed() { sh_ pm list packages | grep -x "package:$1" >/dev/null; }
open_app()  { sh_ monkey -p "$1" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null \
              || sh_ monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null; }
home()      { sh_ input keyevent KEYCODE_HOME; }

# wait_fg <pkg> <seconds>: true once <pkg> is in front.
wait_fg() { local t=$(( $2 * 2 )); while (( t-- > 0 )); do [[ "$(fg)" == "$1" ]] && return 0; sleep 0.5; done; return 1; }
# wait_log <regex> <seconds>: true once the service log matches.
wait_log() { local t=$(( $2 * 2 )); while (( t-- > 0 )); do hook log | grep -E "$1" >/dev/null && return 0; sleep 0.5; done; return 1; }

result() { # result <name> <PASS|FAIL|SKIP> <detail>
  RESULTS+=("$(printf '%-22s %-4s %s' "$1" "$2" "$3")")
  case "$2" in PASS) ((PASS++));; FAIL) ((FAIL++));; *) ((SKIP++));; esac
  printf '%-22s %-4s %s\n' "$1" "$2" "$3"
}
check() { # check <name> <detail> <command...>
  local name=$1 detail=$2; shift 2
  if "$@"; then result "$name" PASS "$detail"; else result "$name" FAIL "$detail (front: $(fg))"; fi
}

# ---- environment -----------------------------------------------------------------
TARGET=$(state target)
TEST_APP=""
for p in com.esaba.downloader com.amazon.firetv.youtube io.gh.reisxd.tizentube.cobalt com.nuvio.tv; do
  if installed "$p" && [[ "$p" != "$TARGET" ]]; then TEST_APP=$p; break; fi
done
MAPS=$(hook state | sed -n 's/^customMaps=//p')

t_service() {
  [[ -n "$TARGET" && "$TARGET" != "null" ]] \
    && result service PASS "running, launcher=$TARGET, precise=$(state precise)" \
    || result service FAIL "service not answering or no launcher chosen"
}

t_home_from_app() {
  [[ "$(state hijack)" != "true" ]] && { result home_from_app SKIP "'Home opens my launcher' is off"; return; }
  [[ -z "$TEST_APP" ]] && { result home_from_app SKIP "no test app installed"; return; }
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; home
  check home_from_app "Home in $TEST_APP opens $TARGET" wait_fg "$TARGET" 5
}

t_home_from_launcher() {
  [[ "$(state hijack)" != "true" ]] && { result home_from_launcher SKIP "'Home opens my launcher' is off"; return; }
  home; sleep 2; home
  check home_from_launcher "Home on the launcher stays on $TARGET" wait_fg "$TARGET" 5
}

t_double_tap() {
  [[ "$(state hijack)" != "true" ]] && { result double_tap SKIP "'Home opens my launcher' is off"; return; }
  wait_fg "$TARGET" 5; sleep 1
  sh_ input keyevent KEYCODE_HOME KEYCODE_HOME   # one input call: ~40 ms apart
  sleep 3
  if [[ "$(fg)" == "$AMAZON_HOME" ]]; then
    sleep 4
    check double_tap "double-tap stays on Amazon home (7 s)" test "$(fg)" == "$AMAZON_HOME"
  else
    result double_tap FAIL "expected Amazon home, front: $(fg)"
  fi
}

t_single_after_escape() {
  [[ "$(state hijack)" != "true" ]] && { result single_after_escape SKIP "'Home opens my launcher' is off"; return; }
  home
  check single_after_escape "single Home after the escape returns to $TARGET" wait_fg "$TARGET" 5
}

back_was_ignored() { hook log | grep 'is the main screen, ignoring Back' >/dev/null && [[ "$(fg)" == "$TARGET" ]]; }

t_back_on_launcher() {
  [[ "$(state backStays)" != "true" ]] && { result back_on_launcher SKIP "switched off"; return; }
  home; wait_fg "$TARGET" 5; sleep 2.5; hook clearlog >/dev/null
  hook key BACK tap >/dev/null; sleep 1
  check back_on_launcher "Back on the launcher's main screen is ignored" back_was_ignored
}

t_hold_back() {
  [[ "$(state backHold)" != "true" ]] && { result hold_back SKIP "switched off"; return; }
  [[ -z "$TEST_APP" ]] && { result hold_back SKIP "no test app"; return; }
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; sleep 1; hook clearlog >/dev/null
  hook key BACK hold >/dev/null
  # Leaves for the active home: the launcher while the Home redirect is on, else Amazon's.
  local home_pkg=$TARGET; [[ "$(state hijack)" != "true" ]] && home_pkg=$AMAZON_HOME
  check hold_back "holding Back in $TEST_APP closes it to $home_pkg" wait_fg "$home_pkg" 5
}

t_short_back() {
  [[ "$(state backHold)" != "true" ]] && { result short_back SKIP "hold-Back off"; return; }
  [[ -z "$TEST_APP" ]] && { result short_back SKIP "no test app"; return; }
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; sleep 1; hook clearlog >/dev/null
  hook key BACK tap >/dev/null
  check short_back "a short Back is passed on to the app" wait_log 'short press passed on' 3
}

t_buttons() {
  [[ "$(state remap)" != "true" || -z "$MAPS" ]] && { result button_remap SKIP "no custom mappings"; return; }
  local m win app
  for m in $MAPS; do
    win=${m%%->*}; win=${win%%/*}; app=${m##*->}
    installed "$win" && installed "$app" || { result "button:$win" SKIP "app not installed"; continue; }
    # Opening the branded app yourself must leave it alone ...
    home; wait_fg "$TARGET" 5; open_app "$win"
    sleep 4
    check "manual_open:${win##*.}" "opening $win yourself is left alone" test "$(fg)" == "$win"
    # ... while its remote button opens the mapped app.
    home; wait_fg "$TARGET" 5
    hook button "$win" >/dev/null; open_app "$win"
    check "button:${win##*.}" "$win button opens $app" wait_fg "$app" 8
  done
}

t_voice() {
  [[ "$(state voice)" != "true" ]] && { result voice SKIP "switched off"; return; }
  local app
  for app in io.gh.reisxd.tizentube.cobalt com.amazon.firetv.youtube com.nuvio.tv; do
    installed "$app" || continue
    home; wait_fg "$TARGET" 5; open_app "$app"; wait_fg "$app" 10; sleep 2; hook clearlog >/dev/null
    hook voice "$app" lofi music >/dev/null
    check "voice:${app##*.}" "voice search runs in $app" \
      wait_log "Voice search (sent to|typed into) $app|is set to use Alexa" 20
  done
}

ALL=(service home_from_app home_from_launcher double_tap single_after_escape back_on_launcher
     hold_back short_back buttons voice)
if [[ "${1:-}" == "--list" ]]; then printf '%s\n' "${ALL[@]}"; exit 0; fi
TESTS=("$@"); [[ ${#TESTS[@]} -eq 0 ]] && TESTS=("${ALL[@]}")

echo "FireStick Fixer device test — $(sh_ getprop ro.build.version.name), launcher: $TARGET, test app: ${TEST_APP:-none}"
echo "---------------------------------------------------------------------"
for t in "${TESTS[@]}"; do "t_$t"; done
home
echo "---------------------------------------------------------------------"
echo "PASS $PASS   FAIL $FAIL   SKIP $SKIP"
echo "Manual only: start-up app after a reboot; real remote presses; real Alexa voice."
(( FAIL == 0 ))
