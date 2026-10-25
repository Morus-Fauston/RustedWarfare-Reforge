package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame

class EconomyContractTest {
    private fun scenario(balances: Map<String, Long> = mapOf("p1" to 1750L, "p2" to 0L)) =
        CombatTestContent.scenario().copy(economyContract = EconomyContract(balances))

    @Test
    fun initial_accounts_are_explicit_frozen_values_and_old_world_cannot_change_reopened_money() {
        val initial = linkedMapOf("p1" to 1750L, "p2" to 0L)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario(initial))).context
        try {
            val world = context.world
            val host = context.observe()
            val player = context.observePlayer(world, "p1")
            assertEquals(1750L, player.balance)
            assertEquals(0L, context.observePlayer(world, "p2").balance)
            assertEquals(mapOf("p1" to 1750L, "p2" to 0L), assertNotNull(host.economy).balances)
            initial["p1"] = 99L
            val advanced = context.advance(Step(0, 1.0, "sim-tick"))
            assertNotSame(host, advanced, "A fresh committed host sample must still use the frozen initial money")
            assertEquals(mapOf("p1" to 1750L, "p2" to 0L), assertNotNull(advanced.economy).balances)
            assertEquals(1750L, context.observePlayer(world, "p1").balance)
            assertFailsWith<UnsupportedOperationException> {
                (host.economy.balances as MutableMap<String, Long>)["p1"] = 1L
            }
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scenario(mapOf("p1" to 349L, "p2" to 12L))))
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(
                context.submit(SelectAction("reused-id", "p1", "a", world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("reused-id", "p1", "a", context.world)))
            assertEquals(349L, context.observePlayer(context.world, "p1").balance)
            assertEquals(mapOf("p1" to 349L, "p2" to 12L), assertNotNull(context.observe().economy).balances)
            assertEquals(1750L, player.balance)
            assertEquals(1750L, host.economy.balances["p1"])
        } finally { context.close() }
    }

    @Test
    fun enemy_balance_and_other_player_observation_order_do_not_change_the_callers_full_view() {
        val contexts = listOf(0L, 999L).map { assertIs<InitResult.Accepted>(
            SimulationContext.initialize(scenario(mapOf("p1" to 1750L, "p2" to it)))).context }
        try {
            assertNotEquals(contexts[0].observe().economy?.balances, contexts[1].observe().economy?.balances)
            contexts[1].observePlayer(contexts[1].world, "p2")
            for (step in 0L..2L) {
                val views = contexts.map { context ->
                    val before = context.observePlayer(context.world, "p1")
                    assertSame(before, context.observePlayer(context.world, "p1"))
                    context.advance(Step(step, 1.0, "sim-tick"))
                    context.observePlayer(context.world, "p1")
                }
                assertEquals(values(views[0]), values(views[1]))
                assertEquals(1750L, views[0].balance)
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun invalid_account_owner_sets_or_negative_amounts_reject_the_entire_initialization() {
        for (balances in listOf(mapOf("p1" to 1750L), mapOf("p1" to 1750L, "p2" to -1L),
                mapOf("p1" to 1750L, "p2" to 0L, "extra" to 0L))) {
            val result = SimulationContext.initialize(scenario(balances))
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("invalid-economy-contract", assertIs<InitResult.Rejected>(result).error.code)
        }
    }

    @Test
    fun production_metadata_cannot_silently_use_a_free_or_default_account() {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(root.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input/units/landFactory.properties"))).value
        val base = CombatTestContent.scenario()
        assertEquals("missing-economy-contract", assertIs<InitResult.Rejected>(SimulationContext.initialize(
            base.copy(unitDefinitions = base.unitDefinitions + (factory.id to factory)))).error.code)
        val legacy = assertIs<InitResult.Accepted>(SimulationContext.initialize(base)).context
        try {
            assertNull(legacy.observe().economy)
            assertNull(legacy.observePlayer(legacy.world, "p1").balance)
        } finally { legacy.close() }
    }

    private fun values(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId, view.scenarioId,
        view.playerId, view.stepIndex, view.simulationTime, view.vision, view.knowledge, view.units, view.events,
        view.actionResults, view.projectiles, view.projectileEvents, view.balance)
}
