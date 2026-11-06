"""Read owned PC diagnostics and stop waits at the current request's outcome."""
import json
import os
from pathlib import Path
import time
from collections import deque


class DiagnosticError(ValueError):
    def __init__(self,message,record=None,category='diagnostic-rejected',secondary=None):
        super().__init__(message)
        self.record=record
        self.category=category
        self.exit_code=3
        self.secondary=list(secondary or [])


def _failed(row):
    return row['kind'].endswith(('-error','-failed')) or row['kind']=='diagnostic-incomplete'


class DiagnosticReader:
    def __init__(self,path):
        self.path=Path(path)
        self._events=[]
        self._samples=deque(maxlen=64)
        self._offset=0
        self._pending=b''
        self._next_sequence=0
        self._run_id=None
        self._failure=None
        self._identity=None
        self._head=b''
        self._tail=b''

    def poll(self):
        if self._failure is not None:raise self._failure
        fresh=[]
        try:
            if not self.path.is_file():
                if self._identity is not None:raise DiagnosticError('Live diagnostic file disappeared',category='diagnostic-stream')
                return []
            state=self.path.stat();end=state.st_size
            identity=(state.st_dev,state.st_ino)
            if self._identity is not None and identity!=self._identity:
                raise DiagnosticError('Live diagnostic file was replaced',category='diagnostic-stream')
            if end<self._offset:
                raise DiagnosticError('Live diagnostic file was truncated',category='diagnostic-stream')
            with self.path.open('rb') as stream:
                opened=os.fstat(stream.fileno())
                if (opened.st_dev,opened.st_ino)!=identity or opened.st_size<end:
                    raise DiagnosticError('Diagnostic identity changed while opening',category='diagnostic-stream')
                if stream.read(len(self._head))!=self._head:
                    raise DiagnosticError('Diagnostic startup prefix was rewritten',category='diagnostic-stream')
                stream.seek(self._offset-len(self._tail))
                if stream.read(len(self._tail))!=self._tail:
                    raise DiagnosticError('Diagnostic cursor prefix was rewritten',category='diagnostic-stream')
                self._identity=identity
                stream.seek(self._offset)
                # Bound this poll to the prefix observed at entry, even if the
                # producer keeps appending while records are being decoded.
                while self._offset<end:
                    chunk=stream.read(min(1024*1024,end-self._offset))
                    if not chunk:raise DiagnosticError('Observed diagnostic prefix disappeared')
                    if len(self._head)<4096:self._head=(self._head+chunk)[:4096]
                    self._tail=(self._tail+chunk)[-64:]
                    self._offset+=len(chunk)
                    lines=(self._pending+chunk).split(b'\n');self._pending=lines.pop()
                    for line in lines:
                        row=json.loads(line.decode('utf8'))
                        if (not isinstance(row,dict) or row.get('format')!='p001.windows-diagnostic.v1' or
                                not isinstance(row.get('runId'),str) or not row['runId'] or
                                not isinstance(row.get('kind'),str) or not row['kind'] or
                                self._run_id is not None and row['runId']!=self._run_id or
                                type(row.get('sequence')) is not int or row['sequence']!=self._next_sequence or
                                'generation' in row and (type(row['generation']) is not int or row['generation']<0)):
                            raise DiagnosticError('Diagnostic run/format/sequence differs',row,category='diagnostic-stream')
                        self._run_id=row['runId'];self._next_sequence+=1;fresh.append(row)
            return fresh
        except (DiagnosticError,OSError,ValueError) as error:
            self._failure=error if isinstance(error,DiagnosticError) else DiagnosticError(
                type(error).__name__+': '+str(error),category='diagnostic-stream')
            raise self._failure
        finally:
            # A later malformed line must not erase the successfully parsed prefix.
            for row in fresh:
                if row['kind']=='observation' and row.get('phase') not in (
                        'initial','before-action','after-action','before-close'):
                    self._samples.append(row)
                else:self._events.append(row)

    @property
    def record_count(self):return self._next_sequence

    @property
    def cursor(self):return self._next_sequence-1

    def view(self):
        """Current live view only; full observation history stays in the raw file."""
        return sorted([*self._events,*self._samples],key=lambda row:row['sequence'])

    def finish(self,exit_code):
        stream_error=None
        try:self.poll()
        except DiagnosticError as error:stream_error=error
        rows=self.view();last=rows[-1] if rows else None
        failure=next((r for r in rows if _failed(r)),None)
        if failure is not None:
            secondary=([dict(category=stream_error.category,message=str(stream_error),record=stream_error.record)]
                       if stream_error is not None else [])
            raise DiagnosticError('Diagnostic contains a failure: '+str(failure.get('message') or failure['kind']),failure,secondary=secondary)
        if stream_error is not None:raise stream_error
        if (type(exit_code) is not int or exit_code!=0 or self._pending or last is None or
                last['kind']!='process-completed' or last.get('status')!='normal' or
                not any(r['kind']=='application-disposed' for r in rows)):
            raise DiagnosticError('Complete diagnostic tail and normal own process exit are both required',
                                  last,category='diagnostic-stream')
        return dict(records=self.record_count,runId=self._run_id,
                    scope='Stream continuity and normal process tail only; raw remains authoritative')

    def wait(self,label,predicate,process,after_sequence=-1,generation=None,
             timeout=15,clock=time.monotonic,sleep=time.sleep,after_generation=None,check=None):
        if generation is not None and after_generation is not None:
            raise ValueError('Choose an exact generation or the next request boundary')
        deadline=clock()+timeout
        while clock()<deadline:
            stream_error=None
            try:self.poll()
            except DiagnosticError as error:stream_error=error
            current=[r for r in self.view() if r['sequence']>after_sequence]
            selected=generation
            if after_generation is not None:
                request=next((r for r in current if r.get('kind')=='load-request' and
                              type(r.get('generation')) is int and r['generation']>after_generation),None)
                if request is not None:selected=request['generation']
            def matches(row):
                if selected is not None:return row.get('generation')==selected
                if after_generation is not None:
                    return type(row.get('generation')) is int and row['generation']>after_generation
                return True
            rows=[r for r in current if matches(r)]
            failure=next((r for r in current if _failed(r) and ('generation' not in r or matches(r))),None)
            if failure is not None:
                reason=failure.get('message') or failure.get('errors') or failure.get('error') or failure['kind']
                secondary=([dict(category=stream_error.category,message=str(stream_error),record=stream_error.record)]
                           if stream_error is not None else [])
                raise DiagnosticError('Current request failed: '+str(reason),failure,secondary=secondary)
            if stream_error is not None:raise stream_error
            if process.poll() is not None:
                raise DiagnosticError('Own process exited during '+label,category='adapter-process')
            if check is not None:check()
            if predicate(rows):return rows
            sleep(.05)
        raise DiagnosticError('Missing current diagnostic event: '+label,category='input-timeout')
