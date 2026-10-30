"""Run existing Android pure-JDK harnesses; no SDK, APK, instrumentation or device claim."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from platform_scenario import save, sha, tree

FUNCTIONAL = [
    'SandboxExploreOracleTest', 'SandboxFunctionalEntryTest', 'SandboxFunctionalOracleTest',
    'SandboxKnowledgeEncodingTest', 'SandboxNormalCompletionOracleTest', 'SandboxPauseOracleTest',
    'SandboxRecoveryOracleTest', 'SandboxRecreateOracleTest', 'SandboxUiCallTest',
    'SandboxVisibilityOracleTest', 'SandboxVisibilityRouteTest',
]
STORAGE = ['SandboxAssetFilesTest', 'metadata.MetadataProbeHostTest',
           'ownruns.OwnRunsProbeHostTest', 'ownruns.OwnRunsDirectedAbsenceHostTest']


def run(distribution, fixture, output, kind='all', java='java', javac='javac'):
    repo = Path(__file__).resolve().parents[2]
    distribution, fixture, output = (Path(p).resolve() for p in (distribution, fixture, output))
    source_root = repo/'Engine/android/src'
    source_before = tree(source_root)
    jars = sorted((distribution/'lib').glob('*.jar'))
    if not jars or not fixture.is_dir():
        raise ValueError('Installed shared JVM package and fixed P002 input root required')
    package_before = tree(distribution)
    output.mkdir()
    classes = Path(tempfile.mkdtemp(prefix='reforge-android-host-'))
    shutil.copytree(fixture, output/'inputSnapshot')
    result = dict(status='execution-failed', backend='android-host-jvm', device=False,
                  classes=str(classes), commands=[], startedUnix=time.time(), source=source_before,
                  package=package_before, inputs=tree(output/'inputSnapshot'))
    def command(name, arguments):
        begin = time.time()
        with (output/(name+'.stdout.log')).open('xb') as stdout, (output/(name+'.stderr.log')).open('xb') as stderr:
            process = subprocess.run(list(map(str, arguments)), cwd=repo, stdout=stdout, stderr=stderr, timeout=180)
        result['commands'].append(dict(name=name, argv=list(map(str, arguments)), exitCode=process.returncode,
                                       elapsedSeconds=time.time()-begin))
        if process.returncode:
            raise RuntimeError(name+': exit '+str(process.returncode))
    try:
        selected = (FUNCTIONAL if kind in ('all', 'functional') else []) + (STORAGE if kind in ('all', 'storage') else [])
        sources = []
        for name in selected:
            category = 'hostTest' if name in FUNCTIONAL else 'test'
            sources.append(source_root/category/'java/com/rustedwarfare/reforge/android'/Path(name.replace('.', '/')+'.java'))
        classpath = os.pathsep.join(map(str, jars))
        sourcepath = os.pathsep.join(str(source_root/p/'java') for p in ['main', 'androidTest', 'hostTest', 'test'])
        command('compile', [javac, '--release', '17', '-encoding', 'UTF-8', '-cp', classpath,
                            '-sourcepath', sourcepath, '-d', classes, *sources])
        runtime = os.pathsep.join([str(classes), classpath])
        def harness(name, args=(), properties=(), suffix=''):
            command(name.replace('.', '-')+suffix, [java, '-Xmx512m', *properties,
                '-Dp002.inputs.dir='+str(output/'inputSnapshot'), '-cp', runtime,
                'com.rustedwarfare.reforge.android.'+name, *args])
        if kind in ('all', 'functional'):
            for name in FUNCTIONAL:
                if name in ('SandboxVisibilityOracleTest', 'SandboxVisibilityRouteTest'):
                    continue
                if name == 'SandboxExploreOracleTest':
                    (output/'explore').mkdir()
                    arguments = [str(output/'explore')]
                elif name == 'SandboxFunctionalEntryTest':
                    arguments = ['all']
                elif name == 'SandboxKnowledgeEncodingTest':
                    arguments = ['unit']
                else:
                    arguments = []
                harness(name, arguments)
            (output/'capacity').mkdir()
            harness('SandboxKnowledgeEncodingTest', ['capacity', str(output/'capacity')], suffix='-capacity')
            (output/'visibility').mkdir()
            harness('SandboxVisibilityRouteTest', [str(output/'visibility')])
            harness('SandboxVisibilityOracleTest', [str(output/'visibility'), 'all'])
        if kind in ('all', 'storage'):
            harness('SandboxAssetFilesTest', [tempfile.mkdtemp(prefix='reforge-assets-host-')])
            harness('metadata.MetadataProbeHostTest', ['all'])
            harness('ownruns.OwnRunsProbeHostTest', ['all'],
                    ['-Downruns.fixtureDir='+tempfile.mkdtemp(prefix='reforge-ownruns-host-')])
            harness('ownruns.OwnRunsDirectedAbsenceHostTest', ['all'])
        if source_before != tree(source_root) or package_before != tree(distribution):
            raise RuntimeError('Source or fixed shared package changed during host verification')
        result['status'] = 'passed'
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result['error'] = str(error)
    result['endedUnix'] = time.time()
    save(output/'result.json', result)
    print(json.dumps(dict(status=result['status'], device=False, commands=len(result['commands']), error=result.get('error'))))
    return 0 if result['status'] == 'passed' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('distribution', 'fixture', 'output'):
        parser.add_argument('--'+option, type=Path, required=True)
    parser.add_argument('--kind', choices=['all', 'functional', 'storage'], default='all')
    args = parser.parse_args()
    return run(args.distribution, args.fixture, args.output, args.kind)


if __name__ == '__main__':
    raise SystemExit(main())
