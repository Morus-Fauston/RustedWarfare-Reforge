package com.rustedwarfare.reforge.testing.cross

import kotlin.math.hypot

/** Event predicates over already-qualified complete raw calls. Clear causes are supplied explicitly. */
internal object MotionV2Events {
    data class Call(val entryQ: Double, val exitQ: Double, val phase: Long, val input: Long,
                    val beforeX: Double, val beforeY: Double, val x: Double, val y: Double,
                    val targetBefore: Boolean, val targetAfter: Boolean)
    data class Event(val interval: MotionV2Math.Interval, val index: Int, val confirmedIndex: Int = index)
    data class Tail(val event: Event, val endIndex: Int?, val fixedX: Double, val fixedY: Double,
                    val eighteenQWithinBudget: Boolean, val eighteenQStable: Boolean?,
                    val fullRawFutureStable: Boolean, val clearLifecycleIntact: Boolean)
    data class Deceleration(val event: Event?, val firstCandidate: Event?, val unavailable: String?)
    private fun validate(calls: List<Call>, input: Long) {
        require(input >= 0 && calls.isNotEmpty() && calls.first().entryQ == 0.0)
        require(calls.all { c->listOf(c.entryQ,c.exitQ,c.beforeX,c.beforeY,c.x,c.y).all { it.isFinite() } &&
            c.entryQ >= 0 && c.exitQ >= c.entryQ && c.phase >= 0 && c.input >= -1 &&
            hypot(c.x-c.beforeX,c.y-c.beforeY).isFinite() })
        require(calls.zipWithNext().all { (a,b)->a.exitQ == b.entryQ && a.phase <= b.phase && a.input <= b.input &&
            a.x.toBits() == b.beforeX.toBits() && a.y.toBits() == b.beforeY.toBits() }) {
            "Missing/reordered calls or unexplained between-call displacement"
        }
    }
    private fun interval(c: Call)=MotionV2Math.Interval(c.entryQ,c.exitQ)
    private fun speed(c: Call)=hypot(c.x-c.beforeX,c.y-c.beforeY)/(c.exitQ-c.entryQ)
    fun firstMove(calls: List<Call>, input: Long): Event? {
        validate(calls,input)
        val index=calls.indexOfFirst { it.input==input && it.exitQ>it.entryQ && hypot(it.x-it.beforeX,it.y-it.beforeY)>.001 }
        return if(index<0)null else Event(interval(calls[index]),index)
    }
    fun stableTail(calls: List<Call>, input: Long, clear: Event, maximumQ: Double=600.0): Tail? {
        validate(calls,input);require(maximumQ.isFinite() && maximumQ >= 0 && maximumQ <= 600)
        require(clear.index in calls.indices && calls[clear.index].input==input && !calls[clear.index].targetAfter)
        val clearCall=calls[clear.index]
        require(clear.interval.low.isFinite() && clear.interval.high.isFinite() &&
            clear.interval.low>=clearCall.entryQ && clear.interval.low<=clear.interval.high && clear.interval.high<=clearCall.exitQ)
        for(i in clear.index until calls.size) {
            val c=calls[i]
            if(c.exitQ>maximumQ || c.exitQ<clear.interval.high || c.input!=input || c.targetAfter)continue
            val confirm=(i until calls.size).firstOrNull { calls[it].exitQ>=c.exitQ+6 && calls[it].exitQ<=maximumQ } ?: continue
            fun stable(range: IntRange)=range.all { j->val z=calls[j]
                z.input==input && !z.targetAfter && hypot(z.x-c.x,z.y-c.y)<=.001 }
            if(stable(i..confirm)) {
                // The earliest six-q event is fixed before examining its required
                // tail. Drift at q+7 must not silently select a later anchor.
                val end=(i until calls.size).firstOrNull { calls[it].exitQ>=c.exitQ+18 }
                return Tail(Event(interval(c),i,confirm),end,c.x,c.y,
                    end!=null && calls[end].exitQ<=maximumQ,end?.let { stable(i..it) },stable(i..calls.lastIndex),
                    calls.drop(clear.index+1).takeWhile { it.input==input }.all { !it.targetBefore && !it.targetAfter })
            }
        }
        return null
    }
    fun deceleration(calls: List<Call>, input: Long): Deceleration {
        validate(calls,input)
        var plateauStart:Double?=null;var plateauEnd:Double?=null;var lastPhase:Long?=null;var lastInput:Long?=null
        for((i,c)in calls.withIndex()) {
            if(lastPhase!=c.phase || lastInput!=c.input || c.input!=input || !c.targetBefore) {
                plateauStart=null;plateauEnd=null
            }
            lastPhase=c.phase;lastInput=c.input
            if(c.input!=input || !c.targetBefore || c.exitQ==c.entryQ)continue
            val value=speed(c)
            if(value>=1.045) {
                if(plateauStart==null)plateauStart=c.entryQ
                plateauEnd=c.exitQ;continue
            }
            val complete=plateauStart!=null && plateauEnd!=null && plateauEnd-plateauStart>=6
            if(value<1.025 && complete) {
                val candidate=Event(interval(c),i)
                val next=(i+1 until calls.size).firstOrNull { calls[it].exitQ>calls[it].entryQ }
                if(next!=null) {
                    val n=calls[next]
                    if(n.input==input && n.phase==c.phase && speed(n)<1.025)
                        return Deceleration(Event(interval(c),i,next),candidate,null)
                }
                return Deceleration(null,candidate,"first-low-not-confirmed")
            }
            if(!complete) { plateauStart=null;plateauEnd=null }
        }
        return Deceleration(null,null,"no-qualified-plateau-transition")
    }
}
