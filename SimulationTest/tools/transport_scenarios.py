"""Fixed owned transport examples. Constants are specified before running the simulator."""
from pathlib import Path
import copy
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / 'SimulationTest/public'


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')


def generate():
    template = json.loads((PUBLIC / 'scenarios/submarine-json-short.json').read_text(encoding='utf8'))
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/transport-production/{fmt}'
        prefix = f'../inputs/transport-production/{fmt}/'
        files = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in sorted(root.rglob('*')) if p.is_file()}
        pack = json.loads((root / 'production-package/pack.json').read_text(encoding='utf8'))
        loader = {'sea-factory': 'factory', 'tank': 'declared-tank', 'hovercraft': 'hovercraft'}
        refs = []
        for entry in pack['units']:
            name = 'production-package/' + entry['file']
            if entry['type'] in ('tank',):
                unit_id = 'c_tank'
            elif entry['type'] == 'underwater-target':
                unit_id = 'navalUnderwaterTarget'
            elif entry['type'] == 'submarine':
                unit_id = 'attackSubmarine'
            else:
                unit_id = Path(entry['file']).stem
            refs.append(dict(id=unit_id, path=prefix + name, sha256=files[name], loader=loader.get(entry['type'], entry['type'])))
        used = {ref['path'].removeprefix(prefix) for ref in refs} | {'maps/naval-shore.tmx'}
        scene = copy.deepcopy(template)
        scene.update(scenarioId='p009-transport-production', inputs=prefix.removesuffix('/'),
                     contentPack=dict(directory='production-package', unitId='commandCenter'))
        scene['content'] = dict(map=dict(id='map', path=prefix+'maps/naval-shore.tmx', sha256=files['maps/naval-shore.tmx']),
            unitVariants=refs, dependencies=[dict(id=f'dep-{i}', path=prefix+name, sha256=sha)
                for i, (name, sha) in enumerate(files.items()) if name not in used])
        # Stable order puts the passenger before the carrier, so the release boundary is observed
        # before its next active update, as in the independently specified core unload example.
        scene['units'] = [dict(unitId='a-passenger', owner='p1', type='builder', position=dict(x=360, y=150), heading=90),
                          dict(unitId='craft', owner='p1', type='hovercraft', position=dict(x=375, y=150), heading=90)]
        scene['combatContract'] = dict(worldSeed=12345, initialFrameCounter=-1, unitObjectIds={'a-passenger':8, 'craft':9}, nextObjectId=10)
        scene['actions'] = [dict(actionId='board', atStep=0, kind='load', actor='p1', unitId='a-passenger', targetUnitId='craft', direction='INTO'),
                            dict(actionId='unload', atStep=1, kind='unload', actor='p1', unitId='craft', unloading=True),
                            dict(actionId='cancel', atStep=11, kind='unload', actor='p1', unitId='craft', unloading=False),
                            dict(actionId='resume', atStep=21, kind='unload', actor='p1', unitId='craft', unloading=True)]
        scene['termination']['steps'] = [dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(100)]
        scene['intents'] = [dict(intentId='select-worker', kind='select', position=dict(x=360, y=150), waitSteps=0),
            dict(intentId='board', coreActionId='board', kind='load', direction='INTO', targetUnitId='craft', position=dict(x=375,y=150), waitSteps=1),
            dict(intentId='select-craft', kind='select', position=dict(x=375,y=150), waitSteps=0),
            dict(intentId='unload', coreActionId='unload', kind='unload', unloading=True, waitSteps=10),
            dict(intentId='cancel', coreActionId='cancel', kind='unload', unloading=False, waitSteps=10),
            dict(intentId='resume', coreActionId='resume', kind='unload', unloading=True, waitSteps=79)]
        scene['testScene'] = dict(formatVersion=1, displayName=f'Landing craft transport ({fmt})',
            summary='Owned short transport admission, cancellation and timed release; normal production is a separate session.', files=files)
        contract = dict(schemaVersion=1, kind='behavior-contract', contractId=f'public.transport-{fmt}.short.contract.v1',
            scope='self-behavior', scenarioIds=['p009-transport-production'],
            comparison=dict(numberMode='declared-per-field', tolerances={}),
            approval=dict(status='tool-fixture-only', source='ENG-P009-007 fixed native static facts; owned self-behavior only'),
            exclusions=['original-compatibility', 'platform-input', 'performance'], rules=[])
        for rule_id, step, assertions in [
            ('boarded', 0, [('units.a-passenger.carriedBy','equals','craft'), ('units.craft.transport.passengerCount','equals',1), ('units.craft.transport.occupiedSlots','equals',1)]),
            ('cancel-preserves', 20, [('units.a-passenger.carriedBy','equals','craft'), ('units.craft.transport.unloading','equals',False)]),
            ('released', 50, [('units.a-passenger.carriedBy','absent-or-null',None), ('units.craft.transport.passengerCount','equals',0), ('units.a-passenger.transportExit.remaining','equals',45)]),
            ('exit-reference-cleared', 95, [('units.a-passenger.transportExit','absent-or-null',None)])]:
            contract['rules'].append(dict(ruleId=rule_id, when=dict(phase='after-advance', stepIndex=step),
                assertions=[dict(path=p, operator=op, **({'value':v} if op=='equals' else {})) for p,op,v in assertions]))
        write(PUBLIC / f'scenarios/transport-{fmt}-short.json', scene)
        write(PUBLIC / f'contracts/transport-{fmt}-short.json', contract)
        def intent(name, kind, wait=0, point=None, **values):
            return dict(intentId=name, kind=kind, waitSteps=wait,
                        **(dict(position=dict(x=point[0], y=point[1])) if point else {}), **values)
        production = dict(schemaVersion=1, scenarioId=f'transport-{fmt}-production-chain',
            inputs=f'../../inputs/transport-production/{fmt}',
            contentPack=dict(directory='production-package', unitId='commandCenter'),
            intents=[intent('select-center', 'select', point=(130,130)),
                intent('produce-worker', 'produce', 600, typeId='builder'),
                intent('leave-center', 'select', 100, (130,130)),
                intent('select-worker', 'select', point=(129,220)),
                intent('move-worker-shore', 'move', 600, (380,250)),
                intent('stop-worker', 'stop', 40),
                intent('build-factory', 'build', 600, (450,250), typeId='seaFactory'),
                intent('wait-factory', 'select', 600, (380,250)),
                intent('finish-factory', 'select', 600, (380,250)),
                intent('select-factory', 'select', point=(450,250)),
                intent('produce-craft', 'produce', 600, typeId='hovercraft'),
                intent('select-craft', 'select', point=(449,385)),
                intent('approach-worker', 'move', 600, (380,230)),
                intent('stop-shore', 'stop', 40),
                intent('board', 'load', 400, (380,250), direction='UP'),
                intent('cross-water', 'move', 600, (520,400)),
                intent('return-land', 'move', 600, (300,250)),
                intent('stop-land', 'stop', 40),
                intent('start-unload', 'unload', unloading=True),
                intent('cancel-unload', 'unload', unloading=False),
                intent('resume-unload', 'unload', 100, unloading=True)])
        production['windowsPausedIntents'] = ['start-unload','cancel-unload']
        production['windowsWaits'] = {i['intentId']: dict(rate=4, progressOnly=True,
            maximumSimulationSteps=i['waitSteps']+180, stallSeconds=20, maximumWallSeconds=60)
            for i in production['intents'] if i['waitSteps']}
        write(PUBLIC / f'platform/scenarios/transport-{fmt}-production-chain.json', production)
        history = dict(initialBalance=10000, ownerPlayerId='p1', initialOwnedTypes=['commandCenter'],
            initialIncome=dict(unitId='a', typeId='commandCenter', period=10, credits=4.5),
            productionOrder=['builder', 'hovercraft'], projectileBirthsRequired=False,
            spend=[dict(intentId=name, amount=amount, at=when) for name,amount,when in [
                ('produce-worker',500,'action'), ('build-factory',1000,'construction-created'), ('produce-craft',600,'action')]],
            finalUnits=[dict(ownerPlayerId='p1',typeId=kind,fields=dict(alive=True))
                for kind in ('commandCenter','builder','seaFactory','hovercraft')],
            transportStages=[dict(intentId=name, passengerCount=count, unloading=unloading, **extra)
                for name,count,unloading,extra in [
                    ('board',1,False,{}), ('cross-water',1,False,dict(minimumX=400)),
                    ('return-land',1,False,dict(maximumX=350)), ('stop-land',1,False,dict(maximumX=350)),
                    ('start-unload',1,True,{}), ('cancel-unload',1,False,{}), ('resume-unload',0,False,{})]])
        write(PUBLIC / f'platform/contracts/transport-{fmt}-production-chain.json',
            dict(schemaVersion=1,scenarioId=production['scenarioId'],
                rules=[dict(intentId=i['intentId']) for i in production['intents']],history=history))


if __name__ == '__main__':
    generate()
