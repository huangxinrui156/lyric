"""Host tests for benchmark target enforcement; fixtures below are synthetic, not device results."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

CHECKER = pathlib.Path(__file__).with_name("check-benchmarks.py")


class BenchmarkResultsTest(unittest.TestCase):
    def check(self, frames=10.0, cold=1000.0, search=500.0, tier="reference", missing=False):
        with tempfile.TemporaryDirectory() as temp:
            benches = [
                {"name": "pageTurnFrameTiming", "sampledMetrics": {"frameDurationCpuMs": {"runs": [[frames] * 100]}}},
                {"name": "tenMegabyteTxtColdOpen", "metrics": {"timeToFullDisplayMs": {"runs": [cold] * 5}}},
                {"name": "searchFirstResult", "metrics": {"Lyric.SearchFirstResultMs": {"runs": [search] * 5}}},
            ]
            if missing:
                benches.pop()
            pathlib.Path(temp, "test-benchmarkData.json").write_text(json.dumps({"benchmarks": benches}))
            return subprocess.run([sys.executable, str(CHECKER), temp, tier], text=True, capture_output=True)

    def test_targets_use_selected_tier(self):
        self.assertEqual(0, self.check().returncode)
        self.assertEqual(1, self.check(frames=20, cold=2000).returncode)
        self.assertEqual(0, self.check(frames=20, cold=2000, tier="entry").returncode)

    def test_missing_measurement_cannot_pass(self):
        result = self.check(missing=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("searchFirstResult", result.stdout)

    def test_strict_open_and_search_limits_and_inclusive_frame_limit(self):
        self.assertEqual(0, self.check(frames=16.7).returncode)
        self.assertEqual(1, self.check(cold=1500).returncode)
        self.assertEqual(1, self.check(search=1000).returncode)


if __name__ == "__main__":
    unittest.main()
