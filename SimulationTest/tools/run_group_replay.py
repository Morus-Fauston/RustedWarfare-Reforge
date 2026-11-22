"""Run one ordinary group order against an identified desktop install (JDK 24+)."""
import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from workflow import execute_step, resources, save_json, sha
from group_replay_summary import summarize


def run(args):
    tools = Path(__file__).resolve().parent
    lib, scene, out = args.lib.resolve(), args.scene.resolve(), args.output.resolve()
    jars = sorted(lib.glob('*.jar'))
    if not (lib/'core.jar').is_file():
        raise ValueError(f'No core.jar in {lib}')
    if not scene.is_file() or not out.parent.is_dir() or out.exists():
        raise ValueError('Supply an existing scene and a fresh output in an existing directory')
    engine = args.engine.resolve()
    if (engine/'Engine/gradlew.bat').is_file():
        engine = engine/'Engine'
    if not (engine/'gradlew.bat').is_file() or not (engine/'core').is_dir():
        raise ValueError('engine must identify the repository or its Engine build directory')
    java = shutil.which('java')
    with resources(['cpu:rw-reforge-paired','build:'+engine.as_posix()], formal=True):
        with tempfile.TemporaryDirectory(prefix='rw-planning-clock-') as temporary:
            tmp = Path(temporary)
            subprocess.run(['javac','-d',str(tmp),str(tools/'PlanningClock.java')],check=True)
            manifest=tmp/'MANIFEST.MF'
            manifest.write_text('Premain-Class: PlanningClock\n\n',encoding='ascii')
            agent=tmp/'clock.jar'
            subprocess.run(['jar','cfm',str(agent),str(manifest),'-C',str(tmp),'PlanningClock.class',
                            '-C',str(tmp),'PlanningClock$1.class'],check=True)
            classpath=os.pathsep.join(str(p) for p in jars)
            scenario = json.loads(scene.read_text(encoding='utf-8-sig'))
            inputs = (scene.parent/scenario['inputs']).resolve()
            tracked = [scene,*jars,*(tools/name for name in ('GroupReplay.java','PlanningClock.java',
                'group_replay_summary.py','group_replay_fixtures.py','run_group_replay.py'))]
            for relative, expected in scenario['testScene']['files'].items():
                asset = (inputs/relative).resolve()
                if not asset.is_relative_to(inputs) or sha(asset) != expected:
                    raise ValueError(f'input mismatch: {relative}')
                tracked.append(asset)
            geometry = scene.with_name(scene.stem+'-geometry.json')
            if geometry.is_file(): tracked.append(geometry)
            before={str(p):sha(p) for p in tracked}
            argv=[java,'-Xmx1g',f'-javaagent:{agent}=planning.tsv','-cp',classpath,
                  str(tools/'GroupReplay.java'),str(scene),str(out),args.command,
                  *map(str,args.click),str(args.steps)]
            result=execute_step(argv,out,out,timeout=args.timeout)
            unchanged=all(sha(Path(path))==digest for path,digest in before.items())
            save_json(out/'identity.json',dict(files=before,stableThroughRun=unchanged,
                jvm=subprocess.run([java,'-version'],capture_output=True,text=True,check=True).stderr.strip(),
                cache='fresh JVM, world and ordinary order',agentSha256=sha(agent)))
            if not result['passed'] or not unchanged:
                raise RuntimeError(f'Inspect the execution receipt in {out}')
        report=summarize(out,args.members,args.quiet)
        save_json(out/'summary.json',report)
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for key,desc in [('engine','Engine checkout owning the build lock'),('lib','installed desktop lib directory'),
                     ('scene','verified test scenario JSON'),('output','new receipt directory')]:
        parser.add_argument('--'+key,required=True,type=Path,help=desc)
    parser.add_argument('--command',required=True)
    parser.add_argument('--click',nargs=2,required=True,type=float)
    for key in ('steps','members','quiet'):
        parser.add_argument('--'+key,required=True,type=int)
    parser.add_argument('--timeout',type=int,default=900)
    args=parser.parse_args()
    if args.steps<1 or args.members<1 or args.quiet<1: parser.error('positive window, roster and observation required')
    print(run(args))
