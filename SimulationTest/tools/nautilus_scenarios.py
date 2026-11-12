"""Owned submerged-production cases with explicit independent completion obligations."""
import hashlib
import copy
import json
import xml.etree.ElementTree as ET
from pathlib import Path

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def save(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf8', newline='\n')


def fixed_terrain(fmt):
    """Read only owned input tiles; no runtime output contributes expected state."""
    tmx=ET.parse(PUBLIC/f'inputs/nautilus-production/{fmt}/maps/naval-shore.tmx').getroot()
    water_gids=set()
    for tileset in tmx.findall('tileset'):
        for tile in tileset.findall('tile'):
            props={p.attrib['name'] for p in tile.findall('properties/property')}
            if 'water' in props and 'water-bridge' not in props:
                water_gids.add(int(tileset.attrib['firstgid'])+int(tile.attrib['id']))
    gids=[int(x) for x in tmx.find("layer[@name='Ground']/data").text.split(',')]
    return dict(**{k:int(tmx.attrib[v]) for k,v in [('width','width'),('height','height'),('tileWidth','tilewidth'),('tileHeight','tileheight')]},
                waterCells=[i for i,gid in enumerate(gids) if gid in water_gids])


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/nautilus-production/{fmt}'
        pack = root / 'package'
        def ref(path, identity, **extra):
            return dict(id=identity, path='../'+path.relative_to(PUBLIC).as_posix(),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)
        scene = json.loads((PUBLIC / f'scenarios/engineer-{fmt}-short.json').read_text(encoding='utf8'))
        paths = [(pack / f'nautilus.{fmt}', 'nautilusSubmarine', 'nautilus'),
                 (pack / f'nautilusSurface.{fmt}', 'nautilusSubmarineSurface', 'nautilus'),
                 (pack / f'nautilusLand.{fmt}', 'nautilusSubmarineLand', 'nautilus'),
                 (pack / f'scout/robotCrab.{fmt}', 'robotCrab', 'scout-bot'),
                 (pack / f'scout/robotCrabWater.{fmt}', 'robotCrabWater', 'scout-bot'),
                 (pack / f'hover/hover.{fmt}', 'hoverTank', 'hover-tank')]
        used = {p for p, _, _ in paths}
        scene.update(scenarioId='p009-nautilus-mobile-production')
        scene['content'] = dict(map=ref(root / 'maps/naval-shore.tmx', 'map'),
            unitVariants=[ref(p, identity, loader=kind) for p, identity, kind in paths],
            dependencies=[ref(p, f'dep-{i}') for i, p in enumerate(sorted(
                p for directory in ('package', 'assets') for p in (root / directory).rglob('*') if p.is_file() and p not in used))])
        scene['units'] = [dict(unitId='mother', owner='p1', type='nautilusSubmarine', position=dict(x=500, y=180), heading=90)]
        scene['combatContract'] = dict(worldSeed=12345, initialFrameCounter=-1, unitObjectIds=dict(mother=8), nextObjectId=9)
        scene['economyContract'] = dict(initialBalances=dict(p1=5000, p2=0))
        scene.pop('automaticVision', None)
        scene.pop('maintenanceContract', None)
        scene['actions'] = [dict(actionId=f'queue-{i}', atStep=0, kind='produce', actor='p1', unitId='mother',
            productionTypeId='hoverTank', unitNonce=f'{i:032x}') for i in range(1, 6)]
        scene['actions'].append(dict(actionId='cancel-waiting', atStep=1260, kind='cancel-production', actor='p1', unitId='mother', productionTypeId='hoverTank'))
        scene['termination']['steps'] = [dict(stepIndex=t, delta=1, deltaUnit='sim-tick') for t in range(1261)]
        def rule(identity, step, fields):
            return dict(ruleId=identity, when=dict(phase='after-advance', stepIndex=step),
                assertions=[dict(path=key, operator='equals', value=value) for key, value in fields.items()])
        contract = dict(schemaVersion=1, kind='behavior-contract', contractId=f'public.nautilus-{fmt}.short.v1',
            scope='self-behavior', scenarioIds=[scene['scenarioId']], comparison=dict(numberMode='declared-per-field', tolerances={}),
            approval=dict(status='tool-fixture-only', source='ENG-P009-014 qualified DEC005: .002 base x2, strict used>3 completion gate; owned self behavior'),
            exclusions=['original-compatibility', 'platform-input', 'performance'],
            producedUnits=[dict(unitId=f'produced:p1:{i:032x}', productionActionId=f'queue-{i}',
                producerId='mother', owner='p1', typeId='hoverTank', unitNonce=f'{i:032x}', birthStepIndex=250*i-1) for i in range(1, 5)],
            nautilusTerrain=dict(mapSha256=scene['content']['map']['sha256'],**fixed_terrain(fmt)),rules=[
                rule('first-internal-completion', 249, {'units.mother.transport.passengerCount': 1,
                    'units.mother.transport.capacity': 3, 'units.mother.transport.canUnload': False}),
                rule('exactly-three', 749, {'units.mother.transport.occupiedSlots': 3}),
                rule('fourth-completion', 999, {'units.mother.transport.occupiedSlots': 4}),
                rule('head-waits-without-new-identity', 1259, {'units.mother.transport.passengerCount': 4,
                    'production.lastBirthSerial': 4, 'economy.balances.p1': 2750,
                    'economy.queues.mother': [dict(typeId='hoverTank', unitNonce=f'{5:032x}', progress=1,
                        cost=450, rate=.002)]}),
                rule('full-price-cancel', 1260, {'economy.balances.p1': 3200,
                    'production.lastBirthSerial': 4, 'units.mother.transport.passengerCount': 4})])
        save(PUBLIC / f'scenarios/nautilus-{fmt}-short.json', scene)
        save(PUBLIC / f'contracts/nautilus-{fmt}-short.json', contract)


def generate_platform():
    for fmt in ('ini', 'json', 'hjson'):
        base = json.loads((PUBLIC / f'platform/scenarios/transport-{fmt}-production-chain.json').read_text(encoding='utf8'))
        intents = base['intents'][:10]
        def intent(identity, kind, wait=0, point=None, **extra):
            return dict(intentId=identity, kind=kind, waitSteps=wait,
                **(dict(position=dict(x=point[0], y=point[1])) if point else {}), **extra)
        intents.append(intent('upgrade-t2', 'upgrade', 600, upgradeId='upgrade_t2'))
        intents += [intent(f'wait-upgrade-{i}', 'select', 600, (450, 250)) for i in range(3)]
        intents += [intent('finish-upgrade', 'select', 200, (450, 250)),
            intent('produce-mother', 'produce', 600, typeId='nautilusSubmarine')]
        intents += [intent(f'wait-mother-{i}', 'select', 600, (450, 250)) for i in range(5)]
        intents += [intent('leave-shipyard', 'select', 600, (450, 250)),
            intent('select-mother', 'select', point=(449, 385))]
        intents += [intent(f'queue-hover-{i}', 'produce', typeId='hoverTank') for i in range(1, 6)]
        intents += [intent('mobile-production', 'move', 600, (600, 385)),
            intent('stop-mother', 'stop', 600), intent('wait-full-head', 'stop', 100)]
        plan = dict(schemaVersion=1, scenarioId=f'nautilus-{fmt}-production-chain',
            inputs=f'../../inputs/nautilus-production/{fmt}',
            contentPack=dict(directory='production-package', unitId='commandCenter'), intents=intents,
            waitPlan={i['intentId']:dict(minimumSteps=i['waitSteps'],timeoutSeconds=240) for i in intents if i['waitSteps']})
        charges = [('produce-worker',500,'action'),('build-factory',1000,'construction-created'),
            ('upgrade-t2',2000,'action'),('produce-mother',18000,'action')]
        charges += [(f'queue-hover-{i}',450,'action') for i in range(1,6)]
        contract = dict(schemaVersion=1,scenarioId=plan['scenarioId'],rules=[dict(intentId=i['intentId']) for i in intents],
            history=dict(initialBalance=30000,ownerPlayerId='p1',initialOwnedTypes=['commandCenter'],
                initialIncome=dict(unitId='a',typeId='commandCenter',period=10,credits=4.5),
                productionOrder=['builder','nautilusSubmarine'],completedUpgradeTypes=['seaFactoryT2'],
                spend=[dict(intentId=identity,amount=price,at=at) for identity,price,at in charges],
                transportStages=[dict(intentId=identity,typeId='nautilusSubmarine',passengerCount=count,
                    unloading=False,minimumX=500) for identity,count in [('mobile-production',2),('wait-full-head',4)]],
                finalUnits=[dict(ownerPlayerId='p1',typeId=t,fields=dict(alive=True)) for t in
                    ['commandCenter','builder','seaFactoryT2','nautilusSubmarine']]))
        save(PUBLIC / f'platform/scenarios/nautilus-{fmt}-production-chain.json',plan)
        save(PUBLIC / f'platform/contracts/nautilus-{fmt}-production-chain.json',contract)


def generate_land_platform():
    for fmt in ('ini','json','hjson'):
        intents=[]
        def add(identity,kind,wait=0,point=None,**extra):
            intents.append(dict(intentId=identity,kind=kind,waitSteps=wait,
                **(dict(position=dict(x=point[0],y=point[1])) if point else {}),**extra))
        def wait(identity,ticks,point):
            for i in range((ticks+599)//600):add(f'{identity}-{i}','select',min(600,ticks-i*600),point)
        add('select-center','select',point=(130,130))
        add('produce-worker','produce',600,typeId='builder')
        add('leave-center','select',100,(130,130))
        add('select-worker','select',point=(129,220))
        add('build-experimental','build',600,(210,310),typeId='experimentalLandFactory')
        wait('construct-experimental',6400,(210,310))
        add('select-experimental','select',point=(210,310))
        add('produce-mother','produce',600,typeId='nautilusSubmarineLand')
        wait('wait-mother',3400,(210,310))
        add('select-mother','select',point=(209,475))
        add('queue-hover','produce',260,typeId='hoverTank')
        add('enter-water','move',600,(500,400))
        wait('reach-water',1400,(210,310))
        add('select-surface','select',point=(500,400))
        add('stop-surface','stop',60)
        add('dive','unit-action',140,actionName='dive')
        add('surface','unit-action',1,actionName='surface')
        add('release','unload',150,unloading=True)
        add('return-shore','move',600,(250,400))
        wait('reach-shore',1200,(210,310))
        add('select-land','select',point=(250,400))
        add('stop-land','stop',60)
        plan=dict(schemaVersion=1,scenarioId=f'nautilus-{fmt}-land-forms',
            inputs=f'../../inputs/nautilus-production/{fmt}',
            contentPack=dict(directory='production-package',unitId='commandCenter'),intents=intents,
            waitPlan={i['intentId']:dict(minimumSteps=i['waitSteps'],timeoutSeconds=240) for i in intents if i['waitSteps']})
        stages=[('queue-hover','nautilusSubmarineLand',1,False),('stop-surface','nautilusSubmarineSurface',1,False),
                ('dive','nautilusSubmarine',1,False),('surface','nautilusSubmarineSurface',1,False),
                ('release','nautilusSubmarineSurface',0,False),('stop-land','nautilusSubmarineLand',0,False)]
        contract=dict(schemaVersion=1,scenarioId=plan['scenarioId'],rules=[dict(intentId=i['intentId']) for i in intents],
            history=dict(initialBalance=30000,ownerPlayerId='p1',initialOwnedTypes=['commandCenter'],
                initialIncome=dict(unitId='a',typeId='commandCenter',period=10,credits=4.5),
                productionOrder=['builder','nautilusSubmarineLand'],
                projectileBirthsRequired=True,
                minimumBirths=[dict(ownerPlayerId='p1',typeId='nautilusSubmarineLand',weaponNames=['2'],targetUnitId='b')],
                spend=[dict(intentId=i,amount=p,at=at) for i,p,at in [('produce-worker',500,'action'),
                    ('build-experimental',11000,'construction-created'),('produce-mother',18000,'action'),('queue-hover',450,'action')]],
                mechLegs=dict(nautilusSubmarineLand=6,nautilusSubmarineSurface=6),nautilusTerrain=fixed_terrain(fmt),
                transportStages=[dict(intentId=i,typeId=t,passengerCount=n,unloading=u) for i,t,n,u in stages],
                finalUnits=[dict(ownerPlayerId='p1',typeId=t,fields=dict(alive=True)) for t in
                    ['commandCenter','builder','experimentalLandFactory','nautilusSubmarineLand','hoverTank']]+
                    [dict(unitId='b',fields=dict(alive=False))]))
        save(PUBLIC/f'platform/scenarios/nautilus-{fmt}-land-forms.json',plan)
        save(PUBLIC/f'platform/contracts/nautilus-{fmt}-land-forms.json',contract)


def generate_forms():
    for fmt in ('ini','json','hjson'):
        scene=copy.deepcopy(json.loads((PUBLIC/f'scenarios/nautilus-{fmt}-short.json').read_text(encoding='utf8')))
        scene['scenarioId']='p009-nautilus-three-forms'
        scene['units'][0]['height']=-8
        scene['actions']=scene['actions'][:1]+[
            dict(actionId='surface',atStep=250,kind='unit-action',actor='p1',unitId='mother',actionName='surface',cancelQueued=False),
            dict(actionId='release',atStep=251,kind='unload',actor='p1',unitId='mother',unloading=True),
            dict(actionId='dive',atStep=300,kind='unit-action',actor='p1',unitId='mother',actionName='dive',cancelQueued=False),
            dict(actionId='resurface',atStep=500,kind='unit-action',actor='p1',unitId='mother',actionName='surface',cancelQueued=False),
            dict(actionId='shore',atStep=501,kind='move',actor='p1',unitId='mother',target=dict(x=300,y=180))]
        scene['termination']['steps']=[dict(stepIndex=t,delta=1,deltaUnit='sim-tick') for t in range(1601)]
        def rule(identity,step,fields):
            return dict(ruleId=identity,when=dict(phase='after-advance',stepIndex=step),assertions=[
                dict(path=k.replace('.typeId','.type'),operator='equals',value=v) for k,v in fields.items()])
        contract=dict(schemaVersion=1,kind='behavior-contract',contractId=f'public.nautilus-{fmt}.forms.v1',
            scope='self-behavior',scenarioIds=[scene['scenarioId']],comparison=dict(numberMode='declared-per-field',tolerances={}),
            approval=dict(status='tool-fixture-only',source='ENG-P009-015 DEC005 and qualified 004a/068 static facts; owned input only'),
            exclusions=['original-compatibility','platform-input','performance'],
            producedUnits=[dict(unitId='produced:p1:'+f'{1:032x}',productionActionId='queue-1',producerId='mother',owner='p1',
                typeId='hoverTank',unitNonce=f'{1:032x}',birthStepIndex=249)],
            rules=[rule('retained',249,{'units.mother.typeId':'nautilusSubmarine','units.mother.transport.passengerCount':1}),
                rule('surface',250,{'units.mother.typeId':'nautilusSubmarineSurface','units.mother.transport.passengerCount':1}),
                rule('released',281,{'units.mother.transport.passengerCount':0}),
                rule('dived',434,{'units.mother.typeId':'nautilusSubmarine'}),
                rule('resurfaced',500,{'units.mother.typeId':'nautilusSubmarineSurface'}),
                rule('landed',1600,{'units.mother.typeId':'nautilusSubmarineLand','units.mother.transport.passengerCount':0,
                    'units.mother.health':4000,'units.mother.energy.current':6,'production.lastBirthSerial':1,
                    'economy.balances.p1':4550})])
        # Independent tile predicate declaration comes from the fixed owned map, never a run.
        contract['nautilusTerrain']=dict(mapSha256=scene['content']['map']['sha256'],**fixed_terrain(fmt))
        save(PUBLIC/f'scenarios/nautilus-{fmt}-forms.json',scene)
        save(PUBLIC/f'contracts/nautilus-{fmt}-forms.json',contract)


def generate_scout():
    for fmt in ('ini', 'json', 'hjson'):
        plan = json.loads((PUBLIC / f'platform/scenarios/nautilus-{fmt}-land-forms.json').read_text(encoding='utf8'))
        select_mother = next(i for i, intent in enumerate(plan['intents']) if intent['intentId'] == 'select-mother')
        plan['scenarioId'] = f'nautilus-{fmt}-scout-launch'
        plan['intents'] = plan['intents'][:select_mother + 1]
        target = dict(x=270, y=475)
        plan['intents'] += [
            dict(intentId='queue-scout', kind='unit-action', waitSteps=15, actionName='launchScoutBot', target=target),
            dict(intentId='cancel-scout', kind='unit-action', waitSteps=0, actionName='launchScoutBot', cancelQueued=True),
            dict(intentId='launch-scout', kind='unit-action', waitSteps=380, actionName='launchScoutBot', target=target),
        ]
        plan['waitPlan'] = {intent['intentId']: dict(minimumSteps=intent['waitSteps'], timeoutSeconds=240)
                            for intent in plan['intents'] if intent['waitSteps']}
        save(PUBLIC / f'platform/scenarios/nautilus-{fmt}-scout-launch.json', plan)


if __name__ == '__main__':
    generate()
    generate_platform()
    generate_forms()
    generate_land_platform()
    generate_scout()
