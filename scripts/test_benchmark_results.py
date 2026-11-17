"""Checker unit tests use synthetic JSON only; these are never device measurements."""
import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

CHECKER = pathlib.Path(__file__).with_name("check-benchmarks.py")
NAMES = ("epub1248ColdOpen", "tenMegabyteTxtColdOpen", "cbz500ColdOpen",
         "textScrollFrameTiming", "comicScrollFrameTiming")


def fixture():
    benches = []
    for name in NAMES:
        metrics = {f"memory{part}MaxKb": {"runs": [value] * 5} for part, value in
                   (("HeapSize", 10000), ("RssAnon", 50000), ("RssFile", 20000),
                    ("RssShmem", 1000), ("Gpu", 10000))}
        bench = {"name": name, "metrics": metrics}
        if "ColdOpen" in name:
            metrics["timeToFullDisplayMs"] = {"runs": [900.0] * 5}
        else:
            bench["sampledMetrics"] = {"frameDurationCpuMs": {"runs": [[10.] * 100 for _ in range(5)]}}
        benches.append(bench)
    return benches


class BenchmarkResultsTest(unittest.TestCase):
    def check(self, benches=None, tier="reference"):
        with tempfile.TemporaryDirectory() as temp:
            pathlib.Path(temp, "test-benchmarkData.json").write_text(json.dumps(
                {"benchmarks": fixture() if benches is None else benches}))
            return subprocess.run([sys.executable, str(CHECKER), temp, tier], text=True, capture_output=True)

    def test_both_tiers_use_unified_limits(self):
        for tier in ("reference", "entry"):
            self.assertEqual(0, self.check(tier=tier).returncode)
            benches = fixture()
            benches[1]["metrics"]["timeToFullDisplayMs"]["runs"][4] = 2000
            self.assertEqual(1, self.check(benches, tier).returncode)
            benches = fixture()
            benches[3]["sampledMetrics"]["frameDurationCpuMs"]["runs"] = [[20.] * 100] * 5
            self.assertEqual(1, self.check(benches, tier).returncode)

    def test_every_required_measurement_is_required(self):
        for index, name in enumerate(NAMES):
            benches = fixture()
            benches.pop(index)
            result = self.check(benches)
            self.assertEqual(1, result.returncode)
            self.assertIn(name, result.stdout)
        self.assertEqual(1, self.check([]).returncode)

    def test_open_boundaries_are_exclusive_and_use_max(self):
        for index, limit in ((0, 1500), (1, 1500), (2, 1000)):
            benches = fixture()
            runs = benches[index]["metrics"]["timeToFullDisplayMs"]["runs"]
            runs[-1] = limit - .01
            self.assertEqual(0, self.check(benches).returncode)
            runs[-1] = limit
            self.assertEqual(1, self.check(benches).returncode)

    def test_scroll_boundaries_inclusive_for_text_and_comic(self):
        for index in (3, 4):
            benches = fixture()
            value = benches[index]["sampledMetrics"]["frameDurationCpuMs"]
            value["runs"] = [[16.7] * 100] * 5
            self.assertEqual(0, self.check(benches).returncode)
            value["runs"] = [[16.7001] * 100] * 5
            self.assertEqual(1, self.check(benches).returncode)

    def test_p95_uses_raw_frames_nearest_rank_not_iteration_average(self):
        benches = fixture()
        value = benches[3]["sampledMetrics"]["frameDurationCpuMs"]
        value["runs"] = [[10.] * 95 + [99.] * 5] * 5
        self.assertEqual(0, self.check(benches).returncode)
        value["runs"] = [[10.] * 94 + [99.] * 6] * 5
        self.assertEqual(1, self.check(benches).returncode)

    def test_memory_exclusive_256_decimal_mb_and_includes_graphics(self):
        for index in range(5):
            benches = fixture()
            metrics = benches[index]["metrics"]
            # 250000 KiB = exactly 256 decimal MB; GPU alone crosses the boundary.
            for part in ("RssAnon", "RssFile", "RssShmem"):
                metrics[f"memory{part}MaxKb"]["runs"] = [0] * 5
            metrics["memoryGpuMaxKb"]["runs"] = [249999] * 5
            self.assertEqual(0, self.check(benches).returncode)
            metrics["memoryGpuMaxKb"]["runs"][-1] = 250000
            self.assertEqual(1, self.check(benches).returncode)

    def test_missing_memory_component_cannot_pass_even_with_heap(self):
        for part in ("HeapSize", "RssAnon", "RssFile", "RssShmem", "Gpu"):
            benches = fixture()
            del benches[0]["metrics"][f"memory{part}MaxKb"]
            self.assertEqual(1, self.check(benches).returncode)

    def test_five_complete_iterations_are_required(self):
        for field, metric, index in (("metrics", "timeToFullDisplayMs", 0),
                                     ("metrics", "memoryGpuMaxKb", 0),
                                     ("sampledMetrics", "frameDurationCpuMs", 3)):
            benches = fixture()
            benches[index][field][metric]["runs"].pop()
            self.assertEqual(1, self.check(benches).returncode)
        benches = fixture()
        benches[3]["sampledMetrics"]["frameDurationCpuMs"]["runs"][0] = []
        self.assertEqual(1, self.check(benches).returncode)

    def test_invalid_samples_fail(self):
        for value in (float("nan"), float("inf"), -1, "10", None):
            benches = fixture()
            benches[0]["metrics"]["timeToFullDisplayMs"]["runs"][0] = value
            self.assertEqual(1, self.check(benches).returncode)

    def test_legacy_results_do_not_replace_required_measurements(self):
        benches = [{"name": "pageTurnFrameTiming"}, {"name": "searchFirstResult"}]
        self.assertEqual(1, self.check(benches).returncode)

    def test_unknown_tier_fails(self):
        self.assertEqual(1, self.check(tier="relaxed").returncode)

    def test_duplicate_reports_do_not_hide_failure(self):
        benches = fixture()
        failed = copy.deepcopy(benches[0])
        failed["metrics"]["timeToFullDisplayMs"]["runs"][0] = 1500
        self.assertEqual(1, self.check([failed] + benches).returncode)


if __name__ == "__main__":
    unittest.main()
