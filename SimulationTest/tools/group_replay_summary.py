"""Summarize a raw group replay. Preserve equal-window costs separately from completed runs."""
import csv
import gzip
import json
import math
import sys
from itertools import groupby
from pathlib import Path


def movement(row):
    pushed = math.hypot(float(row['pushX'] or 0), float(row['pushY'] or 0)) > 1e-7
    if row['origin'] != row['command'] + '-' + row['unit'] or row['steering'] != 'true':
        return False, False, pushed
    turn = abs(math.remainder(float(row['ownEndHeading']) - float(row['beforeHeading']), 360)) > 1e-7
    if not row['aimX']:
        return turn, False, pushed
    x, y = float(row['beforeX']), float(row['beforeY'])
    dx, dy = float(row['ownEndX']) - x, float(row['ownEndY']) - y
    ax, ay = float(row['aimX']) - x, float(row['aimY']) - y
    forward = dx * ax + dy * ay > 1e-7 * math.hypot(ax, ay)
    heading = math.radians(float(row['ownEndHeading']))
    along_body = dx * math.sin(heading) - dy * math.cos(heading)
    return turn, forward and along_body > 1e-7, pushed


def read_steps(path, expected):
    with path.open(encoding='utf-8') as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    if len(rows) != expected or any(int(row['step']) != i for i,row in enumerate(rows)):
        raise ValueError(f'{path.name} does not cover the requested {expected} steps')
    return rows


def metrics(steps, planning, end):
    steps = steps[:end+1]
    result = dict(steps=len(steps), chargedSteps=sum(int(s['work']) > 0 for s in steps),
                  chargedWork=sum(int(s['work']) for s in steps),
                  plannerSliceSteps=sum(int(s['activeSlices'])>0 for s in steps) if 'activeSlices' in steps[0] else None,
                  publishWork=sum(int(s['publicationWork']) for s in steps) if 'publicationWork' in steps[0] else None,
                  restarts=sum(int(s['restarts']) for s in steps) if 'restarts' in steps[0] else None,
                  advanceCpuNs=sum(int(s['advanceThreadCpuNs']) for s in steps),
                  simulationIncludingOrderCpuNs=sum(int(s['advanceThreadCpuNs'])+int(s['orderCpuNs']) for s in steps) if 'orderCpuNs' in steps[0] else None,
                  peakAdvanceWallNs=max(int(s['advanceWallNs']) for s in steps))
    if planning is not None:
        planning = planning[:end+1]
        if [s['step'] for s in planning] != [s['step'] for s in steps]:
            raise ValueError('planning and simulation windows differ')
        result.update(planningCpuNs=sum(int(s['planningCpuNs']) for s in planning),
                      peakPlanningWallNs=max(int(s['planningWallNs']) for s in planning))
    return result


