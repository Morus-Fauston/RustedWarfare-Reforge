"""Independent replay checks for the normal Modular Spider opening."""
import math
import struct


CHASSIS = 'modularSpider'
EMPTY = 'modularSpider_emptySlot'
GUN = 'modularSpider_gunturret'
LASER = 'modularSpider_smallgunturret'
LASER_T2 = 'modularSpider_smallgunturretT2'
AA = 'modularSpider_antiair'
FABRICATOR = 'modularSpider_fabricator'
FABRICATOR_T2 = 'modularSpider_fabricatorT2'
SHIELD = 'modularSpider_shieldGen'
SPEED_INCOMPLETE = 'modularSpider_speedIncomplete'
SPEED = 'modularSpider_speed'
LASER_DEFENSE = 'modularSpider_laserdefense'
INTERCEPTOR = 'modularSpider_antinuke'
BLINK = 'modularSpider_blink'
AMMO = 'buildAntiNuke'
SWITCHES = ('isEnabled', 'isDisabled')
SLOTS = ('selectSlotBuild', *(f'selectSlot{i}' for i in range(1, 7)))
PAID = ('buildGun', 'buildSmallLaser', 'buildAntiair', 'buildLightning', 'buildArtillery',
        'buildFabricator', 'buildShieldGen', 'buildSpeed', 'upgradeT2', 'upgradeFlak',
        'buildLaserdefense', 'buildAntinuke', 'buildBlink', AMMO)
CANCELLABLE = (*PAID, 'reclaim', 'blink')
ACTIONS = (*SLOTS, *CANCELLABLE, 'cancel', 'blinkQueued', *SWITCHES)
OFFSETS = ((-11, 32, 0), (11, 32, 0), (-11, 6, -90), (11, 6, 90), (-10, -20, 180), (11, -20, 180))


def check(condition, message):
    if not condition:
        from platform_scenario import PlatformError
        raise PlatformError('history-spider', message, 2)


def blink_point(point):
    return {axis: struct.unpack('f', struct.pack('f', value))[0] for axis, value in point.items()}


def validate_state(unit, owner):
    actions = unit.get('unitActions')
    layout = unit.get('attachmentOverview')
    if unit.get('ownerPlayerId') != owner or unit.get('alive') is not True:
        check(actions is None and layout is None, 'Another player’s or dead chassis exposed slot controls')
        return
    check(isinstance(layout, dict) and set(layout) == {'selectedSlot', 'slots'}, 'Missing chassis slot overview')
    check(layout['selectedSlot'] is None or type(layout['selectedSlot']) is int and 1 <= layout['selectedSlot'] <= 6,
          'Invalid selected slot')
    selected = next((slot for slot in layout['slots'] if slot['number'] == layout['selectedSlot']), None)
    interceptor = selected is not None and selected['typeId'] == INTERCEPTOR
    check(isinstance(actions, dict), 'Invalid slot controls')
    queue = actions.get('queue', [])
    fields = {'actions', 'automaticCooldown'}
    if interceptor:
        fields |= {'stockpile', 'queue', 'interceptorOnline'}
    if queue:
        fields.add('queue')
    check(set(actions) == fields and isinstance(queue, list) and
          actions['automaticCooldown'] == 0 and isinstance(actions['actions'], dict), 'Invalid slot controls')
    check(set(SLOTS) <= set(actions['actions']) <= set(ACTIONS), 'Unknown or missing slot controls')
    for item in queue:
        check(isinstance(item, dict) and set(item) == {'name', 'progress', 'target'} and
              type(item['progress']) in (int, float) and 0 <= item['progress'] < 1, 'Invalid slot order')
        if item['name'] in ('blink', 'blinkQueued'):
            point = item['target']
            check(isinstance(point, dict) and set(point) == {'x', 'y'} and
                  all(type(value) in (int, float) and math.isfinite(value) for value in point.values()),
                  'Invalid Blink target')
    if interceptor:
        stock, queue, online = actions['stockpile'], actions['queue'], actions['interceptorOnline']
        check(type(stock) is int and 0 <= stock <= 12 and isinstance(queue, list) and type(online) is bool,
              'Invalid module stock, queue or switch')
        switch = 'isEnabled' if online else 'isDisabled'
        check(AMMO in actions['actions'] and set(SWITCHES) & actions['actions'].keys() == {switch},
              'Current module switch and ammo cards must match its state')
        check(all(item['target'] is None and item['name'] in (AMMO, 'reclaim', switch) or
                  item['name'] == 'blinkQueued' for item in queue) and
              stock + sum(item['name'] == AMMO for item in queue) <= 12, 'Invalid ordered module queue')
    for name, action in actions['actions'].items():
        check(isinstance(action, dict) and set(action) == {'queueIndex', 'progress', 'cooldownRemaining', 'available'} and
              type(action['available']) is bool and type(action['cooldownRemaining']) is int and
              0 <= action['cooldownRemaining'] <= (30000 if name == 'blink' else 2000) and
              (action['queueIndex'] is None or type(action['queueIndex']) is int and
               0 <= action['queueIndex'] < len(queue) and queue[action['queueIndex']]['name'] == name and
               queue[action['queueIndex']]['progress'] == action['progress']) and
              (action['progress'] is None or type(action['progress']) in (int, float) and 0 <= action['progress'] <= 1),
              f'Invalid slot action state for {name}')


