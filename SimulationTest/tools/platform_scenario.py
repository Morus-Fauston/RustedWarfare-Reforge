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
import xml.etree.ElementTree as ET


class PlatformError(ValueError):
    def __init__(self, category, message, exit_code=3):
        super().__init__(message)
        self.category = category
        self.exit_code = exit_code


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
    for part in parts:
        if isinstance(current, list):
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


def compare_intent(records, intent, rule, after_sequence, geometry=None):
    assertions = rule.get('assertions')
    if (not isinstance(assertions, list) or not assertions or
            any(not isinstance(a, dict) or a.get('operator') not in ('equals', 'deltaEquals', 'equalsActionTarget') or
                not isinstance(a.get('path'), str) or not a['path'] or
                (a['operator'] != 'equalsActionTarget' and 'value' not in a) for a in assertions) or
            not intent.get('intentId') or intent.get('intentId') != rule.get('intentId') or
            not rule.get('actionType') or not rule.get('unitId') or type(rule.get('accepted')) is not bool):
        raise PlatformError('input-contract', 'Invalid or empty fixed intent contract', 1)
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
        limits = dict(x=0, y=0)
        if geometry is not None:
            viewport = geometry['viewport']
            dimensions = [geometry['worldWidth'], geometry['worldHeight'], viewport['screenWidth'], viewport['screenHeight']]
            if (any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0 for v in dimensions) or
                    (viewport['width'], viewport['height']) not in ((1200, 800), (960, 640)) or
                    viewport['screenWidth'] != viewport['width'] or viewport['screenHeight'] != viewport['height']):
                raise PlatformError('input-geometry', 'Missing or unregistered fixed map/viewport geometry')
            scale = min(704/geometry['worldWidth'], 512/geometry['worldHeight'])
            limits = dict(x=1/(scale*viewport['screenWidth']/960), y=1/(scale*viewport['screenHeight']/640))
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
    frames = set()
    allowed = {'sequence', 'kind', 'eventId', 'resourceId', 'path', 'sha256', 'volume', 'pitch', 'pan',
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
    return json.loads(Path(path).read_text(encoding='utf8'))


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
        root = ET.parse(output/'inputSnapshot/maps/p001-controlled.tmx').getroot()
        geometry = dict(worldWidth=int(root.get('width'))*int(root.get('tilewidth')),
                        worldHeight=int(root.get('height'))*int(root.get('tileheight')))
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
    result = dict(exitCode=3, status='incomplete', backend='session', startedUnix=started)
    try:
        shutil.copyfile(scenario, output/'scenario.json')
        shutil.copyfile(contract, output/'contract.json')
        shutil.copytree(inputs, output/'inputSnapshot')
        if tree(output/'inputSnapshot') != expected_inputs or tree(inputs) != expected_inputs:
            raise PlatformError('input-identity', 'Inputs changed while freezing the run')
        command = [java, '-Xmx512m', '-cp', os.pathsep.join(map(str, jars)),
                   'com.rustedwarfare.reforge.testing.PlatformSessionRunnerKt',
                   str(output/'inputSnapshot'), str(output/'scenario.json'), str(output/'session'), str(timeout)]
        save(output/'manifest.json', dict(backend='session', command=command, timeoutSeconds=timeout,
            scenarioSha256=sha(output/'scenario.json'), contractSha256=sha(output/'contract.json'),
            inputFiles=expected_inputs, packageFiles=[dict(path=p.name, sha256=sha(p)) for p in jars],
            comparatorSha256=sha(__file__)))
        with (output/'stdout.log').open('xb') as stdout, (output/'stderr.log').open('xb') as stderr:
            process = subprocess.run(command, stdout=stdout, stderr=stderr, timeout=timeout, shell=False)
        if process.returncode != 0:
            raise PlatformError('adapter-process', 'Session adapter failed: '+str(process.returncode))
        records = [json.loads(line) for line in (output/'session/client-diagnostic.jsonl').read_text(encoding='utf8').splitlines()]
        save(output/'intents.json', session_anchors(records))
        save(output/'archive.json', dict(complete=True, backend='session', files=tree(output)))
        result.update(compare_archive(output))
    except PlatformError as error:
        result.update(exitCode=error.exit_code, status='failed' if error.exit_code == 2 else 'incomplete',
                      category=error.category, message=str(error))
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        result.update(category='adapter-exception', message=str(error))
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
    compare = commands.add_parser('compare')
    compare.add_argument('--run', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'session':
            result = run_session(args.scenario, args.contract, args.distribution, args.output, args.java, args.timeout)
        elif args.command == 'windows':
            from platform_windows import run_windows
            result = run_windows(args.scenario, args.contract, args.distribution, args.output,
                                 tuple(map(int, args.size.split('x'))), args.java)
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
