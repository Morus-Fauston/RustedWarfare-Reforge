"""Projection and fixed-package refusal controls; not real-window evidence."""
from pathlib import Path
import tempfile
import unittest
from workflow_windows import ui_to_client,world_to_ui,run_windows,stable_stop


class WindowsControls(unittest.TestCase):
    def test_native_short_contract_is_validated_before_any_input_or_long_history(self):
        from platform_windows import validate_windows_plan
        from platform_scenario import PlatformError
        plan=dict(scenarioId='s',intents=[dict(intentId='one',kind='select')])
        rule=dict(intentId='one',actionType='SelectAction',unitId='a',accepted=True,
            assertions=[dict(path='units.a.typeId',operator='equals',value='builder')])
        contract=dict(scenarioId='s',rules=[rule])
        validate_windows_plan(plan,contract)
        for changed in (dict(contract,rules=[]),dict(contract,history={}),
                        dict(contract,rules=[dict(rule,assertions=[])]),dict(contract,scenarioId='wrong')):
            with self.assertRaises(PlatformError):validate_windows_plan(plan,changed)

    def test_stop_requires_nine_stable_positions_and_headings_after_the_action(self):
        # The current core retains its last steering accumulator without a target.
        # Stopping is observed from actual movement, not that inactive accumulator.
        samples=[dict(kind='observation',phase='step',sequence=i,sample=dict(units=[dict(
            unitId='u1',position=dict(x=118.854,y=112.271),heading=111.25,speed=0,
            angularVelocity=2.25,moveTarget=None)])) for i in range(20,29)]
        self.assertTrue(stable_stop(samples,'u1',19))
        self.assertFalse(stable_stop(samples,'u1',20))
        samples[-1]['sample']['units'][0]['heading']=112
        self.assertFalse(stable_stop(samples,'u1',19))
        samples[-1]['sample']['units'][0].update(heading=111.25,speed=.01)
        self.assertFalse(stable_stop(samples,'u1',19))
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