def validate_call(old, new, row, intent, input_limits):
    name = intent['actionName']
    cancelling = intent.get('cancelQueued', False)
    point = row.get('target')
    expected = intent.get('target')
    target_matches = point is None and expected is None or name == 'blink' and not cancelling and (
        isinstance(point, dict) and set(point) == {'x', 'y'} and
        all(type(point[axis]) in (int, float) and math.isfinite(point[axis]) and
            abs(point[axis] - expected[axis]) <= input_limits[axis] for axis in ('x', 'y')))
    check(name in ACTIONS and row.get('actionName') == name and row.get('cancelQueued') is cancelling and target_matches,
          'Chassis call does not match the declared normal slot action')
    left = old['attachmentOverview']
    right = new['attachmentOverview']
    check(left['slots'] == right['slots'], 'The call synchronously changed slot identities or construction')
    old_action = old['unitActions']['actions'].get(name)
    if name == 'blink':
        before, after = old['unitActions'].get('queue', []), new['unitActions'].get('queue', [])
        check(left['selectedSlot'] is not None and left['selectedSlot'] == right['selectedSlot'] and
              left['slots'][left['selectedSlot'] - 1]['typeId'] == BLINK, 'Blink was not issued by the selected module')
        if cancelling:
            check(any(item['name'] == name for item in before) and
                  after == [item for item in before if item['name'] != name], 'Blink did not cancel the queued target')
        else:
            slot_index = next((index for index, item in enumerate(before) if item['name'] == 'reclaim'), len(before))
            check(old_action and old_action['available'] and point is not None and
                  after == before[:slot_index] + [dict(name=name, progress=0, target=blink_point(point))] +
                  before[slot_index:],
                  'Blink did not queue its accepted target')
        return
    if name in (AMMO, *SWITCHES) or name == 'reclaim' and 'interceptorOnline' in old['unitActions']:
        before, after = old['unitActions'], new['unitActions']
        check(left['selectedSlot'] is not None and left['selectedSlot'] == right['selectedSlot'] and
              before['stockpile'] == after['stockpile'] and before['interceptorOnline'] == after['interceptorOnline'],
              'An interceptor command changed inventory or left its selected slot at submission')
        expected = list(before['queue'])
        if cancelling:
            check(name not in SWITCHES and any(item['name'] == name for item in expected),
                  'Only a matching paid round or reclaim may be cancelled')
            del expected[max(i for i, item in enumerate(expected) if item['name'] == name)]
        else:
            check(old_action and old_action['available'], 'Interceptor command was unavailable')
            first_slot_item = int(bool(expected and expected[0]['name'] == 'blinkQueued'))
            expected.insert(first_slot_item if name in SWITCHES else len(expected), dict(name=name, progress=0, target=None))
        check(after['queue'] == expected, 'The interceptor command did not update the matching queue item')
        return
    before_parent = [item for item in old['unitActions'].get('queue', []) if item['name'] == 'blinkQueued']
    after_parent = [item for item in new['unitActions'].get('queue', []) if item['name'] == 'blinkQueued']
    check(before_parent == after_parent, 'A slot call changed the pending chassis Blink')
    before = old['unitActions'].get('queue', [])
    after = new['unitActions'].get('queue', [])
    if cancelling:
        slot_index = old_action.get('queueIndex') if old_action else None
        new_action = new['unitActions']['actions'].get(name)
        check(name in CANCELLABLE and left['selectedSlot'] is not None and right['selectedSlot'] == left['selectedSlot'] and
              type(slot_index) is int and len(before_parent) <= slot_index < len(before) and
              new_action is not None and new_action.get('queueIndex') is None and new_action.get('progress') is None and
              before[slot_index]['name'] == name and
              after == before[:slot_index] + before[slot_index+1:],
              'Right-click must remove the matching order from the same selected slot')
        return
    check(old_action is not None and old_action.get('available') is True, 'The requested slot action was unavailable')
    if name in SLOTS:
        expected = None if name == 'selectSlotBuild' else int(name.removeprefix('selectSlot'))
        check(right['selectedSlot'] == expected, 'The requested slot page did not become active')
        return
    check(left['selectedSlot'] is not None and right['selectedSlot'] == left['selectedSlot'],
          'A module action must execute through the selected slot')
    new_action = new['unitActions']['actions'].get(name)
    check(new_action is not None and new_action.get('available') is False, 'The module order was not admitted')
    if name != 'cancel':
        check(new_action.get('queueIndex') == len(before) and new_action.get('progress') == 0 and
              after == [*before, dict(name=name, progress=0, target=None)],
              'Module order did not enter its slot queue')


