"""Recompute S08 distributions from preserved raw batches; never run a measurement."""
import argparse, hashlib, json, math, pathlib

def stats(values):
    values = sorted(values)
    return dict(n=len(values), p50=values[math.ceil(.5*len(values))-1] if values else None,
                p95=values[math.ceil(.95*len(values))-1] if values else None, max=max(values) if values else None)

def analyze(root):
    root = pathlib.Path(root)
    completion = json.loads((root/'completion.json').read_text(encoding='utf-8'))
    assert completion['complete'] is True
    summaries = json.loads((root/'summary.json').read_text(encoding='utf-8'))
    assert summaries and len(summaries) == completion['batches']
    groups = {}
    for summary in summaries:
        leaf = summary['raw']; assert pathlib.Path(leaf).name == leaf
        rows = [json.loads(line) for line in (root/leaf).read_text(encoding='utf-8').splitlines()]
        assert rows[0]['kind'] == 'initial' and rows[-1]['kind'] == 'completion'
        assert rows[-1]['summary'] == {k:v for k,v in summary.items() if k not in ('phase','raw')}
        steps = rows[1:-1]; assert steps and [s['step'] for s in steps] == list(range(len(steps)))
        searches = [s for row in steps for s in row['searches']]
        assert len({s['unitId'] for s in searches if s['reason']=='USER_MOVE'}) == summary['count']
        assert sum(s['actualSearchCalls'] for s in searches) == summary['actualSearchCalls']
        key = (summary['case'],summary['count'],summary['retention'],summary['fullTrace'],summary['phase'])
        g = groups.setdefault(key,dict(batches=[],steps=[],searches=[],boundaryCalls=[],boundaryNanos=[],raw=[]))
        g['batches'].append(summary['batchNanos']); g['steps'] += [s['advanceNanos'] for s in steps]
        g['searches'] += [s['searchNanos'] for s in searches]
        g['boundaryCalls'] += [sum(s['actualSearchCalls'] for s in row['searches']) for row in steps]
        g['boundaryNanos'] += [sum(s['searchNanos'] for s in row['searches']) for row in steps]
        g['raw'].append(dict(path=leaf,sha256=hashlib.sha256((root/leaf).read_bytes()).hexdigest()))
    return dict(mode=completion['mode'],performanceVerdict='not-evaluated',groups=[dict(
        case=k[0],count=k[1],retention=k[2],fullTrace=k[3],phase=k[4],
        batchNanos=stats(g['batches']),advanceNanos=stats(g['steps']),singleSearchNanos=stats(g['searches']),
        boundarySearchCallsPeak=max(g['boundaryCalls']),boundarySearchNanosPeak=max(g['boundaryNanos']),raw=g['raw']) for k,g in groups.items()])

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('run'); parser.add_argument('output')
    args=parser.parse_args(); result=analyze(args.run)
    with pathlib.Path(args.output).open('x',encoding='utf-8') as stream: json.dump(result,stream,indent=2)
    print('Recomputed',len(result['groups']),'non-empty groups; no acceptance threshold evaluated')
