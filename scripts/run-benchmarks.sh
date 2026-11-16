#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/box/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/box/Android}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
# ANDROID_SERIAL selects a device when more than one is attached.
adb get-state >/dev/null
out="${1:-benchmark-results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
adb shell getprop > "$out/device-properties.txt"
adb shell dumpsys meminfo > "$out/memory.txt"
./gradlew :app:assembleBenchmark :macrobenchmark:connectedBenchmarkAndroidTest --console=plain 2>&1 | tee "$out/gradle.log"
cp -R macrobenchmark/build/outputs/connected_android_test_additional_output "$out/"
cp -R macrobenchmark/build/reports/androidTests "$out/"
python3 scripts/check-benchmarks.py "$out" "${TIER:-reference}"
