"""Public reuse explanations keep source layers separate."""
import unittest
import json,subprocess,sys,tempfile
from pathlib import Path
from workflow_reuse import classify_sources,explain_reuse
from workflow_device import reference
from workflow_inputs import freeze


class ReuseControls(unittest.TestCase):
    def fixture(self,root,targets,profile):
        subprocess.run(['git','init','-q',str(root)],check=True)
        (root/'map.tmx').write_text('<map/>')
        plan=dict(root=str(root),java=sys.executable,targets=targets,inputs=freeze(root,['map.tmx']))
        previous=dict(schemaVersion=1,sourceBefore={},passed=True,profile=profile,plan=json.loads(json.dumps(plan)),
                      toolIdentity={'java':{'sha256':reference(sys.executable)['sha256']}})
        return plan,previous

    def test_bad_platform_sha_is_refused_even_when_aggregate_previously_passed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);plan,previous=self.fixture(root,['core-pc-ui'],'quick')
            manifest=root/'pc-manifest.json';manifest.write_text('[]')
            plan['windows']={'packageManifest':dict(reference(manifest),sha256='0'*64)}
            path=root/'previous.json';path.write_text(json.dumps(previous))
            with self.assertRaisesRegex(ValueError,'Fixed input changed'):
                explain_reuse(plan,reference(path))

    def test_qualification37_does_not_cover_formal_measurement576(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);plan,previous=self.fixture(root,['core-pc-headless'],'performance')
            protocol=root/'protocol.json';protocol.write_text('{}');ref=reference(protocol)
            previous['performance']=dict(mode='qualify',batches=37)
            previous['plan']['performance']=dict(mode='qualify',protocol=ref)
            plan['performance']=dict(mode='measure',protocol=ref)
            path=root/'previous.json';path.write_text(json.dumps(previous))
            result=explain_reuse(plan,reference(path),profile='performance')
            self.assertFalse(result['coverageMatched'])
    def test_navigation_change_does_not_invalidate_core_or_comparison(self):
        before={'README.md':'old','Engine/core/src/main/A.kt':'same'}
        rows=classify_sources(before,{**before,'README.md':'new'})
        self.assertTrue(rows['core-run']['identityMatched'])
        self.assertTrue(rows['comparison']['identityMatched'])
        self.assertFalse(rows['navigation']['identityMatched'])

    def test_comparator_change_requires_rejudgment_but_preserves_core_layer(self):
        key='SimulationTest/src/main/kotlin/Cross.kt'
        rows=classify_sources({key:'old'},{key:'new'})
        self.assertTrue(rows['core-run']['identityMatched'])
        self.assertFalse(rows['comparison']['identityMatched'])

    def test_new_build_configuration_cannot_be_treated_as_navigation(self):
        rows=classify_sources({}, {'Engine/build.gradle.kts':'new'})
        self.assertFalse(rows['build-inputs']['identityMatched'])


if __name__=='__main__':unittest.main()
