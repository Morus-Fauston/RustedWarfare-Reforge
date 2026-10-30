"""Run one explicitly selected unittest file; zero, skip and import failure never pass."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    source=args.test.resolve(strict=True)
    if args.output.exists():
        parser.error('Output already exists; preserve the earlier result')
    before=hashlib.sha256(source.read_bytes()).hexdigest()
    # Discovery uses the actual file directory so its sibling public modules resolve.
    suite=unittest.defaultTestLoader.discover(str(source.parent),pattern=source.name)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    after=hashlib.sha256(source.read_bytes()).hexdigest()
    passed=result.wasSuccessful() and result.testsRun>0 and not result.skipped and before==after
    record=dict(status='passed' if passed else 'failed' if result.failures or result.errors else 'incomplete',
                tests=result.testsRun,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),
                source=dict(path=str(source),sha256=before),sourceUnchanged=before==after)
    with args.output.open('x',encoding='utf8') as stream:
        json.dump(record,stream,indent=2)
    return 0 if passed else 1


if __name__=='__main__':
    raise SystemExit(main())
