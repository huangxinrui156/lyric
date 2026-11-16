#!/usr/bin/env bash
# Fail if app runtime/kapt dependencies are not on the known-license allowlist
# that matches README and app/src/main/assets/open-source-notices.txt.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
gradle="$root/app/build.gradle.kts"
notices="$root/app/src/main/assets/open-source-notices.txt"
ofl="$root/third_party/NotoSerif-OFL.txt"

# artifact -> license label (must stay in sync with README / notices)
declare -A licenses=(
  [androidx.compose:compose-bom]="Apache-2.0"
  [androidx.activity:activity-compose]="Apache-2.0"
  [androidx.profileinstaller:profileinstaller]="Apache-2.0"
  [androidx.compose.ui:ui]="Apache-2.0"
  [androidx.compose.ui:ui-tooling-preview]="Apache-2.0"
  [androidx.compose.material3:material3]="Apache-2.0"
  [androidx.compose.material:material-icons-extended]="Apache-2.0"
  [androidx.navigation:navigation-compose]="Apache-2.0"
  [androidx.lifecycle:lifecycle-runtime-compose]="Apache-2.0"
  [androidx.lifecycle:lifecycle-viewmodel-compose]="Apache-2.0"
  [androidx.room:room-runtime]="Apache-2.0"
  [androidx.room:room-ktx]="Apache-2.0"
  [androidx.room:room-compiler]="Apache-2.0"
  [androidx.datastore:datastore-preferences]="Apache-2.0"
  [androidx.documentfile:documentfile]="Apache-2.0"
  [org.jetbrains.kotlinx:kotlinx-coroutines-android]="Apache-2.0"
  [org.jetbrains.kotlinx:kotlinx-serialization-json]="Apache-2.0"
  [io.coil-kt:coil-compose]="Apache-2.0"
  [io.coil-kt:coil-svg]="Apache-2.0"
  [com.caverock:androidsvg-aar]="Apache-2.0"
  [com.github.albfernandez:juniversalchardet]="MPL-1.1"
  [org.jsoup:jsoup]="MIT"
)

missing=0
unknown=0

deps=$(python3 - << PY
import re
from pathlib import Path
text = Path("$gradle").read_text()
# implementation("g:a:v") / kapt("g:a:v") / implementation(platform("g:a:v"))
pat = re.compile(r'(?:implementation|api|kapt)\((?:platform\()?["\']([^"\']+)["\']')
for m in pat.finditer(text):
    coord = m.group(1)
    parts = coord.split(":")
    if len(parts) >= 2:
        print(f"{parts[0]}:{parts[1]}")
PY
)

echo "Checking runtime/kapt dependencies in app/build.gradle.kts"
seen=()
while IFS= read -r artifact; do
  [ -z "$artifact" ] && continue
  if [[ -z "${licenses[$artifact]+x}" ]]; then
    echo "UNKNOWN LICENSE: $artifact"
    unknown=$((unknown + 1))
  else
    echo "  $artifact  ${licenses[$artifact]}"
  fi
  seen+=("$artifact")
done <<< "$deps"

# Every allowlisted artifact should still be declared (catch stale docs).
for artifact in "${!licenses[@]}"; do
  if ! grep -q "$artifact" <<<"$deps"; then
    echo "MISSING FROM GRADLE: $artifact (listed in license allowlist)"
    missing=$((missing + 1))
  fi
done

if [[ ! -f "$notices" ]]; then
  echo "MISSING notices file: $notices"
  missing=$((missing + 1))
else
  for needle in "Apache License 2.0" "MIT License" "Mozilla Public License 1.1" "SIL OPEN FONT LICENSE"; do
    if ! grep -q "$needle" "$notices"; then
      echo "MISSING from open-source-notices.txt: $needle"
      missing=$((missing + 1))
    fi
  done
fi

if [[ ! -f "$ofl" ]]; then
  echo "MISSING font license: $ofl"
  missing=$((missing + 1))
fi

if [[ "$unknown" -ne 0 || "$missing" -ne 0 ]]; then
  echo "FAIL: $unknown unknown, $missing missing"
  exit 1
fi
echo "PASS: ${#licenses[@]} known-licensed app dependencies; notices and OFL text present."
