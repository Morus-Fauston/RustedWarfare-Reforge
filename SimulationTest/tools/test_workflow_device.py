"""Host refusal controls. These do not claim ART or real UI coverage."""
from pathlib import Path
import tempfile
import unittest
import workflow_device as device


class DeviceControls(unittest.TestCase):
    def test_occupied_resource_gets_an_end_receipt_without_device_commands(self):
        from workflow import resources
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'attempt'
            config=dict(released=True,owner='test',index=1,creation=1792546680141873,
                        **{k:{} for k in ('adb','manager','powershell','receiver','pm','baseline','hostEpoch')})
            with resources(['mumu:1:1792546680141873']):
                result=device.run_device({'root':temp,'owner':'test'},config,out,'core-mumu-art')
            self.assertEqual(result['status'],'blocked')
            self.assertEqual(result['steps'],[])
            self.assertTrue((out/'result.json').is_file())
            self.assertTrue(result['endedUnix'])
    def test_unreleased_device_is_refused_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'attempt'
            with self.assertRaisesRegex(ValueError,'released'):
                device.run_device({'root':temp,'owner':'test'}, {'released':False,'owner':'test'},out,'core-mumu-art')
            self.assertFalse(out.exists())

    def test_wrong_instance_is_refused_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'attempt'
            with self.assertRaisesRegex(ValueError,'approved MuMu'):
                device.run_device({'root':temp,'owner':'test'},
                    {'released':True,'owner':'test','index':0,'creation':1792546680141873},out,'core-mumu-ui')
            self.assertFalse(out.exists())

    def test_bad_fixed_sha_cannot_be_loaded_as_a_decoder(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'not-executed.py';p.write_text('raise RuntimeError("must not execute")')
            with self.assertRaisesRegex(ValueError,'Fixed input changed'):
                device.load_module({'path':str(p),'sha256':'0'*64,'bytes':p.stat().st_size},'no_execute')

    def test_server_without_unique_owner_is_refused(self):
        value={'listeners':[{'LocalPort':15037,'OwningProcess':123}],'adb':[]}
        with self.assertRaisesRegex(ValueError,'unique executable'):
            device.server_owners(value)


if __name__=='__main__':unittest.main()