def summarize(directory, count, quiet, *, timed=True):
    result = json.loads((directory/'result.json').read_text(encoding='utf-8'))
    args = result['argv']
    expected = int(args[-1])
    scene_path = Path(args[-6])
    scene = json.loads(scene_path.read_text(encoding='utf-8-sig'))
    requested = {u['unitId']:u['type'] for u in scene['units'] if u['owner']=='p1'}
    if len(requested) != count: raise ValueError('scenario and requested member count differ')
    command = args[-4]
    if min(count,quiet,expected)<1: raise ValueError('positive member count and windows required')
    steps = read_steps(directory/'steps.tsv',expected)
    planning = read_steps(directory/'planning.tsv',expected) if timed else None
    if not result['passed']:
        raise ValueError('runner failed')
    first_turn, first_drive, arrived, publication = {}, {}, {}, {}
    roster = None
    poses = None
    individually_still = {}
    stable_since = None; first_stable_end = None
    total_frames = 0; wait_steps = {}; released_after_wait = {}
    pending_release = set()
    planning_wait = {}; stationary_run = {}; longest_stationary = {}
    pushed_samples = 0
    with gzip.open(directory/'trace.tsv.gz', 'rt', encoding='utf-8') as f:
        for expected_step, (key, rows) in enumerate(groupby(csv.DictReader(f, delimiter='\t'), lambda r: r['step'])):
            step = int(key)
            total_frames += 1
            waiting = set(planning[step].get('admissionWaitIds','').split(',')) if planning is not None and step < expected else set()
            frame = {}
            stopped = True
            for row in rows:
                unit = row['unit']
                if unit in frame: raise ValueError('duplicate unit in frame')
                if requested.get(unit) != row['type'] or command != row['command']:
                    raise ValueError('member type or order differs from scenario receipt')
                turn, drive, pushed = movement(row)
                if turn: first_turn.setdefault(unit, step)
                if unit in waiting:
                    wait_steps[unit] = wait_steps.get(unit,0) + 1
                    pending_release.add(unit)
                if drive:
                    first_drive.setdefault(unit,step)
                    if unit in pending_release and unit not in waiting:
                        released_after_wait.setdefault(unit, []).append(step)
                        pending_release.remove(unit)
                if pushed: pushed_samples += 1
                if row['published']=='true': publication.setdefault(unit, step)
                if row['arrived']=='true': arrived[unit] = step
                if unit not in publication and unit not in arrived and not row['activeTargetX']:
                    planning_wait[unit] = planning_wait.get(unit,0)+1
                has_target = bool(row['activeTargetX'])
                stationary = poses is not None and poses[unit] == (row['x'],row['y'],row['heading'])
                gap = stationary_run.get(unit,0)+1 if has_target and stationary and unit not in waiting else 0
                stationary_run[unit] = gap
                longest_stationary[unit] = max(longest_stationary.get(unit,0),gap)
                frame[unit] = (row['x'], row['y'], row['heading'])
                at_rest = unit in arrived and not row['activeTargetX'] and float(row['speed'])==0 and float(row['angular'])==0
                stopped &= at_rest
                if not at_rest:
                    individually_still.pop(unit, None)
                elif poses is None or poses[unit] != frame[unit]:
                    individually_still[unit] = step
                else:
                    individually_still.setdefault(unit,step)
            if step != expected_step or len(frame) != count or (roster is not None and frame.keys() != roster):
                raise ValueError(f'incomplete trace at step {step}')
            roster = frame.keys()
            if not stopped: stable_since = None
            elif poses != frame or stable_since is None: stable_since = step

            if first_stable_end is None and stable_since is not None and step-stable_since >= quiet:
                first_stable_end = step
            poses = frame
    if total_frames != expected:
        raise ValueError(f'trace covers {total_frames} of {expected} requested steps')
    stable_end = stable_since + quiet if stable_since is not None and step - stable_since >= quiet else None
    return dict(count=count, lastStep=step,prePublicationWaitSteps=planning_wait,longestStationaryWithTargetOutsideAdmission=longest_stationary,admissionWaitSteps=wait_steps,releasedAfterWait=released_after_wait, published=len(publication), firstPublication=min(publication.values(),default=None),
                firstTurn=min(first_turn.values(),default=None),leaderTurn=first_turn.get('tank-00'),
                leaderForward=first_drive.get('tank-00'),firstForward=min(first_drive.values(),default=None),
                lastForward=max(first_drive.values()) if len(first_drive)==count else None,forwardCount=len(first_drive),
                arrivalEvents=len(arrived),lastArrival=max(arrived.values(),default=None),pushedSamples=pushed_samples,
                poseStableThrough=stable_end,firstStableThrough=first_stable_end,quiet=quiet,stillAtEnd=sum(step-since>=quiet for since in individually_still.values()),
                equalWindow=metrics(steps,planning,step),
                completedWindow=metrics(steps,planning,stable_end) if stable_end is not None else None)


if __name__=='__main__':
    print(json.dumps(summarize(Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])),indent=2))
