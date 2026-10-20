package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import kotlin.math.*

/** First independent relation failure. A null result means this check, not the whole suite, passed. */
data class RelationFailure(val ruleId: String, val sample: Int, val expected: String, val actual: String)

object P001Relations {
    const val ARITHMETIC_ERROR = 1e-9
    private fun unit(sample: Observation) = sample.units.single()
    private fun near(a: Double, b: Double) = a.isFinite() && b.isFinite() && abs(a - b) <= ARITHMETIC_ERROR
    private fun issue(rule: String, index: Int, expected: Any?, actual: Any?) =
        RelationFailure(rule, index, expected.toString(), actual.toString())

    /** Independent circle/AABB distance check; touching is blocked. */
    fun circleRectangle(p: Position, radius: Double, left: Double, top: Double, right: Double, bottom: Double): RelationFailure? {
        val dx = p.x - p.x.coerceIn(left, right)
        val dy = p.y - p.y.coerceIn(top, bottom)
        return if (dx * dx + dy * dy <= radius * radius) issue("static-geometry", 0, "circle outside rectangle including tangency", p) else null
    }

    fun kinematics(samples: List<Observation>): RelationFailure? {
        for (i in 1 until samples.size) {
            val a = unit(samples[i - 1]); val z = unit(samples[i])
            val theta = Math.toRadians(z.heading)
            if (!near(z.position.x - a.position.x, sin(theta) * z.speed) ||
                !near(z.position.y - a.position.y, -cos(theta) * z.speed)) return issue("displacement", i, "heading components times speed, delta=1", z)
            val angle = ((z.heading - a.heading + 540.0) % 360.0) - 180.0
            if (abs(angle) > 4.1 + ARITHMETIC_ERROR) return issue("turn-limit", i, "abs(turn)<=4.1", angle)
            if (z.speed < 0 || z.speed > 1.1 + ARITHMETIC_ERROR || abs(z.angularVelocity) > 4.1 + ARITHMETIC_ERROR) return issue("speed-limit", i, "speed[0,1.1], abs(omega)<=4.1", z)
            if(z.speed-a.speed>.07+ARITHMETIC_ERROR || a.speed-z.speed>.17+ARITHMETIC_ERROR) return issue("acceleration-limit",i,"speed increases<=.07 or decreases<=.17",z.speed-a.speed)
            if(a.moveTarget==null && z.moveTarget==null) {
                if(z.heading!=a.heading || !near(z.angularVelocity,max(abs(a.angularVelocity)-.25,0.0)*sign(a.angularVelocity))) return issue("no-target-steering",i,"fixed heading, omega approaches zero by .25",z)
            } else if(abs(angle)>ARITHMETIC_ERROR && !near(angle,z.angularVelocity)) {
                // The only exception is overshoot limiting: turn is exactly old remaining diff and omega=0.
                val point=a.pathPoints.firstOrNull() ?: continue
                val desired=Math.toDegrees(atan2(point.x-a.position.x,-(point.y-a.position.y)))
                val diff=((desired-a.heading+540.0)%360.0)-180.0
                if(z.angularVelocity!=0.0 || !near(angle,diff)) return issue("turn-integration",i,"angle increment equals omega or exact overshoot clamp",z)
            }
        }
        return null
    }

    fun deceleration(samples: List<Observation>, fromIndex: Int): RelationFailure? {
        if (fromIndex < 1 || fromIndex >= samples.size) return issue("deceleration", fromIndex, "previous and following boundaries", "missing")
        for (i in fromIndex until samples.size) {
            val previous=unit(samples[i-1]); val current=unit(samples[i])
            if(current.moveTarget!=null || current.pathPoints.isNotEmpty() || !near(current.speed,max(previous.speed-.17,0.0)) || current.heading!=previous.heading) {
                return issue("deceleration",i,"no target/path, fixed heading, speed decrement .17",current)
            }
        }
        return null
    }

