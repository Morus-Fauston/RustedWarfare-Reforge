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
            listOf(.93,.76,.59,.42,.25,.08,0.0).mapIndexed { i, v -> sample(i.toLong(),100.0,v, if(v == 0.0) MovementState.IDLE else MovementState.DECELERATING) }
        assertNull(P001Relations.deceleration(samples, 1))
        val broken=samples.toMutableList(); broken[2]=sample(1,100.0,.75,MovementState.DECELERATING)
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
        // Independent worked example: previous distance 3 (<4), then decelerate .17 per step.
        val samples = listOf(sample(-1, 117.0, .17, MovementState.MOVING, target = Position(120.0,110.0)),
            sample(0, 117.0, 0.0, MovementState.IDLE, arrived = true)) +
            (1L..20L).map { sample(it, 117.0, 0.0, MovementState.IDLE) }
        assertNull(P001Relations.arrival(samples, Position(120.0,110.0)))
        val drift = samples.dropLast(1) + sample(20, 117.0000000001, 0.0, MovementState.IDLE)
        assertEquals("idle-tail", P001Relations.arrival(drift, Position(120.0,110.0))?.ruleId)
        val boundary = listOf(sample(-1, 116.0, .17, MovementState.MOVING, target = Position(120.0,110.0))) + samples.drop(1)
        assertEquals("arrival-radius", P001Relations.arrival(boundary, Position(120.0,110.0))?.ruleId)
    }
}
