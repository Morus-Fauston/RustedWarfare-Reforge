package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class ProductionAddressTest {
    private val nonce = "0".repeat(32)
    private fun values(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId,
        view.scenarioId, view.playerId, view.stepIndex, view.simulationTime, view.units, view.projectiles,
        view.projectileEvents, view.events, view.actionResults, view.balance, view.vision?.worldId,
        view.vision?.playerId, view.vision?.epoch, view.vision?.basicCells, view.vision?.effectiveCells,
        view.knowledge?.worldId, view.knowledge?.playerId, view.knowledge?.width, view.knowledge?.height,
        view.knowledge?.revision, view.knowledge?.cells)

    @Test
    fun static_catalog_not_hidden_factory_presence_selects_safe_namespace_for_all_existing_actor_commands() {
        val base = BuildingTestContent.scenario().let { input -> input.copy(
            unitDefinitions = input.unitDefinitions.mapValues { (_, unit) -> BuildingTestContent.variant(unit, sight = 1) },
            units = input.units.map { if (it.unitId == "f") it.copy(ownerPlayerId = "p2") else it },
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)) }
        val absent = base.copy(units = base.units.filter { it.unitId != "f" },
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L)))
        val contexts = listOf(base, absent).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
        try {
            for (kind in listOf("select", "move", "stop", "attack")) {
                val results = contexts.map { context ->
                    val id = "produced:p2:$nonce"
                    val action = when (kind) {
                        "select" -> SelectAction("probe-$kind", "p1", id, context.world)
                        "move" -> MoveAction("probe-$kind", "p1", id, Position(110.0, 110.0), context.world)
                        "stop" -> StopAction("probe-$kind", "p1", id, context.world)
                        else -> AttackAction("probe-$kind", "p1", id, "a", context.world)
                    }
                    assertIs<ActionResult.Rejected>(context.submit(action))
                }
                assertEquals(results[0], results[1])
                assertEquals(SimulationError("unit-unavailable", "Unit is not available"), results[0].error)
                assertEquals(values(contexts[0].observePlayer(contexts[0].world, "p1")),
                    values(contexts[1].observePlayer(contexts[1].world, "p1")))
            }
            for (context in contexts) {
                assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(context.submit(
                    AttackAction("probe-target", "p1", "a", "produced:p2:$nonce", context.world))).error.code)
                context.advance(Step(0, 1.0, "sim-tick"))
            }
            assertEquals(values(contexts[0].observePlayer(contexts[0].world, "p1")),
                values(contexts[1].observePlayer(contexts[1].world, "p1")))
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun malformed_unknown_and_own_unborn_addresses_keep_safe_errors_without_skipping_action_registration() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(BuildingTestContent.scenario())).context
        try {
            val addresses = listOf("produced:", "produced:p1:0", "produced:p1:${"A".repeat(32)}", "produced:p9:$nonce", "produced:p1:$nonce")
            addresses.forEachIndexed { index, id ->
                val result = assertIs<ActionResult.Rejected>(context.submit(StopAction("bad-$index", "p1", id, context.world)))
                assertEquals(SimulationError("unit-unavailable", "Unit is not available"), result.error)
                assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(context.submit(
                    SelectAction("bad-$index", "p1", "a", context.world))).error.code)
            }
        } finally { context.close() }
    }

    @Test
    fun owner_namespace_with_colon_checks_local_control_before_absent_object_lookup() {
        val base = BuildingTestContent.scenario()
        val input = base.copy(players = base.players.map { if (it.playerId == "p2") it.copy(playerId = "p:2") else it },
            units = base.units.map { if (it.ownerPlayerId == "p2") it.copy(ownerPlayerId = "p:2") else it },
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p:2" to 350L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertEquals("actor-not-controlled", assertIs<ActionResult.Rejected>(context.submit(
                StopAction("none-own", "p:2", "produced:p:2:$nonce", context.world))).error.code)
            assertEquals("unit-unavailable", assertIs<ActionResult.Rejected>(context.submit(
                StopAction("none-foreign", "p:2", "produced:p1:$nonce", context.world))).error.code)
        } finally { context.close() }
    }

    @Test
    fun production_catalog_reserves_initial_public_namespace_even_if_no_factory_is_spawned() {
        val base = BuildingTestContent.scenario()
        val id = "produced:p1:$nonce"
        val input = base.copy(units = base.units.filter { it.unitId != "f" }.map { if (it.unitId == "a") it.copy(unitId = id) else it },
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf(id to 8L, "b" to 9L)))
        assertEquals("reserved-unit-id", assertIs<InitResult.Rejected>(SimulationContext.initialize(input)).error.code)
    }

    @Test
    fun no_production_legacy_initial_prefix_id_keeps_old_selection_owner_and_scheduled_meaning() {
        val base = CombatTestContent.scenario()
        val id = "produced:p1:$nonce"
        val input = base.copy(units = base.units.map { if (it.unitId == "a") it.copy(unitId = id) else it },
            combatContract = base.combatContract!!.let { it.copy(unitObjectIds = it.unitObjectIds - "a" + (id to it.unitObjectIds.getValue("a"))) })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("legacy-select", "p1", id, context.world, 0)))
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(StopAction("legacy-owner", "p2", id, context.world))).error.code)
            assertTrue(context.advance(Step(0, 1.0, "sim-tick")).actionResults.any { it.actionId == "legacy-select" && it is ActionResult.Accepted })
        } finally { context.close() }
    }
}
