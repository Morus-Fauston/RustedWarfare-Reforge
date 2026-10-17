package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class TouchSessionTest {
    private val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))

    @Test fun `touch ground moves the selected unit through the core without clearing selection`() {
        SandboxSession(ControlledScenario.load(root)).use { session ->
            session.tap(Position(110.0, 110.0))
            session.tap(Position(230.0, 110.0))
            assertEquals("u1", session.selectedId)
            assertEquals(Position(230.0, 110.0), session.observation.units.first().moveTarget)
            repeat(60) { session.tick() }
            assertTrue(session.observation.units.first().position.x > 130.0)
            session.stop()
            repeat(30) { session.tick() }
            assertNull(session.observation.units.first().moveTarget)
            assertEquals(MovementState.IDLE, session.observation.units.first().movementState)
        }
    }

    @Test fun `touch empty ground without selection does not submit an action`() {
        SandboxSession(ControlledScenario.load(root)).use { session ->
            session.tap(Position(200.0, 110.0))
            assertNull(session.selectedId)
            assertTrue(session.observation.actionResults.isEmpty())
            assertNull(session.observation.units.first().moveTarget)
        }
    }

    @Test fun `touch switches selection and the core rejects foreign move and stop`() {
        SandboxSession(ControlledScenario.load(root)).use { session ->
            session.tap(Position(110.0, 110.0))
            session.tap(Position(510.0, 310.0))
            val before = session.observation.units
            session.tap(Position(550.0, 310.0))
            assertEquals("u2", session.selectedId)
            assertEquals("not-owner", session.feedback)
            assertEquals(before, session.observation.units)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.stop()).error.code)
            assertEquals(before, session.observation.units)
        }
    }
}
