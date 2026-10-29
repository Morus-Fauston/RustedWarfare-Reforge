package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

/** Read-only typed fact serialization. No protocol admission or rule result. */
internal object MotionV2CoreTraceJson {
    fun error(e:Exception?):JsonElement=e?.let { obj("type" to s(it.javaClass.name),"message" to it.message?.let(::s),
        "suppressed" to array(it.suppressed.map { x->obj("type" to s(x.javaClass.name),"message" to x.message?.let(::s)) })) } ?: JsonNull
    private fun action(a:Action?):JsonElement=a?.let { obj("actionId" to s(it.actionId),"actorPlayerId" to s(it.actorPlayerId),
        "unitId" to s(it.unitId),"worldId" to s(it.world.id),"atStep" to it.atStep?.let(::n),
        "kind" to s(when(it) { is MoveAction->"move";is StopAction->"stop";is SelectAction->"select"
            is AttackAction->error("Movement v2 trace does not support AttackAction")
            is PatrolAction->error("Movement v2 trace does not support PatrolAction")
            is BuildAction,is ResumeConstructionAction,is RepairBuildingAction,is ReclaimBuildingAction->
                error("Movement v2 trace does not support construction actions")
            is ProduceAction,is CancelProductionAction,is UpgradeAction,is CancelUpgradeAction->error("Movement v2 trace does not support economy actions") }),
        "target" to (it as? MoveAction)?.target?.json()) } ?: JsonNull
    fun motion(m:MotionStepSample):JsonObject=with(m) { obj(
        "worldId" to s(worldId),"stepIndex" to n(stepIndex),"unitId" to s(unitId),"ownerPlayerId" to s(ownerPlayerId),"typeId" to s(typeId),
        "timeBefore" to n(timeBefore),"delta" to n(delta),"originMoveId" to originMoveId?.let(::s),"positionBefore" to positionBefore.json(),
        "headingBefore" to n(headingBefore),"speedBefore" to n(speedBefore),"angularVelocityBefore" to n(angularVelocityBefore),
        "commandTargetBefore" to commandTargetBefore?.json(),"normalCompletion" to b(normalCompletion),"steeringInvoked" to b(steeringInvoked),
        "steeringAim" to steeringAim?.json(),"finalLocalPoint" to finalLocalPoint?.let(::b),"steeringDifference" to steeringDifference?.let(::n),
        "targetFraction" to n(targetFraction),"integratedHeading" to n(integratedHeading),"integratedSpeed" to n(integratedSpeed),
        "candidate" to candidate.json(),"terrainResolved" to terrainResolved?.json(),"terrainDisposition" to s(terrainDisposition.name),
        "finalPosition" to finalPosition.json(),"finalHeading" to n(finalHeading),"finalSpeed" to n(finalSpeed),"finalAngularVelocity" to n(finalAngularVelocity),
        "localProgressBefore" to n(localProgressBefore),"localProgressAfter" to n(localProgressAfter),
        "correctionTimeBefore" to n(correctionTimeBefore),"correctionTimeAfter" to n(correctionTimeAfter)) }
    fun trace(t:MotionV2CoreExecution.Trace):JsonObject=obj(
        "initialization" to t.initialization?.let { obj("status" to s(if(it.error==null)"accepted"else"rejected"),"worldId" to it.worldId?.let(::s),
            "error" to it.error?.let { x->obj("code" to s(x.code),"message" to s(x.message)) }) },
        "initial" to t.initial?.json(),"operations" to array(t.operations.map { op->obj(
            "ordinal" to n(op.ordinal),"kind" to s(op.kind),"q" to n(op.q),"attempted" to b(op.attempted),
            "scheduled" to op.scheduled?.let { obj("sequence" to n(it.sequence),"q" to n(it.q),"original" to it.original) },
            "step" to op.step?.let { obj("stepIndex" to n(it.stepIndex),"delta" to n(it.delta),"deltaUnit" to s(it.deltaUnit)) },
            "issuedAction" to action(op.issuedAction),"before" to op.before?.json(),"after" to op.after?.json(),"result" to op.result?.json(),
            "motion" to op.motion?.let { array(it.map(::motion)) },"error" to error(op.error)) }),
        "firstError" to t.firstError?.let { obj("stage" to s(it.stage),"error" to error(it.error)) },
        "closeResult" to t.closeResult?.let { s(when(it) { CloseResult.Closed->"Closed";CloseResult.AlreadyClosed->"AlreadyClosed" }) },
        "closeError" to error(t.closeError),"inputPostErrors" to array(t.inputPostErrors.map(::error)),
        "runtimePostErrors" to array(t.runtimePostErrors.map(::error)))
}
