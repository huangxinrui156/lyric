#!/usr/bin/env python3
"""Read actual Macrobenchmark JSON, enforce targets, never synthesize a device result."""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
tier = sys.argv[2] if len(sys.argv) > 2 else "reference"
if tier not in ("reference", "entry"):
    raise SystemExit("TIER must be reference or entry")
limits = {"pageTurnFrameTiming": ("frameDurationCpuMs", 16.7 if tier == "reference" else 25.0),
          "tenMegabyteTxtColdOpen": ("timeToFullDisplayMs", 1500.0 if tier == "reference" else 3000.0),
          "searchFirstResult": ("Lyric.SearchFirstResult", 1000.0)}
found = set()
failed = False
for path in root.rglob("*benchmarkData.json"):
    for bench in json.loads(path.read_text()).get("benchmarks", []):
        name = bench["name"].split("[")[0]
        if name not in limits:
            continue
        metric, limit = limits[name]
        # AndroidX appends Ms to custom trace metric names; distributions contain raw frames.
        source = bench.get("sampledMetrics", {}) if name == "pageTurnFrameTiming" else bench.get("metrics", {})
        values = next((v for k, v in source.items() if k == metric or k == metric + "Ms"), None)
        if values is None:
            print(f"MISSING {name}: {metric}")
            failed = True
            continue
        runs = values.get("runs", [])
        def flatten(value):
            return [n for item in value for n in (flatten(item) if isinstance(item, list) else [item])]
        samples = sorted(flatten(runs))
        if not samples:
            raise SystemExit(f"No samples for {name}")
        if name == "pageTurnFrameTiming":
            import math
            measured = samples[max(0, math.ceil(len(samples) * .95) - 1)]
            passed = measured <= limit
            statistic = "P95"
        else:
            measured = max(samples)
            passed = measured < limit
            statistic = "max"
        found.add(name)
        failed |= not passed
        print(f"{'PASS' if passed else 'FAIL'} {name}: {statistic} {measured:.2f}ms; target {limit}ms ({tier})")
missing = limits.keys() - found
if missing:
    print("Missing measurements: " + ", ".join(sorted(missing)))
    failed = True
raise SystemExit(1 if failed else 0)
