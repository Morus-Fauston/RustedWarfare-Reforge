package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnitYieldTest {
    @Test
    fun a_shorter_legal_shift_can_clear_the_actual_step_in_a_narrow_channel() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val start = Position(10.0, 82.0)
            val occupied = Position(10.0, 70.0)
            val target = Position(10.0, 74.7)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, start)
            val water = opening.unitDefinitions.getValue("water-test")
            val mover = withShape(water, "short-step", MovementCollisionShape.Circle(6.0),
                water.movement.copy(moveSpeed = 0.6, moveAccelerationSpeed = 1.0))
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + (mover.id to mover), units = listOf(
                UnitSpawn("mover", "p1", mover.id, start, 0.0),
                UnitSpawn("blocker", "p1", water.id, occupied, 0.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enableMotionDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("short-step", "p1", "mover", target, context.world)))
                val frames = (0L..10L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                val sample = context.drainMotionDiagnostics(context.world).first { it.unitId == "mover" }
                val moved = frames.first().find("blocker").position
                assertEquals(occupied.y, moved.y)
                val longitudinalGap = sample.candidate.y - occupied.y
                val required = sqrt(144.0 - longitudinalGap * longitudinalGap)
                assertTrue(required > 0.0 && required < 4.0)
                assertEquals(required, abs(moved.x - occupied.x), 1e-8,
                    "The 20-unit channel admits the finite circle sweep")
                assertEquals(sample.candidate, sample.finalPosition)
                assertTrue(frames.drop(1).all { it.find("blocker").position == moved })
                val arrived = frames.single { Event("movement-arrived", "mover") in it.events }
                println("Short required shift=$required blocker=$moved arrival=${arrived.stepIndex}")
            } finally {
                context.close()
            }
        }

    @Test
    fun the_same_move_keeps_the_new_position_after_a_short_yield() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val start = Position(10.0, 82.0)
            val occupied = Position(10.0, 70.0)
            val target = Position(10.0, 30.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, start)
            val water = opening.unitDefinitions.getValue("water-test")
            val mover = withShape(water, "slow-step", MovementCollisionShape.Circle(6.0),
                water.movement.copy(moveSpeed = 0.2, moveAccelerationSpeed = 1.0))
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + (mover.id to mover), units = listOf(
                UnitSpawn("mover", "p1", mover.id, start, 0.0),
                UnitSpawn("blocker", "p1", water.id, occupied, 0.0)))
            val frames = runMove(scene, target, 12L)
            val newPosition = frames.first().find("blocker").position
            assertTrue(abs(newPosition.x - occupied.x) in 2.0..3.0)
            val nextGap = frames.first().find("mover").position.y - mover.movement.moveSpeed - occupied.y
            assertTrue(sqrt(144.0 - nextGap * nextGap) < 4.0, "A second nudge would fit")
            assertTrue(Event("movement-blocked", "mover") in frames[1].events)
            assertTrue(frames.drop(1).all { it.find("blocker").position == newPosition })
            assertEquals(target, frames.last().find("mover").moveTarget)
        }

    @Test
    fun a_rotating_rectangle_uses_the_other_side_when_the_short_shift_would_enter_the_shore() =
        WaterTestScene.withMap { base ->
            val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
                base.tileSets, base.groundGids, base.blocking, setOf(27 to 10))
            val original = Position(540.0, 190.0)
            val start = Position(470.0, 190.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, start)
            val water = opening.unitDefinitions.getValue("water-test")
            val ship = withShape(water, "long-ship", MovementCollisionShape.Rectangle(102.0, 20.0))
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + (ship.id to ship), units = listOf(
                UnitSpawn("mover", "p1", ship.id, start, 90.0),
                UnitSpawn("blocker", "p1", water.id, original, 90.0)))
            val result = runMove(scene, Position(650.0, 190.0), 480L)
            val shifted = result.first { it.find("blocker").position != original }
            assertEquals(Position(540.0, 174.0), shifted.find("blocker").position)
            assertEquals(90.0, shifted.find("blocker").heading)
            assertTrue(result.any { Event("movement-arrived", "mover") in it.events })
            assertTrue(result.filter { it.stepIndex >= shifted.stepIndex }.all { it.find("blocker").position.y == 174.0 })
            println("Rectangle yielded around shoreline at ${shifted.stepIndex}, blocker=${shifted.find("blocker").position}")
        }

    @Test
    fun a_side_shift_larger_than_one_tile_keeps_the_unit_in_its_slot() =
        WaterTestScene.withMap { map ->
            val start = Position(450.0, 190.0)
            val occupied = Position(500.0, 190.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, start)
            val water = opening.unitDefinitions.getValue("water-test")
            val wide = withShape(water, "wide-ship", MovementCollisionShape.Circle(20.0))
            val quick = withShape(water, "long-step", MovementCollisionShape.Circle(6.0),
                water.movement.copy(moveSpeed = 40.0, moveAccelerationSpeed = 1.0))
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + mapOf(wide.id to wide, quick.id to quick), units = listOf(
                UnitSpawn("mover", "p1", quick.id, start, 90.0),
                UnitSpawn("blocker", "p1", wide.id, occupied, 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enableMotionDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("long-step", "p1", "mover",
                    Position(570.0, 190.0), context.world)))
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                val actual = context.drainMotionDiagnostics(context.world).single { it.unitId == "mover" }
                val closestLongitudinalGap = (occupied.x - actual.candidate.x).coerceAtLeast(0.0)
                val necessary = sqrt(26.0 * 26.0 - closestLongitudinalGap * closestLongitudinalGap)
                assertTrue(necessary > 20.0, "Actual finite step needs $necessary units of side clearance")
                assertTrue(Event("movement-blocked", "mover") in frame.events)
                assertEquals(occupied, frame.find("blocker").position)
                assertEquals(474.0, frame.find("mover").position.x, 1e-9)
                println("Required at first contact=$necessary with cap 20.0")
            } finally {
                context.close()
            }
        }

    @Test
    fun a_channel_with_no_side_room_keeps_the_blocker_and_move_in_place() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val start = Position(10.0, 110.0)
            val target = Position(10.0, 30.0)
            val occupied = Position(10.0, 70.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, start)
            val wide = withShape(opening.unitDefinitions.getValue("water-test"), "channel-width", MovementCollisionShape.Circle(10.0))
            val scene = opening.copy(unitDefinitions = mapOf(wide.id to wide), units = listOf(
                UnitSpawn("mover", "p1", wide.id, start, 0.0), UnitSpawn("blocker", "p1", wide.id, occupied, 0.0)))
            val frames = runMove(scene, target, 100L)
            assertTrue(frames.any { Event("movement-blocked", "mover") in it.events })
            assertTrue(frames.all { it.find("blocker").position == occupied && it.find("mover").moveTarget == target })
            assertEquals(90.0, frames.last().find("mover").position.y, 1e-9)
        }

    @Test
    fun an_active_friendly_route_and_other_owners_do_not_allow_side_shifts() =
        WaterTestScene.withMap { map ->
            val start = Position(450.0, 190.0)
            val target = Position(570.0, 190.0)
            val direct = Position(500.0, 190.0)
            val farther = Position(520.0, 190.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, start)
            for ((owner, otherTeam) in listOf("p1" to "rival", "p2" to "own", "p2" to "rival")) {
                val units = listOf(UnitSpawn("mover", "p1", "water-test", start, 90.0),
                    UnitSpawn("blocker", owner, "water-test", direct, 90.0))
                val active = owner == "p1"
                val scene = opening.copy(
                    players = listOf(Player("p1", PlayerControl.LOCAL, "own"), Player("p2", PlayerControl.NONE, otherTeam)),
                    units = if (active) units + UnitSpawn("farther", "p2", "water-test", farther, 90.0) else units)
                val frames = runMove(scene, target, 130L) { context ->
                    if (active) assertIs<ActionResult.Accepted>(context.submit(MoveAction("own-route", "p1", "blocker",
                        Position(530.0, 190.0), context.world)))
                }
                val contact = frames.first { Event("movement-blocked", "mover") in it.events }
                assertEquals(target, contact.find("mover").moveTarget)
                assertTrue(frames.all { it.find("blocker").position.y == direct.y })
                if (active) {
                    assertEquals("own-route", contact.find("blocker").tasks.single().actionId)
                    assertTrue(contact.find("blocker").position.x > direct.x)
                    assertTrue(frames.all { it.find("farther").position == farther })
                } else assertTrue(frames.all { it.find("blocker").position == direct })
            }
        }

    @Test
    fun occupied_side_spaces_prevent_a_shift_without_pushing_a_chain() =
        WaterTestScene.withMap { map ->
            val start = Position(450.0, 190.0)
            val target = Position(570.0, 190.0)
            val original = mapOf("blocker" to Position(500.0, 190.0),
                "left-slot" to Position(500.0, 178.0), "right-slot" to Position(500.0, 202.0),
                "behind" to Position(520.0, 190.0))
            val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(
                units = listOf(UnitSpawn("mover", "p1", "water-test", start, 90.0)) +
                    original.map { (id, position) -> UnitSpawn(id, "p1", "water-test", position, 90.0) })
            val frames = runMove(scene, target, 130L)
            assertTrue(frames.any { Event("movement-blocked", "mover") in it.events })
            assertTrue(frames.all { frame -> original.all { (id, position) -> frame.find(id).position == position } })
            assertEquals(target, frames.last().find("mover").moveTarget)
        }

    @Test
    fun a_directly_blocking_idle_unit_can_step_aside_once_and_stay_there() = WaterTestScene.withMap { map ->
        val origin = Position(450.0, 190.0)
        val originalBlocker = Position(500.0, 190.0)
        val target = Position(570.0, 190.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, origin).copy(units = listOf(
            UnitSpawn("mover", "p1", "water-test", origin, 90.0),
            UnitSpawn("yielding", "p1", "water-test", originalBlocker, 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("continue-straight", "p1", "mover", target, context.world)))
            val frames = (0L..420L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val firstYield = assertNotNull(frames.firstOrNull { it.find("yielding").position != originalBlocker },
                "The directly blocking unit never used the legal side space")
            val finalPosition = firstYield.find("yielding").position
            assertEquals(originalBlocker.x, finalPosition.x, 1e-9)
            assertTrue(abs(finalPosition.y - originalBlocker.y) in 12.0..20.0)
            assertEquals(target, firstYield.find("mover").moveTarget)
            val arrived = frames.single { Event("movement-arrived", "mover") in it.events }
            assertTrue(arrived.stepIndex > firstYield.stepIndex)
            assertTrue(arrived.find("mover").position.x > target.x - 7.0)
            assertTrue(frames.all { frame ->
                val mover = frame.find("mover")
                val occupant = frame.find("yielding")
                abs(mover.position.y - origin.y) < 1e-9 &&
                    ScreenPlane.distanceSquared(mover.position, occupant.position) >= 144.0 - 1e-8
            })
            assertTrue(frames.filter { it.stepIndex >= firstYield.stepIndex }.all { frame ->
                val unit = frame.find("yielding")
                unit.position == finalPosition && unit.speed == 0.0 && unit.moveTarget == null && unit.tasks.isEmpty()
            })
            println("Yielded at ${firstYield.stepIndex} to $finalPosition and passed at ${arrived.stepIndex}")
        } finally {
            context.close()
        }
    }

    private fun runMove(scene: Scenario, target: Position, lastStep: Long,
                        prepare: (SimulationContext) -> Unit = {}): List<Observation> {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            prepare(context)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("mover-move", "p1", "mover", target, context.world)))
            return (0L..lastStep).map { context.advance(Step(it, 1.0, "sim-tick")) }
        } finally {
            context.close()
        }
    }

    private fun withShape(template: UnitDefinition, name: String, shape: MovementCollisionShape,
                          movement: MovementProfile = template.movement) =
        UnitDefinition(name, template.className, template.radius, template.displayRadius, template.totalFrames,
            template.imagePath, template.imageScale, movement, template.retainedUnconsumed, movementCollision = shape)

    private fun Observation.find(id: String) = units.single { it.unitId == id }
}
