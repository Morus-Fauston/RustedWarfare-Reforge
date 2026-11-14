"""Compact validated Session or Windows evidence and restore the original archive."""
import argparse
from contextlib import contextmanager
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import time
import zlib


@contextmanager
def archive_lock(batch):
    if os.name == "nt":
        from ctypes import WinDLL, create_unicode_buffer

        folder = create_unicode_buffer(260)
        shell = WinDLL("shell32")
        if shell.SHGetFolderPathW(None, 0x001C, None, 0, folder) != 0:  # CSIDL_LOCAL_APPDATA
            raise OSError("Unable to locate the Windows user lock directory")
        lock_base = Path(folder.value)
    else:
        lock_base = Path("/tmp")
    directory = lock_base / "rw-reforge-evidence-locks"
    directory.mkdir(exist_ok=True)
    key = hashlib.sha256(os.path.normcase(str(Path(batch).resolve())).encode("utf-8")).hexdigest()
    with (directory / key).open("a+b") as stream:
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError(f"Resource occupied: {batch}") from error
        yield


def durable_replace(source, destination):
    if os.name == "nt":
        from ctypes import WinDLL, WinError, get_last_error
        from ctypes.wintypes import BOOL, DWORD, LPCWSTR

        move = WinDLL("kernel32", use_last_error=True).MoveFileExW
        move.argtypes = (LPCWSTR, LPCWSTR, DWORD)
        move.restype = BOOL
        if not move(str(source), str(destination), 0x1 | 0x8):  # REPLACE_EXISTING | WRITE_THROUGH
            raise WinError(get_last_error())
    else:
        os.replace(source, destination)
        for directory in (destination.parent, *destination.parent.parents):
            descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)


def canonical_member(name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or name != path.as_posix()
            or any(part in (".", "..") or ":" in part or "\\" in part
                   or part.endswith((".", " ")) for part in path.parts)):
        raise ValueError(f"Invalid archive path: {name!r}")
    return path


def ordinary_path(path):
    for part in (path, *path.parents):
        if not part.exists() and not part.is_symlink():
            continue
        info = part.lstat()
        if part.is_symlink() or getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise ValueError(f"Links are outside the evidence domain: {part}")
    return path


def digest(stream):
    result = hashlib.sha256()
    size = 0
    for data in iter(lambda: stream.read(1024 * 1024), b""):
        size += len(data)
        result.update(data)
    return size, result.hexdigest()


def file_identity(path):
    with path.open("rb") as stream:
        return digest(stream)


def verified_gzip(path, expected_sha, expected_size=None):
    compressed_identity = file_identity(path)
    with gzip.open(path, "rb") as stream:
        size, sha256 = digest(stream)
        if sha256 != expected_sha or (expected_size is not None and size != expected_size):
            raise ValueError(f"Decompressed bytes differ: {path}")
    return size, compressed_identity


def sync_write(path, contents):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
            output.close()
            durable_replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def collect_files(batch):
    files = {}
    for directory, subdirectories, names in os.walk(batch, followlinks=False):
        for name in subdirectories + names:
            path = ordinary_path(Path(directory) / name)
            if path.is_file():
                relative = path.relative_to(batch).as_posix()
                files[relative] = path
    return files