def validate_blink_history(records):
    slots = origin = target = queued_at = None
    teleports = 0
    for row in records:
        if row.get('kind') == 'action' and row.get('actionName') == 'blink' and not row['cancelQueued']:
            target = blink_point(row['target'])
        if row.get('kind') != 'observation' or row.get('phase') not in ('initial', 'step'):
            continue
        sample = row['sample']
        units = {unit['unitId']: unit for unit in sample['units']}
        parent = units['a']
        overview = parent['attachmentOverview']
        actions = parent['unitActions']
        identities = [slot['unitId'] for slot in overview['slots']]
        if slots is None:
            slots = identities
            check(len(slots) == len(set(slots)) == 6, 'Expected six independent Blink attachment slots')
        check(identities == slots, 'Blink changed a slot identity')
        event = any(event['kind'] == 'teleported' and event['unitId'] == 'a' for event in sample['events'])
        charge = next((item for item in actions.get('queue', []) if item['name'] == 'blinkQueued'), None)
        if charge:
            if queued_at is None:
                origin = parent['position']
                queued_at = sample['stepIndex']
                check(charge['progress'] == 0 and charge['target'] == target, 'Chassis did not keep the child target')
            check(parent['position'] == origin and not event and
                  abs(charge['progress'] - (sample['stepIndex'] - queued_at) / 210) < 1e-6,
                  'Charging moved the chassis or used a different independent clock')
        if event:
            teleports += 1
            check(queued_at is not None and sample['stepIndex'] - queued_at == 210 and charge is None and
                  parent['position'] == target and parent['position'] != origin,
                  'Blink completion must write the accepted point after 210 steps')
            angle = math.radians(parent['heading'])
            for identity, (x, y, rotation) in zip(slots, OFFSETS, strict=True):
                child = units[identity]
                point = child['position']
                check(abs(point['x'] - (target['x'] + math.sin(angle) * y + math.cos(angle) * x)) < 1e-6 and
                      abs(point['y'] - (target['y'] - math.cos(angle) * y + math.sin(angle) * x)) < 1e-6 and
                      child['height'] == parent['height'] and abs((child['heading'] - parent['heading'] - rotation + 180) % 360 - 180) < 1e-6,
                      'Blink did not move the six existing slots with the chassis')
    check(teleports == 1, 'Expected exactly one completed chassis Blink')


