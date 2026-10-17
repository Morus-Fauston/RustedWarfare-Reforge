package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

object P001Lifecycle {
    fun unchanged(before: Observation, after: Observation): RelationFailure? =
        if(before.units == after.units) null else RelationFailure("ownership-immediate",0,"unchanged full unit DTO",after.units.toString())
    fun fresh(original: Observation, reopened: Observation): RelationFailure? =
        if(original.worldId != reopened.worldId && original.units == reopened.units && reopened.simulationTime == 0.0 &&
            reopened.stepIndex == 0L && reopened.events.isEmpty() && reopened.actionResults.isEmpty()) null
        else RelationFailure("fresh-world",0,"new world with exact original units, zero time and no pending results/events",reopened.worldId+reopened.units)

    internal data class Capture(val observations: List<Observation>, val rows: List<JsonObject>, val failure: RelationFailure?)
    internal fun capture(input: LoadedScenario, caseId: String): Capture {
        val context=(SimulationContext.initialize(input.core) as? InitResult.Accepted)?.context ?: error("Core initialization rejected")
        val samples=mutableListOf<Observation>()
        val rows=mutableListOf<JsonObject>()
        var failure: RelationFailure?=null
        fun fail(rule:String, wanted:Any?, actual:Any?) { if(failure==null) failure=RelationFailure(rule,samples.lastIndex,wanted.toString(),actual.toString()) }
        fun observe(phase:String): Observation=context.observe().also { rows+=observationRow(phase,it) }
        fun action(value:JsonObject): Action = when(value.text("kind")) {
            "move"->MoveAction(value.id("actionId"),value.id("actor"),value.id("unitId"),position(value.objectAt("target")),context.world)
            "stop"->StopAction(value.id("actionId"),value.id("actor"),value.id("unitId"),context.world)
            else->error("Unsupported lifecycle action")
        }
        rows+=record("p001-sequence-start","caseId" to s(caseId))
        val initial=observe("initial"); samples+=initial
        try {
            if(caseId=="M07") {
                val first=context.submit(action(input.actions.single()))
                rows+=record("p001-call","call" to s("submit-first"),"result" to first.json())
                val firstStep=context.advance(Step(0,1.0,"sim-tick")); rows+=observationRow("first-generation-step0",firstStep)
                val oldWorld=context.world
                val queued=MoveAction("M07.old-queued","p1","u1",Position(150.0,110.0),oldWorld,atStep=2)
                val queuedResult=context.submit(queued)
                rows+=record("p001-call","call" to s("queue-old-world"),"atStep" to n(2),"result" to queuedResult.json())
                if(queuedResult !is ActionResult.Accepted || queuedResult.queuedForStep!=2L) fail("old-queue","accepted atStep=2",queuedResult)
                rows+=record("p001-call","call" to s("close"),"result" to s(context.close().toString()))
                val reopened=context.reopen(input.core)
                if(reopened !is InitResult.Accepted) error("Reopen rejected: $reopened")
                rows+=record("p001-call","call" to s("reopen"),"accepted" to b(true),"sameWorldCapability" to b(oldWorld===context.world))
                if(oldWorld===context.world) fail("world-capability","different capability","same instance")
                val reopenedSample=observe("reopened-initial")
                failure=failure?:fresh(initial,reopenedSample)
                samples.clear();samples+=reopenedSample
                val stale=context.submit(queued)
                rows+=record("p001-call","call" to s("submit-old-handle"),"result" to stale.json())
                if(stale !is ActionResult.Rejected || stale.error.code!="stale-world") fail("stale-world","Rejected(stale-world)",stale)
                for(step in input.steps) {
                    val sample=context.advance(step);samples+=sample;rows+=observationRow("reopened-step",sample)
                    if(sample.units!=reopenedSample.units || sample.events.isNotEmpty()) fail("old-world-isolation","exact fresh units and no movement events",sample.units.toString()+sample.events)
                    val expectedActions=if(step.stepIndex==0L) listOf(stale) else emptyList()
                    if(sample.actionResults!=expectedActions) fail("old-queue-isolation",expectedActions,sample.actionResults)
                }
            } else {
                for(step in input.steps) {
                    for(value in input.actions.filter { it.integer("atStep")==step.stepIndex }) {
                        val before=observe("before-${value.id("actionId")}")
                        val result=context.submit(action(value))
                        rows+=record("p001-call","call" to s("submit"),"action" to value,"result" to result.json())
                        val after=observe("after-${value.id("actionId")}")
                        if(caseId=="M06" && value.text("actor")=="p2") {
                            failure=failure?:unchanged(before,after)
                            if(result !is ActionResult.Rejected || result.error.code!="not-owner") fail("ownership-result","Rejected(not-owner)",result)
                        } else if(result !is ActionResult.Accepted) fail("owner-command","accepted",result)
                        if(caseId=="M05" && step.stepIndex==1L) {
                            val a=before.units.single(); val z=after.units.single()
                            if(a.speed<=0.0 || a.moveTarget!=Position(250.0,110.0) || a.pathPoints.isEmpty() || a.movementState!=MovementState.MOVING) fail("unreachable-precondition","old move to (250,110) active with nonzero speed",a)
                            if(z.moveTarget!=null || z.pathPoints.isNotEmpty() || z.speed!=a.speed || z.angularVelocity!=a.angularVelocity) fail("unreachable-immediate","clear target/path, preserve speed/omega",z)
                            if(result !is ActionResult.Accepted || result.events.map { it.kind }!=listOf("movement-cancelled","path-unreachable")) fail("unreachable-events","cancelled then unreachable",result)
                        }
                    }
                    val sample=context.advance(step); samples+=sample; rows+=observationRow("after-advance",sample)
                }
            }
            rows+=record("p001-call","call" to s("final-close"),"result" to s(context.close().toString()))
            rows+=record("p001-sequence-end","caseId" to s(caseId),"complete" to b(true),"observedSteps" to n(input.steps.size),"firstGenerationSteps" to n(if(caseId=="M07") 1 else 0))
        } finally { context.close() }
        return Capture(samples,rows,failure)
    }
}
