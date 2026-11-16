#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/box/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/box/Android}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is missing. Install Android SDK platform-tools and connect one real device; no device measurements were run." >&2
    exit 1
fi
# ANDROID_SERIAL selects exactly one device. adb fails if selection is ambiguous.
if ! adb get-state >/dev/null; then
    echo "ERROR: no authorized device selected; connect one device or set ANDROID_SERIAL." >&2
    exit 1
fi
out="${1:-benchmark-results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
if [[ -n "$(ls -A "$out")" ]]; then
    echo "ERROR: result directory must be empty to avoid mixing device runs: $out" >&2
    exit 1
fi
out="$(cd "$out" && pwd)"
pkg=com.lyric.reader.benchmark
epub="${LYRIC_EPUB_FIXTURE:-/workspace/epub-real/jianlai.epub}"
cbz="${LYRIC_CBZ_FIXTURE:-/workspace/lyric-test-500p.cbz}"
python3 - "$epub" "$cbz" <<'PY'
import hashlib
import pathlib
import sys
for filename, expected in zip(sys.argv[1:], (
    "8368c9b1a06a5ce8ba156fda5892cb20020b008680dbc8a99de2d833cc789940",
    "db3269a3b229bc6f75e351ab10e94175a8bbbea63383eba66f229e48f841a4c7",
)):
    path = pathlib.Path(filename)
    if not path.is_file():
        raise SystemExit(f"ERROR: missing fixture: {path}; set LYRIC_EPUB_FIXTURE / LYRIC_CBZ_FIXTURE")
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else None
        if actual is None:
            stream.seek(0)
            digest = hashlib.sha256()
            for chunk in iter(lambda: stream.read(65536), b""):
                digest.update(chunk)
            actual = digest.hexdigest()
    if actual != expected:
        raise SystemExit(f"ERROR: fixture SHA-256 mismatch: {path}: {actual}")
    print(f"{actual}  {path}")
PY
adb shell getprop > "$out/device-properties.txt"
adb shell cat /proc/meminfo > "$out/device-ram.txt"
adb shell dumpsys display > "$out/device-display.txt"
adb shell dumpsys power > "$out/device-power.txt"
adb shell dumpsys battery > "$out/device-battery.txt"
{
    echo "serial=$(adb get-serialno)"
    echo "model=$(adb shell getprop ro.product.model)"
    echo "incremental=$(adb shell getprop ro.build.version.incremental)"
    echo "API=$(adb shell getprop ro.build.version.sdk)"
    echo "peak_refresh_rate=$(adb shell settings get system peak_refresh_rate)"
    echo "min_refresh_rate=$(adb shell settings get system min_refresh_rate)"
    echo "low_power=$(adb shell settings get global low_power)"
    for setting in animator_duration_scale transition_animation_scale window_animation_scale; do
        echo "$setting=$(adb shell settings get global "$setting")"
    done
} > "$out/device-summary.txt"
cat "$out/device-summary.txt"
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if (( api < 29 )); then
    echo "ERROR: Macrobenchmark requires API 29+." >&2
    exit 1
fi
for setting in animator_duration_scale transition_animation_scale window_animation_scale; do
    value="$(adb shell settings get global "$setting" | tr -d '\r')"
    if [[ "$value" != 1 && "$value" != 1.0 && "$value" != null ]]; then
        echo "ERROR: set $setting to 1× before measuring (currently $value)." >&2
        exit 1
    fi
done
if [[ "$(adb shell settings get global low_power | tr -d '\r')" == 1 ]]; then
    echo "ERROR: disable battery saver before measuring." >&2
    exit 1
fi

./gradlew :app:assembleBenchmark --console=plain 2>&1 | tee "$out/build.log"
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
# This special permission exists only in the benchmark manifest. No run-as/private push.
if (( api >= 30 )); then
    adb shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow
else
    adb shell pm grant "$pkg" android.permission.READ_EXTERNAL_STORAGE
fi
push_fixture() {
    local source="$1" name="$2" pushed=false
    # Prefer shell storage; SELinux may still block app reads even with chmod 644.
    if adb shell mkdir -p /data/local/tmp/lyric-fixtures &&
       adb shell chmod 755 /data/local/tmp/lyric-fixtures &&
       adb push "$source" "/data/local/tmp/lyric-fixtures/$name" &&
       adb shell chmod 644 "/data/local/tmp/lyric-fixtures/$name"; then
        pushed=true
    fi
    # Also stage Download: BenchmarkActivity tries this when shell storage is unreadable.
    if adb shell mkdir -p /sdcard/Download/lyric-baseline &&
       adb push "$source" "/sdcard/Download/lyric-baseline/$name"; then
        # Emulated storage can ignore chmod; the storage app-op provides app access.
        adb shell chmod 644 "/sdcard/Download/lyric-baseline/$name" || true
        pushed=true
    fi
    if [[ "$pushed" != true ]]; then
        echo "ERROR: cannot stage fixture $name in either documented location." >&2
        exit 1
    fi
}
push_fixture "$epub" jianlai.epub
push_fixture "$cbz" lyric-test-500p.cbz

# Exclude stale JSON/traces from earlier devices. Preserve build caches.
rm -rf macrobenchmark/build/outputs/connected_android_test_additional_output macrobenchmark/build/reports/androidTests
set +e
./gradlew :app:assembleBenchmark :macrobenchmark:connectedBenchmarkAndroidTest --console=plain 2>&1 | tee "$out/gradle.log"
gradle_status=${PIPESTATUS[0]}
set -e
for artifact in macrobenchmark/build/outputs/connected_android_test_additional_output macrobenchmark/build/reports/androidTests; do
    if [[ -d "$artifact" ]]; then cp -R "$artifact" "$out/"; fi
done
# Snapshot diagnostics OUTSIDE measurement; never used as a substitute for trace peak.
adb shell dumpsys meminfo "$pkg" > "$out/meminfo-after-suite.txt"
for fixture in txt epub cbz; do
    adb shell am force-stop "$pkg"
    adb shell am start -W -n "$pkg/com.lyric.BenchmarkActivity" --es fixture "$fixture" > "$out/meminfo-launch-$fixture.txt"
    sleep 3
    adb shell dumpsys meminfo "$pkg" > "$out/meminfo-$fixture.txt"
done
set +e
python3 scripts/check-benchmarks.py "$out" "${TIER:-reference}" | tee "$out/check.txt"
checker_status=${PIPESTATUS[0]}
set -e
echo "Results (JSON, traces, reports, device conditions, meminfo, checker): $out"
if (( gradle_status != 0 || checker_status != 0 )); then exit 1; fi
