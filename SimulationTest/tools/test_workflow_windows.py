"""Projection and fixed-package refusal controls; not real-window evidence."""
from pathlib import Path
import tempfile
import unittest
from workflow_windows import ui_to_client,world_to_ui,run_windows


class WindowsControls(unittest.TestCase):
    def test_existing_batch_is_not_used_for_fallback_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            out=Path(temp)/'attempt';out.mkdir();(out/'result.json').write_bytes(b'preserve-old')
            with self.assertRaises(FileExistsError):
                run_windows({'root':temp,'owner':'test'},{'released':True,'owner':'test'},out)
            self.assertEqual((out/'result.json').read_bytes(),b'preserve-old')
            self.assertFalse((out/'receipt-fallback.json').exists())
    def test_live_viewport_projection_keeps_letterbox_offset(self):
        self.assertEqual(ui_to_client(790,80,dict(screenX=120,screenY=0,screenWidth=960,screenHeight=640,height=640)),(910,560))
        self.assertEqual(world_to_ui(110,110,600,400),(134,390))

    def test_unreleased_window_does_not_start_process_or_write_batch(self):
        with tempfile.TemporaryDirectory() as temp:
            out=Path(temp)/'attempt'
            with self.assertRaisesRegex(ValueError,'released'):
                run_windows({'root':temp,'owner':'test'},{'released':False,'owner':'test'},out)
            self.assertFalse(out.exists())


if __name__=='__main__':unittest.main()
