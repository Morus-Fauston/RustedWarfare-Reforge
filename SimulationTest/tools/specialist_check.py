"""Read-only specialist prerequisite identity check. This tool never runs an entry."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--entry',required=True)
    parser.add_argument('--bindings',type=Path)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--list',action='store_true')
    args=parser.parse_args()
    catalog=Path(__file__).resolve().parents[1]/'public/specialist-entries.json'
    entries=json.loads(catalog.read_text(encoding='utf8'))['entries']
    matches=[entry for entry in entries if entry['id']==args.entry]
    if len(matches)!=1:
        parser.error('Unknown specialist entry')
    entry=matches[0]
    if args.list:
        print(json.dumps(entry))
        return 0
    if not args.bindings or not args.output:
        parser.error('--bindings and a new --output are required for a receipt')
    if args.output.exists():
        parser.error('Output exists; previous evidence must remain unchanged')
    result=dict(entry=args.entry,status='evidence-insufficient',executed=False,identities=[],
                boundary=entry['boundary'],documentation=entry['documentation'])
    code=3
    try:
        raw=args.bindings.read_bytes()
        bindings=json.loads(raw)
        if not isinstance(bindings, dict):
            raise ValueError('Bindings must be an object')
        result['bindingsSha256']=hashlib.sha256(raw).hexdigest()
        result['catalogSha256']=hashlib.sha256(catalog.read_bytes()).hexdigest()
        if bindings.get('released') is not True:
            result.update(status='blocked',reason='Resource/scope release not declared; no specialist operation executed')
            code=64
        elif platform.system() not in entry['platforms']:
            result.update(status='blocked',reason='Entry requires a different host platform')
            code=64
        else:
            files=bindings['files']
            if set(files)!=set(entry['requiredRoles']):
                raise ValueError('Required file roles differ from the registered entry')
            for role in entry['requiredRoles']:
                ref=files[role]
                path=Path(ref['path'])
                if not path.is_absolute() or not path.is_file() or path.resolve()!=path or path.is_symlink():
                    raise ValueError('Missing or non-canonical fixed input: '+role)
                digest=hashlib.sha256(path.read_bytes()).hexdigest()
                if digest!=ref['sha256']:
                    raise ValueError('Fixed input SHA mismatch: '+role)
                result['identities'].append(dict(role=role,path=str(path),sha256=digest))
            if args.bindings.read_bytes()!=raw:
                raise ValueError('Bindings changed during prerequisite inspection')
            result.update(status='ready',reason='Declared file identities readable; actual runtime readiness and acceptance remain untested')
            code=0
    except (OSError,ValueError,KeyError,TypeError) as error:
        result['reason']=str(error)
    args.output.mkdir(parents=True,exist_ok=False)
    with (args.output/'result.json').open('x',encoding='utf8') as stream:
        json.dump(result,stream,indent=2)
    print(json.dumps(result))
    return code


if __name__=='__main__':
    sys.exit(main())
