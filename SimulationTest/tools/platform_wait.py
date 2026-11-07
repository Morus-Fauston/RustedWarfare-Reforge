"""Bounded event waits for the real Windows backend; never supplies game events."""

class WaitFailure(ValueError):
    def __init__(self, reason):
        self.reason=reason
        super().__init__('Waiting segment failed: '+reason)


class WaitSegment:
    def __init__(self, *, generation, world, after_sequence, initial_step, minimum_steps,
                 maximum_steps, wall_seconds, stall_seconds, started, event_kind=None,
                 unit_type=None, unit_id=None):
        if (type(generation) is not int or generation<1 or not world or
                not 0<=minimum_steps<=maximum_steps or not 0<stall_seconds<=wall_seconds):
            raise ValueError('Invalid bounded waiting segment')
        self.generation=generation;self.world=world;self.after=after_sequence
        self.initial=initial_step;self.minimum=minimum_steps;self.maximum=maximum_steps
        self.wall=wall_seconds;self.stall=stall_seconds;self.started=started
        self.event_kind=event_kind;self.unit_type=unit_type;self.unit_id=unit_id
        self.step=initial_step;self.progress_at=started;self.event=None;self.failure=None

    @staticmethod
    def speed_ack(records,generation,after_sequence,rate):
        return any(r.get('kind')=='simulation-control' and r.get('sequence',-1)>after_sequence and
                   r.get('generation')==generation and r.get('rate')==rate and r.get('paused') is False
                   for r in records)

    def fail(self,reason):
        if self.failure is None:self.failure=WaitFailure(reason)
        raise self.failure

    def observe(self,records,now):
        if self.failure:raise self.failure
        if now-self.started>self.wall:self.fail('wall-timeout')
        for row in records:
            if row.get('sequence',-1)<=self.after or row.get('kind')!='observation':continue
            sample=row['sample']
            if row.get('generation')!=self.generation or sample.get('worldId')!=self.world:self.fail('wrong-world')
            self.after=row['sequence']
            current=sample['stepIndex']
            if current<self.step:self.fail('step-regressed')
            if current>self.step:self.progress_at=now;self.step=current
            if self.step-self.initial>self.maximum:self.fail('simulation-limit')
            for event in sample.get('events',[]) if row.get('phase')=='step' and current>self.initial else []:
                if event.get('kind')!=self.event_kind:continue
                unit=next((u for u in sample.get('units',[]) if u['unitId']==event.get('unitId')),None)
                if unit and (self.unit_id is None or unit['unitId']==self.unit_id) and (self.unit_type is None or unit['typeId']==self.unit_type):
                    self.event=dict(sequence=row['sequence'],stepIndex=current,event=event)
        if now-self.progress_at>self.stall:self.fail('stalled')
        return self.step-self.initial>=self.minimum and (self.event_kind is None or self.event is not None)

    def receipt(self,now):
        return dict(generation=self.generation,worldId=self.world,initialStep=self.initial,finalStep=self.step,
                    simulationSteps=self.step-self.initial,wallSeconds=now-self.started,event=self.event,
                    maximumSimulationSteps=self.maximum,maximumWallSeconds=self.wall,
                    firstFailure=self.failure.reason if self.failure else None)
