"""Owned self-behavior inputs; literals follow the qualified fixed float energy/action contract."""
from pathlib import Path
import copy
import json
import hashlib
import xml.etree.ElementTree as ET

PUBLIC=Path(__file__).resolve().parents[1]/'public'

def action_map(scene, fmt):
    """Small stationary mechanism map; production/PC retain the full shore map."""
    root=PUBLIC/f'inputs/aa-beam-production/{fmt}'
    target=root/'maps/afterburn-actions.tmx'
    tree=ET.parse(root/'maps/naval-shore.tmx')
    tree.getroot().set('width','12');tree.getroot().set('height','12')
    layer=tree.getroot().find('layer')
    layer.set('width','12');layer.set('height','12')
    layer.find('data').text=','.join(['1']*144)
    content=ET.tostring(tree.getroot(),encoding='utf-8')+b'\n'
    if target.exists():assert target.read_bytes()==content
    else:target.write_bytes(content)
    digest=hashlib.sha256(content).hexdigest()
    scene['content']['map'].update(path=f'../inputs/aa-beam-production/{fmt}/maps/afterburn-actions.tmx',sha256=digest)
    scene['automaticVision']['grid']=dict(width=12,height=12)
    scene['testScene']['files']['maps/afterburn-actions.tmx']=digest

def generate():
    for fmt in ('ini','json','hjson'):
        scene=json.loads((PUBLIC/f'scenarios/aa-beam-{fmt}-short.json').read_text(encoding='utf8'))
        scene.update(scenarioId='p009-afterburn-actions')
        scene['units']=scene['units'][:1]
        scene['combatContract'].update(unitObjectIds=dict(aa=8),nextObjectId=9)
        scene['automaticVision']['observers']=['p1','p2']
        def action(id,step,name,**extra):
            return dict(actionId=id,atStep=step,kind='unit-action',actor='p1',unitId='aa',
                actionName=name,cancelQueued=False,**extra)
        scene['actions']=[action('burn',0,'afterBurn'),action('duplicate',0,'afterBurn'),
            action('locked',10,'afterBurn'),action('cancel-missing',302,'cancelBurn'),action('still-locked',303,'afterBurn')]
        scene['termination']['steps']=[dict(stepIndex=t,delta=1,deltaUnit='sim-tick') for t in range(350)]
        scene['testScene'].update(displayName=f'Afterburn actions ({fmt})',
            summary='Owned queued conversion, matching cooldown and automatic empty-energy recovery.')
        action_map(scene,fmt)
        def equals(path,value):return dict(path=path,operator='equals',value=value)
        contract=json.loads((PUBLIC/f'contracts/aa-beam-{fmt}-short.json').read_text(encoding='utf8'))
        contract.update(contractId=f'public.afterburn-{fmt}.short.contract.v1',scenarioIds=[scene['scenarioId']],
            approval=dict(status='tool-fixture-only',source='ENG-P009-010 qualified DEC static action and energy facts; owned self-behavior only'))
        contract['rules']=[dict(ruleId='queued-conversion',when=dict(phase='after-advance',stepIndex=0),assertions=[
            equals('units.aa.type','aaBeamGunship_afterburn'),equals('units.aa.energy.current',6),
            equals('units.aa.unitActions.actions.afterBurn.cooldownRemaining',25000)]),
            dict(ruleId='positive-energy-does-not-recover',when=dict(phase='after-advance',stepIndex=299),assertions=[
                equals('units.aa.type','aaBeamGunship_afterburn')]),
            dict(ruleId='first-empty-float-update-recovers',when=dict(phase='after-advance',stepIndex=301),assertions=[
                equals('units.aa.type','aaBeamGunship'),equals('units.aa.energy.current',6),
                equals('units.aa.unitActions.automaticCooldown',60)])]
        contract['unitActionConversions']=[dict(unitId='aa',actionId='burn',sourceTypeId='aaBeamGunship',
            targetTypeId='aaBeamGunship_afterburn',completionStepIndex=0),
            dict(unitId='aa',actionId=None,sourceTypeId='aaBeamGunship_afterburn',targetTypeId='aaBeamGunship',completionStepIndex=301)]
        for directory,value in [('scenarios',scene),('contracts',contract)]:
            target=PUBLIC/f'{directory}/afterburn-{fmt}-short.json'
            assert not target.exists(),target
            target.write_text(json.dumps(value,indent=2)+'\n',encoding='utf8')

def generate_platform():
    """Normal paid production, explicit Cancel, real cooldown wait and empty recovery."""
    for fmt in ('ini','json','hjson'):
        plan=json.loads((PUBLIC/f'platform/scenarios/aa-beam-{fmt}-production-chain.json').read_text(encoding='utf8'))
        plan['scenarioId']=f'afterburn-{fmt}-production-chain'
        def action(identity,name,wait=1):
            return dict(intentId=identity,kind='unit-action',actionName=name,waitSteps=wait)
        def stop(identity,wait):return dict(intentId=identity,kind='stop',waitSteps=wait)
        def move(identity,x,y,wait):
            return dict(intentId=identity,kind='move',position=dict(x=x,y=y),waitSteps=wait)
        plan['intents'] += [action('burn-first','afterBurn'),move('burn-move',100,400,180),
            action('cancel-first','cancelBurn'),stop('cancel-stop',40),
            stop('cooldown-wait-1',600),stop('cooldown-wait-2',600),stop('cooldown-ready',400),
            action('burn-second','afterBurn'),move('automatic-recovery',750,50,360),stop('final-stop',40)]
        plan['waitPlan']={i['intentId']:dict(minimumSteps=i['waitSteps'],timeoutSeconds=240)
                          for i in plan['intents'] if i['waitSteps']}
        contract=json.loads((PUBLIC/f'platform/contracts/aa-beam-{fmt}-production-chain.json').read_text(encoding='utf8'))
        contract['scenarioId']=plan['scenarioId']
        contract['rules']=[dict(intentId=i['intentId']) for i in plan['intents']]
        contract['history']['unitsAfterIntents'] = {'stop-home': contract['history']['finalUnits']}
        contract['history']['productionSession'] = 'aa-afterburn'
        # Each boundary is fixed before execution; compare_history must consume it.
        contract['history']['unitActionStages']=[
            dict(intentId='burn-first',typeId='aaBeamGunship_afterburn',cooldown='active'),
            dict(intentId='burn-move',typeId='aaBeamGunship_afterburn',cooldown='active'),
            dict(intentId='cancel-first',typeId='aaBeamGunship',cooldown='active'),
            dict(intentId='cancel-stop',typeId='aaBeamGunship',cooldown='active'),
            dict(intentId='cooldown-ready',typeId='aaBeamGunship',cooldown='ready'),
            dict(intentId='burn-second',typeId='aaBeamGunship_afterburn',cooldown='active'),
            dict(intentId='automatic-recovery',typeId='aaBeamGunship',cooldown='active'),
            dict(intentId='final-stop',typeId='aaBeamGunship',cooldown='active')]
        for directory,value in [('scenarios',plan),('contracts',contract)]:
            target=PUBLIC/f'platform/{directory}/afterburn-{fmt}-production-chain.json'
            assert not target.exists(),target
            target.write_text(json.dumps(value,indent=2)+'\n',encoding='utf8')

if __name__=='__main__':
    generate()
    generate_platform()
