#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
import re
import sys

root = Path('app/src/main/java/com/lyric/ui')
tokens = root / 'theme/LyricTokens.kt'
forbidden = {
    'inline color/hex (move palette or pending artwork to LyricTokens)': r'Color\s*\(\s*(?:0[xX]|[0-9])|#[0-9a-fA-F]{6}\b|\b0[xX][0-9a-fA-F]{6,}\b',
    'raw dp/sp (use a semantic token)': r'(?<![\w.])(?:\d+(?:\.\d+)?|\.\d+)\.(?:dp|sp)\b',
    'local tween/easing (use LyricTokens.Motion)': r'\btween\s*\(|\b\w*Easing\b',
    'spring/bounce motion': r'(?i)\b(?:spring|\w*bounce\w*)\b',
    'raw animation duration': r'\b(?:durationMillis|duration|delayMillis)\s*=\s*\d|\bsetDuration\s*\(\s*\d',
}
errors = []
exceptions = []
for path in sorted(root.rglob('*.kt')):
    if path == tokens:
        continue
    for number, line in enumerate(path.read_text().splitlines(), 1):
        # Comments do not introduce runtime values; literal strings are deliberately checked.
        code = line.split('//', 1)[0]
        if code.lstrip().startswith(('*', '/*')):
            continue
        for reason, pattern in forbidden.items():
            for hit in re.finditer(pattern, code):
                if reason.startswith('raw dp') and hit[0] in ('0.dp', '1.dp'):
                    exceptions.append((path, number, hit[0] + ': zero geometry / spec hairline'))
                else:
                    errors.append(f'{path}:{number}: {reason}: {hit[0]}')

# Guard implicit Compose defaults as well: default state/visibility animations can use springs.
for path in sorted(root.rglob('*.kt')):
    if path == tokens:
        continue
    source = path.read_text()
    for call in re.finditer(r'\b(AnimatedVisibility|animateFloatAsState|animateColorAsState|animateTo|animateScrollBy|animateScrollToPage)\s*\(', source):
        depth, end, quoted, escaped = 1, call.end(), False, False
        while depth and end < len(source):
            char = source[end]
            if quoted:
                if escaped:
                    escaped = False
                elif char == '\\':
                    escaped = True
                elif char == '"':
                    quoted = False
            elif char == '"':
                quoted = True
            elif char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
            end += 1
        arguments = source[call.end():end - 1]
        valid = ('enter =' in arguments and 'exit =' in arguments) if call[1] == 'AnimatedVisibility' else 'LyricTokens.Motion.' in arguments
        if not valid:
            number = source.count('\n', 0, call.start()) + 1
            errors.append(f'{path}:{number}: {call[1]} must specify token motion (no implicit animation defaults)')

# Print the source of each pending family, rather than silently allowlisting UI literals.
anchors = {
    'object Accents': 'six accent day/night pairs from tokens.md; covers hash to these; highlights use 25%/30% overlay',
    'const val shelfSwitchMs': 'shelf switch duration is the approved 300ms; phase fractions retained',
    'const val hintMs': 'existing comic hint dwell/flash timing, not transition speed',
    'fun background(night: Boolean)': 'comic day #F4EFE6 / night #0E0F11 from tokens.md',
    'object Overlay': 'black/white scrim, dimming and image-viewer primitives',
    'val shadowColors': 'page-curl shadow is 8% black fading to transparent, not a palette color',
    'object ReaderContent': 'PAPER/NIGHT follow the UI palette; other user themes keep their own colors',
    'val indicatorStroke': 'component geometry in LyricTokens.Component; touch targets >= 48dp',
    'val focusTime': 'approved 52sp countdown display in Component',
    'const val breatheCycleMs': 'requested skeleton 1200ms rise, then reverse (no shimmer)',
}
for number, line in enumerate(tokens.read_text().splitlines(), 1):
    for anchor, reason in anchors.items():
        if anchor in line:
            exceptions.append((tokens, number, reason))
# Engine artwork remains authoritative outside the Compose scan; never silently recolor exports/content.
external = {
    'app/src/main/java/com/lyric/ui/ReaderSettingsSheet.kt': ('ModalBottomSheet(', 'Material3 1.3.1 internal sheet tween is 300ms FastOutSlowIn; no public spec override (documented library exception)'),
    'app/src/main/java/com/lyric/core/ThemePalette.kt': ('fun selection(', 'selection and curl paper-back styles remain engine-specific; highlight fill now uses accent overlay 25%/30%'),
    'app/src/main/java/com/lyric/ui/ReaderScreen.kt': ('prefs.margin.dp', 'runtime user-selected reading margin, not a literal UI spacing value'),
    'app/src/main/java/com/lyric/ui/ShelfTransition.kt': (').dp.toPx()', 'runtime interpolated translation derived from shelfRise token'),
    'app/src/main/java/com/lyric/ui/ReaderInfo.kt': ('margin.dp', 'runtime reader margin supplied by preferences'),
}
for name, (anchor, reason) in external.items():
    path = Path(name)
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if anchor in line:
            exceptions.append((path, number, reason))
            break
print('Justified exceptions (file:line + reason):')
for path, number, reason in exceptions:
    print(f'  {path}:{number}: {reason}')
if errors:
    print('\nFAIL\n' + '\n'.join(errors))
    sys.exit(1)
print(f'\nPASS: {len(list(root.rglob("*.kt"))) - 1} UI Kotlin files; LyricTokens.kt is the only excluded token source.')
PY
