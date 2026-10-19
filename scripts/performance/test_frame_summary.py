"""Regression checks for gfxinfo parsing; no Android device is required."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "benchmark_animations", Path(__file__).resolve().parents[1] / "benchmark-animations.py"
)
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)

HEADER = "Flags,FrameTimelineVsyncId,IntendedVsync,HandleInputStart,SyncQueued,SwapBuffers,FrameCompleted,"


def dump(rows, process_reset=100, window_reset=100, total=10, janky=2):
    return "\n".join([
        "Applications Graphics Acceleration Info:",
        f"Stats since: {process_reset}ns",
        f"Total frames rendered: {total}",
        f"Janky frames: {janky} (20.00%)",
        "Window: com.ivy.wallet.debug/com.ivy.wallet.RootActivity",
        f"Stats since: {window_reset}ns",
        f"Total frames rendered: {total}",
        f"Janky frames: {janky} (20.00%)",
        "---PROFILEDATA---", HEADER, *rows, "---PROFILEDATA---",
    ])


class FrameSummaryTests(unittest.TestCase):
    def test_rejects_stale_completion_observed_in_emulator(self):
        # Actual BEFORE scroll row: current UI work with a completion timestamp
        # left over from an earlier frame, producing a negative frame duration.
        bad = "0,170564,3924750983060,3924754602900,3924762410600,3924766148800,3923250127300,"
        good = "0,170565,3924751000000,3924755000000,3924760000000,3924767000000,3924771000000,"
        result = BENCHMARK.frame_summary(dump([bad, good], window_reset=3924700000000))
        self.assertEqual(result["sampled_frames"], 1)
        self.assertEqual(result["excluded_frames"]["invalid_timestamps"], 1)
        self.assertEqual(result["frame_ms_p50"], 20)
        self.assertEqual(result["main_thread_ms_p50"], 5)

    def test_uses_window_reset_and_deduplicates_exact_rows(self):
        old = "0,1,150,160,170,180,190,"
        good = "0,2,210,220,230,240,250,"
        other = "0,3,310,320,330,340,350,"
        flagged = "2,4,410,420,430,440,450,"
        result = BENCHMARK.frame_summary(dump([old, good, good, other, flagged], window_reset=200))
        self.assertEqual(result["sampled_frames"], 2)
        self.assertEqual(result["excluded_frames"],
                         {"flagged": 1, "before_reset": 1, "duplicate": 1, "invalid_timestamps": 0})

    def test_counts_process_counters_once_per_dump(self):
        first = dump(["0,1,210,220,230,240,250,"], total=10, janky=2)
        second = dump(["0,2,310,320,330,340,350,"], total=20, janky=3)
        result = BENCHMARK.frame_summary(first + "\n" + second)
        self.assertEqual(result["total_frames"], 30)
        self.assertEqual(result["janky_frames"], 5)
        self.assertAlmostEqual(result["janky_percent"], 100 * 5 / 30)


if __name__ == "__main__":
    unittest.main()
