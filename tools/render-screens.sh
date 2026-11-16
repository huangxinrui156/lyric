#!/usr/bin/env bash
# Render README candidate screenshots on the JVM (Robolectric + Compose, no emulator).
# Output: $LYRIC_SCREENS_DIR or <repo>/screens/  — 1080×2400 (density 2.75, w393dp-h873dp-440dpi) PNGs with public-domain titles.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export JAVA_HOME="${JAVA_HOME:-/home/box/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/box/Android}"
out="${LYRIC_SCREENS_DIR:-$root/screens}"
build_out="$root/app/build/outputs/readme-screens"
mkdir -p "$out" "$build_out"
./gradlew :app:testDebugUnitTest \
  -Plyric.render.screens=true \
  -Plyric.screens.dir="$build_out" \
  --tests com.lyric.ui.ReadmeScreenshotTest
shopt -s nullglob
copied=0
for png in "$build_out"/*.png; do
  cp -f "$png" "$out/"
  copied=$((copied + 1))
done
if [ "$copied" -eq 0 ]; then
  echo "No PNGs were produced in $build_out" >&2
  exit 1
fi
echo "Wrote $copied screenshots to $out"
ls -la "$out"/*.png
