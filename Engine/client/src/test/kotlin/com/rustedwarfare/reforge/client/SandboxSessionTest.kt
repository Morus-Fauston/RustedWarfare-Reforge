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

    @Test fun command_panel_reports_the_before_and_after_credits_from_the_offline_host() {
        val scene = scenario().copy(economyContract = EconomyContract(mapOf("p1" to 100L, "p2" to 20L)))
        val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(scene, ClientEvidence { kind, fields -> records += kind to fields },
            SandboxOptions(deploymentEnabled = true)).use { session ->
            assertEquals(SandboxCommandStatus.QUEUED, session.submitSandboxCommand("/credits p1 +17").status)
            assertEquals(100L, session.observation.balance)
            session.tick()
            assertEquals(117L, session.observation.balance)
            assertTrue(session.sandboxCommandFeedback.contains("p1"))
            assertTrue(session.sandboxCommandFeedback.contains("100"))
            assertTrue(session.sandboxCommandFeedback.contains("117"))
            val creditRecords = records.filter { it.first == "sandbox-command" }.map { it.second }
            assertEquals(listOf("QUEUED", "APPLIED"), creditRecords.map { it["status"] })
            assertEquals(mapOf("player" to "p1", "amount" to "17", "before" to "100", "after" to "117"),
                creditRecords.last()["credits"])
            assertEquals(SandboxCommandStatus.QUEUED, session.submitSandboxCommand("/credits p1 -118").status)
            session.tick()
            assertEquals(117L, session.observation.balance)
            assertTrue(session.sandboxCommandFeedback.contains("insufficient-funds"))
        }
    }

    @Test fun command_panel_replays_foreign_unit_health_and_destruction_with_host_receipts() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val tank = assertIs<ContentResult.Success<com.rustedwarfare.reforge.core.content.UnitDefinition>>(
            ContentLoader.loadCombatUnit(root.resolve("production-combat/production/units/tank.ini"))).value
        val base = scenario()
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (tank.id to tank),
            units = base.units.map { if (it.unitId == "u2") it.copy(typeId = tank.id) else it },
            combatContract = CombatContract(12345, -1, mapOf("u1" to 8L, "u2" to 9L), 10L))
        val full = requireNotNull(tank.maximumHealth)
        val adjusted = full - 2.5
        val outcomes = mutableListOf<List<SandboxUnitChange>>()
        repeat(2) {
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { kind, fields -> records += kind to fields },
                SandboxOptions(deploymentEnabled = true)).use { session ->
                val before = session.observation.units.single { it.unitId == "u2" }
                assertEquals(full, before.health)
                val changes = mutableListOf<SandboxUnitChange>()
                assertEquals(SandboxCommandStatus.QUEUED, session.submitSandboxCommand("/health u2 -2.5").status)
                session.tick()
                changes += assertNotNull(session.lastSandboxReceipt?.unitChange)
                assertEquals(adjusted, session.observation.units.single { it.unitId == "u2" }.health)
                assertEquals("Health for u2 $full to $adjusted", session.sandboxCommandFeedback)
                assertEquals(SandboxCommandStatus.QUEUED, session.submitSandboxCommand("/destroy u2").status)
                session.tick()
                changes += assertNotNull(session.lastSandboxReceipt?.unitChange)
                assertTrue(session.observation.units.none { it.unitId == "u2" && it.alive })
                assertEquals("Destroyed u2", session.sandboxCommandFeedback)
                val applied = records.filter { it.first == "sandbox-command" && it.second["status"] == "APPLIED" }
                assertEquals(mapOf("operation" to "destroy", "unitId" to "u2", "healthBefore" to adjusted,
                    "healthAfter" to 0.0, "destroyed" to true), applied.last().second["unitControl"])
                outcomes += changes
            }
        }
        val expected = listOf(SandboxUnitChange("u2", full, adjusted, false), SandboxUnitChange("u2", adjusted, 0.0, true))
        assertEquals(listOf(expected, expected), outcomes)
    }

    @Test fun explicit_deployment_session_uses_the_shared_next_step_host_and_records_command_receipts() {
        val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        val ordinary = SandboxSession(scenario())
        val session = SandboxSession(scenario(), ClientEvidence { kind, fields -> records += kind to fields },
            SandboxOptions(deploymentEnabled = true), generation = 9L)
        try {
            assertEquals("deployment-disabled", ordinary.submitSandboxCommand("/help").code)
            val before = session.observation.units
            val queued = session.submitSandboxCommand("/spawn tank 190 110 p1 heading=90")
            assertEquals(SandboxCommandStatus.QUEUED, queued.status)
            assertEquals(before, session.observation.units)
            session.tick()
            val applied = assertNotNull(session.lastSandboxReceipt)
            assertEquals(SandboxCommandStatus.APPLIED, applied.status)
            assertEquals(9L, applied.generation)
            assertEquals(0L, applied.stepIndex)
            assertEquals(3, session.observation.units.size)
            assertEquals("tank", session.observation.units.single { it.unitId == applied.unitId }.typeId)
            val commandRecords = records.filter { it.first == "sandbox-command" }.map { it.second }
            assertEquals(listOf("QUEUED", "APPLIED"), commandRecords.map { it["status"] })
            assertEquals(listOf(1L, 1L), commandRecords.map { it["requestSequence"] })
            assertTrue(commandRecords.all { it["generation"] == 9L && it["worldId"] is String })
            assertEquals(SandboxCommandStatus.HELP, session.submitSandboxCommand("/help").status)
            assertTrue(session.sandboxCommandFeedback.contains("/spawn"))
            session.close()
            assertEquals("closed", session.submitSandboxCommand("/help").code)
        } finally { ordinary.close(); session.close() }
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

    @Test fun `unreachable replacement reaches a legal stop and reports the new goal`() {
        SandboxSession(scenario()).use { session ->
            session.select(Position(110.0, 110.0))
            session.move(Position(250.0, 110.0))
            repeat(25) { session.tick() }
            val result = assertIs<ActionResult.Accepted>(session.move(Position(450.0, 110.0)))
            assertTrue(result.events.isEmpty())
            val events = mutableListOf<String>()
            for (tick in 0..12000) {
                session.tick()
                events += session.observation.events.map { it.kind }
                if (session.observation.units.first().moveArrival != null) break
            }
            assertTrue(events.none { it in setOf("movement-cancelled", "path-unreachable") })
            val stopped = session.observation.units.first()
            val arrival = assertNotNull(stopped.moveArrival)
            assertEquals(Position(450.0, 110.0), arrival.originalTarget)
            assertEquals(stopped.position, arrival.stoppedAt)
            assertTrue(arrival.distance > 0.0)
            assertNull(stopped.moveTarget)
            assertTrue(stopped.pathPoints.isEmpty())
            assertEquals(0.0, stopped.speed)
            assertTrue(stopped.position.x > 250.0)
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
