package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.CombatContract
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CollisionBoxViewTest {
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
        }
    }
}
