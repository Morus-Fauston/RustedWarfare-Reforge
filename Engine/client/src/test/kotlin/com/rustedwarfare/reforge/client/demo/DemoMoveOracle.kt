package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.sim.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.*

/** Independent command-cause/tail oracle; the legacy targetRadius field is not a completion gate. */
internal class DemoMoveOracle(private val scenario: Scenario, private val goal: Position, initial: List<UnitObservation>) {
    constructor(scenario: Scenario, goal: Position, initial: Observation) : this(scenario, goal, initial.units)
    private var previous = initial.associateBy { it.unitId }
    private val nearTime = mutableMapOf<String, Int>()
    private val normal = mutableSetOf<String>()
    private val stable = mutableMapOf<String, Int>()

    fun accept(observation: Observation) = accept(observation.units, observation.events, observation.stepIndex)

    fun accept(units: List<UnitObservation>, stepEvents: List<Event>, stepIndex: Long) {
        assertEquals(previous.keys, units.map { it.unitId }.toSet())
        for (unit in units) {
            val before = previous.getValue(unit.unitId)
            if (before.moveTarget == goal && hypot(before.position.x - goal.x, before.position.y - goal.y) < 41.0)
                nearTime[unit.unitId] = (nearTime[unit.unitId] ?: 0) + 1
            val events = stepEvents.filter { it.unitId == unit.unitId }
            val arrivals = events.count { it.kind == "movement-arrived" }
            assertTrue(arrivals <= 1)
            if (arrivals == 1) {
                assertTrue(normal.add(unit.unitId), "Duplicate normal completion for ${unit.unitId}")
                assertEquals(goal, before.moveTarget)
                val time = nearTime[unit.unitId] ?: 0
                val radius = if (time > 340) 36.0 else if (time > 240) 16.0 else 7.0
                assertTrue(hypot(before.position.x - goal.x, before.position.y - goal.y) < radius,
                    "${unit.unitId} normal clear outside strict $radius at $stepIndex")
            }
            if (unit.unitId in normal) {
                assertNull(unit.moveTarget); assertTrue(unit.pathPoints.isEmpty())
                val movement = scenario.unitDefinitions.getValue(unit.typeId).movement
                assertEquals(max(0.0, before.speed - movement.moveDecelerationSpeed * movement.moveSpeed), unit.speed, 1e-9)
                assertEquals(before.heading, unit.heading)
                assertEquals(before.angularVelocity, unit.angularVelocity)
            }
            stable[unit.unitId] = if (unit.unitId in normal && unit.movementState == MovementState.IDLE &&
                unit.position == before.position && unit.heading == before.heading) (stable[unit.unitId] ?: 0) + 1 else 0
            val radius = scenario.unitDefinitions.getValue(unit.typeId).radius
            assertTrue(unit.position.x >= radius && unit.position.y >= radius &&
                unit.position.x <= scenario.map.widthTiles * scenario.map.tileWidth - radius &&
                unit.position.y <= scenario.map.heightTiles * scenario.map.tileHeight - radius)
            assertFalse(scenario.map.blocking.isBlocked((unit.position.x / scenario.map.tileWidth).toInt(),
                (unit.position.y / scenario.map.tileHeight).toInt()), "${unit.unitId} center in true wall")
        }
        previous = units.associateBy { it.unitId }
    }

    fun assertComplete() {
        assertEquals(previous.keys, normal, "Cancellation or a merely empty target cannot stand in for normal completion")
        assertTrue(previous.keys.all { (stable[it] ?: 0) >= 19 }, "Each unit requires a complete fixed 18q IDLE tail")
        println("normalCompletions=${normal.size}, finalTargetDistances=${previous.mapValues { hypot(it.value.position.x - goal.x, it.value.position.y - goal.y) }}")
    }

    val normalCompletions: Int get() = normal.size
}
