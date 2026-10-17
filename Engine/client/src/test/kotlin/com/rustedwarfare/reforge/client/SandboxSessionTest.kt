package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class SandboxSessionTest {
    private fun scenario(): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))
        val map = (ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx")) as ContentResult.Success).value
        val unit = (ContentLoader.loadUnitVariant(root.resolve("units/weaponless_tank.ini")) as ContentResult.Success).value
        return Scenario("p001-windows", map, mapOf("tank" to unit),
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0),
                UnitSpawn("u2", "p2", "tank", Position(510.0, 310.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
    }

    @Test fun `click selects exactly one unit without changing gameplay state`() {
        val session = SandboxSession(scenario())
        val before = session.observation.units
        session.select(Position(110.0, 110.0))
        assertEquals("u1", session.selectedId)
        assertEquals(before, session.observation.units)
        session.select(Position(510.0, 310.0))
        assertEquals("u2", session.selectedId)
        session.select(Position(40.0, 40.0))
        assertNull(session.selectedId)
        session.close()
    }

    @Test fun `selected unit moves through core and stop cancels old target`() {
        SandboxSession(scenario()).use { session ->
            session.select(Position(110.0, 110.0))
            assertIs<ActionResult.Accepted>(session.move(Position(250.0, 110.0)))
            repeat(45) { session.tick() }
            assertTrue(session.observation.units.first().position.x > 130.0)
            assertIs<ActionResult.Accepted>(session.stop())
            repeat(30) { session.tick() }
            val stopped = session.observation.units.first()
            assertNull(stopped.moveTarget)
            assertTrue(stopped.pathPoints.isEmpty())
            assertEquals(MovementState.IDLE, stopped.movementState)
            repeat(20) { session.tick() }
            assertEquals(stopped, session.observation.units.first())
        }
    }

    @Test fun `foreign selection sends move and stop to core which rejects without state changes`() {
        SandboxSession(scenario()).use { session ->
            session.select(Position(510.0, 310.0))
            val before = session.observation.units
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.move(Position(550.0, 310.0))).error.code)
            assertEquals(before, session.observation.units)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.stop()).error.code)
            assertEquals(before, session.observation.units)
        }
    }

    @Test fun `unreachable click cancels a moving unit and never restores the previous goal`() {
        SandboxSession(scenario()).use { session ->
            session.select(Position(110.0, 110.0))
            session.move(Position(250.0, 110.0))
            repeat(25) { session.tick() }
            val result = assertIs<ActionResult.Accepted>(session.move(Position(450.0, 110.0)))
            assertEquals(listOf("movement-cancelled", "path-unreachable"), result.events.map { it.kind })
            repeat(30) { session.tick() }
            val stopped = session.observation.units.first()
            assertNull(stopped.moveTarget)
            assertTrue(stopped.pathPoints.isEmpty())
            assertEquals(0.0, stopped.speed)
            assertTrue(stopped.position.x < 250.0)
            repeat(30) { session.tick() }
            assertEquals(stopped, session.observation.units.first())
        }
    }

    @Test fun `exit and a fresh session reset all presentation and gameplay state`() {
        val first = SandboxSession(scenario())
        first.select(Position(110.0, 110.0))
        first.move(Position(250.0, 110.0))
        repeat(15) { first.tick() }
        first.close(); first.close()
        assertNull(first.selectedId)
        assertFalse(first.isOpen)
        assertNull(first.move(Position(200.0, 110.0)))
        SandboxSession(scenario()).use { reopened ->
            assertNull(reopened.selectedId)
            assertEquals(0.0, reopened.observation.simulationTime)
            assertTrue(reopened.observation.events.isEmpty())
            assertTrue(reopened.observation.actionResults.isEmpty())
            val unit = reopened.observation.units.first()
            assertEquals(Position(110.0, 110.0), unit.position)
            assertNull(unit.moveTarget)
            assertTrue(unit.pathPoints.isEmpty())
        }
    }

    @Test fun `diagnostic failure cannot prevent closing the world and clearing selection`() {
        var fail = false
        val session = SandboxSession(scenario(), ClientEvidence { _, values ->
            if (fail && values["phase"] == "before-close") error("Injected evidence failure")
        })
        session.select(Position(110.0, 110.0))
        fail = true
        assertFailsWith<IllegalStateException> { session.close() }
        assertFalse(session.isOpen)
        assertNull(session.selectedId)
        assertNull(session.move(Position(200.0, 110.0)))
        session.close()
    }
}