def mixed_adjustments(records):
    validate_blink_history(records)
    stages = (
        (1, 'attachment-install-started', LASER),
        (2, 'attachment-install-started', LASER_DEFENSE),
        (1, 'attachment-install-completed', LASER),
        (2, 'attachment-install-completed', LASER_DEFENSE),
        (3, 'attachment-install-started', BLINK),
        (3, 'attachment-install-completed', BLINK),
        (1, 'attachment-slot-emptied', EMPTY),
        (1, 'attachment-install-started', GUN),
        (1, 'attachment-install-completed', GUN),
    )
    actual = []
    refunds = {}
    charging_with_order = False
    cancelled_during_charge = False
    kinds = {kind for _, kind, _ in stages}
    slots = []
    for row in records:
        if row.get('kind') != 'observation':
            continue
        sample = row['sample']
        parent = next(unit for unit in sample['units'] if unit['unitId'] == 'a')
        slots = parent['attachmentOverview']['slots']
        by_id = {slot['unitId']: slot for slot in slots}
        for event in sample['events'] if row['phase'] == 'step' else []:
            slot = by_id.get(event['unitId'])
            if slot is None or event['kind'] not in kinds:
                continue
            actual.append((slot['number'], event['kind'], slot['typeId']))
            if event['kind'] == 'attachment-slot-emptied':
                refunds[row['sequence']] = 350
        queue = [item['name'] for item in parent['unitActions'].get('queue', [])]
        charging_with_order |= queue == ['blinkQueued', 'upgradeT2']
        cancelled_during_charge |= charging_with_order and row['phase'] == 'after-action' and queue == ['blinkQueued']
    check(tuple(actual) == stages and len(refunds) == 1, 'Mixed loadout did not replace only the reclaimed gun')
    check(charging_with_order and cancelled_during_charge,
          'Cancelling a paid slot order must preserve the active chassis charge')
    check([slot['typeId'] for slot in slots] == [GUN, LASER_DEFENSE, BLINK, EMPTY, EMPTY, EMPTY] and
          all(slot['progress'] == 1 for slot in slots), 'Mixed loadout final slot layout differs')
    return refunds


