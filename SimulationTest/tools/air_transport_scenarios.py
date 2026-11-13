"""Fixed public air transport contracts; expected values precede simulator execution."""
from pathlib import Path
import copy
import hashlib
import json

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def write(path, value):
    assert not path.exists(), f'Never overwrite a scenario batch: {path}'
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/air-transport-production/{fmt}'
        prefix = f'../inputs/air-transport-production/{fmt}/'
        files = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in sorted(root.rglob('*')) if p.is_file()}
        pack = json.loads((root / 'production-package/pack.json').read_text(encoding='utf8'))
        refs = []
        for entry in pack['units']:
            name = 'production-package/' + entry['file']
            unit_id = {'tank': 'c_tank', 'underwater-target': 'navalUnderwaterTarget'}.get(entry['type'], Path(entry['file']).stem)
            loader = {'air-factory': 'factory', 'tank': 'declared-tank'}.get(entry['type'], entry['type'])
            refs.append(dict(id=unit_id, path=prefix + name, sha256=files[name], loader=loader))
        used = {ref['path'].removeprefix(prefix) for ref in refs} | {'maps/naval-shore.tmx'}
        scene = json.loads((PUBLIC / f'scenarios/transport-{fmt}-short.json').read_text(encoding='utf8'))
        scene.update(scenarioId='p009-air-transport-production', inputs=prefix.removesuffix('/'))
        scene['content'] = dict(map=dict(id='map', path=prefix+'maps/naval-shore.tmx', sha256=files['maps/naval-shore.tmx']),
            unitVariants=refs, dependencies=[dict(id=f'dep-{i}', path=prefix+name, sha256=sha)
                for i, (name, sha) in enumerate(files.items()) if name not in used])
        scene['units'][1]['type'] = 'dropship'
        scene['testScene'] = dict(formatVersion=1, displayName=f'Air transport ({fmt})',
            summary='Owned air transport loading, timed unloading and cancellation; normal production is separate.', files=files)
        contract = json.loads((PUBLIC / f'contracts/transport-{fmt}-short.json').read_text(encoding='utf8'))
        contract.update(contractId=f'public.air-transport-{fmt}.short.contract.v1', scenarioIds=['p009-air-transport-production'])
        contract['approval']['source'] = 'ENG-P009-008 qualified native static facts; owned self-behavior only'
        write(PUBLIC / f'scenarios/air-transport-{fmt}-short.json', scene)
        write(PUBLIC / f'contracts/air-transport-{fmt}-short.json', contract)

        def intent(name, kind, wait=0, point=None, **values):
            return dict(intentId=name, kind=kind, waitSteps=wait,
                        **(dict(position=dict(x=point[0], y=point[1])) if point else {}), **values)
        production = dict(schemaVersion=1, scenarioId=f'air-transport-{fmt}-production-chain',
            inputs=f'../../inputs/air-transport-production/{fmt}',
            contentPack=dict(directory='production-package', unitId='commandCenter'), intents=[
                intent('select-center', 'select', point=(130,130)),
                intent('produce-worker', 'produce', 600, typeId='builder'),
                intent('leave-center', 'select', 100, (130,130)),
                intent('select-worker', 'select', point=(129,220)),
                intent('place-worker', 'move', 600, (230,200)),
                intent('stop-worker', 'stop', 40),
                intent('build-t1', 'build', 600, (270,150), typeId='airFactory'),
                intent('finish-t1', 'select', 600, (270,150)),
                intent('upgrade-t2', 'upgrade', 600, upgradeId='upgrade_t2'),
                *[intent(f'wait-upgrade-{i}', 'select', 600, (270,150)) for i in range(1,4)],
                intent('finish-upgrade', 'select', 200, (270,150)),
                intent('produce-air', 'produce', 600, typeId='dropship'),
                intent('finish-air', 'select', 600, (270,150)),
                intent('select-air', 'select', point=(269,238)),
                intent('approach-worker', 'move', 400, (230,180)),
                intent('stop-board', 'stop', 40),
                intent('board', 'load', 400, (230,200), direction='UP'),
                intent('cross-water', 'move', 600, (520,400)),
                intent('return-land', 'move', 600, (300,250)),
                intent('stop-land', 'stop', 100),
                intent('start-unload', 'unload', unloading=True),
                intent('cancel-unload', 'unload', unloading=False),
                intent('resume-unload', 'unload', 400, unloading=True),
                intent('select-released', 'select', point=(336,296)),
                intent('move-released', 'move', 400, (320,320)),
                intent('stop-released', 'stop', 40)],
            windowsPausedIntents=['start-unload', 'cancel-unload'])
        production['waitPlan'] = {i['intentId']: dict(minimumSteps=i['waitSteps'], timeoutSeconds=240)
                                  for i in production['intents'] if i['waitSteps']}
        history = dict(initialBalance=10000, ownerPlayerId='p1', initialOwnedTypes=['commandCenter'],
            initialIncome=dict(unitId='a', typeId='commandCenter', period=10, credits=4.5),
            productionOrder=['builder', 'dropship'], projectileBirthsRequired=False,
            spend=[dict(intentId=name, amount=amount, at=when) for name,amount,when in [
                ('produce-worker',500,'action'), ('build-t1',1000,'construction-created'),
                ('upgrade-t2',1500,'action'), ('produce-air',800,'action')]],
            finalUnits=[dict(ownerPlayerId='p1', typeId=kind, fields=dict(alive=True))
                        for kind in ('commandCenter','builder','airFactoryT2','dropship')],
            transportStages=[dict(intentId=name, typeId='dropship', passengerCount=count, unloading=unloading, **extra)
                for name,count,unloading,extra in [
                    ('board',1,False,{}), ('cross-water',1,False,dict(minimumX=400)),
                    ('return-land',1,False,dict(maximumX=350)), ('stop-land',1,False,dict(maximumX=350)),
                    ('start-unload',1,True,{}), ('cancel-unload',1,False,{}), ('resume-unload',0,False,{})]])
        write(PUBLIC / f'platform/scenarios/air-transport-{fmt}-production-chain.json', production)
        write(PUBLIC / f'platform/contracts/air-transport-{fmt}-production-chain.json',
              dict(schemaVersion=1, scenarioId=production['scenarioId'],
                   rules=[dict(intentId=i['intentId']) for i in production['intents']], history=history))


if __name__ == '__main__':
    generate()
