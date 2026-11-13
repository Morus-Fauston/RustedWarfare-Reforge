"""Generate the owned, builder-first flame-mech replay and fixed observations."""
from pathlib import Path
import json

PLATFORM = Path(__file__).resolve().parents[1] / 'public/platform'


def generate():
    for form in ('ini', 'json', 'hjson'):
        identifier = f'flame-mech-{form}-production-chain'
        heavy = PLATFORM / 'scenarios' / f'heavy-mech-{form}-production-chain.json'
        scenario = json.loads(heavy.read_text(encoding='utf8'))
        scenario.update(scenarioId=identifier, inputs=f'../../inputs/flame-mech-production/{form}')
        retained = ['select-builder', 'build-t1', 'finish-t1', 'upgrade-t2', 'wait-upgrade-0',
                    'wait-upgrade-1', 'wait-upgrade-2', 'finish-upgrade', 'produce-heavy', 'wait-heavy-0',
                    'wait-heavy-1', 'leave-factory', 'select-heavy', 'move-heavy', 'attack-ground',
                    'return-cliff', 'stop-heavy']
        by_id = {item['intentId']: item for item in scenario['intents']}
        scenario['intents'] = [by_id[key] for key in retained]
        waits = scenario['windowsWaits']
        scenario['windowsWaits'] = {key: waits[key] for key in retained if key in waits}
        for item in scenario['intents']:
            old = item['intentId']
            new = old.replace('heavy', 'flame')
            item['intentId'] = new
            if old != new and old in scenario['windowsWaits']:
                scenario['windowsWaits'][new] = scenario['windowsWaits'].pop(old)
        by_id['produce-heavy']['typeId'] = 'mechFlame'
        by_id['attack-ground'].update(waitSteps=150, targetUnitId='b', targetGroundPosition=dict(x=500, y=310))
        scenario['windowsWaits'].pop('attack-ground')
        history = dict(
            ownerPlayerId='p1', initialBalance=18000, initialOwnedTypes=['builder'],
            spend=[
                dict(intentId='build-t1', amount=1000, at='construction-created'),
                dict(intentId='upgrade-t2', amount=4000, at='action'),
                dict(intentId='produce-flame', amount=12000, at='action'),
            ],
            mechLegs={'mechFlame': 2, 'mechHeavyMissile': 4},
            productionOrder=['mechFlame'], completedUpgradeTypes=['mechFactoryT2'], projectileBirthsRequired=True,
            minimumBirths=[dict(ownerPlayerId='p1', typeId='mechFlame', weaponNames=[name], targetUnitId=None)
                           for name in ('2', '3')],
            finalUnits=[dict(ownerPlayerId='p1', typeId=name, fields=dict(alive=True))
                        for name in ('builder', 'mechFactoryT2', 'mechFlame')],
        )
        contract = dict(schemaVersion=1, scenarioId=identifier, history=history,
                        rules=[dict(intentId=item['intentId']) for item in scenario['intents']])
        for group, document in [('scenarios', scenario), ('contracts', contract)]:
            path = PLATFORM / group / f'{identifier}.json'
            with path.open('x', encoding='utf8', newline='\n') as out:
                json.dump(document, out, indent=2)
                out.write('\n')


if __name__ == '__main__':
    generate()
