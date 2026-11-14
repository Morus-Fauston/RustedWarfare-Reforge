"""Locate ordinary mouse targets from the current submitted Stage diagnostic."""
import math


class LayoutError(ValueError):
    category = 'current-action-layout'
    exit_code = 3


class LayoutPending(LayoutError):
    """A new draw may resolve this state; malformed or missing actions cannot."""


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


class ActionLayout:
    def __init__(self, rows, generation, size):
        candidates = [r for r in rows if r.get('kind') == 'action-layout' and r.get('generation') == generation]
        if not candidates:
            raise LayoutPending('No drawn layout for current generation')
        self.frame = max(candidates, key=lambda r: r['sequence'])
        f = self.frame
        if f.get('layoutVersion') != 1:
            raise LayoutError('Unsupported layout version')
        for key in ('layoutRevision','selectionRevision','menuEpoch','drawnNanos'):
            if type(f.get(key)) is not int or f[key] < 0:
                raise LayoutError('Invalid layout identity: '+key)
        selected, types = f.get('selectedIds'), f.get('selectedTypes')
        if (not isinstance(selected,list) or any(not isinstance(v,str) or not v for v in selected)
                or len(set(selected)) != len(selected) or not isinstance(types,dict)
                or set(types) != set(selected) or any(not isinstance(v,str) or not v for v in types.values())):
            raise LayoutError('Invalid drawn selection identity')
        for row in rows:
            if row['sequence'] <= f['sequence']:
                continue
            if row.get('kind') in ('resize', 'load-request', 'load-ready'):
                raise LayoutPending('New viewport or world has no current drawn layout')
            if row.get('generation') != generation:
                continue
            if row.get('kind') in ('selection', 'group-recall'):
                ids=row.get('selectedIds')
                if (row.get('selectionRevision')!=f['selectionRevision'] or not isinstance(ids,list)
                        or sorted(ids)!=sorted(f['selectedIds'])):
                    raise LayoutPending('New selection has no current drawn layout')
        self.viewport = f['viewport']
        v=self.viewport
        if (not isinstance(v,dict) or any(type(v.get(k)) is not int for k in
                ('width','height','screenX','screenY','screenWidth','screenHeight'))
                or min(v['width'],v['height'],v['screenWidth'],v['screenHeight']) <= 0
                or min(v['screenX'],v['screenY']) < 0
                or v['screenX']+v['screenWidth'] > v['width']
                or v['screenY']+v['screenHeight'] > v['height']):
            raise LayoutError('Invalid drawn viewport')
        if (self.viewport['width'], self.viewport['height']) != tuple(size):
            raise LayoutError('Drawn viewport does not match owned client size')

    def _client(self, x, y):
        v = self.viewport
        return (round(v['screenX'] + x * v['screenWidth'] / 960),
                round(v['height'] - v['screenY'] - y * v['screenHeight'] / 640))

    def action(self, action_id, button=None):
        matches = [a for a in self.frame['actions'] if a['actionId'] == action_id and (button is None or a['button'] == button)]
        if len(matches) != 1 or matches[0]['enabled'] is not True:
            raise LayoutError('Missing, ambiguous or disabled drawn action: ' + action_id + '/' + str(button))
        button=matches[0]['button']
        if button not in ('left','right'):
            raise LayoutError('Unsupported drawn mouse button')
        b = matches[0]['bounds']
        if (any(not number(b.get(k)) for k in ('x','y','width','height')) or b['width']<=0 or b['height']<=0
                or b['x']<0 or b['y']<0 or b['x']+b['width']>960 or b['y']+b['height']>640):
            raise LayoutError('Invalid or outside drawn action bounds: '+action_id)
        return (*self._client(b['x'] + b['width']/2, b['y'] + b['height']/2), button)

    def world(self, x, y):
        p = self.frame['projection']
        if (not isinstance(p,dict) or any(not number(p.get(k)) for k in ('worldWidth','worldHeight',
                'left','bottom','scale','areaLeft','areaBottom','areaWidth','areaHeight'))
                or min(p['worldWidth'],p['worldHeight'],p['scale'],p['areaWidth'],p['areaHeight'])<=0
                or not number(x) or not number(y) or not 0<=x<p['worldWidth'] or not 0<=y<p['worldHeight']):
            raise LayoutError('Invalid world projection or position')
        scale_x = p.get('scaleX', p['scale'])
        if not number(scale_x) or scale_x <= 0:
            raise LayoutError('Invalid horizontal world projection')
        ux,uy=p['left']+x*scale_x,p['bottom']+(p['worldHeight']-y)*p['scale']
        if (not p['areaLeft']<=ux<p['areaLeft']+p['areaWidth']
                or not p['areaBottom']<uy<=p['areaBottom']+p['areaHeight'] or not 0<=ux<960 or not 0<uy<=640):
            raise LayoutError('World position outside drawn map viewport')
        return self._client(ux,uy)


def wait_layout(wait, generation, size, after_revision=None, enabled_action=None):
    found=[]
    def ready(rows):
        scope=generation
        if scope is None:
            frames=[r for r in rows if r['kind']=='action-layout']
            if not frames:return False
            scope=max(frames,key=lambda r:r['sequence'])['generation']
        try:layout=ActionLayout(rows,scope,size)
        except LayoutPending:return False
        if after_revision is not None and layout.frame['layoutRevision'] <= after_revision:
            return False
        if enabled_action is not None and not any(action['actionId']==enabled_action and action['enabled'] is True
                                                  for action in layout.frame['actions']):
            return False
        found.append(layout)
        return True
    wait('current drawn actions',ready)
    return found[-1]