def opening_refunds(records, weapon_modules=False):
    opening_type, settled_type = (LASER, LASER_T2) if weapon_modules else (GUN, GUN)
    cancelled_type, refund = (AA, 1000) if weapon_modules else (GUN, 2000)
    install_started = 'attachment-install-started'
    install_completed = 'attachment-install-completed'
    emptied = 'attachment-slot-emptied'
    upgraded = 'attachment-upgrade-completed'
    expected = [
        (install_started, cancelled_type, 0), (emptied, EMPTY, 1),
        (install_started, opening_type, 0), (install_completed, opening_type, 1),
    ]
    if weapon_modules:
        expected.append((upgraded, settled_type, 1))
    stages = []
    refunds = {}
    identities = None
    opening_height = None
    moved = False
    fired = False
    waited = False
    origin = None
    for row in records:
        if row.get('kind') != 'observation':
            continue
        sample = row['sample']
        units = {unit['unitId']: unit for unit in sample['units']}
        parents = [unit for unit in units.values() if unit['typeId'] == CHASSIS and unit['ownerPlayerId'] == 'p1']
        check(len(parents) == 1, 'The same single owned normal chassis must remain in the opening')
        parent = parents[0]
        layout = parent.get('attachmentOverview')
        check(parent['alive'] is True and isinstance(layout, dict) and isinstance(layout.get('slots'), list),
              'The normal chassis is missing its slot observation')
        slots = layout['slots']
        check(len(slots) == 6 and [slot['number'] for slot in slots] == list(range(1, 7)), 'Expected six numbered slots')
        slot_ids = [slot['unitId'] for slot in slots]
        center = parent['position']
        if identities is None:
            check(row.get('phase') == 'initial' and len(set(slot_ids)) == 6 and parent['height'] == 170 and
                  parent['heading'] == 90 and all(slot['typeId'] == EMPTY for slot in slots),
                  'The initial world did not create the falling chassis and its six empty slots')
            identities = slot_ids
            opening_height = parent['height']
            origin = center
        check(slot_ids == identities, 'An attachment acquired a different unit identity during normal conversion')
        moved |= math.hypot(center['x'] - origin['x'], center['y'] - origin['y']) > 25
        angle = math.radians(parent['heading'])
        for slot, (x, y, heading) in zip(slots, OFFSETS):
            child = units.get(slot['unitId'])
            check(child is not None and child['typeId'] in (EMPTY, cancelled_type, opening_type, settled_type) and
                  child['ownerPlayerId'] == 'p1' and
                  child['typeId'] == slot['typeId'] and child['constructionProgress'] == slot['progress'] and
                  child.get('attachment') == dict(parentUnitId=parent['unitId'], slot=slot['number'],
                                                   empty=child['typeId'] == EMPTY) and
                  child.get('carriedBy') is None and child.get('transport') is None,
                  'A slot does not bind the reported owned attached entity')
            expected_x = center['x'] + math.sin(angle) * y + math.cos(angle) * x
            expected_y = center['y'] - math.cos(angle) * y + math.sin(angle) * x
            heading_error = (child['heading'] - parent['heading'] - heading + 180) % 360 - 180
            check(abs(child['position']['x'] - expected_x) < 1e-6 and abs(child['position']['y'] - expected_y) < 1e-6 and
                  child['height'] == parent['height'] and abs(heading_error) < 1e-6,
                  'Attached pose differs from the rotated local slot')
        if row.get('phase') != 'step':
            continue
        if opening_height == 170:
            check(abs(parent['height'] - 168.47) < .001, 'Opening drop does not use the fixed first update')
        opening_height = parent['height']
        first = units[identities[0]]
        if weapon_modules and first['typeId'] == LASER and first['constructionProgress'] < 1:
            queued = parent['unitActions']['actions'].get('upgradeT2')
            if queued and queued['queueIndex'] is not None:
                check(queued['progress'] == 0 and not queued['available'], 'Paid upgrade ran before its module was built')
                waited = True
        for event in sample['events']:
            if event.get('kind') not in (install_started, emptied, install_completed, upgraded):
                continue
            check(event.get('unitId') == identities[0], 'The observed order ran on a different slot')
            stage = event['kind']
            stages.append((stage, first['typeId'], first['constructionProgress']))
            if stage == emptied:
                check(parent['unitActions']['actions']['buildGun']['cooldownRemaining'] == 2000,
                      'Cancelled module did not return the same slot to empty with the fixed cooldown')
                refunds[row['sequence']] = refund
        for event in sample.get('projectileEvents', []):
            if event.get('kind') != 'born':
                continue
            check(event.get('sourceUnitId') != parent['unitId'], 'The chassis fired instead of its module')
            if event.get('sourceUnitId') == identities[0]:
                check(first['constructionProgress'] == 1 and
                      event.get('sourceWeaponName') == ('smallLaser1' if weapon_modules else '1'),
                      'An unfinished module fired or used a different weapon')
                fired |= first['typeId'] == settled_type
    check(stages == expected and len(refunds) == 1 and fired and moved and opening_height == 0 and
          (not weapon_modules or waited),
          'Opening must settle, buy, cancel, replace and fire from the same real slot')
    return refunds


def utility_income(slots, phases):
    rates = {FABRICATOR: 5, FABRICATOR_T2: 10}
    payout = 0
    for slot in slots:
        rate = rates.get(slot['typeId'])
        if rate is None or slot['progress'] != 1:
            continue
        identity = slot['unitId']
        phases[identity] = (phases.get(identity, 0) + 1) % 40
        if phases[identity] == 0:
            payout += rate
    return payout


