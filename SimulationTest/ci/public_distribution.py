"""A batch pins one built package; children independently check its bytes and inputs."""
import hashlib
import json
import os
from pathlib import Path
import shutil


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def _is_link(path_or_entry):
    if path_or_entry.is_symlink():
        return True
    is_junction = getattr(path_or_entry, 'is_junction', None)
    return bool(is_junction and is_junction())


def _scandir_metadata(directory, *, root=None):
    base = Path(directory)
    origin = base if root is None else Path(root)
    rows = []
    stack = [base]
    while stack:
        current = stack.pop()
        with os.scandir(current) as entries:
            ordered = sorted(entries, key=lambda entry: entry.name)
        subdirs = []
        for entry in ordered:
            if _is_link(entry):
                raise ValueError('Fixed distribution or artifact tree cannot contain links: ' + entry.path)
            if entry.is_dir(follow_symlinks=False):
                subdirs.append(Path(entry.path))
            elif entry.is_file(follow_symlinks=False):
                st = entry.stat(follow_symlinks=False)
                rel = Path(entry.path).relative_to(origin).as_posix()
                rows.append((rel, st.st_size, st.st_mtime_ns))
            else:
                raise ValueError('Unsupported directory member: ' + entry.path)
        for subdir in reversed(subdirs):
            stack.append(subdir)
    rows.sort(key=lambda item: item[0])
    return rows


def package_metadata_snapshot(directory):
    directory = Path(directory)
    if _is_link(directory) or not directory.is_dir():
        raise ValueError('Fixed distribution must be an ordinary directory')
    rows = _scandir_metadata(directory)
    if not rows:
        raise ValueError('Fixed distribution is empty')
    return rows


def artifact_metadata_snapshot(paths):
    """Fast metadata snapshot for prepared compiler outputs and classpaths."""
    rows = []
    for path in sorted({Path(p).absolute() for p in paths}):
        if _is_link(path):
            raise ValueError('Prepared artifacts cannot contain links')
        if path.is_file():
            st = path.stat()
            rows.append(('file', str(path), st.st_size, st.st_mtime_ns))
        elif path.is_dir():
            rows.append(('directory', str(path), _scandir_metadata(path)))
        elif not path.exists():
            rows.append(('missing', str(path)))
        else:
            raise ValueError('Unsupported prepared artifact')
    return rows


def package_files(directory):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Fixed distribution must be an ordinary directory')
    rows = []
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise ValueError('Fixed distribution cannot contain links')
        if path.is_file():
            rows.append(dict(path=path.relative_to(directory).as_posix(), bytes=path.stat().st_size,
                             sha256=digest(path)))
        elif not path.is_dir():
            raise ValueError('Unsupported fixed distribution member')
    if not rows:
        raise ValueError('Fixed distribution is empty')
    return rows


def toolchain_identity():
    executable = 'java.exe' if os.name == 'nt' else 'java'
    home = os.environ.get('JAVA_HOME')
    # gradlew.bat and the generated Windows launchers remove JAVA_HOME quotes.
    effective_home = home.replace('"', '') if home and os.name == 'nt' else home
    java = Path(effective_home)/'bin'/executable if effective_home else Path(shutil.which('java') or '')
    java = java.resolve(strict=True)
    compiler = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
    release = java.parent.parent/'release'
    def identity(path):
        return dict(path=str(path.resolve(strict=True)), sha256=digest(path))
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle'))).resolve()
    config = [cache/'gradle.properties', cache/'init.gradle', cache/'init.gradle.kts'] + \
        sorted((cache/'init.d').glob('*.gradle')) + sorted((cache/'init.d').glob('*.gradle.kts'))
    return dict(java=identity(java), javac=identity(compiler), release=identity(release),
                JAVA_HOME=home, GRADLE_USER_HOME=str(cache),
                gradleUserConfig=[identity(path) for path in config if path.is_file()])


def artifact_identities(paths):
    """Pin prepared compiler outputs and resolved test classpaths, including absence."""
    rows = []
    for path in sorted({Path(p).absolute() for p in paths}):
        if path.is_symlink():
            raise ValueError('Prepared artifacts cannot contain links')
        row = dict(path=str(path))
        if path.is_file():
            row.update(kind='file', sha256=digest(path))
        elif path.is_dir():
            files = []
            for member in sorted(path.rglob('*')):
                if member.is_symlink():
                    raise ValueError('Prepared artifacts cannot contain links')
                if member.is_file():
                    files.append(dict(path=member.relative_to(path).as_posix(), sha256=digest(member)))
            row.update(kind='directory', files=files)
        elif not path.exists():
            row['kind'] = 'missing'
        else:
            raise ValueError('Unsupported prepared artifact')
        rows.append(row)
    return rows


