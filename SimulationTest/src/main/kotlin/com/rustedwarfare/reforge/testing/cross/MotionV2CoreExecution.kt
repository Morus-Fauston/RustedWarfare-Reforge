package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.CoreFailure
import kotlinx.serialization.json.JsonObject

/** Actual operations only. Contains no approval, source admission, writer or compatibility judgment. */
internal object MotionV2CoreExecution {
    data class Scheduled(val sequence:Int,val q:Int,val original:JsonObject,val create:(WorldHandle)->Action)
    class Prepared(val scenario:Scenario,val horizon:Int,commands:List<Scheduled>) {
        val commands:List<Scheduled> = java.util.Collections.unmodifiableList(ArrayList(commands))
        init {
            require(horizon in 18..604)
            require(this.commands.withIndex().all { (i,c)->c.sequence==i && c.q in 0..horizon })
            require(this.commands.zipWithNext().all { (a,b)->a.q<=b.q })
        }
    }
    data class Initialization(val worldId:String?,val error:SimulationError?)
    data class Failure(val stage:String,val error:Exception)
    data class Operation(val ordinal:Int,val kind:String,val q:Int,val scheduled:Scheduled?,val step:Step?,
                         val attempted:Boolean,val issuedAction:Action?,val before:Observation?,val after:Observation?,
                         val result:ActionResult?,val motion:List<MotionStepSample>?,val error:Exception?)
    data class Trace(val initialization:Initialization?,val initial:Observation?,val operations:List<Operation>,
                     val firstError:Failure?,val closeResult:CloseResult?,val closeError:Exception?,
                     val inputPostErrors:List<Exception>,val runtimePostErrors:List<Exception>,val completed:Boolean)

    fun run(prepared:Prepared,inputGuard:(String)->Unit={},runtimeGuard:(String)->Unit={}):Trace {
        var context:SimulationContext?=null;var initialization:Initialization?=null;var initial:Observation?=null
        var stage="initialize";var first:Failure?=null;var closed:CloseResult?=null;var closeError:Exception?=null
        var reachedEnd=false
        val operations=mutableListOf<Operation>();val inputPost=mutableListOf<Exception>();val runtimePost=mutableListOf<Exception>()
        fun failed(at:String,error:Exception) {
            val primary=first
            if(primary==null)first=Failure(at,error) else if(primary.error!==error)primary.error.addSuppressed(error)
        }
        fun guards(at:String) { inputGuard(at);runtimeGuard(at) }
        try {
            guards(stage)
            when(val initialized=SimulationContext.initialize(prepared.scenario)) {
                is InitResult.Rejected -> {
                    initialization=Initialization(null,initialized.error)
                    failed(stage,CoreFailure("Initialization rejected: ${initialized.error.code}: ${initialized.error.message}"))
                }
                is InitResult.Accepted -> {
                    val active=initialized.context;context=active
                    initialization=Initialization(active.world.id,null)
                    stage="initial";guards(stage)
                    active.enableMotionDiagnostics(active.world)
                    initial=active.observe()
                    fun operation(kind:String,q:Int,scheduled:Scheduled?=null) {
                        stage=if(kind=="advance")"advance:$q"else"submit:${checkNotNull(scheduled).sequence}"
                        val step=if(kind=="advance")Step(q.toLong()-1,1.0,"sim-tick")else null
                        var attempted=false;var issued:Action?=null;var before:Observation?=null;var after:Observation?=null
                        var returned:ActionResult?=null;var motion:List<MotionStepSample>?=null;var error:Exception?=null
                        try {
                            guards(stage);before=active.observe()
                            if(step!=null) {
                                attempted=true;after=active.advance(step)
                                // Keep the entire actual list before any downstream pairing/count check.
                                motion=active.drainMotionDiagnostics(active.world).toList()
                            } else {
                                issued=checkNotNull(scheduled).create(active.world)
                                attempted=true;returned=active.submit(issued)
                                after=active.observe()
                            }
                        } catch(e:Exception) { error=e;throw e }
                        finally {
                            operations+=Operation(operations.size,kind,q,scheduled,step,attempted,issued,before,after,returned,motion,error)
                        }
                    }
                    for(q in 0..prepared.horizon) {
                        if(q>0)operation("advance",q)
                        for(command in prepared.commands.filter { it.q==q })operation("submit",q,command)
                    }
                    reachedEnd=true
                }
            }
        } catch(e:Exception) { failed(stage,e) }
        finally {
            try { closed=context?.close() } catch(e:Exception) { closeError=e;failed("close",e) }
            try { inputGuard("finally") } catch(e:Exception) { inputPost+=e;failed("input-finally",e) }
            try { runtimeGuard("finally") } catch(e:Exception) { runtimePost+=e;failed("runtime-finally",e) }
        }
        return Trace(initialization,initial,operations.toList(),first,closed,closeError,inputPost.toList(),runtimePost.toList(),reachedEnd&&first==null)
    }
}
