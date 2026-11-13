"""Fixed owned bunker action and normal production inputs; no execution-derived expectations."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/bunker-production/{fmt}'
        scene = json.loads((PUBLIC / f'scenarios/heavy-mech-{fmt}-short.json').read_text(encoding='utf8'))
        scene['scenarioId'] = 'p009-bunker-actions'
        tree = ET.parse(root / 'maps/p001-controlled.tmx')
        tree.getroot().set('width', '12'); tree.getroot().set('height', '12')
        layer = tree.getroot().find('layer')
        layer.set('width', '12'); layer.set('height', '12')
        layer.find('data').text = ','.join(['1'] * 144)
        (root / 'maps/bunker-actions.tmx').write_bytes(ET.tostring(tree.getroot(), encoding='utf8') + b'\n')
        def ref(path, identity, **extra):
            return dict(id=identity, path='../' + path.relative_to(PUBLIC).as_posix(),
                        sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)
        scene['content'] = dict(map=ref(root / 'maps/bunker-actions.tmx', 'map'),
            unitVariants=[ref(root / f'package/{file}.{fmt}', identity, loader='mech-bunker')
                          for file, identity in [('bunker', 'mechBunker'), ('deployed', 'mechBunkerDeployed')]],
            dependencies=[ref(path, f'dep-{index}') for index, path in enumerate(
                sorted((root / 'package').glob('*.png')) + sorted((root / 'assets').glob('*.png')))])
        scene['automaticVision']['grid'] = dict(width=12, height=12)
        scene['units'] = [dict(unitId='a', owner='p1', type='mechBunker', position=dict(x=110, y=110), heading=140)]
        def action(identity, step, name, **extra):
            return dict(actionId=identity, atStep=step, kind='unit-action', actor='p1', unitId='a',
                        actionName=name, cancelQueued=False, **extra)
        scene['actions'] = [action('deploy', 0, 'deploy'), action('duplicate', 0, 'deploy'),
            dict(action('cancel', 0, 'deploy'), cancelQueued=True), action('hidden', 170, 'deploy'),
            action('undeploy', 200, 'undeploy')]
        scene['termination']['steps'] = [dict(stepIndex=t, delta=1, deltaUnit='sim-tick') for t in range(330)]
        contract = json.loads((PUBLIC / f'contracts/heavy-mech-{fmt}-short.json').read_text(encoding='utf8'))
        contract.update(contractId=f'public.bunker-{fmt}.short.contract.v1', scenarioIds=[scene['scenarioId']],
            approval=dict(status='tool-fixture-only', source='ENG-P009-011 qualified static normal action facts; owned self-behavior only'))
        def rule(identity, step, fields):
            return dict(ruleId=identity, when=dict(phase='after-advance', stepIndex=step),
                assertions=[dict(path='units.a.' + key, operator='absent-or-null' if value is None else 'equals', value=value)
                            for key, value in fields.items()])
        contract['rules'] = [rule('first-progress', 0, {'type': 'mechBunker', 'unitActions.actions.deploy.progress': 0.006}),
            rule('not-yet-fixed', 165, {'type': 'mechBunker'}),
            rule('fixed', 166, {'type': 'mechBunkerDeployed', 'shield.current': 0, 'health': 800}),
            rule('not-yet-mobile', 299, {'type': 'mechBunkerDeployed'}),
            rule('mobile', 300, {'type': 'mechBunker', 'shield': None, 'health': 800})]
        contract['unitActionConversions'] = [dict(unitId='a', actionId='deploy', sourceTypeId='mechBunker',
            targetTypeId='mechBunkerDeployed', completionStepIndex=166), dict(unitId='a', actionId='undeploy',
            sourceTypeId='mechBunkerDeployed', targetTypeId='mechBunker', completionStepIndex=300)]
        save(PUBLIC / f'scenarios/bunker-{fmt}-short.json', scene)
        save(PUBLIC / f'contracts/bunker-{fmt}-short.json', contract)


def generate_platform():
    for fmt in ('ini', 'json', 'hjson'):
        plan = json.loads((PUBLIC / f'platform/scenarios/aa-beam-{fmt}-production-chain.json').read_text(encoding='utf8'))
        plan.update(scenarioId=f'bunker-{fmt}-production-chain', inputs=f'../../inputs/bunker-production/{fmt}')
        intents = plan['intents'][:8]
        intents[-2]['typeId'] = 'mechFactory'
        def intent(identity, kind, wait=0, **fields):
            return dict(intentId=identity, kind=kind, waitSteps=wait, **fields)
        def select(identity, x, y, wait=0):
            return intent(identity, 'select', wait, position=dict(x=x, y=y))
        def move(identity, x, y, wait):
            return intent(identity, 'move', wait, position=dict(x=x, y=y))
        intents += [intent('produce-bunker', 'produce', 600, typeId='mechBunker'),
            select('wait-bunker-1', 270, 150, 600), select('wait-bunker-2', 270, 150, 600),
            select('select-bunker', 270, 200), move('approach', 300, 270, 300),
            intent('stop-mobile', 'stop', 40), intent('deploy', 'unit-action', 167, actionName='deploy'),
            intent('attack', 'attack', 160, position=dict(x=500, y=310), targetUnitId='b', targetGroundPosition=dict(x=500, y=310)),
            intent('stop-fixed', 'stop', 40), intent('undeploy', 'unit-action', 101, actionName='undeploy'),
            move('return', 240, 300, 400), intent('final-stop', 'stop', 40)]
        plan['intents'] = intents
        plan['waitPlan'] = {i['intentId']: dict(minimumSteps=i['waitSteps'], timeoutSeconds=240)
                            for i in intents if i['waitSteps']}
        contract = dict(schemaVersion=1, scenarioId=plan['scenarioId'], rules=[dict(intentId=i['intentId']) for i in intents],
            history=dict(initialBalance=16000, ownerPlayerId='p1', initialOwnedTypes=['commandCenter'],
                initialIncome=dict(unitId='a', typeId='commandCenter', period=10, credits=4.5),
                productionOrder=['builder', 'mechBunker'], projectileBirthsRequired=True,
                mechLegs=dict(mechGun=2, mechBunker=4, mechBunkerDeployed=4),
                spend=[dict(intentId='produce-worker', amount=500, at='action'),
                       dict(intentId='build-t1', amount=1000, at='construction-created'),
                       dict(intentId='produce-bunker', amount=4500, at='action')],
                finalUnits=[dict(ownerPlayerId='p1', typeId=t, fields=dict(alive=True))
                            for t in ['commandCenter', 'builder', 'mechFactory', 'mechBunker']],
                bunkerStages=[dict(intentId=i, typeId=t) for i, t in [('deploy', 'mechBunkerDeployed'),
                    ('attack', 'mechBunkerDeployed'), ('undeploy', 'mechBunker'), ('final-stop', 'mechBunker')]]))
        save(PUBLIC / f'platform/scenarios/bunker-{fmt}-production-chain.json', plan)
        save(PUBLIC / f'platform/contracts/bunker-{fmt}-production-chain.json', contract)


if __name__ == '__main__':
    generate()
    generate_platform()
