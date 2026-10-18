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

# ---- settings: every feature on for the run, the user's settings back afterwards ----
SWITCHES=(hijack doubleTap backStays backHold backHoldForce remap voice)
declare -A ORIGINAL
snapshot_and_enable() {
  local sw
  for sw in "${SWITCHES[@]}"; do
    ORIGINAL[$sw]=$(state "$sw")
    [[ "${ORIGINAL[$sw]}" == "true" ]] || hook set "$sw" true >/dev/null
  done
}
restore_settings() {
  local sw
  for sw in "${!ORIGINAL[@]}"; do
    [[ -n "${ORIGINAL[$sw]}" ]] && hook set "$sw" "${ORIGINAL[$sw]}" >/dev/null
  done
  [[ -n "${ALEXA_TEMP:-}" ]] && hook alexaApp "$ALEXA_TEMP" >/dev/null
  echo "Settings restored: $(for sw in "${SWITCHES[@]}"; do printf '%s=%s ' "$sw" "$(state "$sw")"; done)"
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
  [[ -z "$TEST_APP" ]] && { result home_from_app SKIP "no test app installed"; return; }
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; home
  check home_from_app "Home in $TEST_APP opens $TARGET" wait_fg "$TARGET" 5
}

t_home_from_launcher() {
  home; sleep 2; home
  check home_from_launcher "Home on the launcher stays on $TARGET" wait_fg "$TARGET" 5
}

t_double_tap() {
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
  home
  check single_after_escape "single Home after the escape returns to $TARGET" wait_fg "$TARGET" 5
}

stays_in_front() { wait_fg "$1" 10 && sleep 2 && [[ "$(fg)" == "$1" ]]; }
back_was_ignored() { hook log | grep 'is the main screen, ignoring Back' >/dev/null && [[ "$(fg)" == "$TARGET" ]]; }

t_back_on_launcher() {
  home; wait_fg "$TARGET" 5; sleep 2.5; hook clearlog >/dev/null
  hook key BACK tap >/dev/null; sleep 1
  check back_on_launcher "Back on the launcher's main screen is ignored" back_was_ignored
}

t_hold_back() {
  [[ -z "$TEST_APP" ]] && { result hold_back SKIP "no test app"; return; }
  # The back-out path (without Fast actions); hold_back_force tests the other one.
  hook set backHoldForce false >/dev/null
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; sleep 1; hook clearlog >/dev/null
  hook key BACK hold >/dev/null; sleep 1.5
  check hold_back "holding Back in $TEST_APP closes it to $TARGET" wait_fg "$TARGET" 5
  hook set backHoldForce true >/dev/null
}

# Fast actions (D42): only if set up (tools/setup-adb.sh); never touches system apps.
t_fast() {
  local st r
  st=$(state fast)
  [[ "$st" == "not_set_up" || -z "$st" ]] && { result fast SKIP "Fast actions not set up (tools/setup-adb.sh)"; return; }
  hook adb test >/dev/null
  r=""; for _ in $(seq 1 40); do r=$(hook adb result); [[ "$r" != RUNNING* ]] && break; sleep 0.5; done
  [[ "$r" == OK* ]] && result fast PASS "$r" || result fast FAIL "$r"
  # The package checks must refuse the launcher, this app and Amazon's home.
  local p bad=""
  for p in "$TARGET" "$PKG" "$AMAZON_HOME"; do
    [[ "$(hook adb check "$p")" == NO* ]] || bad="$bad $p"
  done
  [[ -z "$bad" ]] && result fast_checks PASS "refuses launcher, this app, Amazon home" \
                  || result fast_checks FAIL "would force-stop:$bad"
}

t_hold_back_force() {
  [[ -z "$TEST_APP" ]] && { result hold_back_force SKIP "no test app"; return; }
  [[ "$(state fast)" == ready ]] || { result hold_back_force SKIP "Fast actions not ready ($(state fast))"; return; }
  [[ "$(hook adb check "$TEST_APP")" == OK* ]] || { result hold_back_force SKIP "$TEST_APP may not be force-stopped"; return; }
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; sleep 1; hook clearlog >/dev/null
  hook key BACK hold >/dev/null; sleep 2.5
  if wait_fg "$TARGET" 5 && wait_log "force-stop $TEST_APP done" 5 && [[ -z "$(sh_ pidof "$TEST_APP")" ]]; then
    result hold_back_force PASS "holding Back force-stopped $TEST_APP and opened $TARGET"
  else
    result hold_back_force FAIL "front: $(fg), pid: $(sh_ pidof "$TEST_APP")"
  fi
}

