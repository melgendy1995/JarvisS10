#!/usr/bin/env bash
# Device smoke test for the Jarvis S10 app.
#
# Run from the repo root on the Mac:
#   scripts/device-smoke.sh [--serial SERIAL]
#
# What it does: checks the app is alive, that the shelf IP/port are reachable
# and refuse unauthenticated requests, asks the brain questions in English and
# Arabic through the debug broadcast, drives YouTube Music playback, toggles the
# camera stream, and replays a generated "hey jarvis" wav into the wake
# listener. Nothing here speaks out loud, unlocks the phone, or reads a secret
# store.
#
# Results go to stdout and logs/smoke-<timestamp>.txt as
#   PASS|FAIL|SKIP  <name>  <detail>
# followed by a "N pass, N fail, N skip" summary. A failing check never stops
# the run. Checks that need YouTube Music on screen SKIP while the phone is
# locked; the script never unlocks it.

set -uo pipefail

SERIAL="${ANDROID_SERIAL:-R3CM501081Z}"
while [ $# -gt 0 ]; do
  case "$1" in
    --serial)
      [ $# -ge 2 ] || { echo "--serial needs a value" >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    --serial=*)
      SERIAL="${1#*=}"
      shift
      ;;
    *)
      echo "unknown option: $1" >&2
      exit 2
      ;;
  esac
done

ADB_BIN=""
for candidate in "${ANDROID_HOME:-/dev/null}/platform-tools/adb" adb; do
  if [ -x "$candidate" ] || command -v "$candidate" >/dev/null 2>&1; then
    ADB_BIN="$candidate"
    break
  fi
done
if [ -z "$ADB_BIN" ]; then
  echo "adb not found in \$ANDROID_HOME/platform-tools or PATH" >&2
  exit 2
fi

LOG_DIR="logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/smoke-$(date +%Y%m%d-%H%M%S).txt"

PKG="com.jarvis.app"
MAIN="$PKG/.MainActivity"
ASK_ACTION="com.jarvis.app.DEBUG_ASK"
ASK_COMPONENT="$PKG/.debug.DebugAskReceiver"
YTM_PKG="com.google.android.apps.youtube.music"

START_MARKER=""
ASK_MARKER=""
WAS_PINNED=0
P=0
F=0
S=0

say()
{
  printf '%s\n' "$*" | tee -a "$LOG_FILE"
}

adb_run()
{
  # dumpsys and logcat end every line with \r; strip it so grep works.
  "$ADB_BIN" -s "$SERIAL" "$@" 2>/dev/null | tr -d '\r'
}

# One apostrophe inside the question must survive adb shell, which runs the
# whole command through the device sh. Turn ' into '\''.
esc_sq()
{
  local rep out
  rep="'\\''"
  out=${1//\'/$rep}
  printf '%s' "$out"
}

front()
{
  adb_run shell am start -n "$MAIN" >/dev/null || true
  sleep 1
}

tag_stamp()
{
  adb_run shell date +'%m-%d %H:%M:%S.000'
}

# ask "<text>"
# Record the device time as a logcat -T marker (logcat -c is not allowed),
# broadcast the question to the debug receiver, then poll the buffered logcat
# for the spoken reply line. Echoes the JarvisVoice "tts speak" lines on stdout.
ask()
{
  local text esc replies
  text=$1
  front
  ASK_MARKER=$(tag_stamp)
  esc=$(esc_sq "$text")
  adb_run shell "am broadcast -a $ASK_ACTION -n $ASK_COMPONENT --es q '$esc'" >/dev/null || true
  replies=""
  for _ in $(seq 1 25); do
    sleep 1
    replies=$(adb_run shell "logcat -d -T '$ASK_MARKER'" |
      grep -F "JarvisVoice: tts speak brain=" || true)
    if [ -n "$replies" ]; then
      break
    fi
  done
  printf '%s' "$replies"
}

locked()
{
  adb_run shell dumpsys window | grep -q "isKeyguardShowing=true"
}

pin_state()
{
  adb_run shell dumpsys activity activities | grep -F "mLockTaskModeState" | head -1 || true
}

jarvis_task_id()
{
  adb_run shell am stack list |
    grep -E 'com\.jarvis\.app/(com\.jarvis\.app)?\.MainActivity' |
    grep -oE 'taskId=[0-9]+' | head -1 | cut -d= -f2 || true
}

# The most recent YT Music playback state (3 playing, 2 paused) or "none".
ytm_state()
{
  local out st
  out=$(adb_run shell dumpsys media_session || true)
  if ! printf '%s' "$out" | grep -q "$YTM_PKG"; then
    echo "none"
    return
  fi
  st=$(printf '%s' "$out" |
    grep -A40 "$YTM_PKG" |
    grep -oE 'state=[0-9]+' |
    head -1 | cut -d= -f2)
  echo "${st:-none}"
}

wlan_ip()
{
  adb_run shell ip -f inet addr show wlan0 |
    grep -oE 'inet [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' |
    head -1 | cut -d' ' -f2 || true
}

# record <status> <name> <detail>
record()
{
  case "$1" in
    PASS) P=$((P + 1)) ;;
    FAIL) F=$((F + 1)) ;;
    *) S=$((S + 1)) ;;
  esac
  say "$1  $2  $3"
}

