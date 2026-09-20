#!/usr/bin/env bash
# 跨平台端到端：Linux 客户端（JVM） <-> Android 客户端（模拟器/真机），共用同一自托管服务端。
#
# 步骤：起服务端 -> Linux 写入(seed) -> Android 拉取+并发修改+推送 -> Linux 校验(verify)。
# 用法：bash sync-server/e2e-cross-platform.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${LR_E2E_PORT:-8799}"
USER="${LR_E2E_USER:-e2e}"
PASS="${LR_E2E_PASS:-e2e-password-123}"
WORK="${LR_E2E_WORK:-/tmp/lr-e2e-cross}"

export JAVA_HOME="$ROOT/toolchain/jdk-linux"
export ANDROID_HOME="$ROOT/toolchain/android-sdk-linux"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/toolchain/guser-linux/.android"
export HOME="$ROOT/toolchain/guser-linux"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

rm -rf "$WORK"
mkdir -p "$WORK"
export LR_SYNC_DB="$WORK/sync.db"
export LR_SYNC_BLOB_DIR="$WORK/blobs"
export LR_SYNC_HOST=127.0.0.1
export LR_SYNC_PORT="$PORT"

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT

echo "== [1/5] 启动服务端（端口 $PORT）=="
python3 "$ROOT/sync-server/server.py" create-user "$USER" --password "$PASS"
python3 "$ROOT/sync-server/server.py" serve > "$WORK/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1 && break
  sleep 0.25
done
curl -s "http://127.0.0.1:$PORT/api/v1/health"
echo

echo "== [2/5] Linux 客户端写入（seed）=="
LR_SYNC_BASE="http://127.0.0.1:$PORT" LR_SYNC_STEP=seed LR_SYNC_USER="$USER" LR_SYNC_PASS="$PASS" \
  "$ROOT/toolchain/build.sh" :shared:test --tests '*HostSideSyncStepTest*' --rerun 2>&1 | tail -5

echo "== [3/5] 等待 Android 设备 =="
adb wait-for-device
for _ in $(seq 1 72); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then break; fi
  sleep 5
done
adb devices

echo "== [4/5] Android 客户端：拉取 Linux 数据 -> 并发修改 -> 推送 =="
"$ROOT/toolchain/build.sh" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.linguareader.app.sync.AndroidSyncE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.base="http://10.0.2.2:$PORT" \
  -Pandroid.testInstrumentationRunnerArguments.user="$USER" \
  -Pandroid.testInstrumentationRunnerArguments.pass="$PASS" 2>&1 | tail -20

echo "== [5/5] Linux 客户端校验（verify）=="
LR_SYNC_BASE="http://127.0.0.1:$PORT" LR_SYNC_STEP=verify LR_SYNC_USER="$USER" LR_SYNC_PASS="$PASS" \
  "$ROOT/toolchain/build.sh" :shared:test --tests '*HostSideSyncStepTest*' --rerun 2>&1 | tail -5

echo "跨平台 E2E 完成（Linux <-> Android）"
