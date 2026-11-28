import tempfile
import unittest
from pathlib import Path

from live_hotspot_stress import (
    REQUIRED_STAGES,
    validate_hotspot_summary,
    verify_no_giant_raw_jsonl,
    world_to_screen,
)


class LiveHotspotStressTest(unittest.TestCase):
    def _sample_summary(self, p95: float = 9.2, p99: float = 14.5, steps: int = 3600, mult: float = 3.95) -> dict:
        stages = {
            k: {"mean": 0.4, "p50": 0.3, "p95": 1.1, "p99": 1.8, "max": 3.2}
            for k in REQUIRED_STAGES
        }
        return {
            "schemaVersion": 1,
            "mapId": "[z;p10]Many Islands Large (10p)",
            "mapTiles": {"width": 350, "height": 350},
            "frames": {"totalMatchFrames": 905, "warmupFramesSkipped": 5, "steadyStateSampledFrames": 900},
            "pacing": {"totalExecutedSubsteps": steps, "effectiveRateMultiplier": mult},
            "frameWorkMs": {"mean": 3.5, "p50": 2.8, "p95": p95, "p99": p99, "max": 21.0},
            "frameIntervalMs": {"mean": 16.6, "p50": 16.6, "p95": 17.0, "p99": 18.0, "max": 24.0},
            "stagesMs": stages,
            "slowFramesTop10": [],
        }

    def test_validate_hotspot_summary_accepts_healthy_and_flags_regressions(self) -> None:
        ok = self._sample_summary()
        self.assertEqual(
            [],
            validate_hotspot_summary(ok, min_steps=3600, max_p95_ms=16.7, max_p99_ms=25.0, min_effective_multiplier=3.8),
        )

        slow = self._sample_summary(p95=19.4, p99=28.1, steps=1200, mult=2.5)
        errs = validate_hotspot_summary(
            slow, min_steps=3600, max_p95_ms=16.7, max_p99_ms=25.0, min_effective_multiplier=3.8
        )
        self.assertEqual(4, len(errs))

        missing_stage = self._sample_summary()
        del missing_stage["stagesMs"]["render.minimap"]
        self.assertTrue(any("render.minimap" in e for e in validate_hotspot_summary(missing_stage)))

    def test_world_to_screen_clamps_to_safe_viewport_margin(self) -> None:
        proj = {"offsetX": 100.0, "offsetY": 900.0, "scaleX": 0.5, "scaleY": -0.5}
        self.assertEqual((300, 700), world_to_screen(proj, 400.0, 400.0, 1920, 1080))
        self.assertEqual((4, 4), world_to_screen(proj, -9999.0, 9999.0, 1920, 1080))
        self.assertEqual((1915, 1075), world_to_screen(proj, 99999.0, -99999.0, 1920, 1080))

    def test_verify_no_giant_raw_jsonl_detects_oversized_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            small = root / "small.jsonl"
            small.write_bytes(b'{"ok":true}\n')
            self.assertEqual([], verify_no_giant_raw_jsonl(root, max_jsonl_bytes=1024))

            big = root / "client-diagnostic.jsonl"
            big.write_bytes(b"x" * 2048)
            errs = verify_no_giant_raw_jsonl(root, max_jsonl_bytes=1024)
            self.assertEqual(1, len(errs))
            self.assertIn("client-diagnostic.jsonl", errs[0])


if __name__ == "__main__":
    unittest.main()