t_short_back() {
  [[ -z "$TEST_APP" ]] && { result short_back SKIP "no test app"; return; }
  open_app "$TEST_APP"; wait_fg "$TEST_APP" 8; sleep 1; hook clearlog >/dev/null
  hook key BACK tap >/dev/null
  check short_back "a short Back is passed on to the app" wait_log 'short press passed on' 3
}

t_buttons() {
  [[ -z "$MAPS" ]] && { result button_remap SKIP "no custom mappings configured"; return; }
  local m win app
  for m in $MAPS; do
    win=${m%%->*}; win=${win%%/*}; app=${m##*->}
    installed "$win" && installed "$app" || { result "button:$win" SKIP "app not installed"; continue; }
    # Opening the branded app yourself must leave it alone ...
    home; wait_fg "$TARGET" 5; open_app "$win"
    # A cold start (the app may have been stopped after an earlier remap) can take >4 s.
    check "manual_open:${win##*.}" "opening $win yourself is left alone" stays_in_front "$win"
    # ... while its remote button opens the mapped app.
    home; wait_fg "$TARGET" 5
    hook button "$win" >/dev/null; open_app "$win"
    check "button:${win##*.}" "$win button opens $app" wait_fg "$app" 8
  done
}

t_voice() {
  local app
  for app in io.gh.reisxd.tizentube.cobalt com.amazon.firetv.youtube com.nuvio.tv; do
    installed "$app" || continue
    home; wait_fg "$TARGET" 5; open_app "$app"; wait_fg "$app" 10; sleep 2; hook clearlog >/dev/null
    hook voice "$app" lofi music >/dev/null
    check "voice:${app##*.}" "voice search runs in $app" \
      wait_log "Voice search (sent to|typed into) $app|is set to use Alexa" 20
  done
}

t_voice_keep_alexa() {
  local app=io.gh.reisxd.tizentube.cobalt
  installed "$app" || { result voice_back_keeps SKIP "TizenTube not installed"; return; }
  home; wait_fg "$TARGET" 5; open_app "$app"; wait_fg "$app" 10; sleep 2; hook clearlog >/dev/null
  hook voice "$app" lofi music >/dev/null; sleep 0.6
  hook key BACK tap >/dev/null
  check voice_back_keeps "Back during the countdown keeps Alexa's results" wait_log "kept Alexa's results" 3
}

t_alexa_list() {
  local app=io.gh.reisxd.tizentube.cobalt
  installed "$app" || { result alexa_list SKIP "TizenTube not installed"; return; }
  [[ "$(hook alexaApp "$app")" == *listed=true* ]] || hook alexaApp "$app" >/dev/null
  ALEXA_TEMP=$app; hook clearlog >/dev/null
  hook voice "$app" lofi music >/dev/null
  check alexa_list "an app on the Alexa list keeps Alexa's results" wait_log "is set to use Alexa's results" 3
  hook alexaApp "$app" >/dev/null; ALEXA_TEMP=""
}

ALL=(service home_from_app home_from_launcher double_tap single_after_escape back_on_launcher
     hold_back short_back buttons voice voice_keep_alexa alexa_list fast hold_back_force)
if [[ "${1:-}" == "--list" ]]; then printf '%s\n' "${ALL[@]}"; exit 0; fi
TESTS=("$@"); [[ ${#TESTS[@]} -eq 0 ]] && TESTS=("${ALL[@]}")

snapshot_and_enable
trap 'restore_settings' EXIT
trap 'exit 130' INT TERM
echo "Features switched on for this run (restored at the end)."
echo "FireStick Fixer device test — $(sh_ getprop ro.build.version.name), launcher: $TARGET, test app: ${TEST_APP:-none}"
echo "---------------------------------------------------------------------"
for t in "${TESTS[@]}"; do "t_$t"; done
home
echo "---------------------------------------------------------------------"
echo "PASS $PASS   FAIL $FAIL   SKIP $SKIP"
echo "Manual only: start-up app after a reboot; real remote presses; real Alexa voice."
(( FAIL == 0 ))
