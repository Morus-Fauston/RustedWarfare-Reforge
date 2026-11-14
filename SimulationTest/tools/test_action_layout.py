"""Literal public diagnostic examples; these do not prove native pixels or input."""
import copy
import unittest

from action_layout import ActionLayout, LayoutError, wait_layout
from platform_windows import scroll_to_action


def frame():
    return dict(kind='action-layout', sequence=12, generation=3, layoutVersion=1,
        layoutRevision=4, selectionRevision=2, selectedIds=['u1'], selectedTypes={'u1':'builder'},
        menuEpoch=1, drawnNanos=900, viewport=dict(width=1200,height=800,screenX=0,screenY=0,
            screenWidth=1200,screenHeight=800),
        projection=dict(worldWidth=880,worldHeight=640,left=16,bottom=64,scale=.8,
            areaLeft=16,areaBottom=64,areaWidth=704,areaHeight=512),
        actions=[dict(actionId='choose-construction:landFactory',button='left',enabled=True,
            text='Land factory\n$700',bounds=dict(x=844,y=280,width=84,height=54))])


class LayoutControls(unittest.TestCase):
    def test_start_waits_for_the_enabled_action_to_be_drawn(self):
        blocked = frame()
        blocked['actions'][0].update(actionId='start-match', enabled=False)
        qualified = copy.deepcopy(blocked)
        qualified.update(sequence=13, layoutRevision=5)
        qualified['actions'][0]['enabled'] = True

        def wait(label, ready):
            self.assertFalse(ready([blocked]))
            self.assertTrue(ready([blocked, qualified]))

        result = wait_layout(wait, 3, (1200, 800), enabled_action='start-match')
        self.assertEqual(result.frame['layoutRevision'], 5)
        self.assertEqual(result.action('start-match'), (1108, 416, 'left'))

    def test_scroll_retries_stale_draws_before_clicking_a_newly_visible_card(self):
        first=frame()
        desired=first['actions'].pop()
        first['actions'].extend(dict(desired,actionId=name,text='at-top')
                                for name in ('action-list','action-list-scrollbar'))
        latest=copy.deepcopy(first)
        latest.update(sequence=13,layoutRevision=5)
        latest['actions'][1]['text']='at-bottom'
        latest['actions'].append(desired)
        commands=[]
        def wait(label, ready):
            self.assertFalse(ready([first]), 'A delayed draw must leave the old revision pending')
            self.assertTrue(ready([first,latest]))
        def layout(after_revision=None):
            return wait_layout(wait,3,(1200,800),after_revision)
        result=scroll_to_action(ActionLayout([first],3,(1200,800)),desired['actionId'],layout,
                                lambda *args: commands.append(args))
        self.assertEqual(commands,[(1108,416,1)])
        self.assertEqual(result.frame['layoutRevision'],5)
        self.assertEqual(result.action(desired['actionId']),(1108,416,'left'))

    def test_wide_hud_uses_the_drawn_horizontal_scale_for_world_targets(self):
        f=frame()
        f['viewport'].update(width=1920,height=1080,screenWidth=1920,screenHeight=1080)
        f['projection'].update(left=0,bottom=0,scale=.8,scaleX=.675,areaLeft=0,
                               areaBottom=0,areaWidth=960,areaHeight=640)
        self.assertEqual(ActionLayout([f],3,(1920,1080)).world(200,240),(270,540))
        f['projection']['scaleX']=0
        with self.assertRaisesRegex(LayoutError,'horizontal'):
            ActionLayout([f],3,(1920,1080)).world(200,240)

    def test_reselecting_the_same_identity_does_not_require_a_deduplicated_layout_to_repeat(self):
        for kind in ('selection','group-recall'):
            rows=[frame(),dict(kind=kind,sequence=13,generation=3,selectionRevision=2,selectedIds=['u1'])]
            self.assertEqual(ActionLayout(rows,3,(1200,800)).action('choose-construction:landFactory'),(1108,416,'left'))
            rows[-1]['selectionRevision']=3
            with self.assertRaises(LayoutError):ActionLayout(rows,3,(1200,800))

    def test_current_drawn_card_and_projection_bind_selection_and_viewport(self):
        rows=[frame()]
        layout=ActionLayout(rows,3,(1200,800))
        self.assertEqual(layout.action('choose-construction:landFactory'),(1108,416,'left'))
        self.assertEqual(layout.world(110,110),(130,190))
        # A selection event invalidates the old card until its new draw is observed.
        rows.append(dict(kind='selection',sequence=13,generation=3,selectionRevision=3,selectedIds=['u2']))
        with self.assertRaisesRegex(LayoutError,'selection'):
            ActionLayout(rows,3,(1200,800))
        updated=copy.deepcopy(rows[0]);updated.update(sequence=14,selectionRevision=3,selectedIds=['u2'])
        updated['selectedTypes']={'u2':'builder'};rows.append(updated)
        self.assertEqual(ActionLayout(rows,3,(1200,800)).action('choose-construction:landFactory'),(1108,416,'left'))

    def test_absent_empty_long_title_and_duplicate_cards_do_not_fall_back_to_coordinates(self):
        f=frame();f['actions'][0]['text']='Very long visible factory title\n$700'
        self.assertEqual(ActionLayout([f],3,(1200,800)).action('choose-construction:landFactory'),(1108,416,'left'))
        for actions in ([],[dict(f['actions'][0],enabled=False)],f['actions']*2):
            changed=copy.deepcopy(f);changed['actions']=actions
            with self.assertRaises(LayoutError):
                ActionLayout([changed],3,(1200,800)).action('choose-construction:landFactory')
        with self.assertRaises(LayoutError):
            ActionLayout([f],4,(1200,800))
        with self.assertRaises(LayoutError):
            ActionLayout([f],3,(960,640))
        with self.assertRaisesRegex(LayoutError,'viewport'):
            ActionLayout([f,dict(kind='resize',sequence=13,width=960,height=640)],3,(1200,800))

    def test_nonfinite_or_outside_geometry_and_wrong_selection_are_rejected(self):
        for patch in (dict(x=float('nan')),dict(width=0),dict(x=950),dict(y=-20)):
            with self.subTest(patch=patch):
                f=frame();f['actions'][0]['bounds'].update(patch)
                with self.assertRaises(LayoutError):
                    ActionLayout([f],3,(1200,800)).action('choose-construction:landFactory')
        for position in ((-1,110),(880,110),(110,float('inf'))):
            with self.assertRaises(LayoutError):
                ActionLayout([frame()],3,(1200,800)).world(*position)
        f=frame();f['selectedTypes']={'u2':'builder'}
        with self.assertRaisesRegex(LayoutError,'selection'):
            ActionLayout([f],3,(1200,800))


if __name__=='__main__':unittest.main()
