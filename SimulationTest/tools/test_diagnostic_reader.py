"""Live diagnostic request boundaries; literal fixtures are not PC operation evidence."""
import json
from pathlib import Path
import tempfile
import subprocess
import sys
from types import SimpleNamespace
import unittest

from diagnostic_reader import DiagnosticReader, DiagnosticError


class DiagnosticReaderTest(unittest.TestCase):
    def test_a_later_bad_line_preserves_the_current_business_first_error_but_not_an_old_generation(self):
        for failed_generation in (1,3):
            with self.subTest(failed_generation=failed_generation),tempfile.TemporaryDirectory() as temporary:
                path=Path(temporary)/'diagnostic.jsonl'
                rows=[dict(kind='load-request',generation=3),
                      dict(kind='load-error',generation=failed_generation,message='actual first')]
                path.write_bytes(b''.join((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',
                    sequence=i,**row))+'\n').encode() for i,row in enumerate(rows))+b'{bad complete line}\n')
                reader=DiagnosticReader(path)
                def no_sleep(seconds):self.fail('The complete failing prefix must end the wait immediately')
                with self.assertRaises(DiagnosticError) as failed:
                    reader.wait('ready',lambda rows:True,SimpleNamespace(poll=lambda:None),generation=3,sleep=no_sleep)
                if failed_generation==3:
                    self.assertEqual('actual first',failed.exception.record['message'])
                    self.assertEqual('diagnostic-stream',failed.exception.secondary[0]['category'])
                    with self.assertRaises(DiagnosticError) as completed:reader.finish(0)
                    self.assertEqual('actual first',completed.exception.record['message'])
                    self.assertEqual('diagnostic-stream',completed.exception.secondary[0]['category'])
                else:
                    self.assertEqual('diagnostic-stream',failed.exception.category)
                    self.assertIsNone(failed.exception.record)
                self.assertEqual([0,1],[r['sequence'] for r in reader.view()])

    def test_only_a_complete_stream_and_actual_normal_process_exit_can_finish(self):
        for fault in ('none','partial-tail','nonzero-exit','missing-disposal','wrong-tail','recorded-failure'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory(prefix='diagnostic-finish-') as temporary:
                path=Path(temporary)/'diagnostic.jsonl'
                rows=[dict(kind='started'),dict(kind='application-disposed'),dict(kind='process-completed',status='normal')]
                if fault=='missing-disposal':rows[1]['kind']='note'
                if fault=='wrong-tail':rows[2]['status']='failed'
                if fault=='recorded-failure':rows.insert(2,dict(kind='cleanup-error',message='owned cleanup failed'))
                raw=b''.join((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=i,**row))+'\n').encode('utf8') for i,row in enumerate(rows))
                path.write_bytes(raw+(b'{' if fault=='partial-tail' else b''));reader=DiagnosticReader(path)
                if fault=='none':
                    result=reader.finish(0);self.assertEqual(3,result['records']);self.assertEqual('owned-run',result['runId'])
                else:
                    with self.assertRaises(DiagnosticError):reader.finish(7 if fault=='nonzero-exit' else 0)

    def test_a_live_window_guard_must_pass_before_a_successful_wait_returns(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-owned-guard-') as temporary:
            path=Path(temporary)/'diagnostic.jsonl'
            path.write_bytes((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',
                sequence=0,kind='load-ready',generation=1))+'\n').encode('utf8'))
            def lost_focus():raise DiagnosticError('Owned window lost focus',category='owned-focus')
            with self.assertRaisesRegex(DiagnosticError,'lost focus'):
                DiagnosticReader(path).wait('ready',lambda rows:True,SimpleNamespace(poll=lambda:None),check=lost_focus)

    def test_live_wait_retains_recent_steps_and_call_boundaries_not_the_full_trace(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-live-view-') as temporary:
            path=Path(temporary)/'diagnostic.jsonl'
            rows=[dict(kind='observation',phase='initial',sample=dict(stepIndex=-1))]
            rows += [dict(kind='observation',phase='step',sample=dict(stepIndex=i-1)) for i in range(1,4097)]
            rows += [dict(kind='action'),dict(kind='observation',phase='after-action',sample=dict(stepIndex=4095))]
            path.write_bytes(b''.join((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',
                sequence=i,**row))+'\n').encode('utf8') for i,row in enumerate(rows)))
            def current_view(records):
                sequences={row['sequence'] for row in records}
                self.assertTrue({0,4097,4098}.issubset(sequences))
                self.assertTrue(set(range(4088,4097)).issubset(sequences))
                self.assertNotIn(1,sequences,'Long observation history remains in the raw archive')
                return True
            reader=DiagnosticReader(path)
            reader.wait('current view',current_view,SimpleNamespace(poll=lambda:None))
            self.assertEqual(4099,reader.record_count)
            self.assertEqual(4098,reader.cursor)

    def test_a_real_short_lived_process_ends_the_wait_and_keeps_a_recorded_first_error(self):
        for recorded in (False,True):
            with self.subTest(recorded=recorded),tempfile.TemporaryDirectory(prefix='diagnostic-exit-') as temporary:
                path=Path(temporary)/'diagnostic.jsonl'
                if recorded:
                    path.write_bytes((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',
                        sequence=0,kind='load-error',generation=1,message='first loader error'))+'\n').encode('utf8'))
                process=subprocess.Popen([sys.executable,'-B','-c','raise SystemExit(7)'],
                    stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                    creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                self.assertEqual(7,process.wait(timeout=10))
                def no_sleep(seconds):self.fail('An exited process must not consume the deadline')
                with self.assertRaises(DiagnosticError) as failed:
                    DiagnosticReader(path).wait('ready',lambda rows:False,process,generation=1,sleep=no_sleep)
                if recorded:self.assertEqual('first loader error',failed.exception.record['message'])
                else:self.assertEqual('adapter-process',failed.exception.category)

    def test_missing_current_event_ends_at_its_bounded_deadline(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-timeout-') as temporary:
            reader=DiagnosticReader(Path(temporary)/'not-created-yet.jsonl')
            value=[0.0]
            def sleep(seconds):
                value[0]+=seconds
                self.assertLess(value[0],1,'The controlled deadline must terminate')
            with self.assertRaises(DiagnosticError) as failed:
                reader.wait('ready',lambda rows:False,SimpleNamespace(poll=lambda:None),
                    timeout=.1,clock=lambda:value[0],sleep=sleep)
            self.assertEqual('input-timeout',failed.exception.category)
            self.assertGreaterEqual(value[0],.1)

    def test_waits_ignore_old_generations_and_preserve_an_unscoped_process_failure(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-generation-') as temporary:
            path=Path(temporary)/'diagnostic.jsonl'
            rows=[dict(kind='load-request',generation=1),dict(kind='load-ready',generation=1),
                  dict(kind='load-request',generation=3),dict(kind='load-error',generation=1),
                  dict(kind='load-ready',generation=3)]
            def append(records):
                with path.open('ab') as stream:
                    for sequence,row in records:
                        stream.write((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',
                            sequence=sequence,**row))+'\n').encode('utf8'))
            append(enumerate(rows));reader=DiagnosticReader(path)
            found=reader.wait('ready',lambda rows:any(r['kind']=='load-ready' for r in rows),
                SimpleNamespace(poll=lambda:None),after_sequence=1,generation=3)
            self.assertEqual([2,4],[r['sequence'] for r in found])
            append([(5,dict(kind='process-failed',message='owned process failed')),
                    (6,dict(kind='load-ready',generation=3))])
            with self.assertRaises(DiagnosticError) as failure:
                reader.wait('ready',lambda rows:any(r['kind']=='load-ready' for r in rows),
                    SimpleNamespace(poll=lambda:None),after_sequence=4,generation=3)
            self.assertEqual(5,failure.exception.record['sequence'])

    def test_next_request_binds_its_generation_while_late_older_records_are_ignored(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-next-request-') as temporary:
            path=Path(temporary)/'diagnostic.jsonl'
            rows=[dict(kind='load-ready',generation=1),dict(kind='load-error',generation=1),
                  dict(kind='load-request',generation=3),dict(kind='load-error',generation=2),
                  dict(kind='load-error',generation=4)]
            path.write_bytes(b''.join((json.dumps(dict(format='p001.windows-diagnostic.v1',runId='owned-run',
                sequence=i,**row))+'\n').encode('utf8') for i,row in enumerate(rows)))
            reader=DiagnosticReader(path)
            found=reader.wait('load-request',lambda rows:any(r['kind']=='load-request' for r in rows),
                SimpleNamespace(poll=lambda:None),after_sequence=0,after_generation=1)
            self.assertEqual([2],[r['sequence'] for r in found])

    def test_log_truncation_replacement_or_rewrite_cannot_reuse_a_live_cursor(self):
        for fault in ('truncated','replaced','rewritten','removed'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory(prefix='diagnostic-reopen-') as temporary:
                root=Path(temporary);path=root/'diagnostic.jsonl'
                row=dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=0,kind='started')
                raw=(json.dumps(row)+'\n').encode('utf8');path.write_bytes(raw)
                reader=DiagnosticReader(path);self.assertEqual([row],reader.poll())
                if fault=='truncated':path.write_bytes(b'')
                elif fault=='replaced':
                    replacement=root/'replacement.jsonl';replacement.write_bytes(raw)
                    self.assertEqual(root.resolve(),replacement.resolve().parent)
                    replacement.replace(path)
                elif fault=='rewritten':
                    changed=(json.dumps(dict(row,kind='restart'))+'\n').encode('utf8')
                    self.assertEqual(len(raw),len(changed));path.write_bytes(changed)
                else:path.unlink()
                with self.assertRaises(DiagnosticError):reader.poll()

    def test_stream_identity_and_continuous_integer_sequence_preserve_the_first_error(self):
        first=dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=0,kind='started')
        for fault in ('run','format','gap','boolean-sequence','invalid-json','kind',
                      'boolean-generation','negative-generation','string-generation'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory(prefix='diagnostic-identity-') as temporary:
                path=Path(temporary)/'diagnostic.jsonl'
                path.write_bytes((json.dumps(first)+'\n').encode('utf8'))
                reader=DiagnosticReader(path);reader.poll()
                second=dict(first,sequence=1,kind='note')
                if fault=='run':second['runId']='another-run'
                elif fault=='format':second['format']='unknown-format'
                elif fault=='gap':second['sequence']=2
                elif fault=='boolean-sequence':second['sequence']=True
                elif fault=='kind':second['kind']=None
                elif fault=='boolean-generation':second['generation']=True
                elif fault=='negative-generation':second['generation']=-1
                elif fault=='string-generation':second['generation']='1'
                raw=b'{invalid}\n' if fault=='invalid-json' else (json.dumps(second)+'\n').encode('utf8')
                with path.open('ab') as stream:stream.write(raw)
                with self.assertRaises(DiagnosticError) as original:reader.poll()
                with path.open('ab') as stream:stream.write((json.dumps(dict(first,sequence=1))+'\n').encode('utf8'))
                with self.assertRaises(DiagnosticError) as repeated:reader.poll()
                self.assertIs(original.exception,repeated.exception)

    def test_poll_yields_only_new_complete_records_across_a_split_utf8_character(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-incremental-') as temporary:
            path=Path(temporary)/'diagnostic.jsonl'
            first=dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=0,kind='started')
            second=dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=1,kind='note',text='机甲')
            raw=(json.dumps(second,ensure_ascii=False)+'\n').encode('utf8')
            boundary=raw.index('机'.encode('utf8'))+1
            path.write_bytes((json.dumps(first)+'\n').encode('utf8')+raw[:boundary])
            reader=DiagnosticReader(path)
            self.assertEqual([first],reader.poll())
            self.assertEqual([],reader.poll())
            with path.open('ab') as stream:stream.write(raw[boundary:])
            self.assertEqual([second],reader.poll())
            self.assertEqual([],reader.poll())

    def test_current_load_error_ends_the_wait_without_deadline_polling(self):
        with tempfile.TemporaryDirectory(prefix='diagnostic-first-error-') as temporary:
            path=Path(temporary)/'diagnostic.jsonl'
            rows=[dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=0,
                       kind='load-request',generation=1),
                  dict(format='p001.windows-diagnostic.v1',runId='owned-run',sequence=1,
                       kind='load-error',generation=1,message='owned malformed content')]
            path.write_bytes(b''.join((json.dumps(row)+'\n').encode('utf8') for row in rows))
            reader=DiagnosticReader(path)
            def unexpected_sleep(seconds):
                self.fail('A complete current failure must not wait for another poll')
            with self.assertRaises(DiagnosticError) as captured:
                reader.wait('load-ready',lambda rows:any(r['kind']=='load-ready' for r in rows),
                    SimpleNamespace(poll=lambda:None),generation=1,sleep=unexpected_sleep)
            self.assertEqual('load-error',captured.exception.record['kind'])
            self.assertEqual(1,captured.exception.record['sequence'])


if __name__=='__main__':
    unittest.main()