def defense_adjustments(records):
    """Check the normal fixed defense composition and its independently calculated slot income."""
    from nuclear_platform import f32

    expected = [FABRICATOR, LASER_DEFENSE, INTERCEPTOR, EMPTY, EMPTY, EMPTY]
    slot_ids = None
    phases, credits = {}, {}
    previous = None
    ammo_progress = None
    last_state = None
    deflections = moving_deflections = 0
    births = {}
    toggles = []
    saw_production_while_offline = False
    for row in records:
        if row.get('kind') != 'observation':
            continue
        sample = row['sample']
        units = {unit['unitId']: unit for unit in sample['units']}
        parent = units.get('a')
        check(parent and parent['alive'] and parent['typeId'] == CHASSIS, 'Defense replay lost its own chassis')
        layout = parent['attachmentOverview']
        slots = layout['slots']
        ids = [slot['unitId'] for slot in slots]
        if slot_ids is None:
            check(len(set(ids)) == 6 and all(slot['typeId'] == EMPTY for slot in slots), 'Expected six original empty slots')
            slot_ids = ids
        check(ids == slot_ids, 'A defense module left its original slot')
        for slot, qualified in zip(slots, expected):
            child = units[slot['unitId']]
            check(slot['typeId'] in (EMPTY, qualified) and child['typeId'] == slot['typeId'] and
                  child['constructionProgress'] == slot['progress'] and child['ownerPlayerId'] == 'p1',
                  'An attachment does not match its fixed defense slot')
        if row['phase'] == 'step':
            payout = utility_income(slots, phases)
            if payout:
                credits[row['sequence']] = payout
            laser = units[slot_ids[1]]
            for event in sample['projectileEvents']:
                if event['kind'] == 'born':
                    births[event['token']] = sample['stepIndex']
            matches = [event for event in sample['projectileEvents'] if event['kind'] == 'deflected']
            if matches:
                check(len(matches) == 1 and matches[0]['interceptorUnitId'] == slot_ids[1] and
                      matches[0]['sourceUnitId'] == 'b' and laser['typeId'] == LASER_DEFENSE and
                      laser['constructionProgress'] == 1 and matches[0]['token'] in births and
                      sample['stepIndex'] - births[matches[0]['token']] >= 8,
                      'Ordinary fire must be deflected by the ready owned laser module')
                deflections += 1
                moving_deflections += abs(parent['speed']) > .05
            state = parent.get('unitActions', {})
            if layout['selectedSlot'] == 3 and slots[2]['typeId'] == INTERCEPTOR:
                online = state['interceptorOnline']
                if last_state is None or online != last_state:
                    toggles.append(online)
                last_state = online
                rounds = [item['progress'] for item in state['queue'] if item['name'] == AMMO]
                if rounds and not online and 0 < rounds[0] < 1 and previous is not None:
                    if ammo_progress is not None and rounds[0] > ammo_progress:
                        check(abs(rounds[0] - f32(f32(ammo_progress) + f32(.0007))) < .000001,
                              'Paid offline round differs from the independent production rate')
                    saw_production_while_offline |= abs(parent['speed']) > .05
                ammo_progress = rounds[0] if rounds else None
            previous = parent
    check([slot['typeId'] for slot in slots] == expected and all(slot['progress'] == 1 for slot in slots) and
          deflections > 0 and moving_deflections > 0 and toggles == [True, False, True] and
          saw_production_while_offline and state['stockpile'] == 1 and not state['queue'],
          f'Incomplete defense replay: deflections={deflections}, moving={moving_deflections}, toggles={toggles}')
    return credits


