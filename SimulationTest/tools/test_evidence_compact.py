import hashlib
import gzip
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOL = Path(__file__).with_name("evidence_compact.py")
FIXED_MEMBERS = {name: b"{}\n" for name in ("manifest.json", "scenario.json", "contract.json", "intents.json")}
FIXED_MEMBERS.update({"stdout.log": b"", "stderr.log": b"", "session/client-diagnostic.jsonl": b""})


class EvidenceStorageTest(unittest.TestCase):
    def test_crashed_lock_holder_does_not_block_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, _ = fixture(temporary)
            source = ("from evidence_compact import archive_lock\nimport os,sys\n"
                      "with archive_lock(sys.argv[1]): os._exit(17)\n")
            exited = subprocess.run([sys.executable, "-B", "-c", source, str(batch)],
                                    cwd=TOOL.parent, capture_output=True, timeout=10)
            self.assertEqual(17, exited.returncode, exited.stderr)
            self.assertEqual(0, invoke(batch, "apply").returncode)

    def test_unfinished_or_incomplete_archive_never_deletes_any_member(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, data = fixture(temporary)
            receipt = batch / "result.json"
            fixed = receipt.read_bytes()
            active = json.loads(fixed)
            active["consumption"]["status"] = "running"
            receipt.write_text(json.dumps(active), encoding="utf-8")
            self.assertEqual(1, invoke(batch, "apply").returncode)
            self.assertEqual(data, (batch / "trace.jsonl").read_bytes())
            del active["consumption"]
            receipt.write_text(json.dumps(active), encoding="utf-8")
            self.assertEqual(1, invoke(batch, "apply").returncode)
            self.assertEqual(data, (batch / "trace.jsonl").read_bytes())
            receipt.write_bytes(fixed)
            (batch / "trace.jsonl").unlink()
            self.assertEqual(1, invoke(batch, "apply").returncode)
            self.assertTrue((batch / "session/client-diagnostic.jsonl").is_file())

    def test_case_alias_for_mapping_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, _ = fixture(temporary)
            self.assertIn("outside the compaction mapping", invoke(batch.with_name(".COMPACT"), "apply").stderr)

    def test_interrupted_after_gzip_is_verified_then_resumed(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, data = fixture(temporary)
            compressed = batch.parent / ".compact" / batch.name / "files" / "trace.jsonl.gz"
            compressed.parent.mkdir(parents=True)
            compressed.write_bytes(gzip.compress(data))
            result = invoke(batch, "apply")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse((batch / "trace.jsonl").exists())
            self.assertEqual(data, gzip.decompress(compressed.read_bytes()))

    def test_corrupt_gzip_blocks_apply_and_preserves_the_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, data = fixture(temporary)
            compressed = batch.parent / ".compact" / batch.name / "files" / "trace.jsonl.gz"
            compressed.parent.mkdir(parents=True)
            compressed.write_bytes(b"broken")
            self.assertEqual(1, invoke(batch, "apply").returncode)
            self.assertEqual(data, (batch / "trace.jsonl").read_bytes())

    def test_active_batch_is_rejected_even_with_terminal_files(self):
        from evidence_compact import archive_lock

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "AGENTS.md").touch()
            registered = root / ".scratch" / "开发票据"
            batch, data = fixture(registered)
            with tempfile.TemporaryDirectory() as alternate_temp, archive_lock(batch):
                environment = dict(os.environ, TEMP=alternate_temp, TMP=alternate_temp,
                                   TMPDIR=alternate_temp, LOCALAPPDATA=alternate_temp)
                result = invoke(batch, "apply", env=environment)
            self.assertIn("Resource occupied", result.stderr)
            self.assertEqual(data, (batch / "trace.jsonl").read_bytes())

    def test_escape_paths_are_rejected_without_writes(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, data = fixture(temporary)
            archive = batch / "archive.json"
            document = json.loads(archive.read_bytes())
            document["files"][0]["path"] = "../sibling.jsonl"
            archive.write_text(json.dumps(document), encoding="utf-8")
            self.assertIn("Invalid archive path", invoke(batch, "apply").stderr)
            self.assertEqual(data, (batch / "trace.jsonl").read_bytes())

    def test_missing_manifest_cannot_create_a_partial_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, _ = fixture(temporary)
            self.assertEqual(0, invoke(batch, "apply").returncode)
            (batch.parent / ".compact" / batch.name / "manifest.json").unlink()
            output = Path(temporary) / "restored"
            self.assertIn("mapping is missing", invoke(batch, "restore", "--output", str(output)).stderr)
            self.assertFalse(output.exists())

    def test_orphan_gzip_can_be_registered_against_the_sealed_original_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            batch, data = fixture(temporary)
            self.assertEqual(0, invoke(batch, "apply").returncode)
            manifest = batch.parent / ".compact" / batch.name / "manifest.json"
            manifest.unlink()
            result = invoke(batch, "apply")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(hashlib.sha256(data).hexdigest(),
                             json.loads(manifest.read_bytes())["files"]["trace.jsonl"]["originalSha256"])

    def test_apply_after_executor_release_runs_only_for_the_designated_batch(self):
        import platform_scenario
        from unittest.mock import patch

        with tempfile.TemporaryDirectory() as temporary:
            batch = Path(temporary) / "evidence" / "TEST-P009-001" / "finished"
            batch.mkdir(parents=True)
            seal_fixture(batch, {"session/client-diagnostic.jsonl": b'{"step":1}\n'})
            arguments = ["session", "--scenario", "scenario.json", "--contract", "contract.json",
                         "--distribution", "distribution", "--output", str(batch),
                         "--compact-evidence-root", str(batch.parent)]
            with patch.object(platform_scenario, "run_session", return_value={"exitCode": 0, "status": "passed"}):
                self.assertEqual(0, platform_scenario.main(arguments))
            self.assertFalse((batch / "session/client-diagnostic.jsonl").exists())

    def test_complete_archive_round_trips_to_an_independent_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ticket = root / "evidence" / "TEST-P009-001"
            batch = ticket / "validated-run"
            batch.mkdir(parents=True)
            payloads = {
                "session/client-diagnostic.jsonl": b'{"tick":909}\r\n' * 54,
                "inputs/frozen.jsonl": b'{"startingCredits":10}\n',
                "stdout.log": b'session finished\r\n',
            }
            for name, data in payloads.items():
                path = batch / name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(data)
            seal_fixture(batch, payloads)

            def command(action, *extra):
                result = subprocess.run([sys.executable, "-B", str(TOOL), action,
                                         "--evidence-root", str(ticket), "--batch", "validated-run", *extra],
                                        text=True, capture_output=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                return json.loads(result.stdout)

            self.assertEqual(1, command("plan")["selected"])
            self.assertTrue((batch / "session/client-diagnostic.jsonl").is_file())
            self.assertEqual(1, command("apply")["selected"])
            self.assertFalse((batch / "session/client-diagnostic.jsonl").exists())
            self.assertTrue((batch / "inputs/frozen.jsonl").is_file())
            self.assertEqual(0, command("apply")["selected"])
            output = root / "independent-copy"
            command("restore", "--output", str(output))
            for name, data in payloads.items():
                self.assertEqual(data, (output / name).read_bytes())
            for name in ("archive.json", "result.json"):
                self.assertEqual((batch / name).read_bytes(), (output / name).read_bytes())


def fixture(temporary):
    batch = Path(temporary) / "evidence" / "TEST-P009-001" / "中文批次"
    batch.mkdir(parents=True)
    data = b'{"step":1}\r\n' * 8
    seal_fixture(batch, {"trace.jsonl": data})
    return batch, data


def seal_fixture(batch, overrides):
    payloads = FIXED_MEMBERS | overrides
    for name, data in payloads.items():
        path = batch / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    (batch / "archive.json").write_text(json.dumps({
        "complete": True, "backend": "session",
        "files": [{"path": name, "sha256": hashlib.sha256(data).hexdigest()} for name, data in payloads.items()],
    }), encoding="utf-8")
    (batch / "result.json").write_text(json.dumps(
        {"exitCode": 0, "status": "passed", "backend": "session", "consumption": {"status": "passed"}}
    ), encoding="utf-8")


def invoke(batch, action, *extra, env=None):
    return subprocess.run([sys.executable, "-B", str(TOOL), action,
                           "--evidence-root", str(batch.parent), "--batch", batch.name, *extra],
                          text=True, encoding="utf-8", errors="replace", capture_output=True, timeout=15, env=env)


if __name__ == "__main__":
    unittest.main()
