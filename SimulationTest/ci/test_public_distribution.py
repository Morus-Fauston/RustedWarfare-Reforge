"""Fixed distribution admission at the filesystem/process boundary; no simulated test results."""
from pathlib import Path
from contextlib import contextmanager
import json
import os
import tempfile
import unittest
from unittest.mock import patch

from public_distribution import seal, verify, toolchain_identity
from public_check import build_shared_distribution, identities, source_files


class PublicDistributionTest(unittest.TestCase):
    def test_pr_package_pins_prepared_classes_and_exact_suite_without_granting_long_or_other_inputs(self):
        from public_distribution import artifact_identities
        with self.fixture() as (root, distribution, _, source, toolchain, _):
            compiled = root/'compiled'; compiled.mkdir()
            (compiled/'Example.class').write_bytes(b'owned compiled fixture')
            plan = dict(suiteId='owned-pr', kind='pr', inputScope='public',
                        fixture='inputs/one', tests={'core':['Example']}, pythonTests=[], scenarios=[])
            preparation = dict(repo=str(root.resolve()), selection=[plan],
                tasks=[':core:testClasses', ':installDist'],
                executions=[dict(task=':core:compileTestKotlin', failure=None)],
                artifacts=artifact_identities([compiled]))
            receipt = root/'pr-distribution.json'
            pinned = seal(distribution, receipt, source, toolchain, kind='pr', preparation=preparation)
            self.assertEqual(distribution.resolve(), verify(receipt, pinned, source, toolchain,
                kind='pr', plan=plan, repo=root))
            for changed in [dict(plan, fixture='inputs/two'), dict(plan, tests={'core':['Other']})]:
                with self.assertRaisesRegex(ValueError, 'prepared selection'):
                    verify(receipt, pinned, source, toolchain, kind='pr', plan=changed, repo=root)
            with self.assertRaisesRegex(ValueError, 'kind'):
                verify(receipt, pinned, source, toolchain, kind='long')
            (compiled/'Example.class').write_bytes(b'changed compiled fixture')
            with self.assertRaisesRegex(ValueError, 'prepared artifacts'):
                verify(receipt, pinned, source, toolchain, kind='pr', plan=plan, repo=root)

    def owned_environment(self, root):
        jdk = root/'owned JDK identity fixture'
        (jdk/'bin').mkdir(parents=True)
        for name in [('java.exe' if os.name=='nt' else 'java'), ('javac.exe' if os.name=='nt' else 'javac')]:
            (jdk/'bin'/name).write_bytes(b'Owned non-executable identity fixture; never launched')
        (jdk/'release').write_text('Owned identity fixture', encoding='utf8')
        cache = root/'gradle cache'; cache.mkdir()
        return dict(JAVA_HOME=str(jdk), GRADLE_USER_HOME=str(cache))

    def test_user_root_init_script_add_modify_remove_changes_real_file_identity(self):
        with tempfile.TemporaryDirectory(prefix='reforge-user-init-') as temporary:
            root = Path(temporary)
            environment = self.owned_environment(root)
            with patch.dict(os.environ, environment):
                baseline = toolchain_identity()
                package = root/'package'; package.mkdir()
                (package/'app.jar').write_bytes(b'Owned package identity fixture')
                absent_receipt = root/'absent-init.json'
                absent_pin = seal(package, absent_receipt, [])
                for name in ['init.gradle', 'init.gradle.kts']:
                    with self.subTest(name=name):
                        script = Path(environment['GRADLE_USER_HOME'])/name
                        script.write_text('// owned configuration A', encoding='utf8')
                        added = toolchain_identity()
                        self.assertNotEqual(baseline, added)
                        with self.assertRaisesRegex(ValueError, 'JDK or Gradle environment changed'):
                            verify(absent_receipt, absent_pin, [])
                        present_receipt = root/(name+'-present.json')
                        present_pin = seal(package, present_receipt, [])
                        script.write_text('// owned configuration B', encoding='utf8')
                        self.assertNotEqual(added, toolchain_identity())
                        with self.assertRaisesRegex(ValueError, 'JDK or Gradle environment changed'):
                            verify(present_receipt, present_pin, [])
                        modified_receipt = root/(name+'-modified.json')
                        modified_pin = seal(package, modified_receipt, [])
                        retained = root/('retained-'+name)
                        self.assertTrue(script.resolve().is_relative_to(root.resolve()))
                        self.assertTrue(retained.resolve().is_relative_to(root.resolve()))
                        script.rename(retained)
                        self.assertEqual(baseline, toolchain_identity())
                        with self.assertRaisesRegex(ValueError, 'JDK or Gradle environment changed'):
                            verify(modified_receipt, modified_pin, [])

    def test_java_home_with_spaces_matches_host_wrapper_quote_rules(self):
        with tempfile.TemporaryDirectory(prefix='reforge-quoted-java-home-') as temporary:
            environment = self.owned_environment(Path(temporary))
            with patch.dict(os.environ, environment):
                plain = toolchain_identity()
                expected = Path(environment['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')
                self.assertEqual(str(expected.resolve()), plain['java']['path'])
                quoted = '"'+environment['JAVA_HOME']+'"'
                with patch.dict(os.environ, {'JAVA_HOME':quoted}):
                    if os.name=='nt':
                        accepted = toolchain_identity()
                        for name in ['java', 'javac', 'release']:
                            self.assertEqual(plain[name], accepted[name])
                        self.assertEqual(quoted, accepted['JAVA_HOME'])
                    else:
                        with self.assertRaises(FileNotFoundError):
                            toolchain_identity()

    def test_external_producer_config_change_is_rejected_before_package_publication(self):
        with tempfile.TemporaryDirectory(prefix='reforge-build-config-race-') as temporary:
            root = Path(temporary); repo = root/'repo'; repo.mkdir()
            environment = self.owned_environment(root)
            # Only the external producer process is controlled; real source/JDK/config identities
            # and the public build boundary execute, without invoking an installed Gradle or JVM.
            for path in source_files(repo):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('// owned source fixture', encoding='utf8')
            config = Path(environment['GRADLE_USER_HOME'])/'gradle.properties'
            config.write_text('# owned configuration A\n', encoding='utf8')
            (repo/'SimulationTest/gradlew.bat').write_bytes(
                b'@echo off\r\n> "%GRADLE_USER_HOME%\\gradle.properties" echo # owned configuration B\r\nexit /b 0\r\n')
            (repo/'SimulationTest/gradlew').write_bytes(
                b'#!/bin/sh\nprintf "%s\\n" "# owned configuration B" > "$GRADLE_USER_HOME/gradle.properties"\nexit 0\n')
            package = repo/'SimulationTest/build/install/simulation-test'; package.mkdir(parents=True)
            (package/'app.jar').write_bytes(b'Owned package fixture, never executed')
            with patch.dict(os.environ, environment):
                result = build_shared_distribution(repo, root/'result', identities(repo))
            self.assertEqual('execution-failed', result['status'])
            self.assertEqual(0, result['exitCode'])
            self.assertIn('changed during shared distribution build', result['error'])
            self.assertNotEqual(result['toolchainBefore'], result['toolchainAfter'])
            self.assertFalse((root/'result/distribution.json').exists())

    @contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory(prefix='reforge-distribution-admission-') as temporary:
            root = Path(temporary)
            distribution = root/'package'
            (distribution/'lib').mkdir(parents=True)
            (distribution/'bin').mkdir()
            (distribution/'lib/app.jar').write_bytes(b'owned application bytes')
            (distribution/'lib/dependency.jar').write_bytes(b'owned dependency bytes')
            (distribution/'bin/start').write_text('owned launcher', encoding='utf8')
            source = [dict(path='src/main.kt', sha256='a'*64)]
            toolchain = dict(java='owned-java', sha256='b'*64)
            receipt = root/'distribution.json'
            pinned = seal(distribution, receipt, source, toolchain)
            yield root, distribution, receipt, source, toolchain, pinned

    def test_unchanged_batch_source_toolchain_and_exact_package_are_admitted(self):
        with self.fixture() as (_, distribution, receipt, source, toolchain, pinned):
            accepted = verify(receipt, pinned, source, toolchain)
            self.assertEqual(distribution.resolve(), accepted)

    def test_source_or_build_script_change_refuses_the_previously_built_package(self):
        for path in ['src/main.kt', 'build.gradle.kts']:
            with self.subTest(path=path), self.fixture() as (_, _, receipt, source, toolchain, pinned):
                changed = source + [dict(path=path, sha256='c'*64)]
                with self.assertRaisesRegex(ValueError, 'source or build inputs changed'):
                    verify(receipt, pinned, changed, toolchain)

    def test_jdk_or_gradle_environment_change_refuses_the_package(self):
        for field in ['sha256', 'GRADLE_USER_HOME', 'gradleUserConfig']:
            with self.subTest(field=field), self.fixture() as (_, _, receipt, source, toolchain, pinned):
                changed = dict(toolchain, **{field:'different'})
                with self.assertRaisesRegex(ValueError, 'JDK or Gradle environment changed'):
                    verify(receipt, pinned, source, changed)

    def test_wrong_application_dependency_missing_or_extra_member_are_rejected(self):
        for kind in ['application', 'dependency', 'missing', 'extra']:
            with self.subTest(kind=kind), self.fixture() as (_, distribution, receipt, source, toolchain, pinned):
                if kind in ['application', 'dependency']:
                    file = distribution/('lib/app.jar' if kind=='application' else 'lib/dependency.jar')
                    metadata = file.stat()
                    original = file.read_bytes()
                    file.write_bytes(bytes([original[0]^1])+original[1:])
                    os.utime(file, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
                elif kind=='missing':
                    (distribution/'lib/app.jar').rename(distribution/'app-renamed.jar')
                else:
                    (distribution/'lib/extra.jar').write_bytes(b'not part of the fixed distribution')
                with self.assertRaisesRegex(ValueError, 'members or dependency bytes changed'):
                    verify(receipt, pinned, source, toolchain)

    def test_rewriting_local_receipt_after_corrupting_package_does_not_change_the_parent_pin(self):
        with self.fixture() as (_, distribution, receipt, source, toolchain, pinned):
            (distribution/'lib/app.jar').write_bytes(b'forged bytes')
            record = json.loads(receipt.read_text(encoding='utf8'))
            record['files'] = []
            receipt.write_text(json.dumps(record), encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'receipt changed'):
                verify(receipt, pinned, source, toolchain)


if __name__ == '__main__':
    unittest.main()
