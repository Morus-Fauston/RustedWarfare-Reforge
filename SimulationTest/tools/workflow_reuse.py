"""Conservative layer-by-layer reuse explanations for frozen workflow receipts."""
from pathlib import Path
from workflow_device import checked,reference,require
from workflow_inputs import verify
import json


def subset(mapping,predicate):
    return {name:value for name,value in mapping.items() if predicate(name.replace('\\','/'))}


def classify_sources(previous,current):
    predicates={
        'core-run':lambda n:n.startswith('Engine/core/src/main/'),
        'client-runtime':lambda n:any(n.startswith('Engine/'+p+'/src/main/') for p in ('client','pc','android')),
        'comparison':lambda n:n.startswith('SimulationTest/src/main/'),
        'build-inputs':lambda n:n.endswith(('.gradle','.kts','.properties')) and '/tools/' not in n or '/gradle/wrapper/' in n or n.endswith(('gradlew','gradlew.bat')),
        'automated-tests':lambda n:'/src/test/' in n or '/src/androidTest/' in n,
        'tooling-review':lambda n:('/tools/' in n and n.endswith(('.py','.ps1'))) or n.endswith(('.gradle','.kts','.properties')),
        'navigation':lambda n:n.endswith('.md') or n=='CHANGELOG.txt',
    }
    result={}
    for layer,predicate in predicates.items():
        before=subset(previous,predicate);after=subset(current,predicate)
        changed=[n for n in sorted(set(before)|set(after)) if before.get(n)!=after.get(n)]
        result[layer]=dict(identityMatched=not changed,changedPaths=changed,
            action='eligible for exact covered evidence reuse' if not changed else 'review/re-execute this affected layer')
    # Documents never invalidate unchanged runtime bytes by themselves.
    result['raw-collection']=dict(identityMatched=None,action='Reuse only the explicitly pinned complete raw record; this operation never recollects')
    unknown=[n for n in sorted(set(previous)|set(current)) if previous.get(n)!=current.get(n) and not any(p(n) for p in predicates.values())]
    result['unclassified']=dict(identityMatched=not unknown,changedPaths=unknown,action='No unclassified differences' if not unknown else 'Unknown impact; no automatic reuse')
    return result


def explain_reuse(plan,previous_ref,profile='quick'):
    from workflow import source_identity,tree_identity,evidence_index
    previous=json.loads(checked(previous_ref).read_bytes())
    require(previous.get('schemaVersion')==1 and 'sourceBefore' in previous and 'plan' in previous,'Unsupported previous workflow receipt')
    verify(plan['inputs'],plan['root'])
    def fixed_refs(value):
        if isinstance(value,dict):
            if {'path','bytes','sha256'}<=set(value):checked(value)
            else:
                for child in value.values():fixed_refs(child)
        elif isinstance(value,list):
            for child in value:fixed_refs(child)
    for key in ('windows','device','performance'):
        if key in plan:fixed_refs(plan[key])
    before=previous['sourceBefore'];current=source_identity(Path(plan['root']))
    layers=classify_sources(before,current)
    before_inputs={r['path']:r for r in previous['plan']['inputs']['files']}
    after_inputs={r['path']:r for r in plan['inputs']['files']}
    before_inputs.update({'external:'+r['path']:r for r in previous['plan']['inputs'].get('externalFiles',[])})
    after_inputs.update({'external:'+r['path']:r for r in plan['inputs'].get('externalFiles',[])})
    changed=[n for n in sorted(set(before_inputs)|set(after_inputs)) if before_inputs.get(n)!=after_inputs.get(n)]
    layers['actual-inputs']=dict(identityMatched=not changed,changedPaths=changed,
        action='input closure matches' if not changed else 'Input/standard changes require revalidation of affected core/comparison coverage')
    def core_input(name):
        return name.lower().endswith(('.tmx','.tsx','.ini','.png','.properties')) or '/scenarios/' in name or '/suites/' in name
    core_inputs_match=not any(core_input(name) for name in changed)
    # Validate every runtime byte before offering a previous package as reusable.
    old_root=Path(previous_ref['path']).parent
    package_checks=[]
    for key,folder in [('package','package'),('pcPackage','pc-package')]:
        if key in previous:
            package_checks.append(tree_identity(old_root/folder)==previous[key])
    runtime=bool(package_checks) and all(package_checks)
    artifact_complete='artifactIndex' in previous and evidence_index(old_root)==previous['artifactIndex']
    for path,expected in previous.get('externalArtifactIndices',{}).items():
        artifact_complete=artifact_complete and tree_identity(Path(path))==expected
    same_java=reference(plan['java'])['sha256']==previous['toolIdentity']['java']['sha256']
    covered=set(plan['targets'])<=set(previous['plan']['targets']) and (previous['profile']==profile or previous['profile']=='full' and profile=='quick')
    if profile=='performance':
        wanted=plan.get('performance',{});actual=previous.get('performance',{})
        covered=covered and wanted.get('mode')==actual.get('mode') and actual.get('batches')==(576 if wanted.get('mode')=='measure' else 37)
        covered=covered and wanted.get('protocol')==previous['plan'].get('performance',{}).get('protocol')
    platform_targets=set(plan['targets'])-{'core-pc-headless'}
    platform_supported=not platform_targets
    # Platform baseline reuse remains explicit inside the guarded adapters.
    # This first public reuse evaluator conservatively refuses to grant a
    # platform runtime qualification from a prior aggregate receipt alone.
    product_layers=('core-run','client-runtime','comparison','actual-inputs','build-inputs','unclassified')
    reusable=bool(previous.get('passed')) and covered and runtime and artifact_complete and same_java and platform_supported and all(layers[n]['identityMatched'] for n in product_layers)
    return dict(kind='test-p003-layered-reuse-v1',previous=previous_ref,layers=layers,
        previousPassed=bool(previous.get('passed')),coverageMatched=covered,runtimeBytesMatched=runtime,javaMatched=same_java,
        rawArtifactIndexMatched=artifact_complete,platformRuntimeReuseSupported=platform_supported,
        restriction=None if artifact_complete and platform_supported else 'Requires complete frozen raw index; platform aggregate reuse not qualified by this evaluator',
        coveredRuntimeEvidenceReusable=reusable,selectedTestsStillRerun=True,
        comparisonOnlyMayRejudgeFrozenRecords=layers['core-run']['identityMatched'] and core_inputs_match,
        productExecuted=False,deviceQueried=False)
