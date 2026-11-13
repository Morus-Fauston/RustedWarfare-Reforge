"""Write the owned normal bomber chain and its independently specified economy."""
import json
from pathlib import Path

PLATFORM = Path(__file__).resolve().parents[1] / 'public/platform'


def read(category, format):
    return json.loads((PLATFORM / category / f'aa-beam-{format}-production-chain.json').read_text(encoding='utf8'))


def write(category, identifier, document):
    with (PLATFORM / category / f'{identifier}.json').open('w', encoding='utf8', newline='\n') as stream:
        json.dump(document, stream, indent=2)
        stream.write('\n')


def generate():
    for format in ('ini', 'json', 'hjson'):
        identifier = f'bomber-{format}-production-chain'
        plan = read('scenarios', format)
        plan.update(scenarioId=identifier, inputs=f'../../inputs/bomber-production/{format}')
        plan['contentPack']['directory'] = 'package'
        intents = plan['intents'][:13]

        def intent(name, kind, wait=0, point=None, **fields):
            if point is not None:
                fields['position'] = dict(x=point[0], y=point[1])
            return dict(intentId=name, kind=kind, waitSteps=wait, **fields)

        intents += [intent('produce-air', 'produce', 600, typeId='bomber')]
        intents += [intent(f'wait-air-{index}', 'select', 600, (270, 150)) for index in range(1, 8)]
        intents += [
            intent('select-air', 'select', point=(269, 270)),
            intent('attack-ground', 'attack', 540, (350, 530), targetUnitId='b', targetGroundPosition=dict(x=350, y=530)),
            intent('stop-air', 'stop', 40),
            intent('return-home', 'move', 530, (300, 250)),
            intent('stop-home', 'stop', 320),
        ]
        plan['intents'] = intents
        plan['waitPlan'] = {item['intentId']: dict(minimumSteps=item['waitSteps'], timeoutSeconds=240)
                            for item in intents if item['waitSteps']}
        contract = read('contracts', format)
        contract['scenarioId'] = identifier
        contract['rules'] = [dict(intentId=item['intentId']) for item in intents]
        history = contract['history']
        history['productionOrder'] = ['builder', 'bomber']
        history['spend'][-1]['amount'] = 4000
        history['finalUnits'][-1].update(typeId='bomber', fields=dict(alive=True, height=2))
        write('scenarios', identifier, plan)
        write('contracts', identifier, contract)


if __name__ == '__main__':
    generate()
