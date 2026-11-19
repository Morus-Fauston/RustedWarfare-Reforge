package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrafficSeparationTest {
    private data class Work(val step: Long, val cells: Long, val bodies: Long, val truncated: Long)

    private fun drive(parked: Int): List<Work> {
        val initial = TestContentFactory.openScenario()
        val base = initial.unitDefinitions.getValue("tank")
        val car = TestContentFactory.unitWith(base, radius = 1.0, movement = base.movement.copy(
            moveSpeed = 1.0, moveDecelerationSpeed = 0.000001))
        val map = MapDefinition(40, 24, 20, 20, emptyList(), IntArray(960), BlockingGrid(40, 24, BooleanArray(960)))
        val traffic = listOf(UnitSpawn("a", "p1", "tank", Position(90.0, 100.0), 90.0),
            UnitSpawn("b", "p1", "tank", Position(92.0, 120.0), 90.0))
        val stops = List(parked) { i -> UnitSpawn("p$i", "p1", "tank", Position(96.0 + i % 8 * 3, 72.0 + i / 8 * 3), 90.0) }
        val scene = initial.copy(map = map, units = traffic + stops, unitDefinitions = mapOf("tank" to car))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            traffic.forEach { u -> assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-${u.unitId}",
                "p1", u.unitId, Position(500.0, u.position.y), context.world))) }
            return (0L until 32).map { step ->
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                val work = context.trafficSeparationVisits
                Work(frame.stepIndex!!, work.cells, work.bodies, work.incomplete)
            }
        } finally { context.close() }
    }

    @Test fun small_braking_and_crowded_parking_have_bounded_repeatable_queries() {
        for (parked in listOf(0, 72)) {
            val work = drive(parked)
            assertEquals(work, drive(parked))
            assertEquals(31L, work.last().step)
            assertTrue(work.any { it.bodies > 0 })
            assertTrue(work.all { it.cells <= 2 * 8 * 81 && it.bodies <= 2 * 8 * 64 })
            if (parked > 0) assertTrue(work.any { it.truncated > 0 })
            println("parked=$parked steps=${work.size} maxCells=${work.maxOf { it.cells }} maxBodies=${work.maxOf { it.bodies }} maxIncomplete=${work.maxOf { it.truncated }}")
        }
    }
}