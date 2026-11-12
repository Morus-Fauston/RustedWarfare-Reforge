"""Generate the fixed normal spider scenarios for all supported formats."""
import argparse
import json
from pathlib import Path


PLATFORM = Path(__file__).resolve().parents[1] / 'public/platform'


def intent(identity, kind, wait, **fields):
    return dict(intentId=identity, kind=kind, waitSteps=wait, **fields)


def generate(weapon_modules=False, destination=PLATFORM, *, special_weapons=False):
    for form in ('ini', 'json', 'hjson'):
        case = 'special-weapons' if special_weapons else 'weapon-modules' if weapon_modules else 'opening'
        identity = f'modular-spider-{form}-{case}'
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
        if special_weapons:
            steps[2:] = [
                intent('leave-weapon-range', 'move', 500, position=dict(x=54, y=170)),
                intent('select-slot-1', 'unit-action', 0, actionName='selectSlot1'),
                intent('buy-artillery', 'unit-action', 600, actionName='buildArtillery'),
                *(intent(f'earn-lightning-{index}', 'stop', 600) for index in range(1, 13)),
                intent('select-slot-2', 'unit-action', 0, actionName='selectSlot2'),
                intent('buy-lightning', 'unit-action', 600, actionName='buildLightning'),
                intent('finish-lightning', 'stop', 450),
                intent('bring-both-mounts-into-range', 'move', 450, position=dict(x=190, y=150)),
                intent('hold-fire-position', 'stop', 180),
            ]
            history.pop('spiderOpening')
            history.update(
                spend=[dict(intentId='buy-artillery', amount=5000, at='action'),
                       dict(intentId='buy-lightning', amount=6000, at='action')],
                minimumBirths=[
                    dict(ownerPlayerId='p1', typeId='modularSpider_artillery', weaponNames=['mainCannon'], targetUnitId='b'),
                    dict(ownerPlayerId='p1', typeId='modularSpider_lightning', weaponNames=['1'], targetUnitId='b'),
                ],
                finalUnits=[dict(ownerPlayerId='p1', typeId=unit, fields=dict(alive=True, constructionProgress=1))
                            for unit in ('modularSpider', 'modularSpider_artillery', 'modularSpider_lightning')],
            )
        elif weapon_modules:
            steps[3:] = [
                intent('buy-aa', 'unit-action', 12, actionName='buildAntiair'),
                intent('cancel-aa', 'unit-action', 130, actionName='cancel'),
                intent('buy-laser', 'unit-action', 12, actionName='buildSmallLaser'),
                intent('queue-first-upgrade', 'unit-action', 24, actionName='upgradeT2'),
                intent('withdraw-upgrade', 'unit-action', 6, actionName='upgradeT2', cancelQueued=True),
                intent('queue-laser-t2', 'unit-action', 600, actionName='upgradeT2'),
                intent('move-out-of-range', 'move', 600, position=dict(x=104, y=150)),
                intent('stop-to-finish-construction', 'stop', 600),
                intent('stop-to-finish-upgrade', 'stop', 180),
                intent('attack-ground', 'attack', 510, position=dict(x=465, y=150), targetUnitId='b'),
            ]
            history.pop('spiderOpening')
            history.update(spiderWeaponModules=True, spend=[
                dict(intentId=buy, amount=amount, at='action')
                for buy, amount in (('buy-aa', 2000), ('buy-laser', 700), ('queue-first-upgrade', 3000),
                                    ('withdraw-upgrade', 3000), ('queue-laser-t2', 3000))
            ], minimumBirths=[dict(ownerPlayerId='p1', typeId='modularSpider_smallgunturretT2',
                weaponNames=['smallLaser1'], targetUnitId='b')])
            history['finalUnits'][1]['typeId'] = 'modularSpider_smallgunturretT2'
        contract = dict(schemaVersion=1, scenarioId=identity, history=history,
                        rules=[dict(intentId=item['intentId']) for item in steps])
        for group, document in [('scenarios', scenario), ('contracts', contract)]:
            with (destination / group / f'{identity}.json').open('x', encoding='utf8', newline='\n') as out:
                json.dump(document, out, indent=2)
                out.write('\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    cases = parser.add_mutually_exclusive_group()
    cases.add_argument('--weapon-modules', action='store_true')
    cases.add_argument('--special-weapons', action='store_true')
    arguments = parser.parse_args()
    generate(arguments.weapon_modules, special_weapons=arguments.special_weapons)
