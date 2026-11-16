#!/usr/bin/env python3
"""Enforce unified 1.0 device gates from actual AndroidX 1.3.3 JSON; never fill gaps."""
import json
import math
import pathlib
import sys

OPEN_LIMITS = {"epub1248ColdOpen": 1500.0, "tenMegabyteTxtColdOpen": 1500.0,
               "cbz500ColdOpen": 1000.0}
SCROLLS = {"textScrollFrameTiming", "comicScrollFrameTiming"}
REQUIRED = set(OPEN_LIMITS) | SCROLLS
MEMORY_PARTS = ("RssAnon", "RssFile", "RssShmem", "Gpu")


def samples(bench, metric, sampled=False):
    source = bench.get("sampledMetrics" if sampled else "metrics", {})
    value = source.get(metric)
    if value is None:
        raise ValueError(f"missing {metric}")
    runs = value.get("runs", [])
    if len(runs) != 5:
        raise ValueError(f"{metric}: expected 5 iterations, got {len(runs)}")

    def flatten(items):
        for item in items:
            if isinstance(item, list):
                yield from flatten(item)
            elif isinstance(item, (int, float)) and not isinstance(item, bool) and math.isfinite(item) and item >= 0:
                yield item
            else:
                raise ValueError(f"{metric}: invalid sample {item!r}")

    if sampled and any(not isinstance(run, list) or not run for run in runs):
        raise ValueError(f"{metric}: missing raw frames in an iteration")
    if not sampled and any(isinstance(run, list) for run in runs):
        raise ValueError(f"{metric}: expected one scalar per iteration")
    result = list(flatten(runs))
    if not result:
        raise ValueError(f"{metric}: no samples")
    return result


def check(root, tier):
    if tier not in ("reference", "entry"):
        raise ValueError("TIER must be reference or entry (both use unified 1.0 limits)")
    found = set()
    failed = False
    for path in sorted(root.rglob("*benchmarkData.json")):
        for bench in json.loads(path.read_text()).get("benchmarks", []):
            name = bench["name"].split("[")[0]
            if name not in REQUIRED:
                continue  # Historical page turn/search remain measurements, not 1.0 gates.
            found.add(name)
            try:
                if name in OPEN_LIMITS:
                    measured = max(samples(bench, "timeToFullDisplayMs"))
                    limit = OPEN_LIMITS[name]
                    passed = measured < limit
                    detail = f"MAX {measured:.2f}ms; target <{limit}ms"
                else:
                    frames = sorted(samples(bench, "frameDurationCpuMs", sampled=True))
                    measured = frames[math.ceil(len(frames) * .95) - 1]
                    passed = measured <= 16.7
                    detail = f"raw-frame P95 {measured:.2f}ms; target ≤16.7ms"
                print(f"{'PASS' if passed else 'FAIL'} {name}: {detail} ({tier}, 1.0)")
                failed |= not passed

                # Conservative process-resident upper bound including GPU, in decimal MB.
                # Sum component MAXIMA per iteration, then take MAX across all 5 iterations.
                # HeapSize is required diagnostic data but already belongs to RSS: never add twice.
                samples(bench, "memoryHeapSizeMaxKb")
                parts = [samples(bench, f"memory{part}MaxKb") for part in MEMORY_PARTS]
                peak_mb = max(map(sum, zip(*parts))) * 1024 / 1_000_000
                passed = peak_mb < 256.0
                failed |= not passed
                print(f"{'PASS' if passed else 'FAIL'} {name}: RSS+GPU peak upper bound "
                      f"{peak_mb:.3f}MB; target <256MB (component maxima; not heap-only/PSS)")
            except (ValueError, TypeError) as error:
                print(f"MISSING/INVALID {name}: {error}")
                failed = True
    missing = REQUIRED - found
    if missing:
        print("Missing measurements: " + ", ".join(sorted(missing)))
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        raise SystemExit(check(pathlib.Path(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else "reference"))
    except (ValueError, KeyError, TypeError, OSError, IndexError) as error:
        print(f"Invalid benchmark results: {error}")
        raise SystemExit(1)