def read_archive(batch, store):
    archive_path = batch / "archive.json"
    archive_bytes = ordinary_path(archive_path).read_bytes()
    archive = json.loads(archive_bytes)
    receipt = json.loads(ordinary_path(batch / "result.json").read_bytes())
    backend = archive["backend"]
    consumption = receipt.get("consumption", {}).get("status")
    if (archive["complete"] is not True or backend not in ("session", "windows-input")
            or receipt.get("backend") != backend or type(receipt["exitCode"]) is not int
            or receipt["status"] not in ("passed", "failed")
            or (backend == "session" and consumption not in ("passed", "failed"))):
        raise ValueError("Evidence is missing a complete archive and terminal verification")
    expected = {}
    folded = set()
    for entry in archive["files"]:
        name = entry["path"]
        normalized = str(canonical_member(name)).casefold()
        if normalized in folded or normalized in ("archive.json", "result.json"):
            raise ValueError(f"Duplicate archive member: {name}")
        if not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            raise ValueError(f"Invalid archive hash: {name}")
        folded.add(normalized)
        expected[name] = entry["sha256"]
    if not expected:
        raise ValueError("Empty archive file identity")
    required = {"manifest.json", "scenario.json", "contract.json", "intents.json", "stdout.log", "stderr.log",
                "session/client-diagnostic.jsonl"}
    if backend == "windows-input":
        required.update(("input.jsonl", "runtime/reforge/client-diagnostic.jsonl"))
    if not required <= expected.keys():
        raise ValueError("Archive closure is incomplete")
    manifest = ordinary_path(store / "manifest.json")
    archive_digest = hashlib.sha256(archive_bytes).hexdigest()
    mapping = {}
    if manifest.exists():
        index = json.loads(manifest.read_bytes())
        if index["archiveSha256"] != archive_digest or index["batch"] != batch.name:
            raise ValueError("Compaction mapping belongs to another batch")
        mapping = index["files"]
    paths = collect_files(batch)
    if set(paths) - {"archive.json", "result.json"} - expected.keys():
        raise ValueError("Unexpected archive files")
    if not mapping.keys() <= expected.keys():
        raise ValueError("Compression mapping contains unexpected members")
    return expected, paths, manifest, archive_digest, mapping


def write_gzip(path, source, expected):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            with gzip.GzipFile(filename="", mode="wb", fileobj=stream, mtime=0) as zipped, source.open("rb") as original:
                shutil.copyfileobj(original, zipped, 1024 * 1024)
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
            _, result = verified_gzip(temporary, expected[1], expected[0])
            durable_replace(temporary, path)
            return result
        finally:
            temporary.unlink(missing_ok=True)


def process(action, batch, store, output=None):
    expected, paths, manifest, archive_digest, mapping = read_archive(batch, store)
    members = {}
    selected, original_bytes, compressed_bytes = 0, 0, 0
    def record_mapping(name, original, zipped):
        mapping[name] = dict(originalBytes=original[0], originalSha256=original[1],
                             gzipBytes=zipped[0], gzipSha256=zipped[1])
        sync_write(manifest, json.dumps(dict(batch=batch.name, archiveSha256=archive_digest, files=mapping),
                                        ensure_ascii=False, indent=2).encode("utf-8"))

    changes = []
    for name, sha256 in expected.items():
        source = paths.get(name)
        path = canonical_member(name)
        compressed = ordinary_path(store / "files" / (name + ".gz"))
        eligible = path.suffix == ".jsonl" and not {"inputs", "inputsnapshot"}.intersection(
            part.casefold() for part in path.parts[:-1])
        if not eligible and (name in mapping or compressed.exists()):
            raise ValueError(f"Unexpected compressed member: {name}")
        original = file_identity(source) if source else None
        if original and original[1] != sha256:
            raise ValueError(f"Original bytes differ from archive: {name}")
        record = mapping.get(name)
        if record:
            if record["originalSha256"] != sha256:
                raise ValueError(f"Invalid original digest in mapping: {name}")
            identity = (record["originalBytes"], sha256)
            _, zipped = verified_gzip(compressed, sha256, identity[0])
            if zipped != (record["gzipBytes"], record["gzipSha256"]) or (original and original != identity):
                raise ValueError(f"Compressed bytes differ from mapping: {name}")
        elif compressed.exists():
            if not original and action == "restore":
                raise ValueError(f"Archive closure or compression mapping is missing: {name}")
            size, zipped = verified_gzip(compressed, sha256, original[0] if original else None)
            if not original and action == "apply":
                changes.append((name, None, compressed, (size, sha256), zipped))
        else:
            zipped = None
            if not original:
                raise ValueError(f"Archive closure or compression mapping is missing: {name}")
        if eligible and source and action in ("plan", "apply"):
            selected += 1
            original_bytes += original[0]
            if action == "apply":
                changes.append((name, source, compressed, original, zipped))
        members[name] = (source or compressed, source is None)
    for name, source, compressed, original, zipped in changes:
        compressed.parent.mkdir(parents=True, exist_ok=True)
        # An interrupted publication may leave verified bytes at the final
        # path without durable metadata. Republish before retiring an original.
        gzip_identity = write_gzip(compressed, source, original) if source else zipped
        record_mapping(name, original, gzip_identity)
        if source:
            source.unlink()
            compressed_bytes += gzip_identity[0]
    if action == "restore":
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output.parent, prefix=".evidence-restore-") as temporary:
            restored = Path(temporary) / "archive"
            restored.mkdir()
            for name, (source, zipped) in members.items():
                destination = restored / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                opener = gzip.open if zipped else open
                with opener(source, "rb") as reader, destination.open("xb") as writer:
                    shutil.copyfileobj(reader, writer, 1024 * 1024)
                if file_identity(destination)[1] != expected[name]:
                    raise ValueError(f"Restored bytes differ: {name}")
            for name in ("archive.json", "result.json"):
                shutil.copyfile(paths[name], restored / name)
            if output.exists():
                raise ValueError("Restore output already exists")
            restored.rename(output)
    return dict(selected=selected, originalBytes=original_bytes, gzipBytes=compressed_bytes)