check_app_running()
{
  local pid crash
  pid=$(adb_run shell pidof "$PKG" || true)
  if [ -z "$pid" ]; then
    record FAIL app_running "no pidof com.jarvis.app"
    return
  fi
  crash=$(adb_run shell "logcat -d -b crash -T '$START_MARKER'" |
    grep -F "FATAL EXCEPTION" | grep "$PKG" || true)
  if [ -n "$crash" ]; then
    record FAIL app_running "fatal exception since $START_MARKER: $(printf '%s' "$crash" | head -1 | cut -c1-100)"
    return
  fi
  record PASS app_running "pid=$pid, no fatal exception since $START_MARKER"
}

# The shelf IP label (LanAddress.display renders ip:camera_port) is UI-only
# with no log line to match, so this check validates the device IP itself.
check_shelf_ip()
{
  local ip
  ip=$(wlan_ip)
  if [ -z "$ip" ]; then
    record FAIL shelf_ip "no wlan0 IPv4 address"
    return
  fi
  record PASS shelf_ip "wlan0=$ip"
}

check_port_auth()
{
  local ip code
  ip=$(wlan_ip)
  if [ -z "$ip" ]; then
    record SKIP port_8088_auth "no wlan0 IP so the port is unreachable"
    return
  fi
  if ! command -v curl >/dev/null 2>&1; then
    record SKIP port_8088_auth "curl not installed on the Mac"
    return
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://$ip:8088/" || true)
  if [ "$code" = "401" ]; then
    record PASS port_8088_auth "http://$ip:8088/ answered 401 without credentials"
  else
    record FAIL port_8088_auth "expected 401, got ${code:-timeout} from http://$ip:8088/"
  fi
}

first_reply_text()
{
  # preview= is already capped at 80 characters and secrets are redacted
  # before the line is logged (spokenPreview in JarvisViewModel).
  printf '%s' "$1" | sed -n 's/.*preview=\(.*\)/\1/p' | head -1
}

reply_is_bad()
{
  local text
  text=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
  printf '%s\n' "$text" |
    grep -qiE "i can't|i couldn't|didn't catch|cloud brain|cooling down|isn't set up"
}

check_search_en()
{
  local replies text tool
  replies=$(ask "What is today's date and the latest news headline?")
  text=$(first_reply_text "$replies")
  if [ -z "$text" ]; then
    record FAIL search_en "no JarvisVoice reply within 25 s"
    return
  fi
  if reply_is_bad "$text"; then
    record FAIL search_en "error reply: $text"
    return
  fi
  tool=$(adb_run shell "logcat -d -T '$ASK_MARKER'" | grep -F "JarvisSearch: path=" | head -1 || true)
  record PASS search_en "$text${tool:+ | search line: $(printf '%s' "$tool" | cut -c1-90)}"
}

check_search_ar()
{
  local replies text
  replies=$(ask "ما هو تاريخ اليوم وما آخر خبر؟")
  text=$(first_reply_text "$replies")
  if [ -z "$text" ]; then
    record FAIL search_ar "no JarvisVoice reply within 25 s"
    return
  fi
  if printf '%s' "$text" | grep -q '[؀-ۿ]'; then
    record PASS search_ar "$text"
  elif reply_is_bad "$text"; then
    record FAIL search_ar "error reply: $text"
  else
    record FAIL search_ar "no Arabic script in reply: $text"
  fi
}

state_name()
{
  case "$1" in
    3) echo "playing" ;;
    2) echo "paused" ;;
    none) echo "no session" ;;
    *) echo "state=$1" ;;
  esac
}

