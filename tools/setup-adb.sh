#!/usr/bin/env bash
# One-time setup of FireStick Fixer from a computer: the two grants, and Fast actions
# (the app's own adb on the stick, see docs/DECISIONS.md D34-D42).
#
# What it changes on the Fire TV (all reversible, see the end of this file):
#   - pm grant WRITE_SECURE_SETTINGS and READ_LOGS to FireStick Fixer
#   - settings put global adb_allowed_connection_time 0   (approved adb keys never expire)
#   - approves FireStick Fixer's own adb key (you press Allow on the TV)
#   - allows wireless debugging on the current Wi-Fi, if it isn't yet (you press Allow on the TV)
#
# The app's private key never leaves the TV: the app makes it, and this script only relays
# the app's connection (TV -> computer -> TV:5555) so adbd shows its "Allow USB debugging?"
# prompt for it, which it refuses to do for connections that start on the TV itself.
#
# Usage: tools/setup-adb.sh <fire-tv-ip>
#    or: curl -sL https://raw.githubusercontent.com/Hornet34/firestick-fixer/main/tools/setup-adb.sh | bash -s <fire-tv-ip>
# Needs: adb, and socat or python3.
set -uo pipefail

IP="${1:?usage: tools/setup-adb.sh <fire-tv-ip>}"
DEV="$IP:5555"
PKG=io.github.hatim.firestickfixer
DPORT=5560   # on the TV, via adb reverse
LPORT=5561   # on this computer
RELAY_PID=""

# Every adb call reads /dev/null: with "curl ... | bash" stdin is this script itself, and
# "adb shell" would swallow the rest of it.
A()    { adb -s "$DEV" "$@" < /dev/null; }
hook() { A shell dumpsys activity service "$PKG/.HijackService" "$@" 2>/dev/null | tr -d '\r' \
           | sed -n '/Client:/,$p' | tail -n +2 | sed 's/^ *//'; }
say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

cleanup() {
  [[ -n "$RELAY_PID" ]] && kill "$RELAY_PID" 2>/dev/null
  A reverse --remove "tcp:$DPORT" >/dev/null 2>&1
}
trap cleanup EXIT

# relay <bind-address>: forwards <bind>:$LPORT to the TV's :5555 in the background.
relay() {
  if command -v socat >/dev/null; then
    socat "TCP-LISTEN:$LPORT,bind=$1,reuseaddr,fork" "TCP:$IP:5555" &
  else
    python3 -I - "$1" "$LPORT" "$IP" <<'EOF' &
import socket, sys, threading
bind, port, ip = sys.argv[1], int(sys.argv[2]), sys.argv[3]
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind((bind, port)); srv.listen(4)
def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except OSError: pass
    finally:
        for s in (a, b):
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass
while True:
    c, _ = srv.accept(); u = socket.create_connection((ip, 5555))
    threading.Thread(target=pipe, args=(c, u), daemon=True).start()
    threading.Thread(target=pipe, args=(u, c), daemon=True).start()
EOF
  fi
  RELAY_PID=$!
  sleep 0.5
}

# wait_result <seconds>: prints the hook's final "adb result" line.
wait_result() {
  local r t=$(( $1 * 2 ))
  while (( t-- > 0 )); do
    r="$(hook adb result)"
    # An empty read is a dumpsys hiccup, not an answer: keep waiting.
    [[ -n "$r" && "$r" != RUNNING* ]] && { echo "$r"; return; }
    sleep 0.5
  done
  echo "TIMEOUT"
}

# run_test <seconds>: one read-only Fast actions command; prints its result.
run_test() { hook adb test >/dev/null; wait_result "$1"; }

say "1/5  Connecting to $DEV"
adb connect "$DEV" >/dev/null < /dev/null
A get-state >/dev/null 2>&1 || { echo "Can't reach $DEV. Is ADB debugging on, and was this computer allowed?"; exit 1; }
A shell pm path "$PKG" >/dev/null 2>&1 || { echo "FireStick Fixer isn't installed: adb -s $DEV install firestick-fixer.apk"; exit 1; }

say "2/5  Grants (both survive reboots and app updates)"
A shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
A shell pm grant "$PKG" android.permission.READ_LOGS
# Android 11 forgets an approved adb key after 7 days without a connection (D39).
A shell settings put global adb_allowed_connection_time 0
# Switch the service on (what the app's own switch does), keeping any other enabled services.
SVC="$PKG/.HijackService"
CUR="$(A shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ ":$CUR:" != *":$SVC:"* && ":$CUR:" != *":$PKG/$PKG.HijackService:"* ]]; then
  if [[ -z "$CUR" || "$CUR" == null ]]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
  A shell settings put secure enabled_accessibility_services "$NEW"
  A shell settings put secure accessibility_enabled 1
  echo "Accessibility service switched on."
fi
for _ in $(seq 1 20); do hook adb status | grep -q '^status=' && break; sleep 0.5; done
if ! hook adb status | grep -q '^status='; then
  echo "FireStick Fixer's service isn't answering. Open the app on the TV, check \"Accessibility service\" is on, then run this again."
  exit 1
fi

say "3/5  Approving FireStick Fixer's own adb key"
if [[ "$(hook adb status)" == status=ready* ]] && [[ "$(run_test 15)" == OK* ]]; then
  echo "Already set up and working."
else
  relay 127.0.0.1
  A reverse "tcp:$DPORT" "tcp:$LPORT" >/dev/null
  echo "$(hook adb setup 127.0.0.1 $DPORT)"
  echo "On the TV: \"Allow USB debugging?\" -> tick \"Always allow from this computer\" -> Allow."
  echo "(The fingerprint shown on the TV is the one above.)"
  R="$(wait_result 100)"
  if [[ "$R" == FAIL*onnect* || "$R" == FAIL*efused* ]]; then
    # adb reverse not usable here: let the TV reach this computer directly on the LAN.
    cleanup; RELAY_PID=""
    ME="$(ip route get "$IP" 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')"
    [[ -z "$ME" ]] && { echo "$R"; echo "Couldn't find this computer's LAN address."; exit 1; }
    echo "Retrying over the LAN via $ME:$LPORT (allow it in this computer's firewall if asked)."
    relay "$ME"
    echo "$(hook adb setup "$ME" $LPORT)"
    R="$(wait_result 100)"
  fi
  echo "$R"
  [[ "$R" == OK* ]] || { echo "The key wasn't approved. Run this again and press Allow on the TV."; exit 1; }
fi
cleanup; RELAY_PID=""

say "4/5  Wireless debugging on this Wi-Fi"
R="$(run_test 20)"
if [[ "$R" == *wifi_refused* ]]; then
  echo "On the TV: \"Allow wireless debugging on this network?\" -> tick \"Always allow on this network\" -> Allow."
  echo "(If you don't see it, wake the TV with the Up key; don't press Back.)"
  read -r -p "Press Enter here once you pressed Allow... " _ < /dev/tty
  R="$(run_test 20)"
fi

say "5/5  Test"
echo "$R"
if [[ "$R" == OK* ]]; then
  say "Ready. Fast actions work; this computer isn't needed any more."
else
  echo "Fast actions don't work yet. The app falls back to its normal behaviour; see Settings -> Fast actions."
  exit 1
fi

# Undo (run by hand if you ever want to):
#   adb shell dumpsys activity service io.github.hatim.firestickfixer/.HijackService adb forget
#   adb shell settings delete global adb_allowed_connection_time
#   adb shell service call adb 3     # clears ALL approved adb keys; computers must be allowed again
