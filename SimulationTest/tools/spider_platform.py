"""Independent replay checks for the normal Modular Spider opening."""
import math


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
SLOTS = ('selectSlotBuild', *(f'selectSlot{i}' for i in range(1, 7)))
PAID = ('buildGun', 'buildSmallLaser', 'buildAntiair', 'buildLightning', 'buildArtillery',
        'buildFabricator', 'buildShieldGen', 'buildSpeed', 'upgradeT2', 'upgradeFlak')
CANCELLABLE = (*PAID, 'reclaim')
ACTIONS = (*SLOTS, *CANCELLABLE, 'cancel')
OFFSETS = ((-11, 32, 0), (11, 32, 0), (-11, 6, -90), (11, 6, 90), (-10, -20, 180), (11, -20, 180))


def check(condition, message):
    if not condition:
        from platform_scenario import PlatformError
        raise PlatformError('history-spider', message, 2)


def validate_state(unit, owner):
    actions = unit.get('unitActions')
    layout = unit.get('attachmentOverview')
    if unit.get('ownerPlayerId') != owner or unit.get('alive') is not True:
        check(actions is None and layout is None, 'Another player’s or dead chassis exposed slot controls')
        return
    check(isinstance(layout, dict) and set(layout) == {'selectedSlot', 'slots'}, 'Missing chassis slot overview')
    check(layout['selectedSlot'] is None or type(layout['selectedSlot']) is int and 1 <= layout['selectedSlot'] <= 6,
          'Invalid selected slot')
    check(isinstance(actions, dict) and set(actions) == {'actions', 'automaticCooldown'} and
          actions['automaticCooldown'] == 0 and isinstance(actions['actions'], dict), 'Invalid slot controls')
    check(set(SLOTS) <= set(actions['actions']) <= set(ACTIONS), 'Unknown or missing slot controls')
    for name, action in actions['actions'].items():
        check(isinstance(action, dict) and set(action) == {'queueIndex', 'progress', 'cooldownRemaining', 'available'} and
              type(action['available']) is bool and type(action['cooldownRemaining']) is int and
              0 <= action['cooldownRemaining'] <= 2000 and action['queueIndex'] in (None, 0) and
              (action['progress'] is None or type(action['progress']) in (int, float) and 0 <= action['progress'] <= 1),
              f'Invalid slot action state for {name}')


def validate_call(old, new, row, intent):
    name = intent['actionName']
    cancelling = intent.get('cancelQueued', False)
    check(name in ACTIONS and row.get('actionName') == name and row.get('cancelQueued') is cancelling and row.get('target') is None,
          'Chassis call does not match the declared normal slot action')
    left = old['attachmentOverview']
    right = new['attachmentOverview']
    check(left['slots'] == right['slots'], 'The call synchronously changed slot identities or construction')
    old_action = old['unitActions']['actions'].get(name)
    if cancelling:
        new_action = new['unitActions']['actions'].get(name)
        check(name in CANCELLABLE and left['selectedSlot'] is not None and right['selectedSlot'] == left['selectedSlot'] and
              old_action is not None and old_action.get('queueIndex') == 0 and
              new_action is not None and new_action.get('queueIndex') is None and new_action.get('progress') is None,
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
        check(new_action.get('queueIndex') == 0 and new_action.get('progress') == 0, 'Module order did not enter its slot queue')


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
