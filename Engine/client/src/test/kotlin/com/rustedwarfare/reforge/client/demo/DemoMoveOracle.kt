package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.*
import kotlin.math.hypot
import kotlin.test.*

internal fun demoTerrainScenario(): Scenario {
    val demo = DemoScenario.load()
    val source = demo.unitDefinitions.getValue("demo_tank")
    // Use point hulls to isolate exploration and path following from body clearance.
    val probe = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
        source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
        source.fogOfWarSightRange, movementCollision = MovementCollisionShape.Circle(0.0))
    return demo.copy(unitDefinitions = mapOf("demo_tank" to probe))
}

/** Checks arrival against each member's actual route and the original click. */
internal class DemoMoveOracle(private val scenario: Scenario, private val goal: Position, initial: List<UnitObservation>) {
    constructor(scenario: Scenario, goal: Position, initial: Observation) : this(scenario, goal, initial.units)
    private var previous = initial.associateBy { it.unitId }
    private val previousTarget = mutableMapOf<String, Position>()
    private val nearTime = mutableMapOf<String, Int>()
    private val normal = mutableSetOf<String>()
    private val stable = mutableMapOf<String, Int>()

    fun accept(observation: Observation) = accept(observation.units, observation.events, observation.stepIndex)

    fun accept(units: List<UnitObservation>, stepEvents: List<Event>, stepIndex: Long) {
        assertEquals(previous.keys, units.map { it.unitId }.toSet())
        for (unit in units) {
            val before = previous.getValue(unit.unitId)
            val target = before.moveTarget
            if (target != null) {
                if (previousTarget.put(unit.unitId, target) != target) nearTime[unit.unitId] = 0
                if (hypot(before.position.x - target.x, before.position.y - target.y) < 41.0)
                    nearTime[unit.unitId] = (nearTime[unit.unitId] ?: 0) + 1
            }
            val events = stepEvents.filter { it.unitId == unit.unitId }
            val arrivals = events.count { it.kind == "movement-arrived" }
            assertTrue(arrivals <= 1)
            if (arrivals == 1) {
                assertTrue(normal.add(unit.unitId), "Duplicate normal completion for ${unit.unitId}")
                val receipt = unit.moveArrival
                if (receipt != null) {
                    assertEquals(goal, receipt.originalTarget)
                    assertEquals(unit.position, receipt.stoppedAt)
                    assertTrue(hypot(unit.position.x - goal.x, unit.position.y - goal.y) <= 60.0,
                        "${unit.unitId} parked outside the gathering area at $stepIndex")
                } else {
                    assertEquals(goal, target)
                    val time = nearTime[unit.unitId] ?: 0
                    val radius = if (time > 340) 36.0 else if (time > 240) 16.0 else 7.0
                    assertTrue(hypot(before.position.x - goal.x, before.position.y - goal.y) < radius,
                        "${unit.unitId} normal clear outside strict $radius at $stepIndex")
                }
            }
            if (unit.unitId in normal) {
                assertNull(unit.moveTarget); assertTrue(unit.pathPoints.isEmpty())
                assertEquals(0.0, unit.speed, 1e-9)
                assertEquals(before.heading, unit.heading)
                assertEquals(0.0, unit.angularVelocity, 1e-9)
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