    internal fun evaluate(input: com.rustedwarfare.reforge.testing.LoadedScenario, caseId: String, samples: List<Observation>, naturalTurn: Boolean = false): RelationFailure? {
        if(samples.any { it.units.size!=1 || it.units.single().ownerPlayerId!="p1" || it.units.single().unitId!="u1" }) return issue("unit-identity",0,"one owned u1",samples.firstOrNull()?.units)
        val eventKinds=samples.flatMap { it.events }.map { it.kind }
        val expectedEvents=when {
            caseId=="M01" || caseId.startsWith("M04")->listOf("movement-started","movement-arrived")
            caseId=="M03"->listOf("movement-started","movement-cancelled")
            caseId=="M05"->listOf("movement-started","movement-cancelled","path-unreachable")
            caseId=="M07"->emptyList()
            else->listOf("movement-started")
        }
        if(eventKinds!=expectedEvents) return issue("movement-event-sequence",0,expectedEvents,eventKinds)
        kinematics(samples)?.let { return it }
        for((index,sample) in samples.withIndex()) {
            val u=unit(sample)
            if(sample.events.any { it.kind=="movement-blocked" } || u.movementState==MovementState.BLOCKED) return issue("no-blocking",index,"no blocked event/state",u)
            for(row in 0 until input.core.map.heightTiles) for(col in 0 until input.core.map.widthTiles) {
                if(input.core.map.blocking.isBlocked(col,row)) circleRectangle(u.position,11.0,col*20.0,row*20.0,(col+1)*20.0,(row+1)*20.0)?.let { return it.copy(sample=index) }
            }
            if(u.position.x<=11.0 || u.position.y<=11.0 || u.position.x>=input.core.map.widthTiles*20.0-11.0 || u.position.y>=input.core.map.heightTiles*20.0-11.0) return issue("map-boundary",index,"circle within map",u.position)
            // Without a new command, intermediate consumption removes exactly one old point, never two.
            if(index>1 && input.actions.none { it["atStep"]?.toString()==sample.stepIndex.toString() }) {
                val before=unit(samples[index-1])
                if(before.pathPoints.size>1) {
                    val point=before.pathPoints.first(); val inside=hypot(before.position.x-point.x,before.position.y-point.y)<16.0
                    val expected=if(inside) before.pathPoints.drop(1) else before.pathPoints
                    if(u.pathPoints!=expected) return issue("path-point-capture",index,expected,u.pathPoints)
                }
            }
        }
        if(caseId=="M01" || caseId.startsWith("M04")) {
            val action=input.actions.first(); val t=action["target"]!!.toString()
            val target=com.rustedwarfare.reforge.testing.position(com.rustedwarfare.reforge.testing.json.parseToJsonElement(t) as kotlinx.serialization.json.JsonObject)
            return arrival(samples,target)
        }
        if(caseId=="M02") {
            val states=samples.drop(1).map(::unit)
            val omega = if (naturalTurn) -.25 else .25
            val heading = if (naturalTurn) 305.5 else 54.5
            if(states.first().angularVelocity!=omega || !near(states.last().heading,heading) || states.any { it.position!=Position(100.0,100.0) || it.speed!=0.0 }) return issue("turn-prefix-literals",samples.lastIndex,"omega0=$omega, heading20=$heading; no displacement",states.last())
            if(naturalTurn && states.first().pathPoints!=listOf(Position(100.0,140.0))) return issue("natural-direct-target",1,"single exact target",states.first().pathPoints)
            for(i in 1 until samples.size) {
                val a=unit(samples[i-1]);val z=unit(samples[i])
                if(abs(z.angularVelocity-a.angularVelocity)>.25+ARITHMETIC_ERROR) return issue("turn-prefix-acceleration",i,"omega difference<=.25 (no overshoot in this prefix)",z.angularVelocity)
            }
        }
        if(caseId=="M06") {
            val later=samples.drop(2).map(::unit)
            if(later.any { it.moveTarget!=Position(250.0,110.0) || it.movementState!=MovementState.MOVING } || later.last().position.x<=later.first().position.x) return issue("authorized-command-continues",samples.lastIndex,"original target retained and movement continues",later.last())
        }
        if(caseId=="M03" || caseId=="M05") {
            val start=if(caseId=="M03") 17 else 2
            deceleration(samples,start)?.let { return it }
            if(samples.drop(start).any { it.events.any { e->e.kind=="movement-arrived" || e.kind=="movement-started" } }) return issue("cancelled-command-not-resumed",start,"no arrived/started after cancellation",samples[start].events)
            val events=samples[start].events.map { it.kind }
            val expected=if(caseId=="M03") listOf("movement-cancelled") else listOf("movement-cancelled","path-unreachable")
            if(events!=expected) return issue("cancellation-event-order",start,expected,events)
            if(caseId=="M03") {
                val literal=listOf(.93,.76,.59,.42,.25,.08,0.0)
                literal.forEachIndexed { i,v -> if(!near(unit(samples[start+i]).speed,v)) return issue("stop-speed-literal",start+i,v,unit(samples[start+i]).speed) }
                if(!near(unit(samples.last()).position.x-unit(samples[start-1]).position.x,3.03)) return issue("stop-distance-literal",samples.lastIndex,3.03,unit(samples.last()).position.x-unit(samples[start-1]).position.x)
            }
            return idleTail(samples)
        }
        return null
    }

    fun idleTail(samples: List<Observation>): RelationFailure? {
        if (samples.size < 20) return issue("idle-tail", samples.lastIndex, "20 idle samples", samples.size)
        val tail = samples.takeLast(20).map(::unit)
        if (tail.any { it != tail.first() || it.movementState != MovementState.IDLE ||
                it.speed != 0.0 || it.angularVelocity != 0.0 || it.moveTarget != null || it.pathPoints.isNotEmpty() }) {
            return issue("idle-tail", samples.lastIndex, "20 exactly identical idle unit DTOs", tail)
        }
        return null
    }

    fun arrival(samples: List<Observation>, target: Position): RelationFailure? {
        val arrivals = samples.indices.filter { i -> samples[i].events.any { it.kind == "movement-arrived" } }
        if (arrivals.size != 1 || samples.sumOf { it.events.count { e -> e.kind == "movement-arrived" } } != 1) {
            return issue("arrival-count", samples.lastIndex, "one arrival", arrivals.size)
        }
        val index = arrivals.single()
        if (index == 0) return issue("arrival-radius", index, "previous boundary", "missing")
        val before = unit(samples[index - 1])
        if (hypot(before.position.x - target.x, before.position.y - target.y) >= 4.0 ||
            before.pathPoints.size > 1 || before.moveTarget != target) {
            return issue("arrival-radius", index, "previous distance strictly <4 and final target", before)
        }
        for (i in index until samples.size) {
            val current = unit(samples[i])
            val previous = unit(samples[i - 1])
            if (current.moveTarget != null || current.pathPoints.isNotEmpty()) return issue("arrival-clears-target", i, "empty target/path", current)
            if (!near(current.speed, max(previous.speed - .17, 0.0))) return issue("arrival-deceleration", i, "previous speed minus .17 clamped at 0", current.speed)
            if (i > index && current.heading != previous.heading) return issue("arrival-heading", i, previous.heading, current.heading)
        }
        return idleTail(samples)
    }
}
