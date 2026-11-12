"""Generate the same fixed normal spider opening for all supported formats."""
import json
from pathlib import Path


PLATFORM = Path(__file__).resolve().parents[1] / 'public/platform'


def intent(identity, kind, wait, **fields):
    return dict(intentId=identity, kind=kind, waitSteps=wait, **fields)


def generate():
    for form in ('ini', 'json', 'hjson'):
        identity = f'modular-spider-{form}-opening'
        steps = [
            intent('settle-opening', 'select', 100, position=dict(x=40, y=40)),
            intent('select-spider', 'select', 0, position=dict(x=190, y=150)),
            intent('select-slot-1', 'unit-action', 0, actionName='selectSlot1'),
            intent('buy-gun', 'unit-action', 12, actionName='buildGun'),
            intent('cancel-gun', 'unit-action', 130, actionName='cancel'),
            intent('rebuild-gun', 'unit-action', 600, actionName='buildGun'),
            intent('attack-ground', 'attack', 450, position=dict(x=465, y=150), targetUnitId='b'),
            intent('move-spider', 'move', 280, position=dict(x=285, y=150)),
            intent('stop-spider', 'stop', 20),
        ]
        scenario = dict(schemaVersion=1, scenarioId=identity, inputs=f'../../inputs/modular-spider-start/{form}',
                        contentPack=dict(directory='opening-package', unitId='modularSpider'), intents=steps)
        history = dict(
            ownerPlayerId='p1', initialBalance=5000, noActionIntents=['settle-opening'],
            initialOwnedTypes=['modularSpider', *(['modularSpider_emptySlot'] * 6)],
            initialIncome=dict(unitId='a', typeId='modularSpider', period=40, credits=30),
            spend=[dict(intentId=buy, amount=2000, at='action') for buy in ('buy-gun', 'rebuild-gun')],
            mechLegs={'modularSpider': 8, 'mechGun': 2}, spiderOpening=True, projectileBirthsRequired=True,
            minimumBirths=[dict(ownerPlayerId='p1', typeId='modularSpider_gunturret', weaponNames=['1'], targetUnitId='b')],
            finalUnits=[dict(ownerPlayerId='p1', typeId=unit, fields=dict(alive=True))
                        for unit in ('modularSpider', 'modularSpider_gunturret')],
        )
        contract = dict(schemaVersion=1, scenarioId=identity, history=history,
                        rules=[dict(intentId=item['intentId']) for item in steps])
        for group, document in [('scenarios', scenario), ('contracts', contract)]:
            with (PLATFORM / group / f'{identity}.json').open('x', encoding='utf8', newline='\n') as out:
                json.dump(document, out, indent=2)
                out.write('\n')


if __name__ == '__main__':
    generate()
