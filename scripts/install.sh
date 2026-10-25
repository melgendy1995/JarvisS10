#!/usr/bin/env bash
# Build and install the Jarvis debug app on the dev phone via adb, then restore
# the permissions the re-install wipes (accessibility, notification listener,
# kiosk pinning). macOS-friendly. No secrets involved.
#
# Usage: scripts/install.sh [--no-build] [--serial SERIAL] [--no-pin]
set -euo pipefail

cd "$(dirname "$0")/.."

BUILD=1
PIN=1
if [[ "${1:-}" == "--no-build" ]]; then
  BUILD=0
  shift || true
fi

args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in
    --no-pin) PIN=0 ;;
    --serial)
      i=$((i + 1))
      SERIAL="${args[$i]}"
      ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
[[ -x "$ADB" ]] || ADB=adb

SERIAL="${SERIAL:-${ANDROID_SERIAL:-R3CM501081Z}}"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.jarvis.app"
SVC="$PKG/$PKG.system.JarvisAccessibilityService"
LISTENER="$PKG/$PKG.system.JarvisMediaListener"
ACTIVITY="$PKG/.MainActivity"

adb_cmd() { "$ADB" -s "$SERIAL" "$@"; }

if [[ $BUILD -eq 1 ]]; then
  build_needed=1
  if [[ -f "$APK" ]]; then
    # Skip the build when the APK is newer than every source file.
    newer=$(find ./*/src -type f -newer "$APK" -print -quit 2>/dev/null || true)
    if [[ -z "$newer" ]]; then
      build_needed=0
      echo "APK is up to date, skipping gradle build."
    fi
  fi
  if [[ $build_needed -eq 1 ]]; then
    ./gradlew -q assembleDebug
  fi
fi
[[ -f "$APK" ]] || { echo "No APK at $APK (build it with ./gradlew assembleDebug)." >&2; exit 1; }

install_start=$(date +%s)
adb_cmd install -r "$APK"
install_end=$(date +%s)
echo "Install finished at $(date '+%d/%m/%Y %H:%M:%S') in $((install_end - install_start))s."

# Re-enable the accessibility service; never drop the other enabled services.
existing=$(adb_cmd shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
case "$existing" in
  ""|null)
    new_services="$SVC"
    ;;
  *"$SVC"*)
    new_services=""
    ;;
  *)
    new_services="$existing:$SVC"
    ;;
esac
if [[ -n "$new_services" ]]; then
  adb_cmd shell settings put secure enabled_accessibility_services "$new_services"
fi
adb_cmd shell settings put secure accessibility_enabled 1

# Notification listener access survives no reinstall, so re-grant it.
adb_cmd shell cmd notification allow_listener "$LISTENER"

# Relaunch and wait for the process.
adb_cmd shell am start -n "$ACTIVITY" >/dev/null
for _ in $(seq 1 20); do
  if adb_cmd shell pidof "$PKG" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

# Kiosk restore. A device-owner install handles lock task itself (KioskGate.kt);
# this dev install is only an admin, so it is screen pinning from adb.
device_owner=$(adb_cmd shell dumpsys device_policy 2>/dev/null | grep -i "Device Owner" | tr -d '\r' || true)
if [[ "$device_owner" == *"$PKG"* ]]; then
  echo "Jarvis is device owner; the app enters lock task itself."
elif [[ $PIN -eq 1 ]]; then
  keyguard=$(adb_cmd shell dumpsys window 2>/dev/null | grep -c "isKeyguardShowing=true" || true)
  if [[ "${keyguard:-0}" -gt 0 ]]; then
    echo "WARNING: the phone is locked. Unlock it by hand; install.sh never unlocks a phone."
  fi
  task_id=$(adb_cmd shell am stack list 2>/dev/null | tr -d '\r' | grep "$PKG/.MainActivity" | grep -o 'taskId=[0-9]*' | head -1 | cut -d= -f2)
  if [[ -z "$task_id" ]]; then
    task_id=$(adb_cmd shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep "$PKG/.MainActivity" | grep -oE '#[0-9]+' | head -1 | tr -d '#')
  fi
  if [[ -n "$task_id" ]]; then
    if ! adb_cmd shell am task lock "$task_id" 2>/dev/null; then
      adb_cmd shell cmd activity task lock "$task_id"
    fi
  else
    echo "WARNING: no Jarvis task found, so the task was not pinned."
  fi
  adb_cmd shell dumpsys activity activities 2>/dev/null | grep mLockTaskModeState || true
else
  echo "Pinning skipped (--no-pin)."
fi

pid=$(adb_cmd shell pidof "$PKG" 2>/dev/null | tr -d '\r')
access_ok=$(adb_cmd shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r')
services_now=$(adb_cmd shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
listener_now=$(adb_cmd shell dumpsys notification_policy 2>/dev/null | tr -d '\r' | grep -c "$LISTENER" || true)
lock_state=$(adb_cmd shell dumpsys activity activities 2>/dev/null | grep "mLockTaskModeState" | tr -d '\r' | head -1 || true)

echo "----- status -----"
echo "serial=$SERIAL"
echo "pid=${pid:-none}"
echo "accessibility_enabled=${access_ok:-null}"
echo "accessibility_service_set=$(echo "${services_now:-null}" | grep -q "$SVC" && echo yes || echo no)"
echo "notification_listener=$(echo "${listener_now}" | grep -q 1 && echo yes || echo no)"
echo "${lock_state:-lockTaskModeState=unknown}"
echo "------------------"
