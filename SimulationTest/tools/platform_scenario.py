"""Shared client-intent judgments for session and real Windows input evidence."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import time
import tempfile
import xml.etree.ElementTree as ET


class PlatformError(ValueError):
    def __init__(self, category, message, exit_code=3):
        super().__init__(message)
        self.category = category
        self.exit_code = exit_code


def validate_unit_action_intent(intent):
    name=intent.get('actionName');nuclear=name in ('buildNuke','launchNuke','buildAntiNuke');cancel=intent.get('cancelQueued',False)
    keys={'intentId','kind','actionName','waitSteps'}
    if nuclear and 'cancelQueued' in intent:keys.add('cancelQueued')
    if name=='launchNuke' and cancel is False:keys.add('target')
    target=intent.get('target')
    if (set(intent)!=keys or type(cancel) is not bool or
            name=='launchNuke' and not cancel and (not isinstance(target,dict) or set(target)!={'x','y'} or
                any(type(v) not in (int,float) or not math.isfinite(v) for v in target.values())) or
            not isinstance(name,str) or not nuclear and name not in ('afterBurn','cancelBurn','deploy','undeploy','surface','dive') or
            type(intent.get('waitSteps')) is not int or not 0<=intent['waitSteps']<=600):
        raise PlatformError('input-contract','Expected a finite normal unit action and explicit bounded wait',1)


def value_at(sample, path):
    current = sample
    parts = path.split('.')
    if parts[0] == 'weapons':
        values = sample.get('weapons')
        if len(parts) < 4 or not isinstance(values, list):
            raise PlatformError('missing-field', 'Missing weapon observation: '+path)
        matches = [item for item in values if isinstance(item, dict) and
                   item.get('unitId') == parts[1] and item.get('weaponName') == parts[2]]
        if len(matches) != 1:
            raise PlatformError('missing-field', 'Missing or ambiguous named weapon: '+path)
        current = matches[0]
        parts = parts[3:]
    for depth, part in enumerate(parts):
        if isinstance(current, list):
            if parts[0] == 'projectiles' and depth == 1:
                if part.isascii() and part.isdecimal():
                    index = int(part)
                    if str(index) != part or index >= len(current):
                        raise PlatformError('missing-field', 'Missing projectile observation index: '+path)
                    current = current[index]
                    continue
                matches = [x for x in current if isinstance(x, dict) and x.get('token') == part]
                if len(matches) != 1:
                    raise PlatformError('missing-field', 'Missing or ambiguous projectile token: '+path)
                current = matches[0]
                continue
            matches = [x for x in current if isinstance(x, dict) and
                       (x.get('unitId') == part or x.get('factoryId') == part)]
            if len(matches) != 1:
                raise PlatformError('missing-field', 'Missing or ambiguous object: '+path)
            current = matches[0]
        elif isinstance(current, dict) and part in current:
            current = current[part]
        else:
            raise PlatformError('missing-field', 'Missing observed field: '+path)
    return current


def validate_intent_contract(intent,rule):
    assertions = rule.get('assertions')
    if (not isinstance(assertions, list) or not assertions or
            any(not isinstance(a, dict) or a.get('operator') not in ('equals', 'deltaEquals', 'equalsActionTarget') or
                not isinstance(a.get('path'), str) or not a['path'] or
                (a['operator'] != 'equalsActionTarget' and 'value' not in a) for a in assertions) or
            not intent.get('intentId') or intent.get('intentId') != rule.get('intentId') or
            not rule.get('actionType') or not rule.get('unitId') or type(rule.get('accepted')) is not bool):
        raise PlatformError('input-contract', 'Invalid or empty fixed intent contract', 1)


def input_footprint(geometry):
    if geometry is None:return dict(x=0,y=0)
    viewport=geometry['viewport']
    dimensions=[geometry['worldWidth'],geometry['worldHeight'],viewport['screenWidth'],viewport['screenHeight']]
    if (any(type(v) not in (int,float) or not math.isfinite(v) or v<=0 for v in dimensions) or
            (viewport['width'],viewport['height']) not in ((1200,800),(960,640)) or
            viewport['screenWidth']!=viewport['width'] or viewport['screenHeight']!=viewport['height']):
        raise PlatformError('input-geometry','Missing or unregistered fixed map/viewport geometry')
    scale=min(704/geometry['worldWidth'],512/geometry['worldHeight'])
    return dict(x=1/(scale*viewport['screenWidth']/960),y=1/(scale*viewport['screenHeight']/640))


def compare_intent(records, intent, rule, after_sequence, geometry=None):
    validate_intent_contract(intent,rule)
    assertions=rule['assertions']
    actions = [r for r in records if r.get('sequence', -1) > after_sequence and
               r.get('kind') == 'action' and r.get('type') == rule['actionType']]
    if len(actions) != 1:
        raise PlatformError('action-identity', 'Expected exactly one matching formal action')
    action = actions[0]
    if action.get('unitId') != rule['unitId'] or action.get('actorPlayerId') != intent.get('actor', 'p1'):
        raise PlatformError('action-identity', 'Action owner or target differs from the fixed intent')
    if action.get('result', {}).get('accepted') is not rule['accepted']:
        raise PlatformError('action-result', 'Formal action result differs from the contract', 2)
    for key, expected in rule.get('actionFields', {}).items():
        if key not in action or action[key] != expected:
            raise PlatformError('action-fields', 'Formal action differs: '+key, 2)
    if rule.get('targetFromIntent') is True:
        limits = input_footprint(geometry)
        for axis in ('x', 'y'):
            actual, expected = action.get('target', {}).get(axis), intent.get('position', {}).get(axis)
            if any(type(v) not in (int, float) or not math.isfinite(v) for v in (actual, expected)):
                raise PlatformError('input-geometry', 'Finite fixed intent and formal target required')
            if abs(actual-expected) > limits[axis]:
                raise PlatformError('input-target', 'Formal target exceeds the approved input footprint: '+axis, 2)
    after = next((r for r in records if r.get('sequence', -1) > action['sequence'] and
                  r.get('kind') == 'observation' and r.get('phase') == 'after-action'), None)
    if after is None:
        raise PlatformError('missing-observation', 'Accepted action has no after-action observation')
    before = next((r for r in reversed(records) if r.get('sequence', -1) < action['sequence'] and
                   r.get('kind') == 'observation' and r.get('phase') == 'before-action'), None)
    if before is None:
        raise PlatformError('missing-observation', 'Formal action has no before-action observation')
    index = records.index(action)
    if (index == 0 or index + 1 >= len(records) or records[index - 1] is not before or
            records[index + 1] is not after or before['sequence'] <= after_sequence or
            before['sequence'] + 1 != action['sequence'] or after['sequence'] != action['sequence'] + 1):
        raise PlatformError('call-boundary', 'Before/action/after must belong to one uninterrupted client call')
    if before['sample'].get('worldId') != after['sample'].get('worldId') or not before['sample'].get('worldId'):
        raise PlatformError('world-identity', 'World changed across one client action')
    if any(before['sample'].get(key) != after['sample'].get(key) for key in ('stepIndex', 'simulationTime')):
        raise PlatformError('call-boundary', 'Simulation advanced inside the immediate client call')
    identity = rule.get('unitIdentity', {'ownerPlayerId': intent.get('actor', 'p1')})
    if not isinstance(identity, dict) or not identity or not identity.get('ownerPlayerId'):
        raise PlatformError('input-contract', 'The fixed controlled object needs an expected owner', 1)
    for sample in (before['sample'], after['sample']):
        unit = value_at(sample, 'units.'+rule['unitId'])
        if any(key not in unit or unit[key] != expected for key, expected in identity.items()):
            raise PlatformError('object-identity', 'Controlled object identity differs from the fixed contract', 2)
        if intent.get('kind') == 'attack':
            target_id = intent.get('targetUnitId')
            fixed_target = value_at(before['sample'], 'units.'+target_id)
            target_identity = rule.get('targetIdentity', {
                'ownerPlayerId': fixed_target.get('ownerPlayerId'), 'typeId': fixed_target.get('typeId')})
            if not isinstance(target_identity, dict) or not target_identity:
                raise PlatformError('input-contract', 'An attack needs an independent fixed target identity', 1)
            if action.get('targetUnitId') != target_id:
                raise PlatformError('object-identity', 'Attack target differs from the fixed intent', 2)
            target = value_at(sample, 'units.'+target_id)
            if (not target.get('typeId') or not target.get('ownerPlayerId') or target['ownerPlayerId'] == intent.get('actor', 'p1') or
                    any(key not in target or target[key] != expected for key, expected in target_identity.items()) or
                    type(target.get('health')) not in (int, float) or not math.isfinite(target['health']) or target['health'] <= 0):
                raise PlatformError('object-identity', 'Attack target is not the fixed living enemy', 2)
    for assertion in rule['assertions']:
        current = value_at(after['sample'], assertion['path'])
        expected = assertion.get('value')
        if assertion['operator'] == 'equalsActionTarget':
            if not isinstance(action.get('target'), dict):
                raise PlatformError('missing-field', 'Formal action target is unavailable')
            expected = action['target']
        if assertion['operator'] == 'deltaEquals':
            previous = value_at(before['sample'], assertion['path'])
            if type(current) not in (int, float) or type(previous) not in (int, float):
                raise PlatformError('missing-field', 'Delta requires numeric observations')
            current -= previous
        if current != expected:
            raise PlatformError('assertion-failed', 'Postcondition differs: '+assertion['path'], 2)
    judgment = dict(exitCode=0, actionId=action['result']['actionId'], actionSequence=action['sequence'],
                    sampleSequence=after['sequence'])
    if 'tail' in rule:
        tail = rule['tail']
        count = tail.get('samples')
        if type(count) is not int or count <= 0 or not tail.get('assertions'):
            raise PlatformError('input-contract', 'A stable tail needs a positive fixed count and assertions', 1)
        samples = [row for row in records if row.get('kind') == 'observation' and
                   row.get('phase') == 'step' and row['sequence'] > after['sequence']]
        if len(samples) < count:
            raise PlatformError('missing-tail', 'Missing the fixed number of post-command samples')
        samples = samples[-count:]
        for assertion in tail['assertions']:
            values = [value_at(row['sample'], assertion['path']) for row in samples]
            operator = assertion.get('operator')
            if operator == 'stable':
                passed = all(value == values[0] for value in values)
            elif operator == 'equals' and 'value' in assertion:
                passed = all(value == assertion['value'] for value in values)
            elif operator == 'lessThan' and 'value' in assertion:
                bound = assertion['value']
                if type(bound) not in (int, float) or not math.isfinite(bound):
                    raise PlatformError('input-contract', 'lessThan needs a finite numeric bound', 1)
                passed = all(type(value) in (int, float) and math.isfinite(value) and value < bound for value in values)
            else:
                raise PlatformError('input-contract', 'Unknown stable tail assertion', 1)
            if not passed:
                raise PlatformError('tail-assertion', 'Post-command tail differs: '+assertion['path'], 2)
        judgment['tailSampleSequences'] = [row['sequence'] for row in samples]
    if 'weaponBirths' in rule:
        expected = rule['weaponBirths']
        if (not isinstance(expected, list) or not expected or any(
                not isinstance(item, dict) or set(item) != {'unitId', 'weaponName', 'targetUnitId', 'minimumOccurrences'} or
                any(not isinstance(item[key], str) or not item[key] for key in ('unitId', 'weaponName', 'targetUnitId')) or
                type(item['minimumOccurrences']) is not int or item['minimumOccurrences'] <= 0 for item in expected)):
            raise PlatformError('input-contract', 'Expected fixed named weapon birth identities and positive counts', 1)
        identities = [(item['unitId'], item['weaponName'], item['targetUnitId']) for item in expected]
        if len(set(identities)) != len(identities):
            raise PlatformError('input-contract', 'Duplicate named weapon birth expectation', 1)
        births = []
        for row in records:
            if row.get('kind') != 'observation' or row.get('phase') != 'step' or row['sequence'] <= after['sequence']:
                continue
            events = row['sample'].get('projectileEvents')
            if not isinstance(events, list) or any(not isinstance(event, dict) for event in events):
                raise PlatformError('missing-field', 'Named firing needs actual projectile event observations')
            for event in events:
                if event.get('kind') != 'born':
                    continue
                if type(event.get('occurrences')) is not int or event['occurrences'] <= 0:
                    raise PlatformError('missing-field', 'Birth occurrence count must be a positive integer')
                births.append((row['sequence'], event))
        observed = []
        for item in expected:
            matching = [(sequence, event) for sequence, event in births if
                (event.get('sourceUnitId'), event.get('sourceWeaponName'), event.get('targetUnitId')) ==
                (item['unitId'], item['weaponName'], item['targetUnitId'])]
            count = sum(event['occurrences'] for _, event in matching)
            if count < item['minimumOccurrences']:
                raise PlatformError('weapon-births', 'Missing actual named weapon births: '+item['unitId']+'/'+item['weaponName'], 2)
            observed.append(dict(item, observedOccurrences=count, sampleSequences=[sequence for sequence, _ in matching]))
        judgment['weaponBirths'] = observed
    if 'created' in rule:
        expected = rule['created']
        creations = [(row, event) for row in records if row.get('kind') == 'observation' and
                     row.get('phase') == 'step' and row['sequence'] > action['sequence']
                     for event in row['sample'].get('events', []) if event.get('kind') == expected['event']]
        if len(creations) != 1:
            raise PlatformError('missing-creation', 'Expected one fresh creation event in the fixed intent interval')
        row, event = creations[0]
        identity = event.get('unitId')
        if (not identity or any(unit.get('unitId') == identity for unit in before['sample']['units']) or
                row['sample'].get('worldId') != before['sample']['worldId']):
            raise PlatformError('creation-identity', 'Created object is old, unknown, or from a different world')
        unit = value_at(row['sample'], 'units.'+identity)
        for key in ('typeId', 'position', 'constructionProgress'):
            if key in expected and (key not in unit or unit[key] != expected[key]):
                raise PlatformError('creation-postcondition', 'New object differs: '+key, 2)
        if unit.get('ownerPlayerId') != intent.get('actor', 'p1'):
            raise PlatformError('creation-postcondition', 'New object has a different owner', 2)
        judgment.update(createdUnitId=identity, creationSampleSequence=row['sequence'])
    return judgment


def compare_resource_images(records, expectation):
    fields = {'providerId', 'frameWidth', 'frameHeight', 'totalFrames', 'pixelWidth', 'pixelHeight', 'bound'}
    if not isinstance(expectation, dict) or set(expectation) != {'packOrder', 'resources'}:
        raise PlatformError('input-contract', 'Expected fixed resource pack order and image assertions', 1)
    order, images = expectation['packOrder'], expectation['resources']
    if (not isinstance(order, list) or not order or any(not isinstance(item, str) or not item for item in order) or
            len(set(order)) != len(order) or order[-1] != 'reforge:base' or not isinstance(images, dict) or not images):
        raise PlatformError('input-contract', 'Expected nonempty unique pack order ending in base and image assertions', 1)
    for address, image in images.items():
        if (not isinstance(address, str) or not address or not isinstance(image, dict) or set(image) != fields or
                image['providerId'] not in order or type(image['bound']) is not bool or
                any(type(image[key]) is not int or image[key] <= 0 for key in fields - {'providerId', 'bound'})):
            raise PlatformError('input-contract', 'Invalid fixed resource image assertion: '+str(address), 1)
    ready = [row for row in records if row.get('kind') == 'visual-assets-ready']
    if len(ready) != 1 or not isinstance(ready[0].get('resourceStack'), dict):
        raise PlatformError('resource-preparation', 'Expected exactly one prepared resource stack')
    stack = ready[0]['resourceStack']
    if stack.get('packOrder') != order or not isinstance(stack.get('resources'), dict):
        raise PlatformError('resource-order', 'Prepared priority differs from the fixed contract', 2)
    for address, expected in images.items():
        actual = stack['resources'].get(address)
        if not isinstance(actual, dict) or any(type(actual.get(key)) is not type(value) or actual.get(key) != value for key, value in expected.items()):
            raise PlatformError('resource-image', 'Prepared provider or layout differs: '+address, 2)
    return dict(packOrder=order, checkedResources=len(images), boundary='Prepared bindings; no GL or original-game equivalence claim')


def compare_resource_sounds(records, expected):
    legacy = isinstance(expected, dict) and set(expected) == {'eventId', 'resourceId', 'providerId', 'minimumRequests'}
    weighted = isinstance(expected, dict) and set(expected) == {'eventId', 'events', 'minimumRequests'}
    if (not (legacy or weighted) or not isinstance(expected.get('eventId'), str) or not expected['eventId'] or
            type(expected.get('minimumRequests')) is not int or not 1 <= expected['minimumRequests'] <= 600):
        raise PlatformError('input-contract', 'Expected fixed sound mappings and bounded minimum requests', 1)
    if legacy:
        if any(not isinstance(expected[key], str) or not expected[key] for key in ('resourceId', 'providerId')):
            raise PlatformError('input-contract', 'Invalid single sound mapping', 1)
        permitted = {expected['resourceId']}
    else:
        tables = expected['events']
        if not isinstance(tables, dict) or expected['eventId'] not in tables:
            raise PlatformError('input-contract', 'Missing weighted event contract', 1)
        for event_id, table in tables.items():
            if (not isinstance(event_id, str) or not event_id or not isinstance(table, dict) or
                    set(table) != {'providerId', 'totalWeight', 'candidates'} or
                    not isinstance(table['providerId'], str) or not table['providerId'] or
                    type(table['totalWeight']) is not int or not 0 < table['totalWeight'] <= 9223372036854775807 or
                    not isinstance(table['candidates'], list) or not table['candidates']):
                raise PlatformError('input-contract', 'Invalid weighted table contract', 1)
            for candidate in table['candidates']:
                if (not isinstance(candidate, dict) or set(candidate) != {'sound', 'weight', 'providerId'} or
                        any(not isinstance(candidate[key], str) or not candidate[key] for key in ('sound', 'providerId')) or
                        type(candidate['weight']) is not int or not 1 <= candidate['weight'] <= 2147483647):
                    raise PlatformError('input-contract', 'Invalid weighted candidate contract', 1)
            if sum(candidate['weight'] for candidate in table['candidates']) != table['totalWeight']:
                raise PlatformError('input-contract', 'Inconsistent weighted contract total', 1)
        permitted = {candidate['sound'] for candidate in tables[expected['eventId']]['candidates']}
    ready = [row for row in records if row.get('kind') == 'visual-assets-ready']
    backend = [row for row in records if row.get('kind') == 'sound-backend-ready']
    if (len(ready) != 1 or not isinstance(ready[0].get('soundStack'), dict) or len(backend) != 1 or
            backend[0].get('backend') != 'device-free'):
        raise PlatformError('sound-preparation', 'Expected one sound stack and explicit device-free backend')
    stack = ready[0]['soundStack']
    resources = stack.get('resources', {})
    if legacy:
        mapping = stack.get('events', {}).get(expected['eventId'])
        if (not isinstance(mapping, dict) or mapping.get('sound') != expected['resourceId'] or
                mapping.get('providerId') != expected['providerId']):
            raise PlatformError('sound-mapping', 'Sound mapping differs from the fixed contract', 2)
    else:
        for event_id, table in tables.items():
            mapping = stack.get('events', {}).get(event_id)
            if (not isinstance(mapping, dict) or any(mapping.get(key) != value for key, value in table.items()) or
                    type(mapping.get('totalWeight')) is not int or any(
                        type(candidate.get('weight')) is not int for candidate in mapping.get('candidates', []))):
                raise PlatformError('sound-mapping', 'Weighted event table differs: '+event_id, 2)
            if any(not isinstance(resources.get(candidate['sound']), dict) for candidate in table['candidates']):
                raise PlatformError('sound-mapping', 'Weighted table contains an unbound candidate', 2)
    if any(not isinstance(resources.get(address), dict) for address in permitted):
        raise PlatformError('sound-mapping', 'Sound resource is not bound', 2)
    requests = [row for row in records if row.get('kind') == 'sound-play-requested']
    if (len(requests) < expected['minimumRequests'] or
            any(row.get('kind') == 'sound-play-failed' for row in records)):
        raise PlatformError('sound-output', 'Missing authorized playback request or backend failure', 2)
    loads = [row for row in records if row.get('kind') == 'load-ready']
    world = [row for row in records if row.get('kind') in ('observation','action','closed')]
    playback = backend + requests
    # Older producers labelled the prepared assets but not playback or world records.
    generation_rows = loads + ready
    if any('generation' in row for row in playback + world):
        generation_rows += playback + world
    if any('generation' in row for row in generation_rows):
        generation = ready[0].get('generation')
        if (len(loads) != 1 or type(generation) is not int or generation < 1 or
                any(type(row.get('generation')) is not int or row['generation'] != generation for row in generation_rows)):
            raise PlatformError('sound-generation', 'Playback and preparation must match the loaded generation', 2)
    frames = set()
    allowed = {'sequence', 'kind', 'generation', 'eventId', 'resourceId', 'path', 'sha256', 'volume', 'pitch', 'pan',
               'step', 'serial', 'frame', 'voice'}
    for row in requests:
        resource = resources.get(row.get('resourceId'), {})
        if (set(row) - allowed or row.get('eventId') != expected['eventId'] or row.get('resourceId') not in permitted or
                any(row.get(key) != resource.get(key) or not isinstance(row.get(key), str) for key in ('path', 'sha256')) or
                any(type(row.get(key)) not in (float, int) for key in ('volume', 'pitch', 'pan')) or
                not 0 < row['volume'] <= struct.unpack('!f', struct.pack('!f', .3))[0] or
                not struct.unpack('!f', struct.pack('!f', .93))[0] <= row['pitch'] <= struct.unpack('!f', struct.pack('!f', 1.07))[0] or row['pan'] != 0 or
                any(type(row.get(key)) is not int or row[key] < 0 for key in ('step', 'serial', 'frame', 'voice'))):
            raise PlatformError('sound-request', 'Unbound, unauthorized or invalid sound request', 2)
        identity = (row['frame'], row['path'])
        if identity in frames:
            raise PlatformError('sound-deduplication', 'One sound object was requested twice in the same outer frame', 2)
        frames.add(identity)
    result = dict(requests=len(requests), eventId=expected['eventId'],
                  selectedResources=sorted({row['resourceId'] for row in requests}),
                  boundary='Device-free requests only; finite choices do not establish distribution or device output')
    if legacy: result['resourceId'] = expected['resourceId']
    return result


def validate_history_poses(units, policy):
    counts = policy.get('mechLegs', {})
    if not isinstance(counts, dict) or any(type(n) is not int or n < 1 for n in counts.values()):
        raise PlatformError('input-contract', 'Expected declared positive mech leg counts', 1)
    def number(value):
        return type(value) in (int, float) and math.isfinite(value)
    def point(value):
        return isinstance(value, dict) and set(value) == {'x','y'} and all(number(v) for v in value.values())
    for unit in units:
        if unit.get('typeId') in {'aaBeamGunship','aaBeamGunship_afterburn'}:
            energy=unit.get('energy');pose=unit.get('flightPose')
            if (not isinstance(energy,dict) or set(energy)!={'current','maximum','recharging'} or
                    not number(energy.get('current')) or not 0<=energy['current']<=6 or
                    not number(energy.get('maximum')) or energy['maximum']!=6 or type(energy.get('recharging')) is not bool or
                    unit['typeId']=='aaBeamGunship' and
                    (energy['recharging'] and energy['current']>=6 or not energy['recharging'] and energy['current']<.5)):
                raise PlatformError('history-aa-beam','Missing or inconsistent weapon energy and recharge lock',2)
            if (not isinstance(pose,dict) or set(pose)!={'velocity','height','heightPhase','rotorPhase','rotorHeading','rotorHeightOffset','fall'} or
                    not point(pose.get('velocity')) or any(not number(pose.get(k)) for k in
                        ('height','heightPhase','rotorPhase','rotorHeading','rotorHeightOffset')) or
                    not number(unit.get('height')) or pose['height']!=unit['height'] or pose['rotorHeightOffset']!=0):
                raise PlatformError('history-aa-beam','Missing or invalid rotorless flight pose',2)
            fall=pose['fall']
            if (not isinstance(fall,dict) or set(fall)!={'speed','landed','landedInWater','settled'} or
                    not number(fall.get('speed')) or fall['speed']<0 or
                    any(type(fall.get(k)) is not bool for k in ('landed','landedInWater','settled')) or
                    not fall['landed'] and (fall['landedInWater'] or fall['settled']) or
                    unit.get('alive') is True and (fall['landed'] or fall['speed']!=0)):
                raise PlatformError('history-aa-beam','Missing or inconsistent fall state',2)
            state=unit.get('unitActions')
            if unit.get('ownerPlayerId')!=policy.get('ownerPlayerId','p1'):
                if state is not None:raise PlatformError('history-unit-action','Enemy action controls must be private',2)
            else:
                names={'afterBurn','cancelBurn'} if unit['typeId']=='aaBeamGunship_afterburn' else {'afterBurn'}
                if (not isinstance(state,dict) or set(state)!={'actions','automaticCooldown'} or
                        not number(state.get('automaticCooldown')) or not 0<=state['automaticCooldown']<=60 or
                        not isinstance(state.get('actions'),dict) or set(state['actions'])!=names):
                    raise PlatformError('history-unit-action','Missing or invalid finite unit actions',2)
                indexes=[]
                for name,action in state['actions'].items():
                    if (not isinstance(action,dict) or set(action)!={'queueIndex','progress','cooldownRemaining','available'} or
                            type(action.get('cooldownRemaining')) is not int or not 0<=action['cooldownRemaining']<=25000 or
                            name=='cancelBurn' and action['cooldownRemaining']!=0):
                        raise PlatformError('history-unit-action','Invalid action cooldown or fields',2)
                    index=action['queueIndex'];progress=action['progress']
                    if index is None:
                        if progress is not None:raise PlatformError('history-unit-action','Unqueued action progress',2)
                    else:
                        if type(index) is not int or not 0<=index<=1 or not number(progress) or not 0<=progress<1:
                            raise PlatformError('history-unit-action','Invalid action queue index or progress',2)
                        indexes.append(index)
                    available=(unit.get('alive') is True and unit.get('constructionProgress',0)>=1 and
                               unit.get('carriedBy') is None and index is None and action['cooldownRemaining']==0)
                    if type(action['available']) is not bool or action['available']!=available:
                        raise PlatformError('history-unit-action','Action availability differs from its state',2)
                if sorted(indexes)!=list(range(len(indexes))):
                    raise PlatformError('history-unit-action','Action queue indexes must be unique and contiguous',2)
        elif unit.get('typeId') in ('mechBunker','mechBunkerDeployed'):
            validate_bunker_state(unit, policy.get('ownerPlayerId','p1'))
        elif unit.get('typeId') in {'nautilusSubmarine','nautilusSubmarineSurface','nautilusSubmarineLand'}:
            validate_nautilus_state(unit,policy.get('ownerPlayerId','p1'))
        elif unit.get('typeId') in ('nukeLauncherC','antiNukeLauncherC'):
            from nuclear_platform import validate_nuclear_state
            validate_nuclear_state(unit,policy.get('ownerPlayerId','p1'))
        elif unit.get('energy') is not None:
            raise PlatformError('history-aa-beam','Weapon energy on an unsupported unit',2)
        elif unit.get('unitActions') is not None:
            raise PlatformError('history-unit-action','Unit actions on an unsupported type',2)
        if unit.get('carriedBy') is not None:
            raise PlatformError('history-transport','Carried passenger in a player sample',2)
        transport=unit.get('transport')
        if unit.get('typeId') in {'hovercraft','dropship','nautilusSubmarine','nautilusSubmarineSurface','nautilusSubmarineLand'} and unit.get('ownerPlayerId')==policy.get('ownerPlayerId','p1'):
            nautilus=unit['typeId'] in {'nautilusSubmarine','nautilusSubmarineSurface','nautilusSubmarineLand'}
            if (not isinstance(transport,dict) or set(transport)!={'passengerIds','passengerCount','occupiedSlots','capacity','unloading','unloadRemaining','canUnload'} or
                    not number(unit.get('height'))):
                raise PlatformError('history-transport','Missing or unapproved owner cargo fields',2)
            passengers=transport['passengerIds']
            if (not isinstance(passengers,list) or any(not isinstance(p,str) or not p for p in passengers) or
                    len(passengers)!=len(set(passengers)) or any(p==u.get('unitId') for p in passengers for u in units) or
                    type(transport['passengerCount']) is not int or transport['passengerCount']!=len(passengers) or
                    type(transport['occupiedSlots']) is not int or not 0<=transport['occupiedSlots']<=4 or
                    (not passengers and transport['occupiedSlots']!=0) or type(transport['capacity']) is not int or transport['capacity']!=(3 if nautilus else 4) or
                    type(transport['unloading']) is not bool or type(transport['canUnload']) is not bool or
                    not number(transport['unloadRemaining']) or not 0<=transport['unloadRemaining']<=30 or
                    transport['canUnload'] and (not passengers or transport['unloading'] or unit.get('alive') is not True)):
                raise PlatformError('history-transport','Invalid cargo count, capacity or unload state',2)
            if nautilus and (transport['occupiedSlots']!=len(passengers) or unit['typeId']!='nautilusSubmarineSurface' and (transport['unloading'] or
                    transport['canUnload'])):
                raise PlatformError('history-nautilus','Submerged production must retain its single-slot products',2)
        elif transport is not None:
            raise PlatformError('history-transport','Enemy or unsupported cargo state',2)
        exit_state=unit.get('transportExit')
        if exit_state is not None and (unit.get('ownerPlayerId')!=policy.get('ownerPlayerId','p1') or
                not isinstance(exit_state,dict) or set(exit_state)!={'previousCarrierId','remaining'} or
                not isinstance(exit_state['previousCarrierId'],str) or not exit_state['previousCarrierId'] or
                not number(exit_state['remaining']) or not 0<exit_state['remaining']<=45):
            raise PlatformError('history-transport','Invalid exit reference or lifetime',2)
        if unit.get('typeId')=='amphibiousJet':
            state=unit.get('amphibiousJet');height=unit.get('height')
            fields={'flyingIntent','verticalSpeed','underwater','surfacePresentation','movementType',
                    'maximumSpeed','attackRange','wingFold','phase','sinkSpeed','settled','canDive'}
            if (not isinstance(state,dict) or set(state)!=fields or not number(height) or
                    type(state.get('underwater')) is not bool or state['underwater']!=(height < -1) or
                    any(type(state.get(k)) is not bool for k in ('surfacePresentation','settled')) or
                    state.get('movementType') not in ('AIR','WATER') or
                    not number(state.get('maximumSpeed')) or state['maximumSpeed']!=(.4 if height < -1 else 1.4) or
                    not number(state.get('attackRange')) or state['attackRange']!=(100 if state['movementType']=='WATER' else 170) or
                    any(not number(state.get(k)) for k in ('wingFold','phase','sinkSpeed')) or
                    not 0<=state['wingFold']<=1 or not 0<=state['phase']<=360 or state['sinkSpeed']<0 or
                    any(unit.get(k) is not None for k in ('flightPose','waterPose','submarine'))):
                raise PlatformError('history-amphibious','Missing or inconsistent physical amphibious state',2)
            if unit.get('ownerPlayerId')==policy.get('ownerPlayerId','p1'):
                if (any(type(state.get(k)) is not bool for k in ('flyingIntent','canDive')) or
                        not number(state.get('verticalSpeed')) or not 0<=state['verticalSpeed']<=.6 or
                        (state['movementType']=='WATER')!=(not state['flyingIntent'] or height<0) or
                        state['canDive'] and (not state['flyingIntent'] or unit.get('alive') is not True or
                                             not number(unit.get('constructionProgress')) or unit['constructionProgress']<1)):
                    raise PlatformError('history-amphibious','Missing or inconsistent owner amphibious controls',2)
            elif any(state.get(k) is not None for k in ('flyingIntent','verticalSpeed','canDive')):
                raise PlatformError('history-amphibious','Enemy amphibious controls must be redacted',2)
        elif unit.get('amphibiousJet') is not None:
            raise PlatformError('history-amphibious','Unsupported amphibious capability in the archive',2)
        if unit.get('typeId')=='attackSubmarine':
            state=unit.get('submarine');height=unit.get('height')
            if (not isinstance(state,dict) or set(state)!={'surfaceIntent','verticalSpeed','underwater','surfacePresentation','maximumSpeed','attackRange'} or
                    not number(height) or type(state.get('underwater')) is not bool or state['underwater']!=(height < -1) or
                    type(state.get('surfacePresentation')) is not bool or
                    not number(state.get('maximumSpeed')) or state['maximumSpeed']!=(.45 if height < -1 else .8) or
                    not number(state.get('attackRange')) or state['attackRange']!=(180 if height < -1 else 250)):
                raise PlatformError('history-submarine','Missing or inconsistent physical submarine state',2)
            if unit.get('ownerPlayerId')==policy.get('ownerPlayerId','p1'):
                if (type(state.get('surfaceIntent')) is not bool or not number(state.get('verticalSpeed')) or
                        not 0<=state['verticalSpeed']<=.08):
                    raise PlatformError('history-submarine','Missing or invalid owner submarine control state',2)
            elif state['surfaceIntent'] is not None or state['verticalSpeed'] is not None:
                raise PlatformError('history-submarine','Enemy submarine control state must be redacted',2)
        elif unit.get('submarine') is not None:
            raise PlatformError('history-submarine','Unsupported submarine capability in the archive',2)
        expected = counts.get(unit.get('typeId'))
        if expected is None:
            if 'mechPose' in unit:
                raise PlatformError('input-contract', 'Missing independent leg count for '+str(unit.get('typeId')), 1)
            continue
        pose = unit.get('mechPose')
        if (not isinstance(pose, dict) or set(pose) != {'forwardHeading','angularVelocity','reversing','legs'} or
                not number(pose.get('forwardHeading')) or not number(pose.get('angularVelocity')) or
                type(pose.get('reversing')) is not bool or not isinstance(pose.get('legs'), list) or
                len(pose['legs']) != expected):
            raise PlatformError('history-pose', 'Missing or invalid complete mech pose', 2)
        for index, leg in enumerate(pose['legs']):
            if (not isinstance(leg, dict) or set(leg) != {'index','anchor','foot','height','heading','phase'} or
                    type(leg.get('index')) is not int or leg['index'] != index or
                    not point(leg.get('anchor')) or not point(leg.get('foot')) or
                    not number(leg.get('height')) or not number(leg.get('heading')) or
                    leg.get('phase') not in ('PLANTED','WARMING_UP','LIFTING','SWINGING','LOWERING')):
                raise PlatformError('history-pose', 'Missing, duplicate or invalid mech leg', 2)


def validate_nautilus_state(unit,owner):
    def invalid(message):raise PlatformError('history-nautilus',message,2)
    def number(value):return type(value) in (int,float) and math.isfinite(value)
    energy=unit.get('energy')
    if (not isinstance(energy,dict) or set(energy)!={'current','maximum','recharging'} or
            not number(energy['current']) or not 0<=energy['current']<=6 or
            not number(energy['maximum']) or energy['maximum']!=6 or type(energy['recharging']) is not bool or
            energy['recharging'] and energy['current']>=6 or not energy['recharging'] and energy['current']<1 or
            unit.get('flightPose') is not None):invalid('Invalid energy or recharge lock')
    state=unit.get('unitActions')
    if unit.get('ownerPlayerId')!=owner:
        if state is not None:invalid('Enemy action state must remain private')
        return
    names={'nautilusSubmarine':{'surface'},'nautilusSubmarineSurface':{'dive'},'nautilusSubmarineLand':set()}[unit['typeId']]
    if (not isinstance(state,dict) or set(state)!={'actions','automaticCooldown'} or
            not number(state['automaticCooldown']) or not 0<=state['automaticCooldown']<=60 or
            not isinstance(state['actions'],dict) or set(state['actions'])!=names):invalid('Invalid player action set')
    for name,action in state['actions'].items():
        if (not isinstance(action,dict) or set(action)!={'queueIndex','progress','cooldownRemaining','available'} or
                type(action['cooldownRemaining']) is not int or action['cooldownRemaining']!=0 or
                type(action['available']) is not bool):invalid('Invalid action fields')
        index=action['queueIndex'];progress=action['progress']
        if index is None:
            if progress is not None:invalid('Unqueued progress')
        elif type(index) is not int or index!=0 or not number(progress) or not 0<=progress<1:invalid('Invalid queued conversion')
        available=(unit.get('alive') is True and unit.get('constructionProgress',0)>=1 and unit.get('carriedBy') is None and index is None)
        if action['available']!=available and not (name=='dive' and action['available'] is False):invalid('Invalid action availability')


def validate_bunker_state(unit, owner):
    def invalid(message):raise PlatformError('history-bunker',message,2)
    if unit.get('energy') is not None:invalid('Bunker cannot expose weapon energy')
    state=unit.get('unitActions')
    if unit.get('ownerPlayerId')!=owner:
        if state is not None:invalid('Enemy action controls must remain private')
        return
    name='deploy' if unit['typeId']=='mechBunker' else 'undeploy'
    if (not isinstance(state,dict) or set(state)!={'actions','automaticCooldown'} or state['automaticCooldown']!=0 or
            not isinstance(state['actions'],dict) or set(state['actions'])!={name}):invalid('Invalid finite bunker action state')
    action=state['actions'][name]
    if (not isinstance(action,dict) or set(action)!={'queueIndex','progress','cooldownRemaining','available'} or
            type(action['cooldownRemaining']) is not int or action['cooldownRemaining']!=0):invalid('Invalid bunker action fields')
    index=action['queueIndex'];progress=action['progress']
    if index is None:
        if progress is not None:invalid('Unqueued progress')
    elif type(index) is not int or index!=0 or type(progress) not in (float,int) or not 0<=progress<1:
        invalid('Invalid queued progress')
    available=(unit.get('alive') is True and unit.get('constructionProgress',0)>=1 and unit.get('carriedBy') is None and index is None)
    if type(action['available']) is not bool or action['available']!=available:invalid('Invalid bunker action availability')


def validate_history_action(records, row, intent, geometry=None):
    """Bind a fixed intention to its existing exported call and owned object."""
    index=row['sequence']
    if not 0 < index < len(records)-1:
        raise PlatformError('history-action-boundary','Missing adjacent action observations')
    before,after=records[index-1],records[index+1]
    if (before.get('kind')!='observation' or before.get('phase')!='before-action' or
            after.get('kind')!='observation' or after.get('phase')!='after-action' or
            before.get('sequence')+1!=index or after.get('sequence')!=index+1):
        raise PlatformError('history-action-boundary','Action lacks its immediate before/after observations')
    left,right=before['sample'],after['sample']
    if (not left.get('worldId') or any(left.get(k)!=right.get(k) for k in
            ('worldId','stepIndex','simulationTime')) or
            any('generation' in r for r in (before,row,after)) and
            (type(row.get('generation')) is not int or row['generation']<1 or
             any(r.get('generation')!=row['generation'] for r in (before,after)))):
        raise PlatformError('history-action-boundary','World, generation or simulation boundary changed inside a call')
    actor=intent.get('actor','p1');identity=row.get('unitId');kind=intent['kind']
    if not isinstance(actor,str) or not actor:
        raise PlatformError('input-contract','Fixed actor must be a nonempty player identity',1)
    if row.get('actorPlayerId')!=actor or 'unitId' in intent and identity!=intent['unitId']:
        raise PlatformError('history-action-identity','Formal actor or unit differs from the fixed intention',2)
    for sample in (left,right):
        units=[u for u in sample.get('units',[]) if u.get('unitId')==identity]
        if (sample is right and not units and kind=='load' and intent.get('direction')=='INTO' and
                any(u.get('unitId')==row.get('targetUnitId') and identity in (u.get('transport') or {}).get('passengerIds',[])
                    for u in sample.get('units',[]))):
            continue
        if (len(units)!=1 or units[0].get('alive') is not True or
                kind!='select' and units[0].get('ownerPlayerId')!=actor):
            raise PlatformError('history-action-identity','Formal object is absent, ambiguous, dead or not owned',2)
    fields={}
    if kind=='unit-action':
        validate_unit_action_intent(intent)
        if intent['actionName'] in ('buildNuke','launchNuke','buildAntiNuke'):
            from nuclear_platform import validate_nuclear_call
            validate_nuclear_call(next(u for u in left['units'] if u['unitId']==identity),
                next(u for u in right['units'] if u['unitId']==identity),row,intent,input_footprint(geometry))
            return
        if row.get('actionName')!=intent['actionName'] or row.get('cancelQueued') is not False:
            raise PlatformError('history-action-fields','Normal unit action name or queue cancellation differs',2)
        old=next(u for u in left['units'] if u['unitId']==identity)
        new=next(u for u in right['units'] if u['unitId']==identity)
        old_actions=(old.get('unitActions') or {}).get('actions',{})
        new_actions=(new.get('unitActions') or {}).get('actions',{})
        name=intent['actionName']
        if (name not in old_actions or name not in new_actions or old_actions[name].get('available') is not True or
                old_actions[name].get('queueIndex') is not None or
                new_actions[name].get('queueIndex')!=sum(a.get('queueIndex') is not None for a in old_actions.values()) or
                new_actions[name].get('progress')!=0 or new_actions[name].get('available') is not False or
                any(old.get(k)!=new.get(k) for k in old if k!='unitActions') or
                old['unitActions']['automaticCooldown']!=new['unitActions']['automaticCooldown'] or
                set(old_actions)!=set(new_actions) or any(
                    old_actions[k]!=new_actions[k] for k in old_actions if k!=name) or
                old_actions[name].get('cooldownRemaining')!=new_actions[name].get('cooldownRemaining')):
            raise PlatformError('history-action-fields','Unit action must append its queue item without synchronous conversion or resource edits',2)
    if kind=='build' and 'typeId' in intent:fields['buildingTypeId']=intent['typeId']
    if 'upgradeId' in intent:fields['upgradeId']=intent['upgradeId']
    if 'targetUnitId' in intent:fields['targetUnitId']=intent['targetUnitId']
    if any(row.get(key)!=value for key,value in fields.items()):
        raise PlatformError('history-action-fields','Formal action parameters differ from the fixed intention',2)
    if kind=='load' and (intent.get('direction') not in ('INTO','UP') or row.get('direction')!=intent['direction']):
        raise PlatformError('history-action-fields','Load direction differs from fixed intention',2)
    if kind=='unload' and (type(intent.get('unloading')) is not bool or type(row.get('unloading')) is not bool or row['unloading']!=intent['unloading']):
        raise PlatformError('history-action-fields','Unload action differs from strict fixed boolean',2)
    if kind=='amphibious-flight':
        if type(intent.get('flying')) is not bool or type(row.get('flying')) is not bool or row['flying']!=intent['flying']:
            raise PlatformError('history-action-fields','Flight action differs from the fixed flying boolean',2)
        old=next(u for u in left['units'] if u['unitId']==identity)
        new=next(u for u in right['units'] if u['unitId']==identity)
        state_old,state_new=old.get('amphibiousJet'),new.get('amphibiousJet')
        if (old.get('typeId')!='amphibiousJet' or not isinstance(state_old,dict) or not isinstance(state_new,dict) or
                type(state_old.get('flyingIntent')) is not bool or state_old['flyingIntent']==intent['flying'] or
                type(state_new.get('flyingIntent')) is not bool or state_new['flyingIntent']!=intent['flying'] or
                set(state_old)!=set(state_new) or
                any(state_old[k]!=state_new[k] for k in state_old if k not in ('flyingIntent','movementType','attackRange','canDive')) or
                state_new.get('movementType')!=('WATER' if not intent['flying'] or old.get('height',0)<0 else 'AIR') or
                state_new.get('attackRange')!=(100 if state_new.get('movementType')=='WATER' else 170) or
                any(k not in old or k not in new or old[k]!=new[k] for k in ('height','position','moveTarget','tasks'))):
            raise PlatformError('history-action-fields','Flight intention must preserve physical state and the existing route',2)
    if kind=='submarine-depth':
        if type(intent.get('surface')) is not bool or type(row.get('surface')) is not bool or row['surface']!=intent['surface']:
            raise PlatformError('history-action-fields','Submarine action differs from the fixed surface boolean',2)
        old=next(u for u in left['units'] if u['unitId']==identity)
        new=next(u for u in right['units'] if u['unitId']==identity)
        state_old,state_new=old.get('submarine'),new.get('submarine')
        if (old.get('typeId')!='attackSubmarine' or not isinstance(state_old,dict) or not isinstance(state_new,dict) or
                type(state_old.get('surfaceIntent')) is not bool or state_old['surfaceIntent']==intent['surface'] or
                type(state_new.get('surfaceIntent')) is not bool or state_new['surfaceIntent']!=intent['surface'] or
                set(state_old)!=set(state_new) or
                any(state_old[k]!=state_new[k] for k in state_old if k!='surfaceIntent') or
                any(k not in old or k not in new or old[k]!=new[k] for k in ('height','position','moveTarget','tasks'))):
            raise PlatformError('history-action-fields','Depth intention must preserve physical state and the existing route',2)
    def same_point(actual,expected,limits=None):
        return all(isinstance(p,dict) and set(p)=={'x','y'} and all(
            type(p[k]) in (int,float) and math.isfinite(p[k]) for k in ('x','y'))
            for p in (actual,expected)) and all(abs(actual[k]-expected[k])<=(limits or dict(x=0,y=0))[k] for k in ('x','y'))
    if kind in ('build','move') and 'position' in intent and not same_point(row.get('target'),intent['position'],input_footprint(geometry)):
        raise PlatformError('history-action-fields','Formal destination differs from the fixed Session point',2)
    if kind=='move':
        unit=next(u for u in right['units'] if u.get('unitId')==identity)
        if 'pathUnreachable' in intent and type(intent['pathUnreachable']) is not bool:
            raise PlatformError('input-contract','Expected strict unreachable-path boolean',1)
        if intent.get('pathUnreachable') is True:
            events=row.get('result',{}).get('events',[])
            if (unit.get('moveTarget') is not None or unit.get('tasks')!=[] or
                    any(sum(e.get('kind')==kind and e.get('unitId')==identity for e in events)!=1
                        for kind in ('movement-cancelled','path-unreachable'))):
                raise PlatformError('history-action-fields','Unreachable Move must cancel its route with explicit receipts',2)
        elif not same_point(unit.get('moveTarget'),row.get('target')):
            raise PlatformError('history-action-fields','Move target did not exactly match the accepted formal destination',2)
    if kind=='attack':
        targets=[u for u in left.get('units',[]) if u.get('unitId')==row.get('targetUnitId')]
        # Flight/water bodies are drawn above their core ground position. The
        # runtime hit test binds the click to the exported target ID; an explicit
        # independent ground point keeps that separate coordinate check exact.
        fixed_position=intent.get('targetGroundPosition',intent.get('position'))
        if (len(targets)!=1 or targets[0].get('alive') is not True or targets[0].get('ownerPlayerId')==actor or
                fixed_position is not None and not same_point(targets[0].get('position'),fixed_position)):
            raise PlatformError('history-action-fields','Attack target differs from the fixed visible target/point',2)
    if kind in ('repair','reclaim'):
        targets=[u for u in left.get('units',[]) if u.get('unitId')==row.get('targetUnitId')]
        # Qualified normal bodies: tank and basic mech both have displayRadius 11.
        # Bind the click to those finite body shapes independently of the runtime hit test.
        point=intent.get('position');target=targets[0] if len(targets)==1 else {}
        body=target.get('position',{});footprint=input_footprint(geometry)
        radius={'c_tank':11, 'mechGun':11}.get(target.get('typeId'))
        hit=(point is None or radius is not None and
             isinstance(point,dict) and set(point)=={'x','y'} and isinstance(body,dict) and set(body)=={'x','y'} and
             all(type(p[k]) in (int,float) and math.isfinite(p[k]) for p in (point,body) for k in ('x','y')) and
             math.hypot(max(0,abs(body['x']-point['x'])-footprint['x']),
                        max(0,abs(body['y']-point['y'])-footprint['y']))<=radius)
        if (len(targets)!=1 or targets[0].get('alive') is not True or targets[0].get('carriedBy') is not None or
                row.get('targetUnitId')==identity or kind=='reclaim' and targets[0].get('ownerPlayerId')!=actor or
                not hit):
            raise PlatformError('history-action-fields','Maintenance target differs from the fixed visible unit and point',2)
    if kind=='produce' and 'typeId' in intent:
        queues=[]
        for sample in (left,right):
            matches=[q for q in sample.get('factoryQueues',[]) if q.get('factoryId')==identity]
            if len(matches)!=1 or not isinstance(matches[0].get('items'),list):
                raise PlatformError('history-action-fields','Production lacks the uniquely bound factory queue',2)
            queues.append(matches[0]['items'])
        old,new=queues
        if (len(new)!=len(old)+1 or new[:-1]!=old or not isinstance(new[-1],dict) or
                new[-1].get('typeId')!=intent['typeId']):
            raise PlatformError('history-action-fields','Production did not append the fixed product to its FIFO',2)


def validate_production_history(records, policy):
    """Match declared completion order against fresh simulation events."""
    declarations={'unit-produced':'productionOrder','upgrade-completed':'completedUpgradeTypes'}
    actual={kind:[] for kind in declarations}
    seen={kind:set() for kind in declarations}
    for name in declarations.values():
        if name in policy and (not isinstance(policy[name],list) or
                any(not isinstance(t,str) or not t for t in policy[name])):
            raise PlatformError('input-contract','Completion order requires fixed unit type lists',1)
    for row in records:
        if row.get('kind')!='observation' or row.get('phase')!='step':continue
        sample=row['sample']
        for event in sample.get('events',[]):
            kind=event.get('kind')
            if kind not in declarations or declarations[kind] not in policy:continue
            matches=[u for u in sample.get('units',[]) if u.get('unitId')==event.get('unitId')]
            if len(matches)!=1:
                raise PlatformError('history-production','Completion lacks a unique observed unit',2)
            unit=matches[0]
            if unit.get('ownerPlayerId')!=policy.get('ownerPlayerId','p1'):continue
            identity=unit['unitId']
            if identity in seen[kind]:
                raise PlatformError('history-production','Repeated completion event for one unit',2)
            seen[kind].add(identity);actual[kind].append(unit.get('typeId'))
    for kind,name in declarations.items():
        if name in policy and actual[kind]!=policy[name]:
            raise PlatformError('history-production','Missing or reordered completion: '+name,2)


def validate_launch_appearance(shot, units):
    source=units.get(shot.get('sourceUnitId'),{})
    style=shot.get('appearance','STANDARD')
    expected={'attackSubmarine':('SUBMARINE_MISSILE','SUBMARINE_TORPEDO'),
              'amphibiousJet':('AMPHIBIOUS_LIGHTNING',),'aaBeamGunship':('AA_BEAM',),'nukeLauncherC':('NUCLEAR',),
              'antiNukeLauncherC':('INTERCEPTOR',)}.get(source.get('typeId'),('STANDARD',))
    if (style not in ('STANDARD','SUBMARINE_MISSILE','SUBMARINE_TORPEDO','AMPHIBIOUS_LIGHTNING','AA_BEAM','NUCLEAR','INTERCEPTOR') or
            source and style not in expected):
        raise PlatformError('history-projectile','Invalid native launch appearance or source',2)
    line=shot.get('lightning')
    if style=='AMPHIBIOUS_LIGHTNING':
        def finite(value):return type(value) in (int,float) and math.isfinite(value)
        if (not isinstance(line,dict) or set(line)!={'end','endHeight'} or
                not isinstance(line.get('end'),dict) or set(line['end'])!={'x','y'} or
                any(not finite(v) for v in line['end'].values()) or not finite(line['endHeight']) or
                ('kind' in shot or source) and shot.get('sourceWeaponName') not in ('left','right') or
                any(shot.get(k) is not None for k in ('ground','missile','ballistic','areaExpansion'))):
            raise PlatformError('history-projectile','Missing or invalid native lightning geometry',2)
    elif line is not None:
        raise PlatformError('history-projectile','Lightning geometry on an unsupported launch appearance',2)
    beam=shot.get('beam')
    if style=='AA_BEAM':
        def finite(value):return type(value) in (int,float) and math.isfinite(value)
        if (not isinstance(beam,dict) or set(beam)!={'end','endHeight','textureOffset'} or
                not isinstance(beam.get('end'),dict) or set(beam['end'])!={'x','y'} or
                any(not finite(v) for v in beam['end'].values()) or not finite(beam.get('endHeight')) or
                not finite(beam.get('textureOffset')) or beam['textureOffset']<0 or
                ('kind' in shot or source) and shot.get('sourceWeaponName')!='1' or
                any(shot.get(k) is not None for k in ('ground','missile','ballistic','areaExpansion','lightning'))):
            raise PlatformError('history-aa-beam','Missing or invalid authorized beam geometry',2)
        if 'kind' in shot and shot['kind'] not in ('born','rearmed','impact','expired','target-ended'):
            raise PlatformError('history-aa-beam','Unknown beam lifecycle event',2)
    elif beam is not None:
        raise PlatformError('history-aa-beam','Beam geometry on an unsupported launch appearance',2)
    return style


def validate_unit_action_history(records, policy):
    """Independent finite AA command/step protocol, using the fixed normal one-tick schedule."""
    def f32(value):return struct.unpack('<f',struct.pack('<f',value))[0]
    owner=policy.get('ownerPlayerId','p1')
    previous={};pending={};ends={};automatic={};clock=0
    for row in records:
        if row.get('kind')=='action' and row.get('type')=='UnitAction':
            pending.setdefault(row['unitId'],[]).append(row['actionName'])
        if row.get('kind')!='observation':continue
        sample=row['sample'];step=row.get('phase')=='step'
        if step:clock=int(f32(f32(clock)+f32(16.666666)))
        expected_events=[]
        for unit in sample.get('units',[]):
            if unit.get('ownerPlayerId')!=owner or unit.get('typeId') not in ('aaBeamGunship','aaBeamGunship_afterburn'):continue
            identity=unit['unitId'];old=previous.get(identity);form=old['typeId'] if old else 'aaBeamGunship'
            timer=automatic.get(identity,0)
            queue=pending.setdefault(identity,[])
            if step and old and unit.get('alive') is True:
                name=queue.pop(0) if queue else None
                if name is not None:
                    form='aaBeamGunship_afterburn' if name=='afterBurn' else 'aaBeamGunship'
                    if name=='afterBurn':ends[identity]=clock+25000
                    expected_events.append((identity,'unit-action-completed'))
                if form=='aaBeamGunship_afterburn':
                    timer=max(0,timer-1)
                    # Energy updates before ordinary actions; base afterBurn then sets 6.
                    energy=old['energy'];empty=(old['typeId']=='aaBeamGunship_afterburn' and
                        energy['recharging'] is False and f32(f32(energy['current'])+f32(-.02))<=0)
                    if timer==0 and empty:
                        form='aaBeamGunship';timer=60
                        expected_events.append((identity,'unit-action-automatic'))
            if unit['typeId']!=form:
                raise PlatformError('history-unit-action','Unit form changed outside its normal queue or empty-energy trigger',2)
            state=unit['unitActions'];action=state['actions']['afterBurn']
            if action['cooldownRemaining']!=max(0,ends.get(identity,clock)-clock) or state['automaticCooldown']!=timer:
                raise PlatformError('history-unit-action','Action cooldown differs from accepted commands and the independent clock',2)
            automatic[identity]=timer
            previous[identity]=unit
        if step:
            actual=sorted((e.get('unitId'),e.get('kind')) for e in sample.get('events',[])
                          if e.get('kind') in ('unit-action-completed','unit-action-automatic'))
            if sorted(expected_events)!=actual:
                raise PlatformError('history-unit-action','Missing, extra or wrong unit action completion event',2)
    if any(pending.values()):raise PlatformError('history-unit-action','Normal action queue is unfinished',2)


def validate_bunker_history(records, policy):
    """Replay accepted commands with the fixed binary32 167/101-step queue contract."""
    def f32(value):return struct.unpack('<f',struct.pack('<f',value))[0]
    previous={};pending={};owner=policy.get('ownerPlayerId','p1')
    def fail(message):raise PlatformError('history-bunker',message,2)
    for row in records:
        if row.get('kind')=='action' and row.get('type')=='UnitAction':
            identity=row['unitId'];name=row.get('actionName')
            old=previous.get(identity)
            if (old is None or name!=('deploy' if old['typeId']=='mechBunker' else 'undeploy') or
                    identity in pending or row.get('cancelQueued') is not False):fail('Invalid accepted bunker command')
            pending[identity]=(name,0.0)
        if row.get('kind')!='observation':continue
        sample=row['sample'];step=row.get('phase')=='step';expected=[]
        for unit in sample.get('units',[]):
            if unit.get('ownerPlayerId')!=owner or unit.get('typeId') not in ('mechBunker','mechBunkerDeployed'):continue
            identity=unit['unitId'];old=previous.get(identity);form=old['typeId'] if old else unit['typeId']
            if step and identity in pending:
                name,progress=pending[identity];progress=f32(progress+f32(.006 if name=='deploy' else .010))
                if progress>=1:
                    form='mechBunkerDeployed' if name=='deploy' else 'mechBunker'
                    del pending[identity];expected.append((identity,'unit-action-completed'))
                else:pending[identity]=(name,progress)
            if form!=unit['typeId']:fail('Premature, missing or unsupported bunker conversion')
            state=unit['unitActions'];name='deploy' if form=='mechBunker' else 'undeploy';action=state['actions'][name]
            queue=pending.get(identity)
            if queue is None:
                if action['queueIndex'] is not None or action['progress'] is not None:fail('Undeclared bunker queue')
            elif action['queueIndex']!=0 or f32(action['progress'])!=queue[1]:fail('Bunker queue differs from fixed schedule')
            previous[identity]=unit
        if step:
            actual=sorted((e.get('unitId'),e.get('kind')) for e in sample.get('events',[])
                          if e.get('kind') in ('unit-action-completed','unit-action-automatic'))
            if actual!=sorted(expected):fail('Missing, extra or wrong bunker action completion event')
    if pending:fail('Bunker action queue is unfinished')


def validate_nautilus_history(records,policy):
    """Independent finite action queue and fixed-map water predicates for all intermediate samples."""
    def fail(message):raise PlatformError('history-nautilus-transition',message,2)
    def f32(value):return struct.unpack('f',struct.pack('f',value))[0]
    terrain=policy['nautilusTerrain']
    if (not isinstance(terrain,dict) or set(terrain)!={'width','height','tileWidth','tileHeight','waterCells'} or
            any(type(terrain[k]) is not int or terrain[k]<=0 for k in ('width','height','tileWidth','tileHeight')) or
            not isinstance(terrain['waterCells'],list) or len(set(terrain['waterCells']))!=len(terrain['waterCells']) or
            any(type(i) is not int or not 0<=i<terrain['width']*terrain['height'] for i in terrain['waterCells'])):
        raise PlatformError('input-contract','Invalid fixed Nautilus water cells',1)
    water=set(terrain['waterCells']);states={};owner=policy.get('ownerPlayerId','p1')
    forms={'nautilusSubmarine','nautilusSubmarineSurface','nautilusSubmarineLand'}
    for row in records:
        if row.get('kind')=='action' and row.get('type')=='UnitAction' and row.get('unitId') in states:
            state=states[row['unitId']];name=row.get('actionName')
            if (row.get('actorPlayerId')!=owner or row.get('result',{}).get('accepted') is not True or
                    name!={'nautilusSubmarine':'surface','nautilusSubmarineSurface':'dive'}.get(state['form']) or
                    row.get('cancelQueued') is not False or state['pending'] is not None):fail('Action does not bind the exposed empty queue')
            state.update(pending=name,progress=0)
        if row.get('kind')!='observation':continue
        sample=row['sample'];expected=[]
        for unit in sample.get('units',[]):
            if unit.get('ownerPlayerId')!=owner or unit.get('typeId') not in forms and unit.get('unitId') not in states:continue
            if unit.get('typeId') not in forms:fail('Known mother changed to an unsupported form')
            identity=unit['unitId'];first=identity not in states
            state=states.setdefault(identity,dict(form=unit['typeId'],timer=0,pending=None,progress=0))
            if not first and row.get('phase')=='step' and unit.get('alive') is True and unit.get('constructionProgress')==1:
                name=state['pending']
                if name:
                    state['progress']=f32(state['progress']+(2000 if name=='surface' else f32(f32(1/270)*2)))
                    if state['progress']>=1:
                        state.update(form='nautilusSubmarineSurface' if name=='surface' else 'nautilusSubmarine',pending=None,progress=0)
                        expected.append((identity,'unit-action-completed'))
                state['timer']=max(0,state['timer']-1)
                x=int(f32(unit['position']['x'])/terrain['tileWidth']);y=int(f32(unit['position']['y'])/terrain['tileHeight'])
                if not 0<=x<terrain['width'] or not 0<=y<terrain['height']:fail('Position outside fixed water grid')
                wet=y*terrain['width']+x in water
                if state['timer']==0 and (not wet or state['form']=='nautilusSubmarineLand'):
                    state.update(form='nautilusSubmarineSurface' if wet else 'nautilusSubmarineLand',timer=60,pending=None,progress=0)
                    expected.append((identity,'unit-action-automatic'))
            if unit['typeId']!=state['form']:fail('Form differs from accepted queue and fixed terrain')
            actions=unit.get('unitActions') or {}
            if actions.get('automaticCooldown')!=state['timer']:fail('Automatic clock differs')
            names={'nautilusSubmarine':{'surface'},'nautilusSubmarineSurface':{'dive'},'nautilusSubmarineLand':set()}[state['form']]
            if set(actions.get('actions',{}))!=names:fail('Action set differs from form')
            for name,entry in actions['actions'].items():
                if (entry.get('cooldownRemaining')!=0 or entry.get('queueIndex')!=(0 if state['pending']==name else None) or
                        (type(entry.get('progress')) not in (int,float) or f32(entry['progress'])!=state['progress']
                         if state['pending']==name else entry.get('progress') is not None)):fail('Queue differs from accepted action and float clock')
        if row.get('phase')=='step':
            actual=[(e.get('unitId'),e.get('kind')) for e in sample.get('events',[]) if e.get('unitId') in states and
                    e.get('kind') in ('unit-action-completed','unit-action-automatic')]
            if sorted(actual)!=sorted(expected):fail('Missing, premature or extra conversion event')


def compare_history(records, plan, policy, native_anchors=None, geometry=None):
    """Check a declared ledger at every observation, including call boundaries."""
    allowed={'initialBalance','spend','noActionIntents','projectileBirthsRequired','mechLegs',
             'ownerPlayerId','initialOwnedTypes','finalUnits','minimumBirths',
             'productionOrder','completedUpgradeTypes','initialIncome','producedIncome','transportStages','unitActionStages','bunkerStages','reclaimRefunds','nautilusTerrain','nuclearHistory'}
    if not isinstance(policy,dict) or set(policy)-allowed:
        raise PlatformError('input-contract', 'Unknown or malformed history obligations', 1)
    balance = policy.get('initialBalance')
    if type(balance) not in (int, float) or not math.isfinite(balance):
        raise PlatformError('input-contract', 'History requires a finite initial balance', 1)
    income=policy.get('initialIncome')
    if income is not None and (not isinstance(income,dict) or set(income)!={'unitId','typeId','period','credits'} or
            any(not isinstance(income[k],str) or not income[k] for k in ('unitId','typeId')) or
            type(income['period']) is not int or income['period']<=0 or
            type(income['credits']) not in (int,float) or not math.isfinite(income['credits']) or income['credits']<=0):
        raise PlatformError('input-contract', 'Expected a fixed initial income source, positive integer period and finite credits', 1)
    produced_income=policy.get('producedIncome',[])
    if (not isinstance(produced_income,list) or any(not isinstance(source,dict) or
            set(source)!={'typeId','period','credits'} or not isinstance(source['typeId'],str) or not source['typeId'] or
            type(source['period']) is not int or source['period']<=0 or
            type(source['credits']) not in (int,float) or not math.isfinite(source['credits']) or source['credits']<=0
            for source in produced_income) or len({source['typeId'] for source in produced_income})!=len(produced_income)):
        raise PlatformError('input-contract','Expected unique fixed produced income types with finite positive coefficients',1)
    income_types={source['typeId']:source for source in produced_income}
    income_phases={};income_seen=set()
    charges = {}
    intents = {i['intentId']: i for i in plan['intents']}
    ids = set(intents)
    refunds={}
    declarations=policy.get('reclaimRefunds',[])
    if not isinstance(declarations,list):raise PlatformError('input-contract','Expected fixed reclaim refund declarations',1)
    for refund in declarations:
        if (not isinstance(refund,dict) or set(refund)!={'intentId','typeId','price'} or
                not isinstance(refund['intentId'],str) or refund['intentId'] not in ids or refund['intentId'] in refunds or
                intents[refund['intentId']].get('kind')!='reclaim' or not isinstance(refund['typeId'],str) or not refund['typeId'] or
                type(refund['price']) is not int or refund['price']<0):
            raise PlatformError('input-contract','Invalid fixed native reclaim refund',1)
        refunds[refund['intentId']]=refund
    pending_refunds={};refunded=set()
    action_stages={}
    bunker_stages={}
    declarations=policy.get('bunkerStages',[])
    if not isinstance(declarations,list):raise PlatformError('input-contract','Expected fixed bunker stages',1)
    for stage in declarations:
        if (not isinstance(stage,dict) or set(stage)!={'intentId','typeId'} or
                not isinstance(stage['intentId'],str) or stage['intentId'] not in ids or stage['intentId'] in bunker_stages or
                stage['typeId'] not in ('mechBunker','mechBunkerDeployed')):
            raise PlatformError('input-contract','Invalid fixed bunker stage',1)
        bunker_stages[stage['intentId']]=stage
    declarations=policy.get('unitActionStages',[])
    if not isinstance(declarations,list):
        raise PlatformError('input-contract','Unit action stages must be a fixed list',1)
    for stage in declarations:
        if (not isinstance(stage,dict) or set(stage)!={'intentId','typeId','cooldown'} or
                not isinstance(stage['intentId'],str) or stage['intentId'] not in ids or stage['intentId'] in action_stages or
                stage['typeId'] not in ('aaBeamGunship','aaBeamGunship_afterburn') or
                stage['cooldown'] not in ('active','ready')):
            raise PlatformError('input-contract','Invalid fixed unit action stage',1)
        action_stages[stage['intentId']]=stage
    transport_stages={}
    declarations=policy.get('transportStages',[])
    if not isinstance(declarations,list):
        raise PlatformError('input-contract','Transport stages must be a fixed list',1)
    for stage in declarations:
        if (not isinstance(stage,dict) or set(stage)-{'intentId','typeId','passengerCount','unloading','minimumX','maximumX'} or
                not {'intentId','passengerCount','unloading'}<=set(stage) or stage['intentId'] not in ids or
                not isinstance(stage.get('typeId','hovercraft'),str) or
                stage.get('typeId','hovercraft') not in {'hovercraft','dropship','nautilusSubmarine','nautilusSubmarineSurface','nautilusSubmarineLand'} or
                stage['intentId'] in transport_stages or type(stage['passengerCount']) is not int or stage['passengerCount']<0 or
                type(stage['unloading']) is not bool or any(type(stage[k]) not in (int,float) or not math.isfinite(stage[k])
                    for k in ('minimumX','maximumX') if k in stage) or
                stage.get('minimumX',-math.inf)>stage.get('maximumX',math.inf)):
            raise PlatformError('input-contract','Invalid fixed transport stage',1)
        transport_stages[stage['intentId']]=stage
    no_actions = policy.get('noActionIntents', [])
    if (not isinstance(no_actions, list) or len(no_actions) != len(set(no_actions)) or
            any(i not in ids or intents[i].get('kind') != 'select' for i in no_actions)):
        raise PlatformError('input-contract', 'Only fixed empty selections may declare no formal action', 1)
    require_births = policy.get('projectileBirthsRequired', False)
    if type(require_births) is not bool:
        raise PlatformError('input-contract', 'Projectile birth coverage must be explicitly boolean', 1)
    known_projectiles = set()
    launch_appearances = {}
    births=policy.get('minimumBirths',[])
    if (not isinstance(births,list) or any(not isinstance(b,dict) or
            set(b)!={'ownerPlayerId','typeId','weaponNames','targetUnitId'} or
            not isinstance(b['weaponNames'],list) or not b['weaponNames'] for b in births)):
        raise PlatformError('input-contract', 'Expected fixed firing source/type/weapon/target obligations', 1)
    fired=[False]*len(births)
    declared_charges=policy.get('spend',[])
    if not isinstance(declared_charges,list):
        raise PlatformError('input-contract', 'History spend must be a list', 1)
    for charge in declared_charges:
        if (not isinstance(charge,dict) or set(charge)!={'intentId','amount','at'} or
                not isinstance(charge['intentId'],str)):
            raise PlatformError('input-contract', 'Expected a fixed history charge with intentId, amount and at', 1)
        identity = charge.get('intentId'); amount = charge.get('amount')
        if (identity not in ids or identity in charges or type(amount) not in (int, float) or
                not math.isfinite(amount) or amount <= 0 or charge.get('at') not in ('action', 'first-step', 'construction-created') or
                charge.get('at')=='construction-created' and intents[identity].get('kind')!='build'):
            raise PlatformError('input-contract', 'Invalid or duplicate fixed history charge', 1)
        charges[identity] = charge
        if charge['at']=='construction-created':
            target=intents[identity].get('position')
            if (not isinstance(intents[identity].get('typeId'),str) or not intents[identity]['typeId'] or
                    not isinstance(target,dict) or set(target)!={'x','y'} or
                    any(type(v) not in (int,float) or not math.isfinite(v) for v in target.values())):
                raise PlatformError('input-contract', 'Construction charge requires a fixed building type and target', 1)
    active, pending, observations = None, 0, 0
    completed, steps, intent_steps, initial_step, action_count = 0, 0, 0, None, 0
    action_ids = set()
    construction_charges, construction_ids, seen_units = {}, set(), set()
    stream=records
    if native_anchors is not None:
        if ([a.get('intentId') for a in native_anchors]!=[i['intentId'] for i in plan['intents']] or
                any(type(a.get(k)) is not int for a in native_anchors for k in ('afterSequence','endSequence')) or
                any(a['afterSequence']>=a['endSequence'] for a in native_anchors) or
                any(a['endSequence']>=b['afterSequence'] for a,b in zip(native_anchors,native_anchors[1:])) or
                any(a['afterSequence']<0 or a['endSequence']>=len(records) for a in native_anchors)):
            raise PlatformError('history-intent','Invalid recorded native input boundaries',1)
        starts={a['afterSequence']+1:a['intentId'] for a in native_anchors}
        ends={a['endSequence']:a['intentId'] for a in native_anchors}
        stream=[]
        for row in records:
            if row['sequence'] in starts:stream.append(dict(kind='intent-start',intentId=starts[row['sequence']]))
            stream.append(row)
            if row['sequence'] in ends:stream.append(dict(kind='intent-end',intentId=ends[row['sequence']]))
    for row in stream:
        if row.get('kind') == 'intent-start':
            if (active is not None or completed >= len(plan['intents']) or
                    row.get('intentId') != plan['intents'][completed]['intentId']):
                raise PlatformError('history-intent', 'Missing or reordered fixed client intention')
            active = row.get('intentId')
            intent_steps = 0; action_count = 0
        elif row.get('kind') == 'intent-end':
            if active in construction_charges:
                raise PlatformError('history-construction', 'Build intention lacks its unique new construction site', 2)
            if (active is None or row.get('intentId') != active or
                    (intent_steps < intents[active].get('waitSteps', 0) if native_anchors is not None
                     else intent_steps != intents[active].get('waitSteps', 0)) or
                    action_count != (0 if active in no_actions else 1)):
                raise PlatformError('history-intent', 'Unfinished intention or wrong explicit step count')
            stage=bunker_stages.get(active)
            if stage is not None:
                candidates=[u for u in units.values() if u.get('ownerPlayerId')==policy.get('ownerPlayerId','p1') and
                            u.get('typeId') in ('mechBunker','mechBunkerDeployed')]
                if len(candidates)!=1 or candidates[0]['typeId']!=stage['typeId']:
                    raise PlatformError('history-bunker','Missing or wrong form at fixed stage',2)
            stage=transport_stages.get(active)
            if stage is not None:
                carriers=[u for u in units.values() if u.get('typeId')==stage.get('typeId','hovercraft') and u.get('ownerPlayerId')==policy.get('ownerPlayerId','p1')]
                if len(carriers)!=1:
                    raise PlatformError('history-transport-stage','Missing or ambiguous stage carrier',2)
                carrier=carriers[0];cargo=carrier.get('transport') or {};x=(carrier.get('position') or {}).get('x')
                if (cargo.get('passengerCount')!=stage['passengerCount'] or cargo.get('unloading') is not stage['unloading'] or
                        type(x) not in (int,float) or not math.isfinite(x) or
                        not stage.get('minimumX',-math.inf)<=x<=stage.get('maximumX',math.inf)):
                    raise PlatformError('history-transport-stage','Cargo or shore position differs from declared stage',2)
            stage=action_stages.get(active)
            if stage is not None:
                candidates=[u for u in units.values() if u.get('ownerPlayerId')==policy.get('ownerPlayerId','p1') and
                            u.get('typeId') in ('aaBeamGunship','aaBeamGunship_afterburn')]
                if len(candidates)!=1 or candidates[0]['typeId']!=stage['typeId']:
                    raise PlatformError('history-unit-action-stage','Missing, ambiguous or wrong unit form at fixed boundary',2)
                action=candidates[0]['unitActions']['actions']['afterBurn']
                if ((action['cooldownRemaining']>0)!=(stage['cooldown']=='active') or
                        action['available']!=(stage['cooldown']=='ready') or action['queueIndex'] is not None):
                    raise PlatformError('history-unit-action-stage','Action cooldown or availability differs from fixed boundary',2)
            active = None
            completed += 1
        elif row.get('kind') == 'action':
            outcome = row.get('result', {})
            action_id = outcome.get('actionId')
            if (outcome.get('accepted') is not True or not isinstance(action_id, str) or
                    not action_id or action_id in action_ids):
                raise PlatformError('history-action', 'Missing, rejected or reused formal action', 2)
            action_ids.add(action_id); action_count += 1
            expected_type = {'select':'SelectAction', 'build':'BuildAction', 'upgrade':'UpgradeAction',
                'produce':'ProduceAction', 'move':'MoveAction', 'stop':'StopAction', 'attack':'AttackAction',
                'submarine-depth':'SubmarineDepthAction','amphibious-flight':'AmphibiousFlightAction',
                'load':'LoadAction','unload':'UnloadAction','unit-action':'UnitAction','repair':'RepairUnitAction','reclaim':'ReclaimUnitAction'}.get(
                    intents.get(active, {}).get('kind'))
            if expected_type is None or row.get('type') != expected_type:
                raise PlatformError('history-action', 'Action type differs from the fixed client intention', 2)
            context=None
            if geometry is not None:
                viewport=next((r for r in reversed(records) if r.get('kind')=='resize' and r['sequence']<row['sequence']),None)
                if viewport is None:raise PlatformError('input-geometry','History lacks a formal viewport')
                context=dict(geometry,viewport=viewport)
            validate_history_action(records,row,intents[active],context)
            if active in refunds:
                target=row.get('targetUnitId')
                before=records[row['sequence']-1]['sample']
                matches=[u for u in before.get('units',[]) if u.get('unitId')==target and
                         u.get('typeId')==refunds[active]['typeId'] and u.get('ownerPlayerId')==row.get('actorPlayerId')]
                if len(matches)!=1 or target in pending_refunds or target in refunded:
                    raise PlatformError('history-reclaim','Refund must bind one fresh owned target',2)
                pending_refunds[target]=refunds[active]
            charge = charges.get(active)
            if charge:
                if charge['at'] == 'action': balance -= charge['amount']
                elif charge['at'] == 'first-step': pending += charge['amount']
                else: construction_charges[active] = dict(charge=charge, action=row)
        elif row.get('kind') == 'observation':
            sample = row['sample']
            index, simulation_time = sample.get('stepIndex'), sample.get('simulationTime')
            if initial_step is None:
                if row.get('phase') != 'initial' or type(index) is not int or index not in (-1, 0):
                    raise PlatformError('history-step', 'History must start at a fresh initial observation')
                initial_step = index
                if 'initialOwnedTypes' in policy:
                    actual=sorted(u.get('typeId') for u in sample.get('units',[]) if u.get('ownerPlayerId')==policy.get('ownerPlayerId','p1'))
                    if actual!=sorted(policy['initialOwnedTypes']):
                        raise PlatformError('history-initial', 'Initial owned units differ from the fixed starting chain', 2)
            if row.get('phase') == 'step':
                if (active is None and native_anchors is None) or index != steps:
                    raise PlatformError('history-step', 'Missing or reordered simulation step')
                steps += 1; intent_steps += 1
            elif index != max(initial_step, steps-1):
                raise PlatformError('history-step', 'Call observation differs from its simulation boundary')
            if type(simulation_time) not in (int, float) or simulation_time != steps:
                raise PlatformError('history-step', 'Simulation time differs from explicit steps')
            units = {u['unitId']: u for u in row['sample'].get('units', [])}
            if income_types:
                if len(units)!=len(sample.get('units',[])):
                    raise PlatformError('history-income','Repeated unit identity in income evidence',2)
                if row.get('phase')=='step':
                    for event in sample.get('events',[]):
                        if event.get('kind')!='unit-produced':continue
                        unit=units.get(event.get('unitId'),{})
                        if unit.get('typeId') not in income_types or unit.get('ownerPlayerId')!=policy.get('ownerPlayerId','p1'):continue
                        identity=unit['unitId']
                        if identity in income_phases:
                            raise PlatformError('history-income','Repeated produced income birth',2)
                        income_phases[identity]=dict(typeId=unit['typeId'],birth=index,elapsed=0)
                        income_seen.add(unit['typeId'])
                for identity,state in income_phases.items():
                    unit=units.get(identity)
                    if unit is None or unit.get('typeId')!=state['typeId'] or unit.get('ownerPlayerId')!=policy.get('ownerPlayerId','p1'):
                        raise PlatformError('history-income','Produced income source identity disappeared or changed',2)
                if any(u.get('ownerPlayerId')==policy.get('ownerPlayerId','p1') and u.get('typeId') in income_types and
                        u.get('unitId') not in income_phases for u in units.values()):
                    raise PlatformError('history-income','Produced income requires its observed fresh birth',2)
            if income is not None:
                sources=[u for u in sample.get('units',[]) if u.get('unitId')==income['unitId']]
                if (len(sources)!=1 or sources[0].get('typeId')!=income['typeId'] or
                        sources[0].get('ownerPlayerId')!=policy.get('ownerPlayerId','p1') or
                        sources[0].get('alive') is not True or sources[0].get('constructionProgress')!=1):
                    raise PlatformError('history-income', 'Fixed initial income source changed or is not a live completed owned unit', 2)
            validate_history_poses(units.values(), policy)
            for event in sample.get('projectileEvents',[]):
                source=units.get(event.get('sourceUnitId'),{})
                style=validate_launch_appearance(event,units)
                token=event.get('token')
                if event.get('kind')=='born':
                    if token in launch_appearances and launch_appearances[token]!=style:
                        raise PlatformError('history-projectile','Repeated birth changed launch appearance',2)
                    launch_appearances[token]=style
                elif token in launch_appearances and launch_appearances[token]!=style:
                    raise PlatformError('history-projectile','Projectile event changed launch appearance',2)
                for i,expected in enumerate(births):
                    if (event.get('kind')=='born' and source.get('ownerPlayerId')==expected['ownerPlayerId'] and
                            source.get('typeId')==expected['typeId'] and event.get('sourceWeaponName') in expected['weaponNames'] and
                            event.get('targetUnitId')==expected['targetUnitId']):fired[i]=True
            for projectile in sample.get('projectiles',[]):
                style=validate_launch_appearance(projectile,units)
                token=projectile.get('token')
                if token in launch_appearances and launch_appearances[token]!=style:
                    raise PlatformError('history-projectile','Observed projectile changed launch appearance',2)
            if require_births:
                for event in sample.get('projectileEvents', []):
                    if event.get('kind') == 'born':
                        token = event.get('token')
                        if not isinstance(token, str) or not token:
                            raise PlatformError('history-projectile', 'Missing actual birth token', 2)
                        known_projectiles.add(token)
                for projectile in sample.get('projectiles', []):
                    if projectile.get('token') not in known_projectiles:
                        raise PlatformError('history-projectile', 'Observed projectile has no declared visible birth', 2)
            for weapon in row['sample'].get('weapons', []):
                unit = units.get(weapon.get('unitId'))
                if unit is None or unit.get('alive') is not True:
                    raise PlatformError('history-weapons', 'An absent or dead unit retains a weapon observation', 2)
            if row.get('phase') == 'step':
                balance -= pending; pending = 0
                if income is not None and steps % income['period']==0:balance+=income['credits']
                for identity,state in income_phases.items():
                    source=units[identity]
                    if (index>state['birth'] and source.get('alive') is True and source.get('constructionProgress')==1 and
                            source.get('carriedBy') is None):
                        state['elapsed']+=1
                        profile=income_types[state['typeId']]
                        if state['elapsed']==profile['period']:
                            state['elapsed']=0;balance+=profile['credits']
                events=sample.get('events',[])
                removals=[e.get('unitId') for e in events if e.get('kind')=='building-reclaimed']
                if len(removals)!=len(set(removals)) or any(target not in pending_refunds for target in removals):
                    raise PlatformError('history-reclaim','Missing or repeated target-bound reclaim',2)
                for target in removals:
                    if target in units or any(e.get('kind')=='unit-died' and e.get('unitId')==target for e in events):
                        raise PlatformError('history-reclaim','Native reclaim must remove its target without combat death',2)
                    # Native default is Double(price) * Double(Float(.8)), not a rounded percentage.
                    balance+=pending_refunds.pop(target)['price']*0.800000011920929
                    refunded.add(target)
                if construction_ids and any(event.get('kind')=='construction-created' and event.get('unitId') in construction_ids for event in events):
                    raise PlatformError('history-construction', 'A charged construction site was created again', 2)
                for identity, entry in list(construction_charges.items()):
                    action=entry['action'];intent=intents[identity]
                    matches=[event['unitId'] for event in events if event.get('kind')=='construction-created' and
                        (unit:=units.get(event.get('unitId'))) is not None and
                        unit.get('typeId')==intent.get('typeId') and unit.get('ownerPlayerId')==action.get('actorPlayerId') and
                        unit.get('position')==intent.get('position')]
                    if matches:
                        if len(matches)!=1 or matches[0] in seen_units or identity!=active:
                            raise PlatformError('history-construction', 'Construction charge is not bound to one new owned site in its build intention', 2)
                        balance-=entry['charge']['amount'];construction_ids.add(matches[0]);del construction_charges[identity]
            observed = row['sample'].get('balance')
            expected_balance=math.trunc(balance) if income is not None or income_types else balance
            if type(observed) not in (int, float) or not math.isfinite(observed) or observed != expected_balance:
                raise PlatformError('history-ledger', 'Balance differs at observation '+str(row['sequence']), 2)
            if (income is not None or income_types) and (type(sample.get('credits')) not in (int,float) or
                    not math.isfinite(sample['credits']) or sample['credits']!=balance):
                raise PlatformError('history-ledger', 'Precise credits differ at observation '+str(row['sequence']), 2)
            observations += 1
            seen_units.update(units)
    if income_seen!=set(income_types):
        raise PlatformError('history-income','A declared produced income type never completed a real birth',2)
    if not observations or pending or construction_charges or pending_refunds or active is not None or completed != len(plan['intents']):
        raise PlatformError('incomplete-run', 'Missing history observations or a pending first-step charge')
    for expected in policy.get('finalUnits',[]):
        if not isinstance(expected,dict) or not isinstance(expected.get('fields'),dict) or not expected['fields']:
            raise PlatformError('input-contract', 'Expected unique final unit selectors and scalar fields', 1)
        selected=[u for u in units.values() if (u.get('unitId')==expected['unitId'] if 'unitId' in expected else
            u.get('ownerPlayerId')==expected.get('ownerPlayerId') and u.get('typeId')==expected.get('typeId'))]
        if len(selected)!=1:
            raise PlatformError('history-final', 'Missing or ambiguous fixed terminal unit', 2)
        for name,wanted in expected['fields'].items():
            actual=selected[0].get(name)
            if isinstance(wanted,(list,dict)):
                raise PlatformError('input-contract', 'Final history fields must be scalar predicates', 1)
            if name not in selected[0] or (type(actual) is bool)!=(type(wanted) is bool) or actual!=wanted:
                raise PlatformError('history-final', 'Terminal unit differs: '+name, 2)
    validate_production_history(records,policy)
    if 'unitActionStages' in policy:validate_unit_action_history(records,policy)
    if 'bunkerStages' in policy:validate_bunker_history(records,policy)
    if 'nautilusTerrain' in policy:validate_nautilus_history(records,policy)
    if 'nuclearHistory' in policy:
        if type(policy['nuclearHistory']) is not bool:raise PlatformError('input-contract','Expected explicit nuclear history flag',1)
        from nuclear_platform import validate_nuclear_history
        validate_nuclear_history(records,policy)
    if any(intent.get('actionName')=='buildAntiNuke' for intent in plan['intents']):
        from nuclear_platform import validate_interceptor_history
        validate_interceptor_history(records,policy)
    if not all(fired):raise PlatformError('history-firing', 'Missing independently required named weapon birth/target', 2)
    return dict(exitCode=0, status='passed', scenarioId=plan['scenarioId'], history=dict(observations=observations, balance=balance))


def compare_run(records, plan, contract, anchors, geometry=None):
    intents, rules = plan['intents'], contract['rules']
    ids = [intent['intentId'] for intent in intents]
    if (not records or not ids or len(anchors) != len(ids) or
            not any(row.get('kind') == 'closed' for row in records)):
        raise PlatformError('incomplete-run', 'Missing calls or normal Session closing tail')
    if (len(set(ids)) != len(ids) or [rule['intentId'] for rule in rules] != ids or
            [anchor['intentId'] for anchor in anchors] != ids or
            plan.get('scenarioId') != contract.get('scenarioId')):
        raise PlatformError('input-contract', 'Plan, contract, and intent boundaries differ', 1)
    if [row.get('sequence') for row in records] != list(range(len(records))):
        raise PlatformError('record-sequence', 'Diagnostic records are truncated or out of order')
    # Old Session archives did not label observations. Keep that reader, but a
    # labelled run must never mix unlabelled or stale observations from a reopen.
    world_rows = [r for r in records if r.get('kind') in ('observation', 'action', 'closed')]
    if any('generation' in r for r in world_rows):
        generations = [r.get('generation') for r in world_rows]
        if (any(type(g) is not int or g < 1 for g in generations) or
                len(set(generations)) != 1 or any(
                    r.get('generation') != generations[0] for r in records if r.get('kind') == 'load-ready')):
            raise PlatformError('world-generation', 'Current observations, actions and closing tail must share one loaded generation')
    worlds = {row['sample']['worldId'] for row in records if row.get('kind') == 'observation'}
    if len(worlds) != 1 or not next(iter(worlds), None):
        raise PlatformError('world-identity', 'A short scenario must remain in one nonempty world')
    if any('error' in row.get('kind', '') or row.get('kind') in ('load-failed', 'capture-failed') for row in records):
        raise PlatformError('closing-tail', 'An application or capture error invalidates the completed scenario')
    closes = [row for row in records if row.get('kind') == 'closed']
    if len(closes) != 1 or closes[0]['sequence'] <= anchors[-1]['endSequence']:
        raise PlatformError('closing-tail', 'Exactly one closing record must follow the final intent')
    close_index = closes[0]['sequence']
    if (close_index < 1 or records[close_index-1].get('phase') != 'before-close' or
            records[close_index-1].get('kind') != 'observation'):
        raise PlatformError('closing-tail', 'Session close lacks its adjacent final observation')
    if geometry is not None:
        lifecycle = ['match-exit', 'presentation-released', 'application-disposed', 'process-completed']
        tail = records[close_index+1:]
        if ([row.get('kind') for row in tail] != lifecycle or tail[-1].get('status') != 'normal'):
            raise PlatformError('closing-tail', 'Windows process did not finish its normal lifecycle in order')
        first_start = anchors[0]['afterSequence']
        viewport = next((row for row in reversed(records) if row.get('kind') == 'resize' and
                         row['sequence'] <= first_start), None)
        if (viewport is None or (viewport.get('width'), viewport.get('height')) not in ((1200, 800), (960, 640)) or
                viewport.get('screenWidth') != viewport['width'] or viewport.get('screenHeight') != viewport['height']):
            raise PlatformError('input-geometry', 'No registered actual viewport before input delivery')
        keys = ('width', 'height', 'screenWidth', 'screenHeight', 'screenX', 'screenY')
        if any(any(row.get(key) != viewport.get(key) for key in keys) for row in records
               if row.get('kind') == 'resize' and row['sequence'] > first_start):
            raise PlatformError('input-geometry', 'Viewport changed during the fixed platform scenario')
    elif close_index != len(records)-1:
        raise PlatformError('closing-tail', 'Unexpected records after Session close')
    if 'history' in contract:
        return compare_history(records, plan, contract['history'], anchors if geometry is not None else None, geometry)
    judgments = []
    previous_end = -1
    for intent, rule, anchor in zip(intents, rules, anchors):
        start, end = anchor['afterSequence'], anchor['endSequence']
        if type(start) is not int or type(end) is not int or not previous_end < start < end < len(records):
            raise PlatformError('intent-boundary', 'Invalid or overlapping intent boundaries')
        window = [row for row in records if start < row['sequence'] <= end]
        context = None
        if geometry is not None:
            viewport = next((row for row in reversed(records) if row.get('kind') == 'resize' and row['sequence'] <= start), None)
            if viewport is None:
                raise PlatformError('input-geometry', 'No actual viewport before platform delivery')
            context = dict(geometry, viewport=viewport)
        judgment = compare_intent(window, intent, rule, start, context)
        judgments.append(dict(intentId=intent['intentId'], **judgment))
        previous_end = end
    actions = [row for row in records if row.get('kind') == 'action']
    if ({row['sequence'] for row in actions} != {j['actionSequence'] for j in judgments} or
            len({j['actionId'] for j in judgments}) != len(judgments)):
        raise PlatformError('action-identity', 'Unclaimed or reused formal actions')
    result = dict(exitCode=0, status='passed', scenarioId=plan['scenarioId'], judgments=judgments)
    if 'resourcePacks' in plan or 'resourceImages' in contract:
        result['resourceImages'] = compare_resource_images(records, contract.get('resourceImages'))
    if 'resourceSounds' in contract:
        result['resourceSounds'] = compare_resource_sounds(records, contract['resourceSounds'])
    return result


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, document):
    with Path(path).open('x', encoding='utf8') as stream:
        json.dump(document, stream, indent=2, ensure_ascii=False, allow_nan=False)
        stream.write('\n')


def read(path):
    document = json.loads(Path(path).read_text(encoding='utf8'))
    if isinstance(document, dict) and document.get('kind') == 'behavior-contract' and 'platform' in document:
        return document['platform']
    return document


def tree(root):
    root = Path(root)
    paths = sorted(root.rglob('*'), key=lambda path: path.relative_to(root).as_posix())
    if any(path.is_symlink() for path in paths) or root.is_symlink():
        raise PlatformError('input-identity', 'Symbolic links are not fixed input files', 1)
    return [dict(path=path.relative_to(root).as_posix(), sha256=sha(path)) for path in paths if path.is_file()]


def session_anchors(records):
    markers = [r for r in records if r.get('kind') in ('intent-start', 'intent-end')]
    if len(markers) % 2:
        raise PlatformError('incomplete-run', 'Interrupted client intention')
    anchors = []
    for start, end in zip(markers[::2], markers[1::2]):
        if (start['kind'] != 'intent-start' or end['kind'] != 'intent-end' or
                start.get('intentId') != end.get('intentId')):
            raise PlatformError('intent-boundary', 'Invalid start/end pairing')
        anchors.append(dict(intentId=start['intentId'], afterSequence=start['sequence'], endSequence=end['sequence']-1))
    return anchors


def windows_geometry(output,records):
    graphics=[r for r in records if r.get('kind')=='load-graphics-ready']
    ready=[r for r in records if r.get('kind')=='load-ready']
    if not graphics:
        # Preserve pre-instrumentation short archives without inventing a new map choice.
        path=Path(output)/'inputSnapshot/maps/p001-controlled.tmx'
        if not path.is_file():raise PlatformError('input-geometry','Legacy archive has no fixed controlled map')
        root=ET.parse(path).getroot()
        return dict(worldWidth=int(root.get('width'))*int(root.get('tilewidth')),
                    worldHeight=int(root.get('height'))*int(root.get('tileheight')))
    if len(graphics)!=1 or len(ready)!=1 or graphics[0].get('generation')!=ready[0].get('generation'):
        raise PlatformError('input-geometry','One successful graphics/map generation required')
    g,r=graphics[0],ready[0]
    width,height=g.get('worldWidth'),g.get('worldHeight')
    if any(type(v) not in (int,float) or not math.isfinite(v) or v<=0 for v in (width,height)):
        raise PlatformError('input-geometry','Finite positive loaded map dimensions required')
    for path in sorted((Path(output)/'inputSnapshot/maps').glob('*.tmx')):
        root=ET.parse(path).getroot()
        if (int(root.get('width'))==r.get('mapWidth') and int(root.get('height'))==r.get('mapHeight') and
                int(root.get('width'))*int(root.get('tilewidth'))==width and
                int(root.get('height'))*int(root.get('tileheight'))==height):
            return dict(worldWidth=width,worldHeight=height)
    raise PlatformError('input-geometry','Loaded graphics geometry has no matching frozen input map')


def compare_archive(output):
    output = Path(output)
    archive = read(output / 'archive.json')
    if archive.get('complete') is not True:
        raise PlatformError('incomplete-run', 'Adapter did not finish normally')
    backend = archive.get('backend')
    files = archive.get('files')
    if (backend not in ('session', 'windows-input') or not isinstance(files, list) or not files or
            any(not isinstance(item, dict) or not isinstance(item.get('path'), str) or
                not isinstance(item.get('sha256'), str) for item in files)):
        raise PlatformError('archive-identity', 'A registered backend and nonempty file identities are required')
    names = [item['path'] for item in files]
    required = {'manifest.json', 'scenario.json', 'contract.json', 'intents.json',
                'session/client-diagnostic.jsonl', 'stdout.log', 'stderr.log'}
    actual = {item['path'] for item in tree(output)} - {'archive.json', 'result.json'}
    if len(names) != len(set(names)) or set(names) != actual or not required <= set(names):
        raise PlatformError('archive-identity', 'Archive file closure is incomplete or duplicated')
    for item in files:
        path = (output / item['path']).resolve()
        if (not path.is_relative_to(output.resolve()) or path.relative_to(output.resolve()).as_posix() != item['path'] or
                not path.is_file() or sha(path) != item['sha256']):
            raise PlatformError('archive-identity', 'Archived evidence differs: '+item['path'])
    manifest = read(output/'manifest.json')
    if (manifest.get('backend') != backend or not manifest.get('packageFiles') or
            manifest.get('scenarioSha256') != sha(output/'scenario.json') or
            manifest.get('contractSha256') != sha(output/'contract.json') or
            not manifest.get('inputFiles') or
            sorted(manifest['inputFiles'], key=lambda item: item['path']) != tree(output/'inputSnapshot')):
        raise PlatformError('archive-identity', 'Manifest, fixed inputs, scenario or contract identity differs')
    plan = read(output/'scenario.json')
    if backend == 'windows-input':
        pictures = {intent['intentId']+'.png' for intent in plan['intents']}
        pictures |= {intent['intentId']+'-preview.png' for intent in plan['intents'] if intent['kind'] == 'build'}
        if not {'input.jsonl', 'runtime/reforge/client-diagnostic.jsonl', *pictures} <= set(names):
            raise PlatformError('archive-identity', 'Windows input, screenshots or runtime diagnostic is missing')
        if (output/'runtime/reforge/client-diagnostic.jsonl').read_bytes() != (output/'session/client-diagnostic.jsonl').read_bytes():
            raise PlatformError('archive-identity', 'Runtime and compared diagnostic differ')
        input_rows = [json.loads(line) for line in (output/'input.jsonl').read_text(encoding='utf8').splitlines()]
        if [r.get('intentId') for r in input_rows if r.get('kind') == 'intent-dispatch'] != [i['intentId'] for i in plan['intents']]:
            raise PlatformError('archive-identity', 'Actual input delivery records differ from the fixed intents')
        captures = {Path(r['path']).name for r in input_rows if r.get('kind') == 'captured-owned-no-activation'}
        if not pictures <= captures or not any(r.get('kind') == 'owned-input-down' for r in input_rows):
            raise PlatformError('archive-identity', 'Actual input or capture receipts are missing')
        restored = [r for r in input_rows if r.get('kind') == 'foreground-restoration']
        if len(restored) != 1 or restored[0].get('restored') is False:
            raise PlatformError('closing-tail', 'Missing or failed foreground restoration')
    raw = (output / 'session/client-diagnostic.jsonl').read_bytes()
    if not raw.endswith(b'\n'):
        raise PlatformError('incomplete-run', 'Diagnostic final line is truncated')
    records = [json.loads(line) for line in raw.splitlines()]
    geometry = None
    if backend == 'windows-input':
        geometry = windows_geometry(output,records)
    return compare_run(records, plan, read(output/'contract.json'), read(output/'intents.json'), geometry)


def run_session(scenario, contract, distribution, output, java='java', timeout=60):
    scenario, contract, distribution, output = map(lambda p: Path(p).resolve(), (scenario, contract, distribution, output))
    plan = read(scenario)
    inputs = (scenario.parent / plan['inputs']).resolve()
    expected_inputs = tree(inputs)
    jars = sorted((distribution / 'lib').glob('*.jar'))
    if not jars or not expected_inputs or type(timeout) is not int or not 1 <= timeout <= 600:
        raise PlatformError('input-identity', 'Installed adapter, bounded deadline, and input files are required', 1)
    output.mkdir()
    started = time.time()
    result = dict(exitCode=3, status='incomplete', backend='session', startedUnix=started,
                  execution=dict(status='not-run', exitCode=None),
                  consumption=dict(status='not-run'), sealing=dict(status='pending'))
    try:
        shutil.copyfile(scenario, output/'scenario.json')
        shutil.copyfile(contract, output/'contract.json')
        shutil.copytree(inputs, output/'inputSnapshot')
        if tree(output/'inputSnapshot') != expected_inputs or tree(inputs) != expected_inputs:
            raise PlatformError('input-identity', 'Inputs changed while freezing the run')
        # A single wildcard avoids the expanded JAR list and native-encoding
        # argument files. Windows receives the path through CreateProcessW;
        # Java needs the extended prefix for installs beyond MAX_PATH. Keep
        # the working directory short because CreateProcess rejects deep CWDs.
        classpath=str(distribution/'lib'/'*')
        if os.name=='nt':classpath='\\\\?\\'+classpath
        command_cwd=Path(tempfile.gettempdir()).resolve()
        command = [java, '-Xmx512m', '-cp', classpath,
                   'com.rustedwarfare.reforge.testing.PlatformSessionRunnerKt',
                   str(output/'inputSnapshot'), str(output/'scenario.json'), str(output/'session'), str(timeout)]
        save(output/'manifest.json', dict(backend='session', command=command, commandCwd=str(command_cwd),timeoutSeconds=timeout,
            scenarioSha256=sha(output/'scenario.json'), contractSha256=sha(output/'contract.json'),
            inputFiles=expected_inputs, packageFiles=[dict(path=p.name, sha256=sha(p)) for p in jars],
            comparatorSha256=sha(__file__)))
        result['execution'] = dict(status='running', exitCode=None, startedUnix=time.time())
        with (output/'stdout.log').open('xb') as stdout, (output/'stderr.log').open('xb') as stderr:
            process = subprocess.run(command, cwd=command_cwd, stdout=stdout, stderr=stderr, timeout=timeout, shell=False)
        result['execution'].update(status='passed' if process.returncode == 0 else 'failed',
                                   exitCode=process.returncode, endedUnix=time.time())
        save(output/'execution.json', result['execution'])
        if process.returncode != 0:
            raise PlatformError('adapter-process', 'Session adapter failed: '+str(process.returncode))
        records = [json.loads(line) for line in (output/'session/client-diagnostic.jsonl').read_text(encoding='utf8').splitlines()]
        save(output/'intents.json', session_anchors(records))
        save(output/'archive.json', dict(complete=True, backend='session', files=tree(output)))
        result['sealing'] = dict(status='sealed-complete')
        result['consumption'] = dict(status='running')
        result.update(compare_archive(output))
        result['consumption'] = dict(status='passed', exitCode=result['exitCode'])
    except PlatformError as error:
        if result['consumption']['status'] == 'running':
            result['consumption'] = dict(status='failed', exitCode=error.exit_code, category=error.category)
        result.update(exitCode=error.exit_code, status='failed' if error.exit_code == 2 else 'incomplete',
                      category=error.category, message=str(error))
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        result.update(category='adapter-exception', message=str(error))
        if result['execution']['status'] == 'running':
            result['execution'].update(status='timed-out' if isinstance(error, subprocess.TimeoutExpired)
                                       else 'failed', endedUnix=time.time())
        if result['consumption']['status'] == 'running':
            result['consumption'] = dict(status='incomplete', error=str(error))
    if result['execution']['status'] == 'running':
        result['execution'].update(status='failed', endedUnix=time.time())
    # Failure is evidence too. A partial archive cannot claim collection or
    # consumption success, but keeps its independent process/sealing receipts.
    try:
        if not (output/'execution.json').exists():
            save(output/'execution.json', result['execution'])
        if not (output/'archive.json').exists():
            save(output/'archive.json', dict(complete=False, backend='session', files=tree(output)))
            result['sealing'] = dict(status='preserved-incomplete')
    except OSError as error:
        result['sealing'] = dict(status='failed', error=str(error))
    result['endedUnix'] = time.time()
    save(output/'result.json', result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    session = commands.add_parser('session')
    for option in ('scenario', 'contract', 'distribution', 'output'):
        session.add_argument('--'+option, required=True, type=Path)
    session.add_argument('--java', default='java')
    session.add_argument('--timeout', type=int, default=60)
    windows = commands.add_parser('windows')
    for option in ('scenario', 'contract', 'distribution', 'output'):
        windows.add_argument('--'+option, required=True, type=Path)
    windows.add_argument('--java', default='java')
    windows.add_argument('--size', choices=['1200x800', '960x640'], required=True)
    windows.add_argument('--rate-profile', choices=['normal','segmented'], default='normal')
    compare = commands.add_parser('compare')
    compare.add_argument('--run', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'session':
            result = run_session(args.scenario, args.contract, args.distribution, args.output, args.java, args.timeout)
        elif args.command == 'windows':
            from platform_windows import run_windows
            result = run_windows(args.scenario, args.contract, args.distribution, args.output,
                                 tuple(map(int, args.size.split('x'))), args.java, args.rate_profile)
        else:
            result = compare_archive(args.run)
    except PlatformError as error:
        result = dict(exitCode=error.exit_code, category=error.category, message=str(error))
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = dict(exitCode=3, category='evidence-exception', message=str(error))
    print(json.dumps(result, ensure_ascii=True, allow_nan=False))
    return result['exitCode']


if __name__ == '__main__':
    sys.exit(main())
