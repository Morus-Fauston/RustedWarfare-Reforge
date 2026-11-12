"""Independent scalar nuclear queue and player-view validation; no engine imports."""
import copy
import math
import struct
from platform_scenario import PlatformError


def bad(reason):raise PlatformError('history-nuclear',reason,2)
def finite(value):return type(value) in (int,float) and math.isfinite(value)
def f32(value):return struct.unpack('!f',struct.pack('!f',value))[0]
def point(value):return isinstance(value,dict) and set(value)=={'x','y'} and all(finite(v) for v in value.values())


def validate_nuclear_state(unit,owner):
    doors=unit.get('nuclearDoors')
    if not finite(doors) or not 0<=doors<=1:bad('Invalid nuclear door pose')
    state=unit.get('unitActions')
    interceptor=unit.get('typeId')=='antiNukeLauncherC'
    ammo_action='buildAntiNuke' if interceptor else 'buildNuke'
    actions={ammo_action} if interceptor else {ammo_action,'launchNuke'}
    if unit.get('ownerPlayerId')!=owner:
        if state is not None:bad('Enemy nuclear stock and queue must remain private')
        return
    if (not isinstance(state,dict) or set(state)!={'automaticCooldown','actions','stockpile','queue'} or
            not finite(state['automaticCooldown']) or state['automaticCooldown']!=0 or
            type(state['stockpile']) is not int or state['stockpile']<0 or not isinstance(state['queue'],list) or
            not isinstance(state['actions'],dict) or set(state['actions'])!=actions):bad('Invalid nuclear stock or action fields')
    queue=state['queue']
    for item in queue:
        if (not isinstance(item,dict) or set(item)!={'name','progress','target'} or item['name'] not in actions or
                not finite(item['progress']) or not 0<=item['progress']<1 or
                (item['target'] is not None if item['name']==ammo_action else not point(item['target']))):bad('Invalid nuclear queue item')
    ready=unit.get('alive') is True and unit.get('constructionProgress',0)>=1 and unit.get('carriedBy') is None
    for name,action in state['actions'].items():
        index=next((i for i,item in enumerate(queue) if item['name']==name),None)
        limit=12 if interceptor else 4
        available=ready and (state['stockpile']>0 if name=='launchNuke' else state['stockpile']+sum(i['name']==ammo_action for i in queue)<limit)
        if (not isinstance(action,dict) or set(action)!={'queueIndex','progress','cooldownRemaining','available'} or
                action['queueIndex']!=index or index is not None and type(action['queueIndex']) is not int or
                action['progress']!=(None if index is None else queue[index]['progress']) or
                type(action['cooldownRemaining']) is not int or action['cooldownRemaining']!=0 or
                type(action['available']) is not bool or action['available']!=available):bad('Nuclear controls differ from stock and queue')


def command(state,row,interceptor=False):
    name=row.get('actionName');cancel=row.get('cancelQueued',False);queue=state['queue']
    ammo_action='buildAntiNuke' if interceptor else 'buildNuke'
    allowed={ammo_action} if interceptor else {ammo_action,'launchNuke'}
    if name not in allowed or type(cancel) is not bool:bad('Invalid nuclear command')
    if cancel:
        indexes=[i for i,item in enumerate(queue) if item['name']==name]
        if not indexes:bad('Cancellation has no pending work')
        queue.pop(indexes[-1])
        if name=='launchNuke':state['ammo']+=1
    elif name==ammo_action:
        if state['ammo']+sum(i['name']==ammo_action for i in queue)>=(12 if interceptor else 4):bad('Accepted over-limit ammunition')
        queue.append(dict(name=name,progress=0,target=None))
    else:
        if state['ammo']<=0 or not point(row.get('target')):bad('Launch has no round or ground point')
        state['ammo']-=1
        index=next((i for i,item in enumerate(queue) if item['name']!='launchNuke'),len(queue))
        queue.insert(index,dict(name=name,progress=0,target=row['target']))


def validate_nuclear_call(old,new,row,intent,footprint=None):
    actual,expected=row.get('target'),intent.get('target')
    limits=footprint or dict(x=0,y=0)
    matches=(actual is None and expected is None or point(actual) and point(expected) and
             all(abs(actual[k]-expected[k])<=limits[k] for k in ('x','y')))
    if (row.get('actionName')!=intent['actionName'] or row.get('cancelQueued')!=intent.get('cancelQueued',False) or
            not matches):bad('Nuclear command differs from fixed intent')
    if any(old.get(k)!=new.get(k) for k in set(old)|set(new) if k!='unitActions'):bad('Nuclear call edited physical state')
    state=dict(ammo=old['unitActions']['stockpile'],queue=copy.deepcopy(old['unitActions']['queue']))
    command(state,row,old.get('typeId')=='antiNukeLauncherC')
    if new['unitActions']['stockpile']!=state['ammo'] or new['unitActions']['queue']!=state['queue']:bad('Nuclear reservation or cancellation differs')


