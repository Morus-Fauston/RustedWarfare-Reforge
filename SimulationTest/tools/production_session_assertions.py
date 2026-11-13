"""Additional observations consumed from the same verified production Session archive."""


def validate_production_session(records, family, error_type):
    def require(condition, message):
        if not condition:
            raise error_type('production-session', message, 2)

    if family not in ('amphibious', 'air-transport', 'transport', 'aa-afterburn'):
        raise error_type('input-contract', 'Unknown production Session assertions', 1)
    stages, actions = {}, {}
    observations = []
    active, sample = None, None
    for row in records:
        if row['kind'] == 'intent-start':
            active = row['intentId']
        elif row['kind'] == 'observation':
            sample = row['sample']
            observations.append((active, sample))
        elif row['kind'] == 'action':
            actions[active] = row
        elif row['kind'] == 'intent-end':
            stages[row['intentId']] = sample
    require(stages.keys() == actions.keys(), 'Every production intent must deliver an action')

    def owned(stage, type_id):
        selected = [u for u in stages[stage]['units'] if u['ownerPlayerId'] == 'p1' and u['typeId'] == type_id]
        require(len(selected) == 1, f'{stage} requires one owned {type_id}')
        return selected[0]

    if family == 'aa-afterburn':
        aa_id = owned('wait-air-3', 'aaBeamGunship')['unitId']
        full_health = owned('wait-air-3', 'aaBeamGunship')['maxHealth']
        approach = owned('approach-air', 'aaBeamGunship')
        home = owned('stop-home', 'aaBeamGunship')
        require(full_health == 2500 and approach['position']['x'] > 400
                and home['position']['x'] < 350 and home['moveTarget'] is None,
                'AA health, movement or stop differs')
        locked = full = refired = automatic = False
        prefix = set(list(stages)[:list(stages).index('stop-home')+1])
        for stage, observation in observations:
            aircraft = [u for u in observation['units'] if u['ownerPlayerId'] == 'p1'
                        and u['typeId'] in ('aaBeamGunship', 'aaBeamGunship_afterburn')]
            if not aircraft:
                continue
            require(len(aircraft) == 1 and aircraft[0]['unitId'] == aa_id, 'AA conversion replaced the product')
            unit = aircraft[0]
            energy = unit['energy']
            if stage in prefix and unit['typeId'] == 'aaBeamGunship':
                locked |= energy['recharging'] is True
                full |= locked and energy['recharging'] is False and energy['current'] == 6
                refired |= full and energy['current'] < 6
            if stage == 'automatic-recovery' and any(e['kind'] == 'unit-action-automatic' and e.get('unitId') == aa_id
                                                     for e in observation['events']):
                automatic = True
                require(unit['typeId'] == 'aaBeamGunship' and unit['unitActions']['automaticCooldown'] == 60
                        and energy['current'] == 6 and unit['moveTarget']['x'] == 750 and unit['moveTarget']['y'] == 50,
                        'AA recovery changed energy, timer or movement')
        require(locked and full and refired and automatic, 'AA recharge, refire or automatic recovery missing')
        require(owned('burn-move', 'aaBeamGunship_afterburn')['position']['x'] < 200
                and owned('final-stop', 'aaBeamGunship')['moveTarget'] is None, 'Afterburn movement or stop differs')
        return

    require([u['typeId'] for u in stages['select-center']['units'] if u['ownerPlayerId'] == 'p1']
            == ['commandCenter'], 'Production must start with only the command center')
    if family == 'amphibious':
        factory = owned('finish-t1', 'airFactory')
        require(factory['constructionProgress'] == 1
                and factory['unitId'] == owned('finish-upgrade', 'airFactoryT2')['unitId']
                and owned('finish-jet', 'amphibiousJet')['maxHealth'] == 530, 'Amphibious factory or product changed')
        def jet(stage):
            return owned(stage, 'amphibiousJet')
        require(jet('fly-water')['height'] > 18 and jet('dive')['height'] == -8 and jet('fly')['height'] > 18,
                'Amphibious flight or dive height differs')
        require(jet('move-shore')['amphibiousJet']['movementType'] == 'WATER'
                and jet('move-shore')['position']['x'] > 350 and jet('cross-shore')['position']['x'] < 350,
                'Amphibious shore crossing differs')
        require(jet('stop-flight')['moveTarget'] is None and jet('stop-final')['height'] == -8
                and jet('stop-final')['moveTarget'] is None
                and [actions[s]['flying'] for s in ('dive', 'fly', 'dive-again')] == [False, True, False],
                'Amphibious toggle or stop differs')
        for identity, health in (('c', 150), ('d', 1000)):
            target = [u for u in stages['stop-final']['units'] if u['unitId'] == identity]
            require(len(target) == 1 and target[0]['health'] < health, 'Amphibious damage missing: '+identity)
        return

    air = family == 'air-transport'
    type_id = 'dropship' if air else 'hovercraft'
    factory_stage, factory = ('finish-t1', 'airFactory') if air else ('finish-factory', 'seaFactory')
    birth = 'finish-air' if air else 'produce-craft'
    require(owned(factory_stage, factory)['constructionProgress'] == 1
            and owned(birth, type_id)['maxHealth'] == (500 if air else 450), 'Transport factory or product changed')
    require(not any(u['typeId'] == 'builder' for u in stages['board']['units'])
            and owned('cross-water', type_id)['position']['x'] > 400
            and owned('return-land', type_id)['position']['x'] < 350
            and owned('stop-land', type_id)['moveTarget'] is None, 'Transport boarding, shore crossing or stop differs')
    worker = owned('resume-unload', 'builder')
    require(worker['health'] == 170 and actions['board']['direction'] == 'UP'
            and [actions[s]['unloading'] for s in ('start-unload', 'cancel-unload', 'resume-unload')]
            == [True, False, True], 'Transport unload did not preserve the worker or command sequence')
    if air:
        final = owned('stop-released', 'builder')
        require(worker['unitId'] == actions['move-released']['unitId'] and final['moveTarget'] is None
                and final['position']['x'] < worker['position']['x'] and final['position']['y'] > worker['position']['y'],
                'Released transport worker did not move and stop')
