#!/usr/bin/env bash
# Runs 20 am-kill and 20 foreground force-stop trials against the debug-only probe.
# Installs debug APK; does not clear the database. Adds a labelled local fixture.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/box/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/box/Android}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
adb get-state >/dev/null
./gradlew :app:installDebug --console=plain
python3 - "${1:-benchmark-results/kill-$(date +%Y%m%d-%H%M%S).json}" <<'PY'
import json, pathlib, re, subprocess, sys, time
pkg = "com.lyric.reader"
component = pkg + "/com.lyric.KillTestActivity"

def adb(*args):
    result = subprocess.run(["adb", "shell", *args], text=True, capture_output=True)
    if result.returncode and args[0] != "pidof":
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout.strip()

def snapshot(method):
    result = adb("content", "call", "--uri", "content://com.lyric.reader.debug.probe", "--method", method)
    match = re.search(r"snapshot=", result)
    if not match:
        raise RuntimeError("Probe returned no snapshot: " + result)
    return json.JSONDecoder().raw_decode(result[match.end():])[0]

def reopen():
    adb("am", "start", "-W", "-n", component, "-f", "0x10008000")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        live = snapshot("live")
        if live["ready"]:
            # Body layout is asynchronous after the book becomes ready.
            time.sleep(1)
            return live
        time.sleep(.2)
    raise RuntimeError("Reader failed to open")

snapshot("prepare")
results = []
for mode in ("kill", "force-stop"):
    for trial in range(1, 21):
        reopen()
        baseline = snapshot("persisted")["total"]
        adb("input", "keyevent", "25") # first actual reading activity
        time.sleep(14)
        adb("input", "keyevent", "25")
        time.sleep(2) # one 15s rolling checkpoint, then kill during an unfinished session
        before = snapshot("live")
        expected_time = baseline + before["active"]
        if before["active"] < 15:
            raise RuntimeError("Volume navigation failed to start a real reading session")
        if mode == "kill":
            adb("input", "keyevent", "KEYCODE_HOME") # am kill only kills background processes
            time.sleep(.5)
        adb("am", mode, pkg)
        time.sleep(.3)
        if adb("pidof", pkg):
            raise RuntimeError(f"am {mode} did not terminate the process")
        durable = snapshot("persisted") # reads Room after restarting process, before opening reader
        after = reopen()
        keys = ("chapter", "offset", "page")
        same = all(before[k] == durable[k] == after[k] for k in keys)
        raw_loss = expected_time - durable["total"]
        loss = max(0, raw_loss) # lifecycle flush may include time between the snapshot and kill
        ok = same and 0 <= loss <= 15
        results.append(dict(mode=mode, trial=trial, before=before, durable=durable,
                            reopened=after, lossSeconds=loss, rawLossSeconds=raw_loss, passed=ok))
        print(f"{mode} {trial}/20: {'PASS' if ok else 'FAIL'}, same anchor={same}, lost={loss}s", flush=True)
        path = pathlib.Path(sys.argv[1]); path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(results, ensure_ascii=False, indent=2))
if not all(row["passed"] for row in results):
    raise SystemExit(1)
PY