def seal(distribution, receipt, source, toolchain=None, *, kind='long', preparation=None):
    distribution, receipt = Path(distribution), Path(receipt)
    if distribution.resolve().parent != receipt.resolve().parent:
        raise ValueError('Fixed package must live beside its batch receipt')
    if kind not in ('short', 'pr', 'long') or (kind == 'pr') != (preparation is not None):
        raise ValueError('Fixed distribution kind requires matching preparation')
    record = dict(schemaVersion=2, kind=kind, preparation=preparation,
                  distribution=str(distribution.resolve()), source=source,
                  toolchain=toolchain if toolchain is not None else toolchain_identity(),
                  files=package_files(distribution))
    with receipt.open('x', encoding='utf8', newline='\n') as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True, indent=2)+'\n')
    return digest(receipt)


def verify(receipt, expected_sha256, source, toolchain=None, *, kind='long', plan=None, repo=None):
    record = read_receipt(receipt, expected_sha256, source, toolchain, kind=kind, plan=plan, repo=repo)
    preparation = record['preparation']
    if preparation and artifact_identities(r['path'] for r in preparation['artifacts']) != preparation['artifacts']:
        raise ValueError('Fixed distribution prepared artifacts changed')
    return verify_members(receipt, record)


def read_receipt(receipt, expected_sha256, source, toolchain=None, *, kind='long', plan=None, repo=None):
    receipt = Path(receipt)
    if receipt.is_symlink() or not receipt.is_file() or receipt.stat().st_size > 8*1024*1024:
        raise ValueError('Missing or invalid fixed distribution receipt')
    with receipt.open('rb') as stream:
        captured = stream.read(8*1024*1024+1)
    if len(captured) > 8*1024*1024 or hashlib.sha256(captured).hexdigest() != expected_sha256:
        raise ValueError('Fixed distribution receipt changed')
    record = json.loads(captured)
    if record['schemaVersion'] != 2 or record['source'] != source:
        raise ValueError('Fixed distribution source or build inputs changed')
    if kind not in ('short', 'pr', 'long') or record['kind'] != kind:
        raise ValueError('Fixed distribution kind does not cover this execution')
    current = toolchain if toolchain is not None else toolchain_identity()
    if record['toolchain'] != current:
        raise ValueError('Fixed distribution JDK or Gradle environment changed')
    preparation = record['preparation']
    if kind == 'pr':
        if (not preparation or repo is None or str(Path(repo).resolve()) != preparation['repo'] or
                plan not in preparation['selection']):
            raise ValueError('Fixed distribution prepared selection or workspace differs')
        if (not preparation['tasks'] or not preparation['executions'] or not preparation['artifacts'] or
                any(r.get('failure') for r in preparation['executions'])):
            raise ValueError('Incomplete fixed distribution preparation')
    elif preparation is not None or (plan and (plan['kind'] != kind or plan['tests'] or plan.get('pythonTests'))):
        raise ValueError(f'A {kind} package cannot replace selected mechanism tests')
    return record


def verify_members(receipt, record):
    distribution = Path(record['distribution'])
    if distribution.resolve().parent != Path(receipt).resolve().parent or package_files(distribution) != record['files']:
        raise ValueError('Fixed distribution members or dependency bytes changed')
    return distribution.resolve()


def verify_fast(receipt, expected_sha256, pinned_source, toolchain, *, kind='long', plan=None, repo=None,
                expected_artifact_meta=None, expected_package_meta=None):
    """Mid-batch metadata verification after batch-start full byte SHA-256 admission."""
    record = read_receipt(receipt, expected_sha256, pinned_source, toolchain, kind=kind, plan=plan, repo=repo)
    preparation = record['preparation']
    if preparation:
        current_artifact_meta = artifact_metadata_snapshot(r['path'] for r in preparation['artifacts'])
        if expected_artifact_meta is not None:
            if current_artifact_meta != expected_artifact_meta:
                raise ValueError('Fixed distribution prepared artifacts changed')
        elif artifact_identities(r['path'] for r in preparation['artifacts']) != preparation['artifacts']:
            raise ValueError('Fixed distribution prepared artifacts changed')
    distribution = Path(record['distribution'])
    if distribution.resolve().parent != Path(receipt).resolve().parent:
        raise ValueError('Fixed distribution members or dependency bytes changed')
    current_package_meta = package_metadata_snapshot(distribution)
    if expected_package_meta is not None:
        if current_package_meta != expected_package_meta:
            raise ValueError('Fixed distribution members or dependency bytes changed')
    else:
        expected_pairs = [(row['path'], row['bytes']) for row in record['files']]
        if [(row[0], row[1]) for row in current_package_meta] != expected_pairs:
            raise ValueError('Fixed distribution members or dependency bytes changed')
    return distribution.resolve()
