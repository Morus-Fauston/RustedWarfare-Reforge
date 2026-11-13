"""Owned nuclear histories. Expected prices, stock and damage come from the fixed contract."""
import hashlib
import json
from pathlib import Path

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def save(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf8', newline='\n')


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/nuclear-silo/{fmt}'
        pack = root / 'normal-package'
        def ref(path, identity, **extra):
            return dict(id=identity, path='../'+path.relative_to(PUBLIC).as_posix(),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)
        scene = json.loads((PUBLIC/f'scenarios/engineer-{fmt}-short.json').read_text(encoding='utf8'))
        units = [(pack/f'nukeLauncherC.{fmt}', 'nukeLauncherC', 'nuclear-silo'),
                 (pack/f'builder.{fmt}', 'builder', 'builder'),
                 (pack/f'commandCenter/commandCenter.{fmt}', 'commandCenter', 'command-center')]
        used = {p for p, _, _ in units}
        scene.update(scenarioId='p009-nuclear-silo')
        scene['content'] = dict(map=ref(root/'maps/open.tmx', 'map'),
            unitVariants=[ref(p, identity, loader=loader) for p, identity, loader in units],
            dependencies=[ref(p, f'dep-{i}') for i,p in enumerate(sorted(
                p for directory in ('normal-package','assets') for p in (root/directory).rglob('*')
                if p.is_file() and p not in used))])
        scene['units'] = [dict(unitId='silo',owner='p1',type='nukeLauncherC',position=dict(x=220,y=180),heading=90),
            dict(unitId='target',owner='p2',type='commandCenter',position=dict(x=540,y=300),heading=90)]
        scene['combatContract'] = dict(worldSeed=12345,initialFrameCounter=-1,unitObjectIds=dict(silo=8,target=9),nextObjectId=10)
        scene['economyContract'] = dict(initialBalances=dict(p1=44000,p2=0))
        scene.pop('automaticVision',None)
        scene.pop('maintenanceContract',None)
        scene['actions'] = [dict(actionId='build-round',atStep=0,kind='unit-action',actor='p1',unitId='silo',
            actionName='buildNuke',cancelQueued=False),
            dict(actionId='launch-round',atStep=3340,kind='unit-action',actor='p1',unitId='silo',
                actionName='launchNuke',cancelQueued=False,target=dict(x=540,y=300))]
        scene['termination']['steps'] = [dict(stepIndex=t,delta=1,deltaUnit='sim-tick') for t in range(4100)]
        def rule(identity,step,fields):
            return dict(ruleId=identity,when=dict(phase='after-advance',stepIndex=step),
                assertions=[dict(path=k,operator='equals',value=v) for k,v in fields.items()])
        contract = dict(schemaVersion=1,kind='behavior-contract',contractId=f'public.nuclear-{fmt}.short.v1',
            scope='self-behavior',scenarioIds=[scene['scenarioId']],comparison=dict(numberMode='declared-per-field',tolerances={}),
            approval=dict(status='tool-fixture-only',source='ENG-P009-016 fixed DEC009 source contract; owned self behavior'),
            exclusions=['original-compatibility','platform-input','performance'],rules=[
                rule('prepaid',0,{'economy.balances.p1':33000,'units.silo.unitActions.stockpile':0}),
                rule('built-round',3339,{'units.silo.unitActions.stockpile':1}),
                rule('prepaid-launch',3340,{'units.silo.unitActions.stockpile':0}),
                rule('real-point-impact',4099,{'units.target.health':1500,'units.silo.unitActions.stockpile':0,
                    'units.silo.unitActions.queue':[]})])
        save(PUBLIC/f'scenarios/nuclear-{fmt}-short.json',scene)
        save(PUBLIC/f'contracts/nuclear-{fmt}-short.json',contract)
        intents=[]
        def add(identity,kind,wait=0,point=None,**extra):
            intents.append(dict(intentId=identity,kind=kind,waitSteps=wait,
                **(dict(position=dict(x=point[0],y=point[1])) if point else {}),**extra))
        add('select-center','select',point=(130,130))
        add('produce-worker','produce',600,typeId='builder')
        add('leave-center','select',100,(130,130))
        add('select-worker','select',point=(129,220))
        add('build-silo','build',600,(250,150),typeId='nukeLauncherC')
        add('finish-silo','select',600,(250,150))
        add('build-round','unit-action',600,actionName='buildNuke')
        for i in range(5):add(f'wait-round-{i}','select',600,(250,150))
        add('launch-round','unit-action',600,actionName='launchNuke',target=dict(x=540,y=300))
        add('finish-impact','select',400,(250,150))
        plan=dict(schemaVersion=1,scenarioId=f'nuclear-{fmt}-production-chain',
            inputs=f'../../inputs/nuclear-silo/{fmt}',contentPack=dict(directory='normal-package',unitId='commandCenter'),
            intents=intents,waitPlan={i['intentId']:dict(minimumSteps=i['waitSteps'],timeoutSeconds=180) for i in intents if i['waitSteps']})
        policy=dict(initialBalance=70000,ownerPlayerId='p1',initialOwnedTypes=['commandCenter'],
            initialIncome=dict(unitId='a',typeId='commandCenter',period=10,credits=4.5),productionOrder=['builder'],
            spend=[dict(intentId=i,amount=p,at=at) for i,p,at in [('produce-worker',500,'action'),
                ('build-silo',45000,'construction-created'),('build-round',11000,'action')]],
            nuclearHistory=True,projectileBirthsRequired=True,
            finalUnits=[dict(ownerPlayerId='p1',typeId='nukeLauncherC',fields=dict(alive=True)),
                dict(ownerPlayerId='p2',typeId='commandCenter',fields=dict(health=1500))])
        save(PUBLIC/f'platform/scenarios/nuclear-{fmt}-production-chain.json',plan)
        save(PUBLIC/f'platform/contracts/nuclear-{fmt}-production-chain.json',
            dict(schemaVersion=1,scenarioId=plan['scenarioId'],rules=[dict(intentId=i['intentId']) for i in intents],history=policy))


def generate_interception():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/nuclear-silo/{fmt}'
        package = root / 'interception-package'
        def ref(path, identity, **extra):
            return dict(id=identity, path='../'+path.relative_to(PUBLIC).as_posix(),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)
        scene = json.loads((PUBLIC/f'scenarios/nuclear-{fmt}-short.json').read_text(encoding='utf8'))
        types = ('nukeLauncherC', 'antiNukeLauncherC')
        entries = [package/f'{unit}.{fmt}' for unit in types]
        assets = [p for p in sorted((root/'assets').rglob('*')) if p.is_file()]
        dependencies = [p for p in sorted(package.glob('*.png'))] + assets
        scene.update(scenarioId='p009-ground-interception', content=dict(
            map=ref(root/'maps/open.tmx', 'map'),
            unitVariants=[ref(path, unit, loader='nuclear-silo') for path,unit in zip(entries,types)],
            dependencies=[ref(p,f'dep-{i}') for i,p in enumerate(dependencies)]))
        scene['players'][1]['control'] = 'local'
        scene['units'] = [
            dict(unitId='silo',owner='p2',type=types[0],position=dict(x=180,y=180),heading=90),
            dict(unitId='defense',owner='p1',type=types[1],position=dict(x=280,y=280),heading=90)]
        scene['combatContract'] = dict(worldSeed=12345,initialFrameCounter=-1,unitObjectIds=dict(silo=1,defense=2),nextObjectId=3)
        scene['economyContract'] = dict(initialBalances=dict(p1=4000,p2=11000))
        scene['actions'] = [
            dict(actionId='stock-defense',atStep=0,kind='unit-action',actor='p1',unitId='defense',actionName='buildAntiNuke',cancelQueued=False),
            dict(actionId='stock-attack',atStep=0,kind='unit-action',actor='p2',unitId='silo',actionName='buildNuke',cancelQueued=False),
            dict(actionId='launch',atStep=3340,kind='unit-action',actor='p2',unitId='silo',actionName='launchNuke',cancelQueued=False,target=dict(x=440,y=180))]
        def rule(identity,step,fields):
            return dict(ruleId=identity,when=dict(phase='after-advance',stepIndex=step),
                assertions=[dict(path=path,operator='equals',value=value) for path,value in fields.items()])
        contract = dict(schemaVersion=1,kind='behavior-contract',contractId=f'public.anti-nuclear-{fmt}.short.v1',
            scope='self-behavior',scenarioIds=[scene['scenarioId']],comparison=dict(numberMode='declared-per-field',tolerances={}),
            approval=dict(status='tool-fixture-only',source='ENG-P009-017 fixed DEC009 ground interceptor contract'),
            exclusions=['original-compatibility','platform-input','performance'],rules=[
                rule('pay-once',0,{'economy.balances.p1':0,'economy.balances.p2':0,'units.defense.unitActions.stockpile':0}),
                rule('stocked',1428,{'units.defense.unitActions.stockpile':1}),
                rule('nuclear-hit-prevented',4099,{'units.defense.health':2800,
                    'units.defense.unitActions.stockpile':0,'projectiles':[]})])
        save(PUBLIC/f'scenarios/anti-nuclear-{fmt}-short.json',scene)
        save(PUBLIC/f'contracts/anti-nuclear-{fmt}-short.json',contract)

        plan = json.loads((PUBLIC/f'platform/scenarios/nuclear-{fmt}-production-chain.json').read_text(encoding='utf8'))
        plan['scenarioId'] = f'anti-nuclear-{fmt}-production-chain'
        plan['contentPack']['directory'] = 'interception-package'
        plan['intents'] = plan['intents'][:9]
        for intent in plan['intents']:
            if intent['kind']=='build':intent['typeId']=types[1]
            if intent['kind']=='unit-action':intent['actionName']='buildAntiNuke'
        plan['waitPlan'] = {i['intentId']:dict(minimumSteps=i['waitSteps'],timeoutSeconds=180) for i in plan['intents'] if i['waitSteps']}
        history = dict(initialBalance=70000,ownerPlayerId='p1',initialOwnedTypes=['commandCenter'],
            initialIncome=dict(unitId='a',typeId='commandCenter',period=10,credits=4.5),productionOrder=['builder'],
            spend=[dict(intentId=i,amount=p,at=at) for i,p,at in [('produce-worker',500,'action'),
                ('build-silo',15000,'construction-created'),('build-round',4000,'action')]],
            finalUnits=[dict(ownerPlayerId='p1',typeId=types[1],fields=dict(alive=True,constructionProgress=1))])
        save(PUBLIC/f'platform/scenarios/{plan["scenarioId"]}.json',plan)
        save(PUBLIC/f'platform/contracts/{plan["scenarioId"]}.json',
            dict(schemaVersion=1,scenarioId=plan['scenarioId'],rules=[dict(intentId=i['intentId']) for i in plan['intents']],history=history))


if __name__ == '__main__':
    generate()
    generate_interception()
