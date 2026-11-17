"""Run: python3 -m unittest discover -s scripts/tests"""
import csv
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import latency_report as lr  # noqa: E402


class LatencyReportTest(unittest.TestCase):
    def test_percentiles_nearest_rank(self):
        v = list(range(1, 11))  # 1..10
        self.assertEqual(lr.percentile(v, 50), 5)
        self.assertEqual(lr.percentile(v, 90), 9)
        self.assertEqual(lr.percentile([7], 90), 7)
        self.assertIsNone(lr.percentile([], 50))

    def test_only_ok_rows_and_stage_pairs(self):
        cols = ["id", "outcome", "mode", "source"] + [s + "_ms" for s in lr.STAGES]
        rows = []
        for i in range(10):
            r = {c: "" for c in cols}
            r.update(id=str(i), outcome="ok", mode="tap", source="glasses",
                     image_header_ms="0", image_received_ms=str(2000 + i), play_started_ms=str(9000 + i))
            rows.append(r)
        bad = {c: "" for c in cols}
        bad.update(id="x", outcome="model_timeout", mode="tap", source="glasses", image_header_ms="0")
        rows.append(bad)
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, newline="") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            w.writeheader()
            w.writerows(rows)
        runs, total = lr.load(f.name, "tap", None)
        os.unlink(f.name)
        self.assertEqual(total, 11)
        self.assertEqual(len(runs), 10)
        text = lr.report(runs)
        self.assertIn("image_header -> image_received", text)
        self.assertIn("image_received -> play_started", text)
        self.assertIn("(first stage) -> play_started", text)


if __name__ == "__main__":
    unittest.main()
