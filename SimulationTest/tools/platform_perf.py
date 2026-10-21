"""Bounded platform sampling/parsing. Frame sources and input observation boundaries stay distinct."""
import argparse, hashlib, io, json, pathlib, shlex, subprocess, time
from perf_report import stats

def surface_frames(text):
    lines = text.strip().splitlines()
    assert lines and lines[0].strip().isdigit(), 'Missing SurfaceFlinger period/header'
    period = int(lines[0]); assert 0 < period < 1_000_000_000
    presents = []
    for line in lines[1:]:
        fields = line.split(); assert len(fields) == 3 and all(v.isdigit() for v in fields), 'Malformed frame row'
        values = list(map(int,fields))
        # Zero/uncompleted ring slots are not samples. Preserve raw and report their count.
        if any(v in (0,9223372036854775807) for v in values): continue
        present = values[1]
        assert not presents or present > presents[-1], 'Duplicate/non-monotonic frame timestamp'
        presents.append(present)
    assert len(presents) >= 2, 'No completed app Surface frames; unavailable, never FPS=0/pass'
    return dict(refreshPeriodNanos=period,presentNanos=presents,intervalNanos=stats([b-a for a,b in zip(presents,presents[1:])]),
                excludedRows=len(lines)-1-len(presents),scope='SurfaceFlinger actualPresentTime, selected app Surface only; emulator presentation, not phone/GPU work')

def windows_frames(path):
    rows = [json.loads(line) for line in pathlib.Path(path).read_text(encoding='utf-8').splitlines()]
    boundaries=[r for r in rows if r['kind']=='perf-sampling-boundary']; assert len(boundaries)==1
    frames=[s for r in rows if r['kind']=='perf-frame-timing' for s in r['samples']]
    responses=[r for r in rows if r['kind']=='perf-input-response']
    assert len(frames)>=2 and responses, 'Non-empty real frame/input samples required'
    assert [s['frame'] for s in frames]==list(range(len(frames)))
    assert all(b['startNanos']>a['startNanos'] for a,b in zip(frames,frames[1:]))
    intervals=[s['intervalNanos'] for s in frames if s['intervalNanos'] is not None]
    assert intervals and all(v>0 for v in intervals)
    dispatch=[]; rendered=[]
    for r in responses:
        for s in r['samples']:
            assert s['dispatchStartNanos']<=s['dispatchReturnNanos']<=r['renderReturnNanos']
            dispatch.append(s['dispatchReturnNanos']-s['dispatchStartNanos'])
            rendered.append(r['renderReturnNanos']-s['dispatchStartNanos'])
    return dict(boundary=boundaries[0],frameIntervalNanos=stats(intervals),renderWorkNanos=stats([s['workNanos'] for s in frames]),
                inputDispatchNanos=stats(dispatch),inputToRenderReturnNanos=stats(rendered),
                scope='same-JVM native callback to next render return; correlate semantic action raw and screenshot; no photon/present-fence claim')

