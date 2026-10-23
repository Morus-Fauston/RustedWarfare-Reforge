package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

private fun simInputs(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))

private fun actualUnit(): UnitDefinition = assertIs<ContentResult.Success<UnitDefinition>>(
    ContentLoader.loadUnitVariant(simInputs().resolve("units/weaponless_tank.ini")),
).value

private fun actualMap(): MapDefinition = assertIs<ContentResult.Success<MapDefinition>>(
    ContentLoader.loadMap(simInputs().resolve("maps/p001-controlled.tmx")),
).value

private fun scenario(
    units: List<UnitSpawn> = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 100.0), 0.0)),
): Scenario = Scenario(
    scenarioId = "p001-test",
    map = actualMap(),
    unitDefinitions = mapOf("tank" to actualUnit()),
    players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
    units = units,
    coordinateModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
    timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
    movementContract = MovementContract(16.0, 4.0),
)

private fun initialized(scenario: Scenario = scenario()): SimulationContext =
    assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context

private fun advance(context: SimulationContext, step: Long): Observation =
    context.advance(Step(step, 1.0, "sim-tick"))

class MovementContractTest {
    @Test
    fun repeated_runs_produce_identical_step_observations() {
        fun run(): List<Observation> {
            val context = initialized()
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(100.0, 140.0), context.world)))
            return (0L..30L).map { advance(context, it) }
        }

        fun completeSample(sample: Observation): List<Any> = listOf(
            sample.sampleId, sample.worldId, sample.scenarioId, sample.stepIndex, sample.simulationTime,
            sample.units, sample.events, sample.actionResults,
        )
        assertEquals(run().map(::completeSample), run().map(::completeSample))
    }

    @Test
    fun m01_moves_in_a_line_and_stops_with_process_samples() {
        val context = initialized(scenario(listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 0.0))))
        traceObservation("M01", "initial", context.observe())
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(110.0, 90.0), context.world)))
        val samples = (0L..80L).map { advance(context, it) }
        val arrived = samples.indexOfFirst { sample -> sample.events.any { it.kind == "movement-arrived" } }
        assertTrue(arrived > 0)
        assertTrue(hypot(samples[arrived - 1].units.single().position.x - 110.0,
            samples[arrived - 1].units.single().position.y - 90.0) < 7.0)
        samples.forEach { traceObservation("M01", "step", it) }
        val unit = samples.last().units.single()

        assertTrue(samples.map { it.units.single().position }.distinct().size > 2)
        assertEquals(MovementState.IDLE, unit.movementState)
        assertEquals(null, unit.moveTarget)
        assertEquals(0.0, unit.speed)
        assertTrue(hypot(unit.position.x - 110.0, unit.position.y - 90.0) < 7.03)
        assertEquals(1, samples.sumOf { sample -> sample.events.count { it.kind == "movement-arrived" } })
        assertTrue(samples.takeLast(20).all { it.units.single() == unit })
        val after = advance(context, 81).units.single()
        assertEquals(unit.position, after.position)
    }

    @Test
    fun m02_turning_respects_angular_limits_and_keeps_trajectory() {
        val context = initialized()
        traceObservation("M02", "initial", context.observe())
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(100.0, 140.0), context.world)))
        val samples = (0L..20L).map { advance(context, it).also { sample -> traceObservation("M02", "step", sample) }.units.single() }

        assertTrue(samples.drop(1).zip(samples).all { (next, previous) ->
            kotlin.math.abs(normalizeAngle(next.heading - previous.heading)) <= 4.1 + 1e-9
        })
        assertTrue(samples.any { it.heading != 0.0 })
        assertTrue(samples.all { it.position == Position(100.0, 100.0) })
        assertEquals(-0.25, samples.first().angularVelocity)
        assertEquals(305.5, samples.last().heading, 1e-9)
        samples.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.abs(a.angularVelocity - b.angularVelocity) <= 0.25 + 1e-9) }
        samples.forEachIndexed { index, unit -> println("M02 step=$index $unit") }
    }

    @Test
    fun m03_stop_cancels_target_and_decelerates() {
        val context = initialized(scenario(listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0))))
        traceObservation("M03", "initial", context.observe())
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(250.0, 110.0), context.world)))
        println("M03 initial=${context.observe().units}")
        (0L..15L).forEach { step -> traceObservation("M03", "step", advance(context, step)) }
        val beforeStop = context.observe().units.single()
        val result = assertIs<ActionResult.Accepted>(context.submit(StopAction("a2", "p1", "u1", context.world)))
        assertTrue(result.events.any { it.kind == "movement-cancelled" })
        assertEquals(null, context.observe().units.single().moveTarget)
        val stopped = (16L..42L).map { advance(context, it).also { sample -> traceObservation("M03", "step", sample) }.units.single() }

        assertTrue(stopped.first().speed < beforeStop.speed)
        assertEquals(0.0, stopped.last().speed)
        assertEquals(MovementState.IDLE, stopped.last().movementState)
        assertTrue(stopped.zipWithNext().all { (a, b) -> b.position.x >= a.position.x })
        listOf(0.913, 0.726, 0.539, 0.352, 0.165, 0.0).forEachIndexed { index, speed ->
            assertEquals(speed, stopped[index].speed, 1e-9)
        }
        assertEquals(2.695, stopped.last().position.x - beforeStop.position.x, 1e-9)
        assertTrue(stopped.takeLast(20).all { it == stopped.last() })
    }

    @Test
    fun m04_routes_from_gap_row9() = assertRoutesFromGapRow(9)

    @Test
    fun m04_routes_from_gap_row10() = assertRoutesFromGapRow(10)

    @Test
    fun m04_routes_from_gap_row11() = assertRoutesFromGapRow(11)

    private fun assertRoutesFromGapRow(row: Int) {
            val context = initialized(scenario(listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, row * 20.0 + 10.0), 90.0))))
            traceObservation("M04-row$row", "initial", context.observe())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(500.0, row * 20.0 + 10.0), context.world)))
            var observation = context.observe()
            var arrivals = 0
            var stable = 0
            println("M04 initial row=$row units=${observation.units}")
            for (step in 0L..900L) {
                val before = observation.units.single()
                observation = advance(context, step)
                traceObservation("M04-row$row", "step", observation)
                val unit = observation.units.single()
                if (observation.events.any { it.kind == "movement-arrived" }) {
                    arrivals += observation.events.count { it.kind == "movement-arrived" }
                    assertEquals(listOf(Position(500.0, row * 20.0 + 10.0)), before.pathPoints)
                    assertTrue(hypot(before.position.x - 500.0, before.position.y - (row * 20.0 + 10.0)) < 7.0)
                    assertEquals(null, unit.moveTarget)
                }
                println("M04 row=$row step=$step time=${observation.simulationTime} x=${unit.position.x} y=${unit.position.y} heading=${unit.heading} speed=${unit.speed} omega=${unit.angularVelocity} waypoint=${unit.pathPoints.firstOrNull()} target=${unit.moveTarget} state=${unit.movementState} events=${observation.events}")
                assertFalse(intersectsBlocked(actualMap(), unit.position, actualUnit().radius))
                if (observation.events.any { it.kind == "movement-blocked" }) {
                    assertTrue(unit.speed >= maxOf(0.0, before.speed - .187) - 1e-9,
                        "Terrain correction must not hard-stop propulsion")
                    if (arrivals == 0) assertNotNull(unit.moveTarget)
                }
                stable = if (unit.moveTarget == null && unit.movementState == MovementState.IDLE &&
                    unit.position == before.position && unit.heading == before.heading) stable + 1 else 0
                if (stable >= 18) break
            }
            assertEquals(1, arrivals)
            assertTrue(stable >= 18)
            assertTrue(
                observation.units.single().movementState == MovementState.IDLE &&
                    hypot(observation.units.single().position.x - 500.0, observation.units.single().position.y - (row * 20.0 + 10.0)) < 7.03,
                "row=$row position=${observation.units.single().position} heading=${observation.units.single().heading} speed=${observation.units.single().speed} state=${observation.units.single().movementState} target=${observation.units.single().moveTarget} path=${observation.units.single().pathPoints}",
            )
    }

    @Test
    fun m05_unreachable_target_cancels_old_move_without_nearby_replacement() {
        val context = initialized(scenario(listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0))))
        traceObservation("M05", "initial", context.observe())
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(400.0, 100.0), context.world)))
        println("M05 initial=${context.observe().units}")
        traceObservation("M05", "step", advance(context, 0))
        val before = context.observe().units.single()
        val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("a2", "p1", "u1", Position(450.0, 100.0), context.world)))
        assertTrue(result.events.any { it.kind == "path-unreachable" })
        val unit = context.observe().units.single()
        assertEquals(null, unit.moveTarget)
        assertTrue(unit.pathPoints.isEmpty())
        assertEquals(before.speed, unit.speed)
        val after = advance(context, 1)
        traceObservation("M05", "step", after)
        assertEquals(0.0, after.units.single().speed)
        println("M05 accepted=$result before=$before step1=${after.units} events=${after.events}")
    }

    private fun normalizeAngle(angle: Double): Double = ((angle + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun intersectsBlocked(map: MapDefinition, position: Position, radius: Double): Boolean {
        if (position.x < radius || position.y < radius || position.x > map.widthTiles * map.tileWidth - radius ||
            position.y > map.heightTiles * map.tileHeight - radius) return true
        for (row in 0 until map.heightTiles) for (col in 0 until map.widthTiles) {
            if (!map.blocking.isBlocked(col, row)) continue
            val left = col * map.tileWidth.toDouble()
            val top = row * map.tileHeight.toDouble()
            if (position.x >= left && position.x < left + map.tileWidth &&
                position.y >= top && position.y < top + map.tileHeight) return true
        }
        return false
    }
}
