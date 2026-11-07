"""A batch pins one built package; children independently check its bytes and inputs."""
import hashlib
import json
import os
from pathlib import Path
import shutil


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            result.update(chunk)
    return result.hexdigest()


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
    if kind not in ('pr', 'long') or (kind == 'pr') != (preparation is not None):
        raise ValueError('Fixed distribution kind requires matching preparation')
    record = dict(schemaVersion=2, kind=kind, preparation=preparation,
                  distribution=str(distribution.resolve()), source=source,
                  toolchain=toolchain if toolchain is not None else toolchain_identity(),
                  files=package_files(distribution))
    with receipt.open('x', encoding='utf8', newline='\n') as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True, indent=2)+'\n')
    return digest(receipt)


def verify(receipt, expected_sha256, source, toolchain=None, *, kind='long', plan=None, repo=None):
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
    if kind not in ('pr', 'long') or record['kind'] != kind:
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
        if artifact_identities(r['path'] for r in preparation['artifacts']) != preparation['artifacts']:
            raise ValueError('Fixed distribution prepared artifacts changed')
    elif preparation is not None or (plan and (plan['kind'] != 'long' or plan['tests'] or plan.get('pythonTests'))):
        raise ValueError('A long package cannot replace selected mechanism tests')
    distribution = Path(record['distribution'])
    if distribution.resolve().parent != receipt.resolve().parent or package_files(distribution) != record['files']:
        raise ValueError('Fixed distribution members or dependency bytes changed')
    return distribution.resolve()
