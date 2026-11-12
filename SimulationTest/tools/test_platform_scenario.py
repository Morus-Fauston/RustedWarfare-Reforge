"""Independent tool examples; actual Session/PC runs are verified separately."""
import unittest
import copy

from platform_scenario import compare_history, compare_intent, compare_run, PlatformError, validate_launch_appearance
import json
from pathlib import Path
import tempfile


class PlatformIntentTest(unittest.TestCase):
    def test_spider_fabricator_income_follows_completion_conversion_and_same_slot_phase(self):
        import spider_platform

        def slot(identity, form, ready):
            return dict(unitId=identity, typeId="modularSpider_" + form, progress=1 if ready else .75)

        phases = {}
        first = slot("first", "fabricator", True)
        unfinished = slot("second", "fabricator", False)
        for _ in range(39):
            self.assertEqual(0, spider_platform.utility_income([first, unfinished], phases))
        upgraded = slot("first", "fabricatorT2", True)
        second = slot("second", "fabricator", True)
        self.assertEqual(10, spider_platform.utility_income([upgraded, second], phases))
        self.assertEqual(0, spider_platform.utility_income([upgraded, second], phases))
        empty = slot("first", "emptySlot", True)
        for _ in range(37):
            self.assertEqual(0, spider_platform.utility_income([empty, second], phases))
        self.assertEqual(5, spider_platform.utility_income([empty, second], phases))
        self.assertEqual(1, phases["first"])
        self.assertEqual(0, spider_platform.utility_income([first, second], phases))
        self.assertEqual(2, phases["first"])

    def test_spider_lightning_requires_its_own_source_mount_and_geometry(self):
        source = {"slot": dict(typeId="modularSpider_lightning")}
        geometry = dict(end=dict(x=5, y=10), endHeight=0)
        shot = dict(kind="born", appearance="SPIDER_LIGHTNING", lightning=geometry,
                    sourceUnitId="slot", sourceWeaponName="1")
        self.assertEqual("SPIDER_LIGHTNING", validate_launch_appearance(shot, source))
        for change in (dict(sourceWeaponName="left"), dict(lightning=None), dict(appearance="STANDARD")):
            with self.subTest(change=change), self.assertRaises(PlatformError):
                validate_launch_appearance(dict(shot, **change), source)
        with self.assertRaises(PlatformError):
            validate_launch_appearance(shot, {"slot": dict(typeId="modularSpider_artillery")})

    def test_spider_lightning_expiration_uses_the_verified_birth_after_slot_conversion_or_redaction(self):
        lightning = dict(unitId="slot", typeId="modularSpider_lightning", ownerPlayerId="p1",
                         alive=True, constructionProgress=1)
        geometry = dict(end=dict(x=5, y=10), endHeight=0)
        birth = dict(kind="born", token="bolt", appearance="SPIDER_LIGHTNING", lightning=geometry,
                     sourceUnitId="slot", sourceWeaponName="1")
        plan = dict(scenarioId="expiry", intents=[dict(intentId="watch", kind="select", waitSteps=2)])
        policy = dict(initialBalance=0, noActionIntents=["watch"], projectileBirthsRequired=True)

        def observe(sequence, phase, index, units, projectiles, events=()):
            return dict(sequence=sequence, kind="observation", phase=phase, sample=dict(
                stepIndex=index, simulationTime=index + 1, balance=0, units=units,
                projectileEvents=projectiles, events=events))

        def verify(units, expiration, *, born_units=None, born_events=()):
            rows = [
                observe(0, "initial", -1, [lightning], []),
                dict(kind="intent-start", intentId="watch"),
                observe(2, "step", 0, [lightning] if born_units is None else born_units, [birth], born_events),
                observe(3, "step", 1, units, [expiration]),
                dict(kind="intent-end", intentId="watch"),
            ]
            return compare_history(rows, plan, policy)["status"]

        empty = dict(lightning, typeId="modularSpider_emptySlot")
        expiration = dict(birth, kind="expired")
        self.assertEqual("passed", verify([empty], expiration))
        self.assertEqual("passed", verify([], dict(expiration, sourceUnitId=None, sourceWeaponName=None)))
        self.assertEqual("passed", verify([empty], expiration, born_units=[empty],
            born_events=[dict(kind="attachment-slot-destroyed", unitId="slot")]))
        with self.assertRaises(PlatformError):
            verify([empty], expiration, born_units=[empty])
        for change in (dict(sourceWeaponName="left"), dict(sourceUnitId="other"), dict(kind="born"),
                       dict(lightning=None), dict(appearance="STANDARD", lightning=None)):
            with self.subTest(change=change), self.assertRaises(PlatformError):
                verify([empty], dict(expiration, **change))

    def test_windows_empty_release_binds_through_the_real_generation_filtered_reader(self):
        from platform_windows import wait_for_input
        from diagnostic_reader import DiagnosticReader
        sample=dict(worldId='w',stepIndex=100)
        events=[dict(kind='selection',generation=1,selectedId=None,selectedIds=[]),
            dict(kind='map-release',button=0,intent='Click',selectedIds=[]),
            dict(kind='observation',generation=1,phase='step',sample=sample)]
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'diagnostic.jsonl'
            records=[dict(format='p001.windows-diagnostic.v1',runId='r',sequence=i,**r) for i,r in enumerate(events)]
            path.write_text(''.join(json.dumps(r)+'\n' for r in records),encoding='utf8')
            reader=DiagnosticReader(path)
            class Process:
                def poll(self):return None
            def wait(label,predicate):
                return reader.wait(label,predicate,Process(),generation=1,timeout=.1,sleep=lambda _:None)
            release,observed=wait_for_input(wait,reader,'empty',dict(noFormalAction=True),-1)
            self.assertEqual(records[1],release)
            self.assertEqual(sample,observed)

    def test_windows_empty_selection_wait_keeps_the_explicit_no_action_contract(self):
        from platform_windows import validate_windows_plan
        plan=dict(scenarioId='s',contentPack={},intents=[dict(intentId='empty',kind='select',waitSteps=600)])
        contract=dict(scenarioId='s',rules=[dict(intentId='empty')],
            history=dict(initialBalance=100,noActionIntents=['empty']))
        self.assertEqual([dict(intentId='empty',actionType='SelectAction',noFormalAction=True)],
            validate_windows_plan(plan,contract))
        for fault in ('unknown','duplicate','not-select','not-list'):
            p=copy.deepcopy(plan);c=copy.deepcopy(contract)
            if fault=='unknown':c['history']['noActionIntents']=['other']
            elif fault=='duplicate':c['history']['noActionIntents']=['empty','empty']
            elif fault=='not-select':p['intents'][0]['kind']='stop'
            else:c['history']['noActionIntents']='empty'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):validate_windows_plan(p,c)

    def test_windows_empty_selection_requires_the_current_release_and_observation_without_any_formal_action(self):
        from platform_windows import input_receipt
        rule=dict(actionType='SelectAction',noFormalAction=True)
        sample=dict(worldId='w',stepIndex=100)
        rows=[dict(sequence=20,kind='selection',selectedId=None,selectedIds=[]),
            dict(sequence=21,kind='map-release',button=0,intent='Click',selectedIds=[]),
            dict(sequence=22,kind='observation',phase='step',sample=sample)]
        self.assertEqual((rows[1],sample),input_receipt(rows,rule,19))
        self.assertIsNone(input_receipt(rows,rule,22))
        for missing in (0,1,2):
            with self.subTest(missing=missing):self.assertIsNone(input_receipt(rows[:missing]+rows[missing+1:],rule,19))
        for fault in ('formal-action','selected-unit','wrong-button'):
            changed=copy.deepcopy(rows)
            if fault=='formal-action':changed.append(dict(sequence=23,kind='action',type='SelectAction'))
            elif fault=='selected-unit':changed[0].update(selectedId='a',selectedIds=['a'])
            else:changed[1]['button']=1
            if fault=='wrong-button':self.assertIsNone(input_receipt(changed,rule,19))
            else:
                with self.subTest(fault=fault),self.assertRaises(PlatformError):input_receipt(changed,rule,19)
        formal=[dict(sequence=30,kind='action',type='StopAction'),
            dict(sequence=31,kind='observation',phase='after-action',sample=sample)]
        self.assertEqual((formal[0],sample),input_receipt(formal,dict(actionType='StopAction'),29))
        self.assertIsNone(input_receipt(formal,dict(actionType='StopAction'),30))

    def test_windows_history_accepts_normal_content_menu_and_keeps_wait_bounds(self):
        from platform_windows import validate_windows_plan
        plan=dict(scenarioId='hover',contentPack=dict(directory='package',unitId='commandCenter'),
            intents=[dict(intentId='worker',kind='produce',typeId='builder',waitSteps=600)],
            windowsWaits=dict(worker=dict(rate=4,progressOnly=True,maximumSimulationSteps=720,
                maximumWallSeconds=10,stallSeconds=4)))
        contract=dict(scenarioId='hover',rules=[dict(intentId='worker')],history=dict(initialBalance=5000))
        self.assertEqual([dict(intentId='worker',actionType='ProduceAction')],validate_windows_plan(plan,contract))
        for fault in ('no-entry','wait-bound','unknown-action'):
            changed=copy.deepcopy(plan)
            if fault=='no-entry':changed.pop('contentPack')
            elif fault=='wait-bound':changed['windowsWaits']['worker']['maximumSimulationSteps']=1201
            else:changed['intents'][0]['kind']='invented'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):validate_windows_plan(changed,contract)

    def test_default_windows_startup_uses_no_content_override_and_keeps_evidence_isolated(self):
        from platform_windows import startup_arguments
        snapshot=Path('owned-snapshot');evidence=Path('owned-evidence')
        args,title=startup_arguments(dict(testScene={},windowsDefaultStart=True),snapshot,evidence)
        self.assertEqual(['--evidence',str(evidence),'--visuals','placeholder'],args)
        self.assertEqual('RustedWarfare-Reforge | Phase V sandbox',title)
        explicit,title=startup_arguments({},snapshot,evidence)
        self.assertEqual(['--mode','sandbox','--inputs',str(snapshot),'--evidence',str(evidence),'--visuals','placeholder'],explicit)
        self.assertEqual('RustedWarfare-Reforge | P002 Windows sandbox',title)
        for plan in (dict(windowsDefaultStart='true',testScene={}),dict(windowsDefaultStart=True)):
            with self.subTest(plan=plan),self.assertRaises(PlatformError):startup_arguments(plan,snapshot,evidence)

    def test_progress_only_four_times_wait_is_explicit_and_keeps_all_existing_bounds(self):
        from platform_windows import validate_windows_plan
        plan=dict(scenarioId='s',testScene={},intents=[dict(intentId='wait',kind='select',waitSteps=600)],
            windowsWaits=dict(wait=dict(rate=4,progressOnly=True,maximumSimulationSteps=720,maximumWallSeconds=10,stallSeconds=4)))
        contract=dict(scenarioId='s',rules=[dict(intentId='wait')],history=dict(initialBalance=100))
        self.assertEqual([dict(intentId='wait',actionType='SelectAction')],validate_windows_plan(plan,contract))
        for fault in ('implicit','too-few-steps','unbounded','wall','stall','event-ambiguity'):
            changed=copy.deepcopy(plan);wait=changed['windowsWaits']['wait']
            if fault=='implicit':wait.pop('progressOnly')
            elif fault=='too-few-steps':wait['maximumSimulationSteps']=599
            elif fault=='unbounded':wait['maximumSimulationSteps']=1201
            elif fault=='wall':wait['maximumWallSeconds']=61
            elif fault=='stall':wait['stallSeconds']=11
            else:wait['eventKind']='unit-produced';wait['unitType']='builder'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):validate_windows_plan(changed,contract)

    def test_windows_history_dispatches_the_existing_stop_action_and_rejects_unknown_intents(self):
        from platform_windows import validate_windows_plan
        plan=dict(scenarioId='s',testScene={},intents=[dict(intentId='stop',kind='stop')])
        contract=dict(scenarioId='s',rules=[dict(intentId='stop')],history=dict(initialBalance=100))
        self.assertEqual([dict(intentId='stop',actionType='StopAction')],validate_windows_plan(plan,contract))
        changed=copy.deepcopy(plan);changed['intents'][0]['kind']='invented'
        with self.assertRaises(PlatformError):validate_windows_plan(changed,contract)

    def test_history_checks_fractional_periodic_income_and_its_live_owned_source_at_every_boundary(self):
        from platform_scenario import compare_history
        source=dict(unitId='center',ownerPlayerId='p1',typeId='commandCenter',alive=True,
                    constructionProgress=1,position=dict(x=1,y=2))
        def sample(step,precise):
            return dict(worldId='w',stepIndex=step,simulationTime=step+1,balance=int(precise),credits=precise,
                        units=[copy.deepcopy(source)])
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=sample(-1,100)),
              dict(sequence=1,kind='observation',phase='step',sample=sample(0,100)),
              dict(sequence=2,kind='observation',phase='before-action',sample=sample(0,100)),
              dict(sequence=3,kind='action',type='SelectAction',unitId='center',actorPlayerId='p1',
                   result=dict(actionId='select',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=sample(0,100)),
              dict(sequence=5,kind='observation',phase='step',sample=sample(1,104.5)),
              dict(sequence=6,kind='observation',phase='step',sample=sample(2,104.5)),
              dict(sequence=7,kind='observation',phase='before-close',sample=sample(2,104.5))]
        plan=dict(scenarioId='s',intents=[dict(intentId='select',kind='select',position=dict(x=1,y=2),waitSteps=1)])
        policy=dict(initialBalance=100,initialIncome=dict(unitId='center',typeId='commandCenter',period=2,credits=4.5))
        anchors=[dict(intentId='select',afterSequence=1,endSequence=6)]
        self.assertEqual(0,compare_history(rows,plan,policy,anchors)['exitCode'])
        for fault in ('early','missing','imprecise','double','dead','foreign','wrong-type','duplicate','incomplete'):
            changed=copy.deepcopy(rows)
            if fault=='early':changed[1]['sample'].update(balance=104,credits=104.5)
            elif fault=='missing':changed[5]['sample'].update(balance=100,credits=100)
            elif fault=='imprecise':changed[5]['sample']['credits']=104.4
            elif fault=='double':changed[6]['sample'].update(balance=109,credits=109)
            elif fault=='dead':changed[5]['sample']['units'][0]['alive']=False
            elif fault=='foreign':changed[5]['sample']['units'][0]['ownerPlayerId']='p2'
            elif fault=='wrong-type':changed[5]['sample']['units'][0]['typeId']='builder'
            elif fault=='duplicate':changed[5]['sample']['units'].append(copy.deepcopy(source))
            else:changed[5]['sample']['units'][0]['constructionProgress']=.5
            with self.subTest(fault=fault),self.assertRaises(PlatformError):compare_history(changed,plan,policy,anchors)
        for invalid in (dict(period=0),dict(credits=float('nan')),dict(extra=True),dict(period=True)):
            changed=copy.deepcopy(policy);changed['initialIncome'].update(invalid)
            with self.subTest(invalid=invalid),self.assertRaises(PlatformError):compare_history(rows,plan,changed,anchors)

    def test_native_history_targets_use_the_approved_pixel_footprint_only(self):
        from platform_scenario import validate_history_action
        sample=dict(worldId='w',stepIndex=0,simulationTime=1,
            units=[dict(unitId='a',ownerPlayerId='p1',alive=True,moveTarget=dict(x=170.3125,y=150.00007))])
        rows=[dict(sequence=0,kind='observation',phase='before-action',sample=sample),
              dict(sequence=1,kind='action',unitId='a',actorPlayerId='p1',target=dict(x=170.3125,y=150.00007)),
              dict(sequence=2,kind='observation',phase='after-action',sample=sample)]
        intent=dict(kind='move',position=dict(x=170,y=150))
        geometry=dict(worldWidth=800,worldHeight=600,viewport=dict(width=1200,height=800,screenWidth=1200,screenHeight=800))
        validate_history_action(rows,rows[1],intent,geometry)
        with self.assertRaises(PlatformError):validate_history_action(rows,rows[1],intent)
        for value in (171,float('nan')):
            changed=copy.deepcopy(rows);changed[1]['target']['x']=value
            with self.assertRaises(PlatformError):validate_history_action(changed,changed[1],intent,geometry)
        for value in (None,dict(x=999,y=999),dict(x=170.3125,y=150.00008)):
            changed=copy.deepcopy(rows)
            changed[2]['sample']['units'][0]['moveTarget']=value
            with self.subTest(moveTarget=value),self.assertRaises(PlatformError):
                validate_history_action(changed,changed[1],intent,geometry)

    def test_production_history_requires_the_declared_fifo_and_completed_upgrade(self):
        from platform_scenario import validate_production_history
        def event(identity,typ,kind):
            return dict(kind='observation',phase='step',sample=dict(units=[dict(unitId=identity,typeId=typ,ownerPlayerId='p1')],
                events=[dict(kind=kind,unitId=identity)]))
        rows=[event('dock','spdockt2','upgrade-completed'),event('one','spcorvette','unit-produced'),event('two','spfrigate','unit-produced')]
        policy=dict(productionOrder=['spcorvette','spfrigate'],completedUpgradeTypes=['spdockt2'])
        validate_production_history(rows,policy)
        for changed in [rows[:1]+rows[1:][::-1],rows[1:],rows+rows[2:],rows[:-1]]:
            with self.subTest(rows=changed),self.assertRaises(PlatformError):validate_production_history(changed,policy)

    def test_native_history_uses_recorded_input_boundaries_and_continuous_steps(self):
        from platform_scenario import compare_history
        def sample(step):return dict(worldId='w',stepIndex=step,simulationTime=step+1,balance=100,
            units=[dict(unitId='a',ownerPlayerId='p1',typeId='builder',alive=True,position=dict(x=1,y=2))])
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=sample(-1)),
              dict(sequence=1,kind='observation',phase='step',sample=sample(0)),
              dict(sequence=2,kind='observation',phase='before-action',sample=sample(0)),
              dict(sequence=3,kind='action',type='SelectAction',unitId='a',actorPlayerId='p1',
                   result=dict(actionId='select',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=sample(0)),
              dict(sequence=5,kind='observation',phase='step',sample=sample(1)),
              dict(sequence=6,kind='observation',phase='step',sample=sample(2)),
              dict(sequence=7,kind='observation',phase='before-close',sample=sample(2))]
        plan=dict(scenarioId='s',intents=[dict(intentId='select',kind='select',position=dict(x=1,y=2),waitSteps=1)])
        policy=dict(initialBalance=100)
        anchors=[dict(intentId='select',afterSequence=1,endSequence=6)]
        self.assertEqual(0,compare_history(rows,plan,policy,anchors)['exitCode'])
        for changed in ([dict(anchors[0],endSequence=4)], [dict(anchors[0],afterSequence=3)],
                        [dict(anchors[0],intentId='wrong')]):
            with self.subTest(anchors=changed),self.assertRaises(PlatformError):compare_history(rows,plan,policy,changed)

    def test_history_charges_the_formal_build_when_its_new_site_is_created(self):
        actor=dict(unitId='a',ownerPlayerId='p1',typeId='builder',health=100,maxHealth=100,alive=True)
        site=dict(unitId='site',ownerPlayerId='p1',typeId='dock',position=dict(x=5,y=6),health=10,maxHealth=100,alive=True)
        def sample(step,balance,created=False,event=False):
            return dict(worldId='w',stepIndex=step,simulationTime=max(step+1,0),balance=balance,
                units=copy.deepcopy([actor,site] if created else [actor]),events=[dict(kind='construction-created',unitId='site')] if event else [],
                weapons=[],projectiles=[],projectileEvents=[])
        before=sample(-1,100);first=sample(0,100);created=sample(1,90,True,True);tail=sample(2,90,True)
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=before),
              dict(sequence=1,kind='intent-start',intentId='build'),
              dict(sequence=2,kind='observation',phase='before-action',sample=before),
              dict(sequence=3,kind='action',type='BuildAction',unitId='a',actorPlayerId='p1',buildingTypeId='dock',target=dict(x=5,y=6),append=False,result=dict(actionId='click-1',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=before),
              dict(sequence=5,kind='observation',phase='step',sample=first),
              dict(sequence=6,kind='observation',phase='step',sample=created),
              dict(sequence=7,kind='observation',phase='step',sample=tail),
              dict(sequence=8,kind='intent-end',intentId='build'),
              dict(sequence=9,kind='observation',phase='before-close',sample=tail),dict(sequence=10,kind='closed')]
        plan=dict(scenarioId='s',intents=[dict(intentId='build',kind='build',typeId='dock',position=dict(x=5,y=6),waitSteps=3)])
        contract=dict(scenarioId='s',rules=[dict(intentId='build')],history=dict(initialBalance=100,spend=[dict(intentId='build',amount=10,at='construction-created')]))
        anchors=[dict(intentId='build',afterSequence=1,endSequence=7)]
        self.assertEqual(0,compare_run(rows,plan,contract,anchors)['exitCode'])
        for fault in ['early-debit','missing-debit','missing-event','free-without-event','repeated-event',
                      'wrong-owner','wrong-type','wrong-position','preexisting-site','two-sites']:
            changed=copy.deepcopy(rows)
            if fault=='early-debit':changed[5]['sample']['balance']=90
            elif fault=='missing-debit':changed[6]['sample']['balance']=100
            elif fault in ('missing-event','free-without-event'):
                changed[6]['sample']['events']=[]
                if fault=='free-without-event':
                    for row in changed:
                        if 'sample' in row:row['sample']['balance']=100
            elif fault=='repeated-event':changed[7]['sample']['events']=[dict(kind='construction-created',unitId='site')]
            elif fault in ('wrong-owner','wrong-type','wrong-position'):
                unit=changed[6]['sample']['units'][1]
                unit.update({'ownerPlayerId':'p2'} if fault=='wrong-owner' else {'typeId':'other'} if fault=='wrong-type' else {'position':dict(x=6,y=6)})
            elif fault=='preexisting-site':
                for index in (0,2,4,5):changed[index]['sample']['units']=copy.deepcopy([actor,site])
            elif fault=='two-sites':
                changed[6]['sample']['units'].append(dict(site,unitId='second-site'))
                changed[6]['sample']['events'].append(dict(kind='construction-created',unitId='second-site'))
            with self.subTest(fault=fault),self.assertRaises(PlatformError):compare_run(changed,plan,contract,anchors)
        for field in ('typeId','position'):
            changed=copy.deepcopy(plan);del changed['intents'][0][field]
            with self.subTest(missing=field),self.assertRaises(PlatformError):compare_run(rows,changed,contract,anchors)
        for spend in (None,{},'charges',[None],[[]],[dict(intentId=[],amount=10,at='construction-created')],
                      [dict(intentId='build',amount=10,at='construction-created',unknown=True)]):
            changed=copy.deepcopy(contract);changed['history']['spend']=spend
            with self.subTest(spend=spend):
                with self.assertRaises(PlatformError) as error:compare_run(rows,plan,changed,anchors)
                self.assertEqual(('input-contract',1),(error.exception.category,error.exception.exit_code))

    def test_windows_geometry_uses_the_frozen_loaded_map_dimensions_not_an_old_filename(self):
        from platform_scenario import windows_geometry
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);maps=root/'inputSnapshot/maps';maps.mkdir(parents=True)
            (maps/'naval-shore.tmx').write_text('<map width="40" height="30" tilewidth="20" tileheight="20"/>')
            records=[dict(kind='load-graphics-ready',generation=1,worldWidth=800,worldHeight=600),
                     dict(kind='load-ready',generation=1,mapWidth=40,mapHeight=30)]
            self.assertEqual(dict(worldWidth=800,worldHeight=600),windows_geometry(root,records))
            for changed in ([dict(records[0],worldWidth=900),records[1]],
                            [records[0],dict(records[1],generation=2)],
                            [records[0],dict(records[1],mapWidth=41)]):
                with self.assertRaises(PlatformError):windows_geometry(root,changed)

    def test_attack_history_distinguishes_the_fixed_ground_position_from_the_flight_click_point(self):
        from platform_scenario import validate_history_action
        sample=dict(worldId='w',stepIndex=0,simulationTime=1,units=[
            dict(unitId='a',ownerPlayerId='p1',alive=True),
            dict(unitId='air',ownerPlayerId='p2',alive=True,position=dict(x=750,y=650),height=20)])
        action=dict(sequence=1,kind='action',type='AttackAction',unitId='a',actorPlayerId='p1',
                    targetUnitId='air',result=dict(accepted=True))
        rows=[dict(sequence=0,kind='observation',phase='before-action',sample=copy.deepcopy(sample)),action,
              dict(sequence=2,kind='observation',phase='after-action',sample=copy.deepcopy(sample))]
        intent=dict(kind='attack',targetUnitId='air',position=dict(x=750,y=630),targetGroundPosition=dict(x=750,y=650))
        validate_history_action(rows,action,intent)
        for fault in ('wrong-ground','wrong-id','dead','friendly'):
            changed=copy.deepcopy(rows)
            if fault=='wrong-ground':changed[0]['sample']['units'][1]['position']['y']=651
            elif fault=='wrong-id':changed[1]['targetUnitId']='other'
            elif fault=='dead':changed[0]['sample']['units'][1]['alive']=False
            else:changed[0]['sample']['units'][1]['ownerPlayerId']='p1'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):
                validate_history_action(changed,changed[1],intent)

    def test_history_binds_formal_action_identity_parameters_and_production_fifo(self):
        from platform_scenario import session_anchors
        sample=dict(worldId='w',stepIndex=-1,simulationTime=0,balance=100,weapons=[],
            units=[dict(unitId='a',ownerPlayerId='p1',typeId='owned',alive=True,position=dict(x=0,y=0)),
                   dict(unitId='f',ownerPlayerId='p1',typeId='owned_factory',alive=True,position=dict(x=2,y=2)),
                   dict(unitId='b',ownerPlayerId='p2',typeId='enemy',alive=True,position=dict(x=3,y=4))],
            factoryQueues=[dict(factoryId='f',items=[dict(typeId='old_product',progress=.25)])])
        cases=[('build','BuildAction',dict(typeId='new_factory',position=dict(x=1,y=2)),
                dict(buildingTypeId='new_factory',target=dict(x=1,y=2))),
               ('upgrade','UpgradeAction',dict(upgradeId='upgrade_t2'),dict(upgradeId='upgrade_t2')),
               ('move','MoveAction',dict(position=dict(x=1,y=2)),dict(target=dict(x=1,y=2))),
               ('attack','AttackAction',dict(targetUnitId='b',position=dict(x=3,y=4)),dict(targetUnitId='b')),
               ('produce','ProduceAction',dict(typeId='new_product'),{})]
        for kind,action_type,parameters,fields in cases:
            before=copy.deepcopy(sample);after=copy.deepcopy(sample)
            if kind=='move':after['units'][0]['moveTarget']=copy.deepcopy(fields['target'])
            if kind=='produce':after['factoryQueues'][0]['items'].append(dict(typeId='new_product',progress=0))
            action=dict(kind='action',sequence=3,type=action_type,unitId='f' if kind=='produce' else 'a',
                        actorPlayerId='p1',result=dict(actionId='one',accepted=True),**fields)
            rows=[dict(sequence=0,kind='observation',phase='initial',sample=before),
                  dict(sequence=1,kind='intent-start',intentId='one'),
                  dict(sequence=2,kind='observation',phase='before-action',sample=before),action,
                  dict(sequence=4,kind='observation',phase='after-action',sample=after),
                  dict(sequence=5,kind='intent-end',intentId='one'),
                  dict(sequence=6,kind='observation',phase='before-close',sample=after),dict(sequence=7,kind='closed')]
            plan=dict(scenarioId='s',intents=[dict(intentId='one',kind=kind,**parameters)])
            contract=dict(scenarioId='s',rules=[dict(intentId='one')],history=dict(initialBalance=100))
            anchors=session_anchors(rows)
            self.assertEqual(0,compare_run(rows,plan,contract,anchors)['exitCode'])
            faults=['actor','absent-unit','foreign-unit','boundary']
            if kind=='build':faults+=['building-type','position']
            elif kind=='upgrade':faults+=['upgrade']
            elif kind=='move':faults+=['position']
            elif kind=='attack':faults+=['target','target-position']
            else:faults+=['factory-binding','fifo-prefix','extra-product','product-type']
            for fault in faults:
                changed=copy.deepcopy(rows)
                if fault=='actor':changed[3]['actorPlayerId']='p2'
                elif fault=='absent-unit':changed[3]['unitId']='missing'
                elif fault=='foreign-unit':changed[3]['unitId']='b'
                elif fault=='boundary':changed[2]['phase']='unrelated'
                elif fault=='building-type':changed[3]['buildingTypeId']='wrong_factory'
                elif fault=='position':changed[3]['target']=dict(x=9,y=9)
                elif fault=='upgrade':changed[3]['upgradeId']='wrong_upgrade'
                elif fault=='target':changed[3]['targetUnitId']='f'
                elif fault=='target-position':changed[2]['sample']['units'][2]['position']=dict(x=9,y=9)
                elif fault=='factory-binding':changed[4]['sample']['factoryQueues'][0]['factoryId']='another_factory'
                elif fault=='fifo-prefix':changed[4]['sample']['factoryQueues'][0]['items'][0]['progress']=.5
                elif fault=='extra-product':changed[4]['sample']['factoryQueues'][0]['items'].append(dict(typeId='new_product',progress=0))
                else:changed[4]['sample']['factoryQueues'][0]['items'][-1]['typeId']='wrong_product'
                with self.subTest(kind=kind,fault=fault),self.assertRaises(PlatformError):
                    compare_run(changed,plan,contract,anchors)

    def test_declared_mech_pose_rejects_incomplete_or_private_leg_fields(self):
        from platform_scenario import validate_history_poses
        pose=dict(forwardHeading=90,angularVelocity=0,reversing=False,legs=[
            dict(index=i,anchor=dict(x=0,y=0),foot=dict(x=2,y=3),height=0,heading=90,phase='PLANTED')
            for i in range(2)])
        unit=dict(typeId='owned_mech',mechPose=pose);policy=dict(mechLegs=dict(owned_mech=2))
        validate_history_poses([unit],policy)
        for fault in ('missing-pose','null-pose','missing-leg','duplicate-index','wrong-phase','missing-foot',
                      'string-height','missing-body-spin','string-reverse','private-target'):
            changed=copy.deepcopy(unit);p=changed['mechPose']
            if fault=='missing-pose':del changed['mechPose']
            elif fault=='null-pose':changed['mechPose']=None
            elif fault=='missing-leg':p['legs'].pop()
            elif fault=='duplicate-index':p['legs'][1]['index']=0
            elif fault=='wrong-phase':p['legs'][0]['phase']='HOVERING'
            elif fault=='missing-foot':del p['legs'][0]['foot']
            elif fault=='string-height':p['legs'][0]['height']='0'
            elif fault=='missing-body-spin':del p['angularVelocity']
            elif fault=='string-reverse':p['reversing']='false'
            else:p['predictedFootTarget']=dict(x=1,y=2)
            with self.subTest(fault=fault),self.assertRaises(PlatformError):
                validate_history_poses([changed],policy)

    def test_history_checks_every_balance_and_the_declared_first_step_charge(self):
        def sample(step, balance):
            return dict(worldId='w', stepIndex=step, simulationTime=max(step+1,0), balance=balance,
                units=[dict(unitId='a',ownerPlayerId='p1',typeId='builder',health=100,maxHealth=100,alive=True)],
                weapons=[],projectiles=[],projectileEvents=[])
        before=sample(-1,100);after=sample(0,90)
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=before),
              dict(sequence=1,kind='intent-start',intentId='build'),
              dict(sequence=2,kind='observation',phase='before-action',sample=before),
              dict(sequence=3,kind='action',type='BuildAction',unitId='a',actorPlayerId='p1',result=dict(actionId='click-1',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=before),
              dict(sequence=5,kind='observation',phase='step',sample=after),
              dict(sequence=6,kind='intent-end',intentId='build'),
              dict(sequence=7,kind='observation',phase='before-close',sample=after),dict(sequence=8,kind='closed')]
        plan=dict(scenarioId='s',intents=[dict(intentId='build',kind='build',waitSteps=1)])
        contract=dict(scenarioId='s',rules=[dict(intentId='build')],history=dict(initialBalance=100,
            spend=[dict(intentId='build',amount=10,at='first-step')]))
        anchors=[dict(intentId='build',afterSequence=1,endSequence=5)]
        self.assertEqual(0,compare_run(rows,plan,contract,anchors)['exitCode'])
        for index in (4,5,7):
            changed=copy.deepcopy(rows);changed[index]['sample']['balance']-=1
            with self.subTest(index=index),self.assertRaises(PlatformError):
                compare_run(changed,plan,contract,anchors)
        changed=copy.deepcopy(rows);changed[3]['type']='UpgradeAction'
        with self.assertRaises(PlatformError):
            compare_run(changed,plan,contract,anchors)
        dead=copy.deepcopy(rows)
        for index in (5,7):
            dead[index]['sample']['units'][0].update(alive=False,health=0)
        self.assertEqual(0,compare_run(dead,plan,contract,anchors)['exitCode'])
        dead[5]['sample']['weapons']=[dict(unitId='a',weaponName='main')]
        with self.assertRaises(PlatformError):
            compare_run(dead,plan,contract,anchors)
        wrong_step=copy.deepcopy(rows);wrong_step[5]['sample']['stepIndex']=42
        with self.assertRaises(PlatformError):
            compare_run(wrong_step,plan,contract,anchors)
        unclosed=copy.deepcopy(rows);unclosed[6]['kind']='note'
        with self.assertRaises(PlatformError):
            compare_run(unclosed,plan,contract,anchors)
        free=copy.deepcopy(rows);free_contract=copy.deepcopy(contract);free_contract['history']['spend']=[]
        for row in free:
            if 'sample' in row:row['sample']['balance']=100
        self.assertEqual(0,compare_run(free,plan,free_contract,anchors)['exitCode'])
        free[3]['kind']='note'
        with self.assertRaises(PlatformError):
            compare_run(free,plan,free_contract,anchors)

    def test_session_uses_one_unicode_wildcard_and_a_short_working_directory(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from platform_scenario import run_session, save
        with tempfile.TemporaryDirectory(prefix='platform-argument-file-') as temporary:
            root=Path(temporary); (root/'inputs').mkdir()
            (root/'inputs/owned.txt').write_text('owned',encoding='utf8')
            lib=root/'distribution with spaces/lib';lib.mkdir(parents=True)
            for name in ('owned-one.jar','owned-two.jar'):
                (lib/name).write_bytes(b'Owned non-executable process-boundary fixture')
            save(root/'scenario.json',dict(inputs='inputs'));save(root/'contract.json',{})
            with patch('platform_scenario.subprocess.run',return_value=SimpleNamespace(returncode=1)) as process:
                run_session(root/'scenario.json',root/'contract.json',lib.parent,root/'output')
            argv=process.call_args.args[0]
            self.assertIn('-cp',argv)
            self.assertFalse(any(a.startswith('@') for a in argv))
            wildcard=argv[argv.index('-cp')+1]
            expected=str(lib.resolve()/'*')
            if __import__('os').name=='nt':expected='\\\\?\\'+expected
            self.assertEqual(expected,wildcard)
            self.assertEqual(Path(tempfile.gettempdir()).resolve(),process.call_args.kwargs['cwd'])
            manifest=json.loads((root/'output/manifest.json').read_text(encoding='utf8'))
            self.assertEqual(['owned-one.jar','owned-two.jar'],[p['path'] for p in manifest['packageFiles']])
            self.assertEqual(str(Path(tempfile.gettempdir()).resolve()),manifest['commandCwd'])

    def test_session_timeout_preserves_separate_execution_and_incomplete_archive(self):
        from unittest.mock import patch
        import subprocess
        from platform_scenario import run_session, save, compare_archive
        with tempfile.TemporaryDirectory(prefix='platform-timeout-preservation-') as temporary:
            root=Path(temporary); (root/'inputs').mkdir(); (root/'inputs/owned.txt').write_text('owned',encoding='utf8')
            (root/'dist/lib').mkdir(parents=True); (root/'dist/lib/owned.jar').write_bytes(b'Owned non-executable fixture')
            save(root/'scenario.json',dict(inputs='inputs'));save(root/'contract.json',{})
            with patch('platform_scenario.subprocess.run',side_effect=subprocess.TimeoutExpired('owned external boundary',1)):
                result=run_session(root/'scenario.json',root/'contract.json',root/'dist',root/'output',timeout=1)
            self.assertEqual('timed-out',result['execution']['status'])
            self.assertEqual('not-run',result['consumption']['status'])
            self.assertEqual('preserved-incomplete',result['sealing']['status'])
            self.assertTrue((root/'output/stdout.log').is_file())
            self.assertIs(False,json.loads((root/'output/archive.json').read_text(encoding='utf8'))['complete'])
            with self.assertRaises(PlatformError):
                compare_archive(root/'output')

    def test_reused_world_text_does_not_hide_an_old_or_missing_generation(self):
        sample = dict(worldId='reused-world-text', stepIndex=-1, simulationTime=0,
                      units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')])
        intent = dict(intentId='choose', kind='select', unitId='f')
        rule = dict(intentId='choose', actionType='SelectAction', unitId='f', accepted=True,
                    assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        rows = [dict(sequence=0, kind='load-ready', generation=2),
                dict(sequence=1, kind='observation', generation=2, phase='initial', sample=sample),
                dict(sequence=2, kind='intent-start', generation=2, intentId='choose'),
                dict(sequence=3, kind='observation', generation=2, phase='before-action', sample=sample),
                dict(sequence=4, kind='action', generation=2, type='SelectAction', unitId='f',
                     actorPlayerId='p1', result=dict(actionId='a', accepted=True)),
                dict(sequence=5, kind='observation', generation=2, phase='after-action', sample=sample),
                dict(sequence=6, kind='intent-end', generation=2, intentId='choose'),
                dict(sequence=7, kind='observation', generation=2, phase='before-close', sample=sample),
                dict(sequence=8, kind='closed', generation=2)]
        plan = dict(scenarioId='s', intents=[intent]); contract = dict(scenarioId='s', rules=[rule])
        anchors = [dict(intentId='choose', afterSequence=2, endSequence=5)]
        self.assertEqual(0, compare_run(rows, plan, contract, anchors)['exitCode'])
        for generation in (1, None, True, -1):
            changed = copy.deepcopy(rows); changed[5]['generation'] = generation
            with self.subTest(generation=generation), self.assertRaises(PlatformError):
                compare_run(changed, plan, contract, anchors)
        changed = copy.deepcopy(rows); del changed[5]['generation']
        with self.assertRaises(PlatformError):
            compare_run(changed, plan, contract, anchors)
        legacy = [{k:v for k,v in row.items() if k != 'generation' or row['kind']=='load-ready'} for row in rows]
        self.assertEqual(0, compare_run(legacy, plan, contract, anchors)['exitCode'])

    def test_session_forwards_its_explicit_process_budget_to_the_actual_java_load_boundary(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from platform_scenario import run_session, save
        with tempfile.TemporaryDirectory(prefix='platform-budget-') as folder:
            root=Path(folder);(root/'inputs').mkdir();(root/'inputs/input.txt').write_text('owned',encoding='utf8')
            (root/'dist/lib').mkdir(parents=True);(root/'dist/lib/owned.jar').write_bytes(b'owned')
            save(root/'plan.json',dict(inputs='inputs'));save(root/'contract.json',{})
            with patch('platform_scenario.subprocess.run',return_value=SimpleNamespace(returncode=1)) as process:
                result=run_session(root/'plan.json',root/'contract.json',root/'dist',root/'out',timeout=180)
            self.assertEqual('adapter-process',result['category'])
            self.assertEqual('180',process.call_args.args[0][-1])
            self.assertEqual(180,process.call_args.kwargs['timeout'])
            self.assertEqual('failed',result['execution']['status'])
            self.assertEqual(1,result['execution']['exitCode'])
            self.assertEqual('not-run',result['consumption']['status'])
            self.assertEqual('preserved-incomplete',result['sealing']['status'])
            self.assertIs(False,json.loads((root/'out/archive.json').read_text(encoding='utf8'))['complete'])
            for invalid in (0,-1,601):
                with self.subTest(timeout=invalid),self.assertRaises(PlatformError):
                    run_session(root/'plan.json',root/'contract.json',root/'dist',root/('invalid-'+str(invalid)),timeout=invalid)
                self.assertFalse((root/('invalid-'+str(invalid))).exists())

    def test_weighted_sound_contract_checks_exact_tables_and_only_legal_actual_choices_without_sample_quotas(self):
        from platform_scenario import compare_resource_sounds
        event = 'reforge:events/tank-fire'
        candidates = [dict(sound='owned:sounds/a', weight=2, providerId='owned:upper'),
                      dict(sound='owned:sounds/b', weight=1, providerId='owned:lower')]
        tables = {event: dict(providerId='owned:upper', totalWeight=3, candidates=candidates),
                  'owned:events/other': dict(providerId='owned:lower', totalWeight=1,
                      candidates=[dict(sound='owned:sounds/b', weight=1, providerId='owned:lower')])}
        expected = dict(eventId=event, events=tables, minimumRequests=1)
        stack = dict(events=copy.deepcopy(tables), resources={
            'owned:sounds/a': dict(path='a.wav', sha256='a'*64),
            'owned:sounds/b': dict(path='b.wav', sha256='b'*64)})
        request = dict(kind='sound-play-requested', eventId=event, resourceId='owned:sounds/b',
            path='b.wav', sha256='b'*64, volume=.3, pitch=1., pan=0., frame=1, serial=1, step=0, voice=1)
        rows = [dict(kind='visual-assets-ready', soundStack=stack),
                dict(kind='sound-backend-ready', backend='device-free'), request]
        self.assertEqual(['owned:sounds/b'], compare_resource_sounds(rows, expected)['selectedResources'])
        for field, value in [('weight', 1), ('providerId', 'owned:wrong'), ('sound', 'owned:sounds/missing')]:
            changed = copy.deepcopy(rows); changed[0]['soundStack']['events'][event]['candidates'][0][field] = value
            with self.subTest(field=field), self.assertRaises(PlatformError): compare_resource_sounds(changed, expected)
        changed = copy.deepcopy(rows); del changed[0]['soundStack']['events']['owned:events/other']
        with self.assertRaises(PlatformError): compare_resource_sounds(changed, expected)
        for field, value in [('resourceId', 'owned:sounds/outside'), ('path', 'a.wav'), ('position', [1, 2])]:
            changed = copy.deepcopy(rows); changed[-1][field] = value
            with self.subTest(field=field), self.assertRaises(PlatformError): compare_resource_sounds(changed, expected)
        with self.assertRaises(PlatformError): compare_resource_sounds(rows+[request], expected)

    def test_sound_contract_requires_authorized_requests_and_rejects_duplicates_failures_or_wrong_bindings(self):
        from platform_scenario import compare_resource_sounds
        expected = dict(eventId='reforge:events/tank-fire', resourceId='owned:sounds/shot',
                        providerId='owned:sound-wav', minimumRequests=1)
        stack = dict(events={expected['eventId']:dict(sound=expected['resourceId'], providerId=expected['providerId'])},
                     resources={expected['resourceId']:dict(path='fixed.wav', sha256='a'*64)})
        request = dict(kind='sound-play-requested', eventId=expected['eventId'], resourceId=expected['resourceId'],
                       path='fixed.wav', sha256='a'*64, volume=.3, pitch=1., pan=0., frame=1, serial=1, step=0, voice=1)
        rows = [dict(kind='visual-assets-ready', soundStack=stack),
                dict(kind='sound-backend-ready', backend='device-free'), request]
        self.assertEqual(1, compare_resource_sounds(rows, expected)['requests'])
        for key, value in [('resourceId', 'wrong:resource'), ('volume', 2.), ('pitch', 2.), ('pan', 1.),
                           ('position', [1, 2]), ('path', 'other.wav'), ('sha256', 'b'*64), ('voice', -1), ('frame', True)]:
            changed = copy.deepcopy(rows); changed[-1][key] = value
            with self.subTest(key=key), self.assertRaises(PlatformError): compare_resource_sounds(changed, expected)
        for changed in (rows[:-1], rows+[request], rows+[dict(kind='sound-play-failed')],
                        [rows[0], dict(kind='sound-backend-ready', backend='actual'), request]):
            with self.subTest(changed=changed), self.assertRaises(PlatformError): compare_resource_sounds(changed, expected)

        current=[dict(kind='load-ready',generation=1),*[dict(row,generation=1) for row in rows]]
        self.assertEqual(1,compare_resource_sounds(current,expected)['requests'])
        with self.assertRaises(PlatformError):compare_resource_sounds([current[0],*current],expected)
        for index in range(len(current)):
            for wrong in (2,True,None):
                changed=copy.deepcopy(current)
                if wrong is None:del changed[index]['generation']
                else:changed[index]['generation']=wrong
                with self.subTest(index=index,generation=wrong),self.assertRaises(PlatformError):
                    compare_resource_sounds(changed,expected)
        legacy=[dict(kind='load-ready',generation=1),dict(rows[0],generation=1),*rows[1:],dict(kind='closed')]
        self.assertEqual(1,compare_resource_sounds(legacy,expected)['requests'])
        for fault in ('missing-preparation','wrong-preparation','labelled-world','labelled-playback','duplicate-load'):
            changed=copy.deepcopy(legacy)
            if fault=='missing-preparation':del changed[1]['generation']
            elif fault=='wrong-preparation':changed[0]['generation']=2
            elif fault=='labelled-world':changed[-1]['generation']=1
            elif fault=='labelled-playback':changed[-2]['generation']=1
            else:changed.insert(0,copy.deepcopy(changed[0]))
            with self.subTest(legacy=fault),self.assertRaises(PlatformError):compare_resource_sounds(changed,expected)
        with self.assertRaises(PlatformError):compare_resource_sounds([*current,dict(kind='closed',generation=2)],expected)

    def test_resource_stack_contract_rejects_wrong_winners_layout_or_missing_preparation(self):
        from platform_scenario import compare_resource_images
        expectation = dict(packOrder=['owned:amber','owned:blue','reforge:base'], resources={
            'reforge:units/c_tank/body':dict(providerId='owned:amber',frameWidth=20,frameHeight=20,
                totalFrames=3,pixelWidth=120,pixelHeight=40,bound=True)})
        row = dict(kind='visual-assets-ready',resourceStack=copy.deepcopy(expectation))
        self.assertEqual(expectation['packOrder'],compare_resource_images([row],expectation)['packOrder'])
        for field,value in [('providerId','owned:blue'),('frameWidth',40),('pixelWidth',60),('bound',False)]:
            changed=copy.deepcopy(row)
            changed['resourceStack']['resources']['reforge:units/c_tank/body'][field]=value
            with self.subTest(field=field),self.assertRaises(PlatformError):compare_resource_images([changed],expectation)
        for rows in ([],[row,row]):
            with self.subTest(rows=len(rows)),self.assertRaises(PlatformError):compare_resource_images(rows,expectation)
        for bad in ({},dict(expectation,resources={}),dict(expectation,packOrder=[]),
                    dict(expectation,resources={'reforge:units/c_tank/body':dict(unknown=1)})):
            with self.subTest(bad=bad),self.assertRaises(PlatformError):compare_resource_images([row],bad)

    def test_input_manifest_uses_case_sensitive_posix_order_on_every_host(self):
        from platform_scenario import tree
        with tempfile.TemporaryDirectory(prefix='platform-manifest-') as folder:
            root = Path(folder)
            for name in ('extractor_back.ini', 'extractorT2.ini'):
                (root/name).write_text(name, encoding='utf8')
            self.assertEqual(['extractorT2.ini', 'extractor_back.ini'], [row['path'] for row in tree(root)])

    def test_archive_input_identity_survives_platform_order_but_rejects_changed_members(self):
        from platform_scenario import compare_archive, save, sha, tree
        with tempfile.TemporaryDirectory(prefix='platform-order-') as folder:
            root = Path(folder)
            (root/'session').mkdir()
            (root/'inputSnapshot').mkdir()
            for name in ('extractorT2.ini', 'extractor_back.ini'):
                (root/'inputSnapshot'/name).write_text(name, encoding='utf8')
            sample = dict(worldId='w',stepIndex=0,simulationTime=0,
                          units=[dict(unitId='f',ownerPlayerId='p1',typeId='landFactory')])
            save(root/'scenario.json',dict(scenarioId='s',intents=[dict(intentId='choose',kind='select',unitId='f')]))
            save(root/'contract.json',dict(scenarioId='s',rules=[dict(intentId='choose',actionType='SelectAction',
                unitId='f',accepted=True,assertions=[dict(path='units.f.typeId',operator='equals',value='landFactory')])]))
            save(root/'intents.json',[dict(intentId='choose',afterSequence=1,endSequence=4)])
            rows=[dict(sequence=0,kind='observation',phase='initial',sample=sample),
                  dict(sequence=1,kind='intent-start',intentId='choose'),
                  dict(sequence=2,kind='observation',phase='before-action',sample=sample),
                  dict(sequence=3,kind='action',type='SelectAction',unitId='f',actorPlayerId='p1',result=dict(actionId='a',accepted=True)),
                  dict(sequence=4,kind='observation',phase='after-action',sample=sample),
                  dict(sequence=5,kind='observation',phase='before-close',sample=sample),dict(sequence=6,kind='closed')]
            (root/'session/client-diagnostic.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows),encoding='utf8')
            for name in ('stdout.log','stderr.log'):
                (root/name).write_bytes(b'')
            manifest=dict(backend='session',packageFiles=[dict(path='adapter.jar',sha256='identity')],
                          scenarioSha256=sha(root/'scenario.json'),contractSha256=sha(root/'contract.json'))
            original=tree(root/'inputSnapshot')
            for identities in (original, list(reversed(original))):
                manifest['inputFiles']=identities
                (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf8')
                (root/'archive.json').write_text(json.dumps(dict(complete=True,backend='session',
                    files=[row for row in tree(root) if row['path']!='archive.json'])),encoding='utf8')
                self.assertEqual(0,compare_archive(root)['exitCode'])
            for identities in (original[:-1],original+[original[0]],
                               [dict(original[0],sha256='changed'),original[1]]):
                manifest['inputFiles']=identities
                (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf8')
                (root/'archive.json').write_text(json.dumps(dict(complete=True,backend='session',
                    files=[row for row in tree(root) if row['path']!='archive.json'])),encoding='utf8')
                with self.assertRaises(PlatformError):
                    compare_archive(root)

    def test_session_closed_does_not_hide_a_failed_windows_process_tail(self):
        intent=dict(intentId='choose',kind='select',unitId='f')
        rule=dict(intentId='choose',actionType='SelectAction',unitId='f',accepted=True,
                  assertions=[dict(path='units.f.typeId',operator='equals',value='landFactory')])
        sample=dict(worldId='w',stepIndex=0,simulationTime=0,units=[dict(unitId='f',ownerPlayerId='p1',typeId='landFactory')])
        rows=[dict(sequence=0,kind='resize',width=1200,height=800,screenWidth=1200,screenHeight=800),
              dict(sequence=1,kind='observation',phase='initial',sample=sample),
              dict(sequence=2,kind='observation',phase='before-action',sample=sample),
              dict(sequence=3,kind='action',type='SelectAction',unitId='f',actorPlayerId='p1',result=dict(actionId='click',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=sample),
              dict(sequence=5,kind='observation',phase='before-close',sample=sample),dict(sequence=6,kind='closed'),
              dict(sequence=7,kind='match-exit'),dict(sequence=8,kind='presentation-released'),
              dict(sequence=9,kind='application-disposed'),dict(sequence=10,kind='process-completed',status='normal')]
        args=(dict(scenarioId='s',intents=[intent]),dict(scenarioId='s',rules=[rule]),
              [dict(intentId='choose',afterSequence=1,endSequence=4)],dict(worldWidth=600,worldHeight=400))
        self.assertEqual(0,compare_run(rows,*args)['exitCode'])
        for fault in ('failed','missing-dispose','error','missing-process','out-of-order'):
            changed=copy.deepcopy(rows)
            if fault=='failed':changed[-1]['status']='failed'
            elif fault=='missing-dispose':changed[-2]['kind']='other'
            elif fault=='error':changed[-2]['kind']='application-error'
            elif fault=='missing-process':changed[-1]['kind']='other'
            else:changed[-2]['kind'],changed[-3]['kind']=changed[-3]['kind'],changed[-2]['kind']
            with self.subTest(fault=fault),self.assertRaises(PlatformError):compare_run(changed,*args)

    def test_attack_requires_a_unique_real_enemy_with_the_fixed_identity(self):
        public = Path(__file__).resolve().parents[1]/'public/platform'
        intent = next(i for i in json.loads((public/'scenarios/production.json').read_text())['intents'] if i['kind']=='attack')
        rule = next(i for i in json.loads((public/'contracts/production.json').read_text())['rules'] if i['actionType']=='AttackAction')
        sample = dict(worldId='w', stepIndex=0, simulationTime=0,
                      units=[dict(unitId='a', ownerPlayerId='p1', typeId='c_tank', attackTargetId='b'),
                             dict(unitId='b', ownerPlayerId='p2', typeId='c_tank', health=210)])
        rows = [dict(sequence=2, kind='observation', phase='before-action', sample=copy.deepcopy(sample)),
                dict(sequence=3, kind='action', type='AttackAction', unitId='a', actorPlayerId='p1',targetUnitId='b',
                     result=dict(actionId='click', accepted=True)),
                dict(sequence=4, kind='observation', phase='after-action', sample=copy.deepcopy(sample))]
        self.assertEqual(0, compare_intent(rows, intent, rule, 1)['exitCode'])
        for index in (0, 2):
            for fault in ('missing','duplicate','owner','type','dead'):
                changed=copy.deepcopy(rows);units=changed[index]['sample']['units']
                if fault=='missing': units.pop()
                elif fault=='duplicate': units.append(copy.deepcopy(units[-1]))
                elif fault=='owner': units[-1]['ownerPlayerId']='p1'
                elif fault=='type': units[-1]['typeId']='builder'
                else: units[-1]['health']=0
                with self.subTest(index=index,fault=fault), self.assertRaises(PlatformError):
                    compare_intent(changed, intent, rule, 1)

    def test_fixed_self_selection_contracts_bind_observed_owner_before_and_after(self):
        public = Path(__file__).resolve().parents[1]/'public/platform'
        for scene in ('production', 'construction', 'upgrade'):
            plan = json.loads((public/'scenarios'/(scene+'.json')).read_text(encoding='utf8'))
            contract = json.loads((public/'contracts'/(scene+'.json')).read_text(encoding='utf8'))
            for intent, rule in zip(plan['intents'], contract['rules']):
                if intent['kind'] != 'select':
                    continue
                sample = dict(worldId='w', stepIndex=0, simulationTime=0,
                              units=[dict(unitId=rule['unitId'], typeId=rule['assertions'][0]['value'], ownerPlayerId='p1')])
                rows = [dict(sequence=2, kind='observation', phase='before-action', sample=copy.deepcopy(sample)),
                        dict(sequence=3, kind='action', type='SelectAction', unitId=rule['unitId'], actorPlayerId='p1',
                             result=dict(actionId='click', accepted=True)),
                        dict(sequence=4, kind='observation', phase='after-action', sample=copy.deepcopy(sample))]
                self.assertEqual(0, compare_intent(rows, intent, rule, 1)['exitCode'])
                for index in (0, 2):
                    changed = copy.deepcopy(rows)
                    changed[index]['sample']['units'][0]['ownerPlayerId'] = 'p2'
                    with self.subTest(scene=scene, unit=rule['unitId'], observation=index), self.assertRaises(PlatformError):
                        compare_intent(changed, intent, rule, 1)

    def test_an_explicit_foreign_selection_contract_keeps_legal_enemy_selection(self):
        intent=dict(intentId='enemy',kind='select',unitId='b',actor='p1')
        rule=dict(intentId='enemy',actionType='SelectAction',unitId='b',accepted=True,
                  unitIdentity=dict(ownerPlayerId='p2',typeId='c_tank'),
                  assertions=[dict(path='units.b.typeId',operator='equals',value='c_tank')])
        sample=dict(worldId='w',stepIndex=0,simulationTime=0,units=[dict(unitId='b',ownerPlayerId='p2',typeId='c_tank')])
        rows=[dict(sequence=2,kind='observation',phase='before-action',sample=sample),
              dict(sequence=3,kind='action',type='SelectAction',unitId='b',actorPlayerId='p1',result=dict(actionId='click',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=sample)]
        self.assertEqual(0,compare_intent(rows,intent,rule,1)['exitCode'])

    def test_archive_with_empty_file_manifest_cannot_claim_complete_evidence(self):
        from platform_scenario import compare_archive
        with tempfile.TemporaryDirectory(prefix='platform-empty-archive-') as folder:
            root = Path(folder)
            (root/'archive.json').write_text(json.dumps(dict(complete=True, backend='session', files=[])))
            with self.assertRaises(PlatformError) as caught:
                compare_archive(root)
            self.assertEqual('archive-identity', caught.exception.category)

    def test_platform_archive_rejects_viewport_changes_after_input_delivery(self):
        intent = dict(intentId='choose', kind='select', unitId='f')
        rule = dict(intentId='choose', actionType='SelectAction', unitId='f', accepted=True,
                    assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        sample = dict(worldId='w', stepIndex=0, simulationTime=0, units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')])
        records = [dict(sequence=0, kind='resize', width=1200, height=800, screenWidth=1200, screenHeight=800),
                   dict(sequence=1, kind='observation', phase='initial', sample=sample),
                   dict(sequence=2, kind='observation', phase='before-action', sample=sample),
                   dict(sequence=3, kind='action', type='SelectAction', unitId='f', actorPlayerId='p1',
                        result=dict(actionId='click', accepted=True)),
                   dict(sequence=4, kind='observation', phase='after-action', sample=sample),
                   dict(sequence=5, kind='resize', width=960, height=640, screenWidth=960, screenHeight=640),
                   dict(sequence=6, kind='observation', phase='before-close', sample=sample),
                   dict(sequence=7, kind='closed'),dict(sequence=8,kind='match-exit'),
                   dict(sequence=9,kind='presentation-released'),dict(sequence=10,kind='application-disposed'),
                   dict(sequence=11,kind='process-completed',status='normal')]
        with self.assertRaises(PlatformError) as caught:
            compare_run(records, dict(scenarioId='s', intents=[intent]), dict(scenarioId='s', rules=[rule]),
                        [dict(intentId='choose', afterSequence=1, endSequence=4)],
                        dict(worldWidth=600, worldHeight=400))
        self.assertEqual('input-geometry', caught.exception.category)

    def test_platform_click_uses_one_pixel_from_real_geometry_but_state_matches_the_exact_action(self):
        intent = dict(intentId='move', kind='move', unitId='a', position=dict(x=450, y=200))
        rule = dict(intentId='move', actionType='MoveAction', unitId='a', accepted=True, targetFromIntent=True,
                    assertions=[dict(path='units.a.moveTarget', operator='equalsActionTarget')])
        geometry = dict(worldWidth=600, worldHeight=400,
                        viewport=dict(width=1200, height=800, screenWidth=1200, screenHeight=800))
        def rows(x, observed=None):
            target = dict(x=x, y=200)
            base = dict(worldId='w', stepIndex=0, simulationTime=0, units=[dict(unitId='a', ownerPlayerId='p1', moveTarget=None)])
            after = copy.deepcopy(base)
            after['units'][0]['moveTarget'] = target if observed is None else observed
            return [dict(sequence=2, kind='observation', phase='before-action', sample=base),
                    dict(sequence=3, kind='action', type='MoveAction', unitId='a', actorPlayerId='p1', target=target,
                         result=dict(actionId='click-1', accepted=True)),
                    dict(sequence=4, kind='observation', phase='after-action', sample=after)]
        self.assertEqual(0, compare_intent(rows(449.9), intent, rule, 1, geometry)['exitCode'])
        for evidence, context in ((rows(449), geometry), (rows(449.9, dict(x=450, y=200)), geometry),
                                  (rows(449.9), None)):
            with self.assertRaises(PlatformError) as caught:
                compare_intent(evidence, intent, rule, 1, context)
            self.assertEqual(2, caught.exception.exit_code)

    def test_stop_requires_the_fixed_stable_tail_after_the_actual_command(self):
        intent = dict(intentId='stop', kind='stop', unitId='a')
        rule = dict(intentId='stop', actionType='StopAction', unitId='a', accepted=True,
                    assertions=[dict(path='units.a.moveTarget', operator='equals', value=None)],
                    tail=dict(samples=2, assertions=[dict(path='units.a.speed', operator='equals', value=0),
                                                    dict(path='units.a.position', operator='stable')]))
        def observation(sequence, phase, speed, x):
            return dict(sequence=sequence, kind='observation', phase=phase,
                        sample=dict(worldId='w', stepIndex=sequence, simulationTime=sequence,
                                    units=[dict(unitId='a', ownerPlayerId='p1', moveTarget=None, speed=speed, position=dict(x=x, y=0))]))
        rows = [observation(2, 'before-action', 1, 0),
                dict(sequence=3, kind='action', type='StopAction', unitId='a', actorPlayerId='p1',
                     result=dict(actionId='click-1', accepted=True)), observation(4, 'after-action', 1, 0),
                observation(5, 'step', 0, 1), observation(6, 'step', 0, 2)]
        rows[2]['sample'].update(stepIndex=2, simulationTime=2)
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, 1)
        self.assertEqual('tail-assertion', caught.exception.category)
        rows[4]['sample']['units'][0]['position']['x'] = 1
        self.assertEqual(0, compare_intent(rows, intent, rule, 1)['exitCode'])
        del rows[-1]
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, 1)
        self.assertEqual('missing-tail', caught.exception.category)

    def test_a_wrong_upgrade_branch_is_not_accepted_by_a_matching_money_delta(self):
        intent = dict(intentId='upgrade', kind='upgrade', unitId='t')
        rule = dict(intentId='upgrade', actionType='UpgradeAction', unitId='t', accepted=True,
                    actionFields=dict(upgradeId='upgrade_gun'),
                    assertions=[dict(path='balance', operator='deltaEquals', value=-1000)])
        rows = [dict(sequence=2, kind='observation', phase='before-action',
                     sample=dict(worldId='w', stepIndex=0, simulationTime=0, balance=1750)),
                dict(sequence=3, kind='action', type='UpgradeAction', unitId='t', actorPlayerId='p1',
                     upgradeId='upgrade_artillery', result=dict(actionId='click-1', accepted=True)),
                dict(sequence=4, kind='observation', phase='after-action',
                     sample=dict(worldId='w', stepIndex=0, simulationTime=0, balance=750))]
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, 1)
        self.assertEqual('action-fields', caught.exception.category)

    def test_build_acceptance_requires_one_fresh_real_creation_of_the_fixed_type_and_site(self):
        intent = dict(intentId='build', kind='build', unitId='worker')
        rule = dict(intentId='build', actionType='BuildAction', unitId='worker', accepted=True,
                    assertions=[dict(path='units.worker.typeId', operator='equals', value='builder')],
                    created=dict(event='construction-created', typeId='extractorT1',
                                 position=dict(x=190, y=290), constructionProgress=0))
        sample = dict(worldId='w', stepIndex=0, simulationTime=0,
                      units=[dict(unitId='worker', ownerPlayerId='p1', typeId='builder')], events=[])
        rows = [dict(sequence=2, kind='observation', phase='before-action', sample=copy.deepcopy(sample)),
                dict(sequence=3, kind='action', type='BuildAction', unitId='worker', actorPlayerId='p1',
                     result=dict(actionId='click-1', accepted=True)),
                dict(sequence=4, kind='observation', phase='after-action', sample=copy.deepcopy(sample))]
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, 1)
        self.assertEqual('missing-creation', caught.exception.category)
        created = copy.deepcopy(sample)
        created.update(stepIndex=1, simulationTime=1)
        created['units'].append(dict(unitId='unchanged-runtime-id', ownerPlayerId='p1', typeId='extractorT1',
                                     position=dict(x=190, y=290), constructionProgress=0))
        created['events'] = [dict(kind='construction-created', unitId='unchanged-runtime-id')]
        rows.append(dict(sequence=5, kind='observation', phase='step', sample=created))
        self.assertEqual('unchanged-runtime-id', compare_intent(rows, intent, rule, 1)['createdUnitId'])
        created['units'][-1]['typeId'] = 'fabricatorT1'
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, 1)
        self.assertEqual(2, caught.exception.exit_code)

    def test_empty_or_unknown_assertion_contracts_are_rejected_before_judgment(self):
        intent = dict(intentId='choose', kind='select', unitId='f')
        valid = dict(intentId='choose', actionType='SelectAction', unitId='f', accepted=True,
                     assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        rows = [dict(sequence=2, kind='observation', phase='before-action',
                     sample=dict(worldId='w', stepIndex=0, simulationTime=0, units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')])),
                dict(sequence=3, kind='action', type='SelectAction', unitId='f', actorPlayerId='p1',
                     result=dict(actionId='click-1', accepted=True)),
                dict(sequence=4, kind='observation', phase='after-action',
                     sample=dict(worldId='w', stepIndex=0, simulationTime=0, units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')]))]
        for assertions in ([], [dict(path='units.f.typeId', operator='guess', value='landFactory')],
                           [dict(path='units.f.typeId', value='landFactory')]):
            with self.subTest(assertions=assertions):
                rule = dict(valid, assertions=assertions)
                with self.assertRaises(PlatformError) as caught:
                    compare_intent(rows, intent, rule, 1)
                self.assertEqual('input-contract', caught.exception.category)

    def test_fixed_production_plan_requires_a_complete_ordered_set_of_real_calls(self):
        public = Path(__file__).resolve().parents[1] / 'public/platform'
        plan = json.loads((public / 'scenarios/production.json').read_text(encoding='utf8'))
        contract = json.loads((public / 'contracts/production.json').read_text(encoding='utf8'))
        with self.assertRaises(PlatformError) as caught:
            compare_run([], plan, contract, [])
        self.assertEqual('incomplete-run', caught.exception.category)

    def test_immediate_payment_is_judged_from_the_same_call_not_absolute_starting_money(self):
        intent = dict(intentId='produce', kind='produce', unitId='f')
        rule = dict(intentId='produce', actionType='ProduceAction', unitId='f', accepted=True,
                    assertions=[dict(path='balance', operator='deltaEquals', value=-350)])
        def rows(balance):
            return [dict(sequence=2, kind='observation', phase='before-action',
                         sample=dict(worldId='w', stepIndex=99, simulationTime=99, balance=2017, units=[dict(unitId='f', ownerPlayerId='p1')])),
                    dict(sequence=3, kind='action', type='ProduceAction', unitId='f', actorPlayerId='p1',
                         result=dict(actionId='click-2', accepted=True)),
                    dict(sequence=4, kind='observation', phase='after-action',
                         sample=dict(worldId='w', stepIndex=99, simulationTime=99, balance=balance, units=[dict(unitId='f', ownerPlayerId='p1')]))]
        self.assertEqual(0, compare_intent(rows(1667), intent, rule, 1)['exitCode'])
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows(1668), intent, rule, 1)
        self.assertEqual(2, caught.exception.exit_code)

    def test_an_unrelated_call_cannot_supply_the_after_action_sample(self):
        intent = dict(intentId='choose', kind='select', unitId='f')
        rule = dict(intentId='choose', actionType='SelectAction', unitId='f', accepted=True,
                    assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        def observation(sequence, phase):
            return dict(sequence=sequence, kind='observation', phase=phase,
                        sample=dict(worldId='world-one', simulationTime=0, stepIndex=0,
                                    units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')]))
        rows = [observation(2, 'before-action'),
                dict(sequence=3, kind='action', type='SelectAction', unitId='f', actorPlayerId='p1',
                     result=dict(actionId='click-1', accepted=True)),
                observation(4, 'before-action'),
                dict(sequence=5, kind='action', type='StopAction', unitId='a', actorPlayerId='p1',
                     result=dict(actionId='click-2', accepted=True)), observation(6, 'after-action')]
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, after_sequence=1)
        self.assertEqual('call-boundary', caught.exception.category)

    def test_a_new_world_cannot_supply_the_postcondition_of_an_accepted_action(self):
        intent = dict(intentId='choose', kind='select', unitId='f')
        rule = dict(intentId='choose', actionType='SelectAction', unitId='f', accepted=True,
                    assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        def observation(sequence, phase, world):
            return dict(sequence=sequence, kind='observation', phase=phase,
                        sample=dict(worldId=world, simulationTime=0, stepIndex=0,
                                    units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')]))
        rows = [observation(2, 'before-action', 'world-one'),
                dict(sequence=3, kind='action', type='SelectAction', unitId='f', actorPlayerId='p1',
                     result=dict(actionId='click-1', accepted=True)), observation(4, 'after-action', 'world-two')]
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, after_sequence=1)
        self.assertEqual(3, caught.exception.exit_code)
        self.assertEqual('world-identity', caught.exception.category)

    def test_prior_acceptance_cannot_satisfy_a_new_input_intent(self):
        intent = dict(intentId='choose-again', kind='select', unitId='f')
        rule = dict(intentId='choose-again', actionType='SelectAction', unitId='f', accepted=True,
                    assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        rows = [dict(sequence=3, kind='action', type='SelectAction', unitId='f', actorPlayerId='p1',
                     result=dict(actionId='old-click', accepted=True)),
                dict(sequence=4, kind='observation', phase='after-action',
                     sample=dict(worldId='world-one', simulationTime=0, stepIndex=0,
                                 units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')]))]
        with self.assertRaises(PlatformError) as caught:
            compare_intent(rows, intent, rule, after_sequence=4)
        self.assertEqual(3, caught.exception.exit_code)
        self.assertEqual('action-identity', caught.exception.category)

    def test_fresh_formal_selection_binds_its_own_after_action_sample(self):
        intent = dict(intentId='choose', kind='select', unitId='f')
        rule = dict(intentId='choose', actionType='SelectAction', unitId='f', accepted=True,
                    assertions=[dict(path='units.f.typeId', operator='equals', value='landFactory')])
        def observation(sequence, phase):
            return dict(sequence=sequence, kind='observation', phase=phase,
                        sample=dict(worldId='world-one', simulationTime=0, stepIndex=0,
                                    units=[dict(unitId='f', ownerPlayerId='p1', typeId='landFactory')]))
        rows = [observation(2, 'before-action'),
                dict(sequence=3, kind='action', type='SelectAction', unitId='f', actorPlayerId='p1',
                     result=dict(actionId='click-1', accepted=True)), observation(4, 'after-action')]
        result = compare_intent(rows, intent, rule, after_sequence=1)
        self.assertEqual(0, result['exitCode'])
        self.assertEqual('click-1', result['actionId'])
        self.assertEqual(3, result['actionSequence'])
        self.assertEqual(4, result['sampleSequence'])


if __name__ == '__main__':
    unittest.main()