wait_ytm_state()
{
  local want tries
  want=$1
  tries=$2
  for _ in $(seq 1 "$tries"); do
    if [ "$(ytm_state)" = "$want" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# check_ytm_common <name> <play> <pause> <resume> <final pause>
check_ytm_common()
{
  local name replies text states
  name=$1
  replies=$(ask "$2")
  if wait_ytm_state 3 15; then
    states="$(state_name 3)"
  else
    text=$(first_reply_text "$replies")
    record SKIP "$name" "no playing session after the ask (reply: ${text:-none}); needs an unlocked phone and YT Music installed"
    return
  fi
  if wait_ytm_state_after 2; then
    states="$states -> $(state_name 2)"
  else
    record FAIL "$name" "\"$3\" did not pause (state=$(ytm_state))"
    return
  fi
  if wait_ytm_state_after 3; then
    states="$states -> $(state_name 3)"
  else
    record FAIL "$name" "\"$4\" did not resume (state=$(ytm_state))"
    return
  fi
  if wait_ytm_state_after 2; then
    states="$states -> $(state_name 2)"
    record PASS "$name" "$states (ends paused)"
    return
  fi
  record FAIL "$name" "\"$5\" did not pause (state=$(ytm_state))"
}

# After each transport ask the session state settles within a couple of
# seconds; wait up to 15 s total for the wanted number (2 paused, 3 playing).
wait_ytm_state_after()
{
  local want
  want=$1
  sleep 1
  if [ "$(ytm_state)" = "$want" ]; then
    return 0
  fi
  wait_ytm_state "$want" 14
}

check_ytm_en()
{
  locked && { record SKIP ytm_en "phone locked"; return; }
  check_ytm_common ytm_en "play Fairuz on YouTube Music" "pause" "resume" "pause"
}

check_ytm_ar()
{
  locked && { record SKIP ytm_ar "phone locked"; return; }
  check_ytm_common ytm_ar "شغل فيروز على يوتيوب ميوزك" "وقف" "كمل" "وقف"
}

check_nothing_playing()
{
  local replies text ctl guard
  replies=$(ask "pause")
  text=$(first_reply_text "$replies")
  if printf '%s\n' "$text" | grep -q "Nothing is playing"; then
    record PASS nothing_playing "reply: $text"
    return
  fi
  # Not the phrase? A real pause is still a pass: the JarvisAppControl
  # "media control pkg=... action=pause via=session state=N" line, or a guard
  # reroute of the claim.
  ctl=$(adb_run shell "logcat -d -T '$ASK_MARKER'" |
    grep -F "media control pkg=" | grep -F "action=pause" |
    grep -F "via=session" | head -1 || true)
  guard=$(adb_run shell "logcat -d -T '$ASK_MARKER'" |
    grep -F "JarvisBrain: guard claim=" | head -1 || true)
  if [ -n "$ctl" ]; then
    record PASS nothing_playing "real pause via dashboard line: $(printf '%s' "$ctl" | grep -oE 'pkg=[^ ]+ action=[a-z]+ via=[a-z]+ state=[0-9]+' | head -1)${guard:+ | guard: $(printf '%s' "$guard" | cut -c1-60)}"
    return
  fi
  record FAIL nothing_playing "reply \"${text:-none}\" shows neither the nothing-playing line nor a session pause"
}

camera_service_lines()
{
  adb_run shell dumpsys activity services "$PKG" | grep -F "CameraStreamService" || true
}

wait_camera_gone()
{
  local tries
  tries=$1
  for _ in $(seq 1 "$tries"); do
    if [ -z "$(camera_service_lines)" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

check_camera_onoff()
{
  local replies text
  # "turn on the camera" / "turn off the camera" both route in CameraSkill;
  # the stream uses its stored password internally, we only watch the service.
  replies=$(ask "turn on the camera")
  text=$(first_reply_text "$replies")
  if [ -z "$(camera_service_lines)" ]; then
    record FAIL camera_onoff "CameraStreamService absent after the ask (reply: ${text:-none}); needs the camera password stored and permission granted"
    return
  fi
  replies=$(ask "turn off the camera")
  text=$(first_reply_text "$replies")
  # ViewerIdleWatch keeps the service for a short grace after the last viewer,
  # so allow up to 25 s for it to disappear.
  if wait_camera_gone 25; then
    record PASS camera_onoff "service started, then gone (final reply: ${text:-none})"
  else
    record FAIL camera_onoff "CameraStreamService still present 25 s after the stop ask (reply: ${text:-none})"
  fi
}

check_wake_sim()
{
  local wav=/tmp/hey_jarvis.wav
  local aiff=/tmp/hj.aiff
  local marker trig score pid
  if command -v say >/dev/null 2>&1 && command -v afconvert >/dev/null 2>&1; then
    rm -f "$aiff" "$wav"
    say -v Daniel -o "$aiff" "hey jarvis" >/dev/null 2>&1 || { record SKIP wake_sim "say could not render the clip"; return; }
    afconvert -f WAVE -d LEI16@16000 -c 1 "$aiff" "$wav" >/dev/null 2>&1 || { record SKIP wake_sim "afconvert failed"; return; }
  else
    record SKIP wake_sim "say/afconvert missing on this Mac"
    return
  fi
  adb_run shell "run-as $PKG mkdir -p files" || true
  adb_run push "$wav" /data/local/tmp/hey_jarvis.wav >/dev/null || { record FAIL wake_sim "adb push failed"; return; }
  adb_run shell "run-as $PKG cp /data/local/tmp/hey_jarvis.wav files/hey_jarvis.wav" >/dev/null || { record FAIL wake_sim "run-as copy failed"; return; }
  if ! "$ADB_BIN" -s "$SERIAL" shell "run-as $PKG test -f files/hey_jarvis.wav" >/dev/null 2>&1; then
    record FAIL wake_sim "wav did not land in the app files directory"
    return
  fi
  marker=$(tag_stamp)
  adb_run shell "am broadcast -a com.jarvis.app.DEBUG_WAKE -p $PKG --es path hey_jarvis.wav" >/dev/null || true
  sleep 3
  trig=$(adb_run shell "logcat -d -T '$marker'" | grep -F "JarvisWake: " | grep -F "trigger score=" | head -1 || true)
  if [ -z "$trig" ]; then
    # WakeRuntime logs "score hey_jarvis=X threshold=T state=..." at or above
    # 0.2; a score at or above its own threshold is the fallback signal.
    score=$(adb_run shell "logcat -d -T '$marker'" |
      awk '/JarvisWake:.*hey_jarvis=/ {
        match($0, /hey_jarvis=[0-9.]+/); s = substr($0, RSTART + 11, RLENGTH - 11) + 0
        match($0, /threshold=[0-9.]+/); t = substr($0, RSTART + 10, RLENGTH - 10) + 0
        if (s >= t) print s }' | head -1 || true)
  fi
  if [ -n "$trig" ] || [ -n "$score" ]; then
    if [ -n "$trig" ]; then
      record PASS wake_sim "trigger line: $(printf '%s' "$trig" | cut -c1-70)"
    else
      record PASS wake_sim "score hey_jarvis=$score crossed its threshold"
    fi
    # Jarvis should return to idle on its own; also confirm the app survived.
    sleep 8
    pid=$(adb_run shell pidof "$PKG" || true)
    if [ -z "$pid" ]; then
      record FAIL wake_sim "app stopped after the simulated wake"
    fi
    return
  fi
  record FAIL wake_sim "no JarvisWake trigger/score line after the broadcast (is the listener running? open Jarvis once first)"
}

repin()
{
  local tid
  if [ "$WAS_PINNED" -eq 1 ]; then
    tid=$(jarvis_task_id)
    if [ -n "$tid" ]; then
      "$ADB_BIN" -s "$SERIAL" shell am task lock "$tid" >/dev/null 2>&1 ||
        "$ADB_BIN" -s "$SERIAL" shell "cmd activity task lock $tid" >/dev/null 2>&1 || true
    fi
  fi
}

main()
{
  trap repin EXIT
  : > "$LOG_FILE"
  say "Device smoke test — serial=$SERIAL start=$(date '+%F %T') adb=$ADB_BIN"
  START_MARKER=$(tag_stamp)
  if locked; then
    say "phone is locked — the YT Music checks will SKIP; the script never unlocks it"
  else
    case "$(pin_state)" in
      *PINNED*)
        WAS_PINNED=1
        # install.sh pins the Jarvis task, and lock task mode keeps YouTube
        # Music from ever coming to the front. Unpin for the media checks;
        # re-pin when the script exits.
        adb_run shell am task lock stop >/dev/null || true
        say "kiosk pin was active, unpinned for this run (restored on exit)"
        ;;
    esac
  fi
  check_app_running
  check_shelf_ip
  check_port_auth
  check_search_en
  check_search_ar
  check_ytm_en
  check_ytm_ar
  check_nothing_playing
  check_camera_onoff
  check_wake_sim
  say "-----"
  say "$P pass, $F fail, $S skip"
  trap - EXIT
  repin
}

main
