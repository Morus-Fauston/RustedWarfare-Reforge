package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.*

class CombatActorVisibilityTest {
    private fun input(lethal: Boolean, fog: Boolean = true): Scenario {
        val base = CombatTestContent.scenario(sight = 8)
        val tank = base.unitDefinitions.getValue("c_tank")
        val cannon = UnitDefinition("hidden-cannon", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 8,
            checkNotNull(tank.combat).let { it.copy(projectile = it.projectile.copy(directDamage = if (lethal) 250.0 else 25.0)) })
        return base.copy(unitDefinitions = base.unitDefinitions + ("hidden-cannon" to cannon),
            players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(350.0, 330.0), 270.0),
                UnitSpawn("b", "p2", "c_tank", Position(510.0, 330.0), 270.0),
                UnitSpawn("c", "p3", "hidden-cannon", Position(550.0, 210.0), 198.434948822922)),
            automaticVision = if (fog) KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false) else null,
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
    }

    private fun publicValues(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId,
        view.scenarioId, view.playerId, view.stepIndex, view.simulationTime, view.units, view.projectiles,
        view.events, view.actionResults, view.projectileEvents, view.vision?.epoch, view.vision?.basicCells, view.vision?.effectiveCells,
        view.vision?.worldId, view.vision?.playerId, view.knowledge?.worldId, view.knowledge?.playerId,
        view.knowledge?.width, view.knowledge?.height, view.knowledge?.revision, view.knowledge?.cells)

    @Test
    fun hidden_enemy_actor_liveness_does_not_change_direct_errors_player_results_or_revisions() {
        val contexts = listOf(false, true).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(input(it))).context }
        try {
            for (context in contexts) {
                assertTrue(context.observePlayer(context.world, "p1").units.any { it.unitId == "b" && it.health == 210.0 })
                assertIs<ActionResult.Accepted>(context.submit(SelectAction("queued-probe", "p1", "b", context.world, 40)))
                context.submit(MoveAction("leave", "p1", "a", Position(110.0, 330.0), context.world))
                repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "b" })
            }
            assertTrue(contexts[0].observe().units.single { it.unitId == "b" }.alive)
            assertFalse(contexts[1].observe().units.single { it.unitId == "b" }.alive)
            assertEquals(publicValues(contexts[0].observePlayer(contexts[0].world, "p1")),
                publicValues(contexts[1].observePlayer(contexts[1].world, "p1")))
            for (kind in listOf("select", "stop", "move", "attack")) {
                val results = contexts.map { context ->
                    val action = when (kind) {
                        "select" -> SelectAction("probe-$kind", "p1", "b", context.world)
                        "stop" -> StopAction("probe-$kind", "p1", "b", context.world)
                        "move" -> MoveAction("probe-$kind", "p1", "b", Position(350.0, 330.0), context.world)
                        else -> AttackAction("probe-$kind", "p1", "b", "a", context.world)
                    }
                    assertIs<ActionResult.Rejected>(context.submit(action))
                }
                assertEquals(results[0], results[1], "Hidden b alive/dead: $kind")
                assertEquals(if (kind == "select") "not-visible" else "not-owner", results.first().error.code)
                assertEquals(publicValues(contexts[0].observePlayer(contexts[0].world, "p1")),
                    publicValues(contexts[1].observePlayer(contexts[1].world, "p1")))
            }
            for (context in contexts) {
                context.advance(Step(40, 1.0, "sim-tick"))
                val result = context.observePlayer(context.world, "p1").actionResults.single { it.actionId == "queued-probe" }
                assertEquals("not-visible", assertIs<ActionResult.Rejected>(result).error.code)
                assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(context.submit(
                    SelectAction("probe-select", "p1", "b", context.world))).error.code)
            }
            assertEquals(publicValues(contexts[0].observePlayer(contexts[0].world, "p1")),
                publicValues(contexts[1].observePlayer(contexts[1].world, "p1")))
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun visible_corpse_is_still_inactive_for_select_and_unauthorized_control_still_rejects_ownership() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input(true, fog = false))).context
        try {
            repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertFalse(context.observePlayer(context.world, "p1").units.single { it.unitId == "b" }.alive)
            assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(context.submit(
                SelectAction("corpse", "p1", "b", context.world))).error.code)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                StopAction("unauthorized-corpse", "p1", "b", context.world))).error.code)
        } finally { context.close() }
    }
}
