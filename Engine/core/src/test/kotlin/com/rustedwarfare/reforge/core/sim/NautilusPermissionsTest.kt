package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusPermissionsTest {
    @Test fun foreign_commands_and_ordinary_loads_cannot_control_the_internal_manifest_or_reserve_an_owner_nonce() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val scene = base.copy(units = base.units + UnitSpawn("external", "p1", "hoverTank", Position(540.0, 180.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "external" to 9L), 10L))
        val c = NautilusScene.open(scene)
        try {
            val nonce = "a".repeat(32)
            assertIs<ActionResult.Rejected>(c.submit(ProduceAction("foreign", "p2", "mother", "hoverTank", nonce, c.world)))
            assertIs<ActionResult.Rejected>(c.submit(LoadAction("ordinary-in", "p1", "external", "mother", LoadDirection.INTO, c.world)))
            assertIs<ActionResult.Rejected>(c.submit(LoadAction("ordinary-up", "p1", "mother", "external", LoadDirection.UP, c.world)))
            assertEquals(0, c.observe().units.single { it.unitId == "mother" }.transport!!.passengerCount)
            assertNull(c.observe().units.single { it.unitId == "external" }.carriedBy)
            assertEquals(5000L, c.observePlayer(c.world, "p1").balance)
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("owner", "p1", "mother", "hoverTank", nonce, c.world)))
            assertIs<ActionResult.Rejected>(c.submit(ProduceAction("duplicate", "p1", "mother", "hoverTank", nonce, c.world)))
            repeat(250) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val child = c.observe().units.single { it.carriedBy == "mother" }
            val before = child.position
            assertIs<ActionResult.Rejected>(c.submit(MoveAction("carried-move", "p1", child.unitId, Position(600.0, 250.0), c.world)))
            assertIs<ActionResult.Rejected>(c.submit(StopAction("carried-stop", "p1", child.unitId, c.world)))
            assertIs<ActionResult.Rejected>(c.submit(UnloadAction("unload", "p1", "mother", true, c.world)))
            assertEquals(before, c.observe().units.single { it.unitId == child.unitId }.position)
            assertEquals(listOf(child.unitId), c.observe().units.single { it.unitId == "mother" }.transport!!.passengerIds)
            assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == child.unitId })
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun insufficient_funds_never_create_a_pending_item_or_consume_a_birth_identity() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val c = NautilusScene.open(base.copy(economyContract = EconomyContract(mapOf("p1" to 449L, "p2" to 0L))))
        try {
            repeat(2) { assertIs<ActionResult.Rejected>(c.submit(ProduceAction("poor-$it", "p1", "mother", "hoverTank", "b".repeat(32), c.world))) }
            assertEquals(449L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("mother").isEmpty())
            repeat(260) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(1, c.observe().units.size)
            assertEquals(0L, c.observe().production!!.lastBirthSerial)
        } finally { c.close() }
    }
}