def validate_nuclear_history(records,policy):
    owner=policy.get('ownerPlayerId','p1');states={};births=0;impacts=0
    for row in records:
        if row.get('kind')=='action' and row.get('type')=='UnitAction' and row.get('unitId') in states:
            if row.get('result',{}).get('accepted') is not True:continue
            command(states[row['unitId']],row)
        if row.get('kind')!='observation':continue
        sample=row['sample'];completed=[];launches=0
        for unit in sample.get('units',[]):
            if unit.get('typeId')!='nukeLauncherC':continue
            validate_nuclear_state(unit,owner)
            if unit.get('ownerPlayerId')!=owner:
                if unit.get('unitId') in states:bad('Silo owner changed inside its history')
                continue
            identity=unit['unitId']
            state=states.setdefault(identity,dict(ammo=0,queue=[],doors=0,open=False,base=0,weight=0))
            if unit.get('alive') is False:state.update(ammo=0,queue=[],doors=0)
            elif row.get('phase')=='step' and unit.get('constructionProgress',0)>=1:
                opened=bool(state['queue'] and state['queue'][0]['name']=='launchNuke')
                if opened!=state['open']:state.update(open=opened,base=state['doors'],weight=1)
                if state['weight']>0:state['weight']=f32(state['weight']-f32(1/(120 if opened else 150)))
                desired=1 if opened else 0
                state['doors']=f32(f32(desired*f32(1-state['weight']))+f32(state['base']*state['weight'])) if state['weight']>0 else desired
                if state['queue']:
                    head=state['queue'][0];head['progress']=f32(head['progress']+f32(.0003 if head['name']=='buildNuke' else 1/210))
                    if head['progress']>=1:
                        state['queue'].pop(0);completed.append(identity)
                        if head['name']=='buildNuke':state['ammo']+=1
                        else:launches+=1
            actual=unit['unitActions']
            if actual['stockpile']!=state['ammo'] or len(actual['queue'])!=len(state['queue']):bad('Intermediate stock or queue length differs')
            for left,right in zip(actual['queue'],state['queue']):
                if left['name']!=right['name'] or left['target']!=right['target'] or f32(left['progress'])!=right['progress']:bad('Independent queue clock or target differs')
            if f32(unit['nuclearDoors'])!=state['doors']:bad('Independent nuclear door clock differs')
        if row.get('phase')=='step':
            actual=[e['unitId'] for e in sample.get('events',[]) if e.get('unitId') in states and e.get('kind')=='unit-action-completed']
            if sorted(actual)!=sorted(completed):bad('Missing or premature nuclear completion')
            visible=[e for e in sample.get('projectileEvents',[]) if e.get('appearance')=='NUCLEAR']
            born=sum(e.get('occurrences',0) for e in visible if e.get('kind')=='born')
            if born!=launches:bad('Nuclear launch completion has no corresponding visible birth')
            births+=born;impacts+=sum(e.get('occurrences',0) for e in visible if e.get('kind')=='impact')
    if policy.get('nuclearHistory') is True and (births<1 or impacts<1):bad('Normal nuclear history needs a real launch and impact')


def validate_interceptor_history(records,policy):
    owner=policy.get('ownerPlayerId','p1')
    states={}
    for row in records:
        if row.get('kind')=='action' and row.get('type')=='UnitAction' and row.get('unitId') in states:
            if row.get('result',{}).get('accepted') is True:
                command(states[row['unitId']],row,interceptor=True)
        if row.get('kind')!='observation':continue
        sample=row['sample']
        born=[event for event in sample.get('projectileEvents',[]) if event.get('kind')=='born' and
              event.get('appearance')=='INTERCEPTOR']
        for unit in sample.get('units',[]):
            if unit.get('typeId')!='antiNukeLauncherC' or unit.get('ownerPlayerId')!=owner:continue
            state=states.setdefault(unit['unitId'],dict(ammo=0,queue=[]))
            if unit.get('alive') is False:state.update(ammo=0,queue=[])
            elif row.get('phase')=='step' and unit.get('constructionProgress',0)>=1:
                if state['queue']:
                    head=state['queue'][0]
                    head['progress']=f32(head['progress']+f32(.0007))
                    if head['progress']>=1:state['queue'].pop(0);state['ammo']+=1
                state['ammo']-=sum(event['occurrences'] for event in born if event.get('sourceUnitId')==unit['unitId'])
            actual=unit['unitActions']
            queue=[dict(item,progress=f32(item['progress'])) for item in actual['queue']]
            if state['ammo']<0 or actual['stockpile']!=state['ammo'] or queue!=state['queue']:
                bad('Interceptor stock or queue differs from paid commands, clock and launches')
