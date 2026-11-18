package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class MoveArrivalSessionTest {
    @Test fun a_mixed_move_reports_both_the_mobile_arrival_and_the_enclosed_rejection() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/production")
        val base = ControlledScenario.load(root)
        val spawn = base.units.single { it.unitId == "a" }
        val source = base.unitDefinitions.getValue(spawn.typeId)
        val tank = com.rustedwarfare.reforge.core.content.UnitDefinition(source.id, source.className,
            10.0, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale, source.movement,
            emptySet(), mass = 1000.0)
        val scene = base.copy(map = MapDefinition(40, 20, 20, 20, emptyList(), IntArray(800),
            BlockingGrid(40, 20, BooleanArray(800) {
                val col = it % 40; val row = it / 40
                col in 4..6 && row in 4..6 && !(col == 5 && row == 5)
            })), unitDefinitions = mapOf(tank.id to tank), units = listOf(
                spawn.copy(position = Position(110.0, 110.0)),
                spawn.copy(unitId = "free", position = Position(400.0, 210.0))), combatContract = null, economyContract = null)
        SandboxSession(scene).use { session ->
            session.select(Position(110.0, 110.0))
            session.select(Position(400.0, 210.0), SelectionMode.ADD)
            assertIs<ActionResult.Accepted>(session.move(Position(450.0, 210.0)))
            for (tick in 0..3000) {
                session.tick()
                if (session.observation.units.single { it.unitId == "free" }.moveArrival != null) break
            }
            assertNotNull(session.observation.units.single { it.unitId == "free" }.moveArrival)
            assertTrue(session.playerFeedback.contains("1 units stopped near target"), session.playerFeedback)
            assertTrue(session.playerFeedback.contains("1 unit cannot move"), session.playerFeedback)
            assertNull(session.moveArrivalMarker)
            session.move(Position(450.0, 300.0))
            assertFalse(session.playerFeedback.contains("stopped near target"))
            assertFalse(session.playerFeedback.contains("cannot move"))
        }
    }

    @Test fun a_factory_occupied_target_stops_outside_its_footprint_and_a_new_order_clears_the_marker() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/production")
        val base = ControlledScenario.load(root)
        val tank = base.units.single { it.unitId == "a" }
        val factory = base.units.single { it.unitId == "f" }
        val scene = base.copy(units = listOf(tank, factory), combatContract = base.combatContract!!.copy(
            unitObjectIds = mapOf("a" to 8L, "f" to 10L), nextObjectId = 11L))
        SandboxSession(scene).use { session ->
            session.select(tank.position)
            assertIs<ActionResult.Accepted>(session.move(factory.position))
            var arrival: MoveArrival? = null
            for (tick in 0..6000) {
                session.tick()
                assertTrue(session.observation.events.none { it.kind == "movement-cancelled" || it.kind == "path-unreachable" })
                arrival = session.observation.units.single { it.unitId == "a" }.moveArrival
                if (arrival != null) break
            }
            val result = assertNotNull(arrival)
            assertEquals(factory.position, result.originalTarget)
            assertTrue(result.distance > 0.0 && result.stoppedAt.x < factory.position.x)
            assertEquals(result.stoppedAt, session.observation.units.single { it.unitId == "a" }.position)
            assertEquals(factory.position, session.observation.units.single { it.unitId == "f" }.position)
            assertEquals(result, session.moveArrivalMarker)
            assertIs<ActionResult.Accepted>(session.move(Position(110.0, 330.0)))
            assertNull(session.moveArrivalMarker)
            assertFalse(session.playerFeedback.startsWith("Reached nearest accessible position"))
        }
    }

    @Test fun a_deferred_fully_trapped_order_explains_why_it_was_rejected() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/production")
        val base = ControlledScenario.load(root)
        val spawn = base.units.single { it.unitId == "a" }
        val source = base.unitDefinitions.getValue(spawn.typeId)
        val tank = com.rustedwarfare.reforge.core.content.UnitDefinition(source.id, source.className,
            10.0, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale, source.movement,
            emptySet(), mass = 1000.0)
        val scene = base.copy(map = MapDefinition(1, 1, 20, 20, emptyList(), IntArray(1),
            BlockingGrid(1, 1, BooleanArray(1))), unitDefinitions = mapOf(tank.id to tank),
            units = listOf(spawn.copy(position = Position(10.0, 10.0))), combatContract = null, economyContract = null)
        SandboxSession(scene).use { session ->
            session.select(Position(10.0, 10.0))
            session.move(Position(19.0, 10.0))
            repeat(50) { session.tick() }
            assertEquals("Unit cannot move", session.playerFeedback)
            assertNull(session.moveArrivalMarker)
        }
    }

    @Test fun a_single_selected_unit_reports_its_nearest_stop_in_the_existing_status_bar() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/production")
        val base = ControlledScenario.load(root)
        val tank = base.units.single { it.unitId == "a" }
        val source = base.map
        val map = MapDefinition(source.widthTiles, source.heightTiles, source.tileWidth, source.tileHeight,
            source.tileSets, source.groundGids, BlockingGrid(source.widthTiles, source.heightTiles,
                BooleanArray(source.widthTiles * source.heightTiles) { it % source.widthTiles == 15 }))
        val scene = base.copy(map = map, units = listOf(tank), combatContract = base.combatContract!!.copy(
            unitObjectIds = mapOf("a" to 8L), nextObjectId = 9L))
        SandboxSession(scene).use { session ->
            session.select(tank.position)
            assertIs<ActionResult.Accepted>(session.move(Position(430.0, 210.0)))
            var result: MoveArrival? = null
            for (tick in 0..6000) {
                session.tick()
                result = session.observation.units.single().moveArrival
                if (result != null) break
            }
            assertNotNull(result)
            assertTrue(session.playerFeedback.startsWith("Reached nearest accessible position"), session.playerFeedback)
            assertTrue(session.playerFeedback.contains("from target"), session.playerFeedback)
            assertEquals(result, session.moveArrivalMarker)
            repeat(180) { session.tick() }
            assertNull(session.moveArrivalMarker)
            assertFalse(session.playerFeedback.startsWith("Reached nearest accessible position"))
        }
    }
}