def ticket_root(path):
    ticket = ordinary_path(Path(path).absolute()).resolve()
    if ticket.parent.name != "evidence" or not re.fullmatch(r"[A-Z]+-P[0-9]+-[0-9]+", ticket.name):
        raise ValueError("Expected a ticket path ending in evidence/<ticket>")
    temp = Path(tempfile.gettempdir()).resolve()
    in_project = any(parent.name == ".scratch" and (parent.parent / "AGENTS.md").is_file()
                     and ticket.is_relative_to(parent / "开发票据") for parent in ticket.parents)
    if not ticket.is_relative_to(temp) and not in_project:
        raise ValueError("Expected registered project evidence or a system temporary fixture")
    return ticket


def compact_batch(ticket, batch):
    start = time.monotonic()
    store = ordinary_path(ticket / ".compact" / batch.name)
    with archive_lock(batch):
        result = process("apply", batch, store)
    return dict(result, seconds=time.monotonic() - start)


def designated_batch(ticket, name):
    member = canonical_member(name)
    if len(member.parts) != 1 or name.casefold() == ".compact":
        raise ValueError("Batch must be a direct child outside the compaction mapping")
    return ordinary_path(ticket / member)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "apply", "restore"))
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--batch", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    start = time.monotonic()
    try:
        ticket = ticket_root(args.evidence_root)
        temp = Path(tempfile.gettempdir()).resolve()
        batch = designated_batch(ticket, args.batch)
        store = ordinary_path(ticket / ".compact" / batch.name)
        output = ordinary_path(args.output.absolute()).resolve() if args.output else None
        if args.action == "restore":
            if output is None or output == temp or not output.is_relative_to(temp) or output.exists():
                raise ValueError("Restore requires a fresh system temporary path")
            if any(output.is_relative_to(path) or path.is_relative_to(output) for path in (batch, store)):
                raise ValueError("Restore output must be independent of the batch and mapping")
        elif output is not None:
            raise ValueError("--output is for restore only")
        with archive_lock(batch):
            result = process(args.action, batch, store, output)
        print(json.dumps(dict(result, seconds=time.monotonic() - start)))
        return 0
    except (OSError, EOFError, ValueError, TypeError, KeyError, zlib.error) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
