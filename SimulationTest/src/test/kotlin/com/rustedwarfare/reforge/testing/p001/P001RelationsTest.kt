package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class P001RelationsTest {
    private fun sample(step: Long, x: Double, speed: Double, state: MovementState,
                       arrived: Boolean = false, target: Position? = null): Observation = Observation(
        "s$step", "world:1", "literal", step, step + 1.0,
        listOf(UnitObservation("u1", "p1", "tank", Position(x, 110.0), 90.0, speed, 0.0,
            target, emptyList(), state)), if (arrived) listOf(Event("movement-arrived", "u1")) else emptyList(), emptyList())

    @Test fun `literal stop sequence rejects a speed change even with identical endpoint`() {
        val samples = listOf(sample(-1, 100.0, 1.1, MovementState.MOVING)) +
            listOf(.913,.726,.539,.352,.165,0.0).mapIndexed { i, v -> sample(i.toLong(),100.0,v, if(v == 0.0) MovementState.IDLE else MovementState.DECELERATING) }
        assertNull(P001Relations.deceleration(samples, 1))
        val broken=samples.toMutableList(); broken[2]=sample(1,100.0,.76,MovementState.DECELERATING)
        assertEquals("deceleration",P001Relations.deceleration(broken,1)?.ruleId)
    }

    @Test fun `literal collision and displacement controls reject tangency and wrong axes`() {
        assertNull(P001Relations.circleRectangle(Position(8.0, 10.0), 1.0, 10.0, 0.0, 20.0, 20.0))
        assertNotNull(P001Relations.circleRectangle(Position(9.0, 10.0), 1.0, 10.0, 0.0, 20.0, 20.0))
        val good = listOf(sample(-1, 100.0, 0.0, MovementState.IDLE), sample(0, 100.07, .07, MovementState.MOVING))
        assertNull(P001Relations.kinematics(good))
        val wrong = listOf(good.first(), sample(0, 100.08, .07, MovementState.MOVING))
        assertEquals("displacement", P001Relations.kinematics(wrong)?.ruleId)
    }

    @Test fun `literal arrival checks use the previous boundary and require idle tail`() {
        // Independent worked example: previous distance 3 (<7), then proportional deceleration .187.
        val samples = listOf(sample(-1, 117.0, .17, MovementState.MOVING, target = Position(120.0,110.0)),
            sample(0, 117.0, 0.0, MovementState.IDLE, arrived = true)) +
            (1L..20L).map { sample(it, 117.0, 0.0, MovementState.IDLE) }
        assertNull(P001Relations.arrival(samples, Position(120.0,110.0)))
        val drift = samples.dropLast(1) + sample(20, 117.0000000001, 0.0, MovementState.IDLE)
        assertEquals("idle-tail", P001Relations.arrival(drift, Position(120.0,110.0))?.ruleId)
        val boundary = listOf(sample(-1, 113.0, .17, MovementState.MOVING, target = Position(120.0,110.0))) + samples.drop(1)
        assertEquals("arrival-radius", P001Relations.arrival(boundary, Position(120.0,110.0))?.ruleId)
    }

    @Test fun `normal clear preserves heading and retained steering on the clearing step itself`() {
        val target=Position(120.0,110.0)
        fun retained(row: Observation, heading: Double = 90.0, omega: Double = 2.25) =
            Observation(row.sampleId,row.worldId,row.scenarioId,row.stepIndex,row.simulationTime,
                listOf(row.units.single().copy(heading=heading,angularVelocity=omega)),row.events,row.actionResults)
        val rows=listOf(retained(sample(-1,117.0,.17,MovementState.MOVING,target=target))) +
            (0L..20L).map { retained(sample(it,117.0,0.0,MovementState.IDLE,arrived=it==0L)) }
        assertNull(P001Relations.arrival(rows,target))
        // All post-clear rows agree, so an oracle that starts one step late misses the defect.
        val turned=listOf(rows.first())+rows.drop(1).map { retained(it,heading=91.0) }
        assertEquals("arrival-heading",P001Relations.arrival(turned,target)?.ruleId)
        for (bad in listOf(0.0,2.0,Double.NaN)) {
            val changed=listOf(rows.first())+rows.drop(1).map { retained(it,omega=bad) }
            assertEquals("arrival-steering",P001Relations.arrival(changed,target)?.ruleId)
        }
    }

    @Test fun `cancellation deceleration preserves nonzero steering state throughout`() {
        fun retained(row: Observation, omega: Double = 2.25) =
            Observation(row.sampleId,row.worldId,row.scenarioId,row.stepIndex,row.simulationTime,
                listOf(row.units.single().copy(angularVelocity=omega)),row.events,row.actionResults)
        val rows=listOf(retained(sample(-1,100.0,1.1,MovementState.MOVING))) +
            listOf(.913,.726,.539,.352,.165,0.0).mapIndexed { i,v ->
                retained(sample(i.toLong(),100.0,v,if(v==0.0) MovementState.IDLE else MovementState.DECELERATING))
            }
        assertNull(P001Relations.deceleration(rows,1))
        for (bad in listOf(0.0,2.0,Double.NaN)) {
            val changed=rows.toMutableList();changed[1]=retained(changed[1],bad)
            assertEquals("deceleration",P001Relations.deceleration(changed,1)?.ruleId)
        }
    }
}
