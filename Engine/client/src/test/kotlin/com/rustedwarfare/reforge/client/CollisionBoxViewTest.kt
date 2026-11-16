package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.UNIT_CONTACT_BUFFER_WIDTH
import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.CombatContract
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollisionBoxViewTest {
    @Test fun buffer_disappears_when_a_helicopter_leaves_its_contact_layer() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("helicopter-production/json")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
        val scenario = contentPackScenario(root, ContentPackChoice(pack, "helicopter"))
        SandboxSession(scenario).use { session ->
            val projection = MapProjection(600.0, 400.0)
            val starting = CollisionBoxView.project(session, projection).single { it.unitId == "a" }
            assertEquals(starting.bound + UNIT_CONTACT_BUFFER_WIDTH * projection.scale, assertNotNull(starting.bufferRadius), .001)
            repeat(100) { session.tick() }
            assertTrue(session.observation.units.single { it.unitId == "a" }.height >= 4.0)
            val flying = CollisionBoxView.project(session, projection).single { it.unitId == "a" }
            assertEquals(starting.bound, flying.bound)
            assertNull(flying.bufferRadius)
            assertTrue(flying.bufferCorners.isEmpty())
        }
    }

    @Test fun rectangle_uses_the_authorized_pose_and_remaining_route() {
        val base = NavalSandboxScene.load("json").scenario
        val pack = ContentLoader.loadContentPack(Path.of("../../SimulationTest/public/inputs/phase-seven-single-ship/json/package"))
        val hull = assertIs<ContentResult.Success<LoadedContentPack>>(pack).value.units.getValue("heavyBattleship")
        val input = base.copy(unitDefinitions = base.unitDefinitions + ("heavyBattleship" to hull),
            units = listOf(UnitSpawn("ship", "p1", "heavyBattleship", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 1L), 2L))
        SandboxSession(input).use { session ->
            val projection = MapProjection(800.0, 600.0, MapArea(0.0, 0.0, 800.0, 600.0))
            val first = CollisionBoxView.project(session, projection).single()
            assertEquals(Position(500.0, 300.0), first.center)
            assertEquals(102.0, first.corners.maxOf { it.x } - first.corners.minOf { it.x }, .001)
            assertEquals(20.0, first.corners.maxOf { it.y } - first.corners.minOf { it.y }, .001)
            val totalBuffer = 2 * UNIT_CONTACT_BUFFER_WIDTH
            assertEquals(102.0 + totalBuffer, first.bufferCorners.maxOf { it.x } - first.bufferCorners.minOf { it.x }, .001)
            assertEquals(20.0 + totalBuffer, first.bufferCorners.maxOf { it.y } - first.bufferCorners.minOf { it.y }, .001)
            assertEquals(hypot(51.0, 10.0), first.bound, .001)
            assertTrue(first.headingTip.x > first.center.x)
            assertEquals(first.center.y, first.headingTip.y, .001)
            assertTrue(first.route.isEmpty())
            session.select(Position(500.0, 300.0))
            val firstGoal = Position(650.0, 300.0)
            assertIs<ActionResult.Accepted>(session.move(firstGoal))
            session.tick()
            val moving = CollisionBoxView.project(session, projection).single()
            assertEquals(firstGoal.let { Position(it.x, 600.0 - it.y) }, moving.target)
            assertTrue(moving.route.isNotEmpty())
            val secondGoal = Position(650.0, 340.0)
            assertIs<ActionResult.Accepted>(session.move(secondGoal))
            session.tick()
            projection.zoomAt(400.0, 300.0, -1.0)
            projection.panBy(-42.0, 35.0)
            val current = session.observation.units.single()
            val next = CollisionBoxView.project(session, projection).single()
            assertEquals(Position(projection.x(current.position.x).toDouble(), projection.y(current.position.y).toDouble()), next.center)
            assertEquals(Position(projection.x(secondGoal.x).toDouble(), projection.y(secondGoal.y).toDouble()), next.target)
            assertEquals(current.pathPoints.map { Position(projection.x(it.x).toDouble(), projection.y(it.y).toDouble()) }, next.route)
            assertEquals(hypot(51.0, 10.0) * projection.scale, next.bound, .001)
            assertEquals(hypot(51.0 + UNIT_CONTACT_BUFFER_WIDTH, 10.0 + UNIT_CONTACT_BUFFER_WIDTH) * projection.scale,
                assertNotNull(next.bufferRadius), .001)
        }
    }
}
