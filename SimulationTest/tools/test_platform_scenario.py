"""Independent tool examples; actual Session/PC runs are verified separately."""
import unittest
import copy

from platform_scenario import compare_intent, compare_run, PlatformError
import json
from pathlib import Path
import tempfile


class PlatformIntentTest(unittest.TestCase):
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
