#!/usr/bin/env bash
set -euo pipefail

remote_host=${ANDROID_REMOTE_HOST:-dingyi@10.1.1.14}
remote_dir=${ANDROID_REMOTE_DIR:-/home/dingyi/projects/lua-parser-android-e2e}
ready_budget=${ANDROID_READY_BUDGET_MS:-10000}
build_type=${ANDROID_E2E_BUILD_TYPE:-release}
clear_data=${ANDROID_E2E_CLEAR_DATA:-1}
case "$build_type" in debug|release) ;; *) echo "Use debug or release" >&2; exit 1 ;; esac
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

rsync -az --exclude .git --exclude .gradle --exclude build \
  --exclude node_modules --exclude local.properties --exclude '*.apk' "$repo_dir/" "$remote_host:$remote_dir/"
printf -v remote_command 'bash -s -- %q %q %q %q' "$remote_dir" "$ready_budget" "$build_type" "$clear_data"
ssh "$remote_host" "$remote_command" <<'REMOTE'
set -euo pipefail
run_remote() {
cd "$1"
ready_budget=$2
build_type=$3
clear_data=$4
variant=$(printf '%s' "$build_type" | sed 's/^./\U&/')
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/temurin-17}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
reports_dir=$PWD/build/android-e2e
mkdir -p "$reports_dir"

./gradlew ":android:sample:assemble$variant" ":android:sample:assemble${variant}AndroidTest" \
  -PandroidE2eBuildType="$build_type" \
  --no-daemon --parallel --max-workers=8 -PjvmTestForks=1 \
  >"$reports_dir/build.log" 2>&1 || { tail -60 "$reports_dir/build.log"; exit 1; }

if ! adb devices | grep -q '^emulator-'; then
  nohup "$ANDROID_HOME/emulator/emulator" -avd lua-parser-e2e28 -port 5554 \
    -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect \
    -accel off -memory 3072 -cores 6 >"$reports_dir/emulator.log" 2>&1 </dev/null &
fi
boot_deadline=$((SECONDS + 360))
until test "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = 1; do
  test "$SECONDS" -lt "$boot_deadline" || { echo "Remote emulator did not boot" >&2; exit 1; }
  sleep 2
done
adb shell am force-stop io.github.dingyi222666.luaparser.sample
if test "$clear_data" = 1 && adb shell pm path io.github.dingyi222666.luaparser.sample | grep -q '^package:'; then
  # Clear fixtures before install: pm clear also clears ART profiles.
  adb shell pm clear io.github.dingyi222666.luaparser.sample
fi
apk_path="android/sample/build/outputs/apk/$build_type/sample-$build_type.apk"
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if test "$build_type" = release && test "$sdk" -ge 28 && test "$sdk" -le 30; then
  python3 - "$apk_path" <<'PY'
from pathlib import Path
import sys,zipfile
apk=Path(sys.argv[1])
with zipfile.ZipFile(apk) as package,zipfile.ZipFile(apk.with_suffix('.dm'),'w',compression=zipfile.ZIP_DEFLATED) as metadata:
    for name in ['baseline.prof','baseline.profm']:
        metadata.writestr(name.replace('baseline','primary'), package.read('assets/dexopt/'+name))
PY
  timeout 180 adb install-multiple --no-streaming -r "$apk_path" "${apk_path%.apk}.dm"
else
  timeout 90 adb install --no-streaming -r "$apk_path"
fi
timeout 90 adb install --no-streaming -r "android/sample/build/outputs/apk/androidTest/$build_type/sample-$build_type-androidTest.apk"
adb shell dumpsys package dexopt >"$reports_dir/dexopt.log"
# The default ring buffer evicts early-test lines (System.err-heavy phases);
# enlarge it so the full run stays inspectable.
# Free build-phase CPU before instrumentation: a lingering Gradle daemon plus the
# TCG emulator on the shared host inflated cold-stage times run-to-run
# (builtins 10.0s -> 16.0s purely from load variance).
pkill -f GradleDaemon >/dev/null 2>&1 || true
sleep 2
adb logcat -G 16M
adb logcat -c
test_result=0
timeout 360 adb shell am instrument -w -e readyBudgetMs "$ready_budget" \
  io.github.dingyi222666.luaparser.sample.test/androidx.test.runner.AndroidJUnitRunner \
  >"$reports_dir/instrumentation.log" 2>&1 || test_result=$?
adb logcat -d >"$reports_dir/logcat.log"
cat "$reports_dir/instrumentation.log"
grep -E 'AndroidE2E|PerfTiming' "$reports_dir/logcat.log" || true
test "$test_result" = 0
grep -q '^OK (' "$reports_dir/instrumentation.log"
}
# Gradle/ADB must not consume the shell script arriving on SSH standard input.
run_remote "$@" </dev/null
REMOTE