def collect_mumu(args):
    """Attach to a caller-started, idle setup screen; one tap opens a static popup ROI."""
    from PIL import Image
    out=pathlib.Path(args.output); out.mkdir(exist_ok=False)
    rows=[]
    device=[args.adb,'-H','localhost','-P',str(args.server),'-s',args.serial]
    def command(name,tokens,exec_out=False):
        argv=device+(['exec-out'] if exec_out else ['shell'])+[shlex.join(tokens)]
        start=time.perf_counter_ns()
        p=subprocess.run(argv,capture_output=True,timeout=10)
        end=time.perf_counter_ns()
        (out/(name+'.stdout')).write_bytes(p.stdout); (out/(name+'.stderr')).write_bytes(p.stderr)
        rows.append(dict(name=name,argv=argv,startHostNanos=start,endHostNanos=end,exitCode=p.returncode))
        (out/'commands.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
        assert p.returncode==0 and not p.stderr, (name,p.returncode)
        return p.stdout
    try:
        assert args.package=='com.rustedwarfare.reforge.android', 'S08 collector is limited to the existing own sandbox'
        pid=command('pid-before',['pidof',args.package]).decode().strip(); assert pid==str(args.pid)
        info=json.loads(subprocess.check_output([args.manager,'info','-v','1'],timeout=10))
        assert info['created_timestamp']==1792546680141873 and info['is_android_started'] and info['adb_port']==int(args.serial.split(':')[-1])
        source=command('apk-path',['pm','path',args.package]).decode().strip()
        assert source.startswith('package:/data/app/') and '\n' not in source
        apk=command('apk',['cat',source[len('package:'):]],True)
        assert hashlib.sha256(apk).hexdigest()==args.apk_sha
        layer_list=command('layers',['dumpsys','SurfaceFlinger','--list']).decode()
        assert args.layer in layer_list and args.package in args.layer and 'SurfaceView' in args.layer
        before=command('frames-before',['dumpsys','SurfaceFlinger','--latency',args.layer]).decode()
        baseline=command('screen-before',['screencap','-p'],True)
        (out/'before.png').write_bytes(baseline)
        roi=tuple(args.roi); assert len(roi)==4 and roi[0]<roi[2] and roi[1]<roi[3]
        image=Image.open(io.BytesIO(baseline)); assert 0<=roi[0]<roi[2]<=image.width and 0<=roi[1]<roi[3]<=image.height
        assert 0<=args.x<image.width and 0<=args.y<image.height
        pixels=image.crop(roi).tobytes()
        command('tap',['input','tap',str(args.x),str(args.y)])
        tap=rows[-1]
        changed=None
        for index in range(5):
            png=command('screen-'+str(index),['screencap','-p'],True)
            (out/('screen-'+str(index)+'.png')).write_bytes(png)
            if Image.open(io.BytesIO(png)).crop(roi).tobytes()!=pixels:
                changed=rows[-1]; break
        assert changed, 'No changed static UI ROI within 5 readbacks'
        after=command('frames-after',['dumpsys','SurfaceFlinger','--latency',args.layer]).decode()
        frames=surface_frames(after)
        before_frames=surface_frames(before)
        new_presents=[v for v in frames['presentNanos'] if v>max(before_frames['presentNanos'])]
        assert len(new_presents)>=2, 'No new completed app frames inside the bounded input observation window'
        frames['windowPresentNanos']=new_presents
        frames['windowIntervalNanos']=stats([b-a for a,b in zip(new_presents,new_presents[1:])])
        assert command('pid-after',['pidof',args.package]).decode().strip()==pid
        result=dict(status='finite-sampling-path-qualified-awaiting-visual-ROI-check',instance=info,pid=pid,package=args.package,
                    apkSha256=args.apk_sha,layer=args.layer,frames=frames,roi=roi,
                    inputObservation=dict(inputBeginHostNanos=tap['startHostNanos'],inputReturnHostNanos=tap['endHostNanos'],
                        firstChangedCaptureBeginHostNanos=changed['startHostNanos'],firstChangedCaptureEndHostNanos=changed['endHostNanos'],
                        responseUpperBoundHostNanos=changed['endHostNanos']-tap['startHostNanos'],
                        scope='one static popup ROI change; includes ADB/input transport and screenshot cost; needs visual semantic check; no device/host clock subtraction'),
                    samplingCommandNanos=stats([r['endHostNanos']-r['startHostNanos'] for r in rows]))
        (out/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print('Finite app Surface and input observation captured; no final performance verdict')
    except BaseException as e:
        (out/'failure.txt').write_text(repr(e),encoding='utf-8'); raise

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); s=p.add_subparsers(dest='action',required=True)
    w=s.add_parser('windows'); w.add_argument('jsonl'); w.add_argument('output')
    m=s.add_parser('mumu'); m.add_argument('--adb',required=True); m.add_argument('--manager',required=True)
    m.add_argument('--server',type=int,default=15037); m.add_argument('--serial',required=True)
    m.add_argument('--package',default='com.rustedwarfare.reforge.android'); m.add_argument('--pid',type=int,required=True)
    m.add_argument('--apk-sha',required=True); m.add_argument('--layer',required=True); m.add_argument('--roi',type=int,nargs=4,required=True)
    m.add_argument('--x',type=int,required=True); m.add_argument('--y',type=int,required=True); m.add_argument('--output',required=True)
    a=p.parse_args()
    if a.action=='mumu': collect_mumu(a)
    else:
        with pathlib.Path(a.output).open('x',encoding='utf-8') as stream: json.dump(windows_frames(a.jsonl),stream,indent=2)
        print('Windows real frame/input samples qualified; no final performance verdict')