def utility_adjustments(records):
    """Check the fixed six-slot composition and independently replay its balance changes."""
    empty = (EMPTY, True)
    expected = [
        [empty, (FABRICATOR, False), (FABRICATOR, True), (FABRICATOR_T2, True), empty],
        [empty, (FABRICATOR, False), (FABRICATOR, True)],
        [empty, (GUN, False), empty, (LASER, False), (LASER, True), empty],
        [empty, (SHIELD, False), (SHIELD, True), empty],
        [empty, (SPEED_INCOMPLETE, False), (SPEED, True), empty],
        [empty, (SPEED_INCOMPLETE, False), (SPEED, True), empty],
    ]
    refund_by_form = {GUN: 2000, FABRICATOR_T2: 3500, LASER: 350, SHIELD: 3000, SPEED: 3000}
    stages = [[] for _ in expected]
    phases, adjustments, moving = {}, {}, set()
    previous_parent = previous_profile = identities = None
    shield_pulses = 0
    for row in records:
        if row.get('kind') != 'observation' or row.get('phase') not in ('initial', 'step'):
            continue
        sample = row['sample']
        units = {unit['unitId']: unit for unit in sample['units']}
        parent = units.get('a')
        check(parent and parent['typeId'] == CHASSIS and parent['ownerPlayerId'] == 'p1' and parent['alive'],
              'The owned Modular Spider chassis disappeared')
        slots = parent['attachmentOverview']['slots']
        slot_ids = [slot['unitId'] for slot in slots]
        if identities is None:
            identities = slot_ids
        check(len(set(slot_ids)) == 6 and slot_ids == identities and
              [slot['number'] for slot in slots] == list(range(1, 7)), 'Six stable numbered slots are required')
        refunds = 0
        emptied = [event['unitId'] for event in sample['events'] if event['kind'] == 'attachment-slot-emptied']
        expected_emptied = []
        for index, slot in enumerate(slots):
            identity, form, progress = slot['unitId'], slot['typeId'], slot['progress']
            child = units.get(identity)
            check(child and child['ownerPlayerId'] == 'p1' and child['alive'] and child['typeId'] == form and
                  child['constructionProgress'] == progress and
                  child.get('attachment') == dict(parentUnitId='a', slot=index + 1, empty=form == EMPTY) and
                  'shield' not in child, 'A utility slot must match its own live attached entity')
            stage = (form, progress == 1)
            prior = stages[index]
            if not prior or stage != prior[-1]:
                check(len(prior) < len(expected[index]) and stage == expected[index][len(prior)],
                      f'Unexpected state in utility slot {index + 1}: {stage}')
                if prior and stage == empty:
                    expected_emptied.append(identity)
                    refunds += refund_by_form[prior[-1][0]]
                prior.append(stage)
        check(sorted(emptied) == sorted(expected_emptied), 'Removal must identify the converted slot')
        shield = parent['shield']
        check(shield['maximum'] == 5000 and 0 <= shield['current'] <= 5000, 'Parent shield left its bounds')
        speed_count = sum(slot['typeId'] == SPEED and slot['progress'] == 1 for slot in slots)
        weapon = any(slot['typeId'] in (GUN, LASER) for slot in slots)
        profile = (speed_count, weapon)
        cap = .7 if speed_count else .4 if weapon else .6
        if previous_parent is not None:
            if previous_profile == profile:
                actual_speed = abs(parent['speed'])
                check(actual_speed <= cap + .001, 'Chassis speed exceeded its active module limit')
                left, right = previous_parent['position'], parent['position']
                distance = math.hypot(right['x'] - left['x'], right['y'] - left['y'])
                if abs(actual_speed - cap) < .001 and abs(distance - cap) < .02:
                    moving.add(profile)
            gain = shield['current'] - previous_parent['shield']['current']
            if gain > .302:
                check((SHIELD, True) == stages[3][-1] and
                      abs(shield['current'] - min(5000, previous_parent['shield']['current'] + 30.3)) < .002,
                      'Only a completed generator may add a capped shield pulse')
                shield_pulses += 1
        previous_parent, previous_profile = parent, profile
        if row['phase'] == 'step':
            credits = refunds + utility_income(slots, phases)
            if credits:
                adjustments[row['sequence']] = credits
    check(stages == expected and shield_pulses > 0 and moving >= {(0, False), (0, True), (1, True), (2, True)},
          f'Incomplete utility composition, shield pulses or measured movement: pulses={shield_pulses}, movement={moving}')
    return adjustments
