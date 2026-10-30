"""Reuse reviewed owned public assets for fixed client basic-operation scenes."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC / 'inputs/platform-basics'


def generate():
    sources = {
        'production': PUBLIC / 'inputs/production-combat/production',
        'construction': PUBLIC / 'inputs/maintenance-defense/maintenance',
        'upgrade': PUBLIC / 'inputs/production-combat/upgrade',
    }
    metadata = {
        'construction/construction.properties': 'format=rw-reforge.construction-sandbox.v1\nvariant=economy-death\n',
        'upgrade/upgrade.properties': 'format=rw-reforge.upgrade-sandbox.v1\nvariant=two-branches\n',
    }
    entries = {}
    for scene, source in sources.items():
        for file in sorted(source.rglob('*')):
            if not file.is_file() or file.name == 'OWNERSHIP.json':
                continue
            relative = scene + '/' + file.relative_to(source).as_posix()
            target = ROOT / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(file.read_bytes())
            entries[relative] = dict(source=file.relative_to(PUBLIC).as_posix(),
                                     sourceSha256=hashlib.sha256(file.read_bytes()).hexdigest())
    for relative, text in metadata.items():
        target = ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf8', newline='\n')
        entries[relative] = dict(source='Newly authored fixed fixture metadata for existing ControlledScenario variants')
    files = [dict(path=relative, sha256=hashlib.sha256((ROOT/relative).read_bytes()).hexdigest(), **origin)
             for relative, origin in sorted(entries.items())]
    (ROOT / 'OWNERSHIP.json').write_text(json.dumps(dict(
        source='Reviewed owned public synthetic inputs only; no private assets or original-game files read',
        semantics='Client basic-operation fixture selection; no new gameplay rule or original-content equivalence',
        files=files), indent=2)+'\n', encoding='utf8', newline='\n')


if __name__ == '__main__':
    generate()
