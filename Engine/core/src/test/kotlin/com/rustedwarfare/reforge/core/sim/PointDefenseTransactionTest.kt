package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class PointDefenseTransactionTest {
    private fun host(row: Observation): List<Any?> = listOf(row.sampleId, row.worldId, row.scenarioId,
        row.stepIndex, row.simulationTime, row.units, row.events, row.actionResults, row.projectiles,
        row.projectileEvents, row.combat, row.economy?.balances, row.economy?.queuedProduction)

    private fun player(row: PlayerObservation): List<Any?> = listOf(row.sampleId, row.worldId, row.scenarioId,
        row.playerId, row.stepIndex, row.simulationTime, row.units, row.events, row.actionResults,
        row.projectiles, row.projectileEvents, row.balance, row.factoryQueues, row.vision?.epoch,
        row.vision?.basicCells, row.vision?.effectiveCells, row.knowledge?.revision, row.knowledge?.cells)

    @Test fun real_defense_update_before_a_later_id_overflow_rolls_back_and_retries_the_same_step_exactly() {
        val base = PointDefenseTestContent.scene(health = 2.02f)
        val tiny = PointDefenseTestContent.variant(base.unitDefinitions.getValue("target"), radius = 1.0)
        val input = PointDefenseTestContent.identities(base.copy(
            unitDefinitions = base.unitDefinitions + (tiny.id to tiny),
            units = base.units.map { if (it.unitId == "s") it.copy(unitId = "a") else it } + listOf(
                UnitSpawn("q", "p1", "target", Position(110.0, 90.0), 0.0),
                UnitSpawn("r", "p1", "target", Position(510.0, 330.0), 0.0),
                UnitSpawn("z", "p2", "shooter", Position(110.0, 330.0), 90.0))))
            .let { it.copy(combatContract = it.combatContract!!.copy(nextObjectId = Long.MAX_VALUE - 1)) }
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        val failed = contexts[0]; val control = contexts[1]
        fun aim(c: SimulationContext, id: String, source: String, target: String) =
            assertIs<ActionResult.Accepted>(c.submit(AttackAction(id, "p2", source, target, c.world)))
        try {
            contexts.forEach { aim(it, "main", "a", "t"); aim(it, "side", "z", "q") }
            for (step in 0L..7L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")); it.observePlayer(it.world, "p1") }
                assertEquals(host(control.observe()), host(failed.observe()))
            }
            assertEquals(8f, failed.observe().projectiles.single().age)
            assertEquals(2.02f, failed.observe().projectiles.single().deflectionHealth)
            assertEquals(Long.MAX_VALUE, failed.observe().combat!!.nextObjectId)
            contexts.forEach { aim(it, "late-shot", "z", "r"); it.observePlayer(it.world, "p1") }
            val old = failed.observe()
            val oldPlayers = listOf("p1", "p2", "p3").map { failed.observePlayer(failed.world, it) }
            listOf("p1", "p2", "p3").forEach { control.observePlayer(control.world, it) }
            assertFailsWith<ArithmeticException> { failed.advance(Step(8, 1.0, "sim-tick")) }
            assertSame(old, failed.observe())
            oldPlayers.forEach { assertSame(it, failed.observePlayer(failed.world, it.playerId)) }
            assertEquals(PointDefenseObservation(1f, false), failed.observe().units.single { it.unitId == "d" }.pointDefense)
            assertEquals(2.02f, failed.observe().projectiles.single().deflectionHealth)
            assertTrue(failed.observe().projectileEvents.none { it.kind == "deflected" })
            contexts.forEach { aim(it, "correct-side", "z", "q") }
            for (step in 8L..33L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                assertEquals(host(control.observe()), host(failed.observe()), "Host retry and later step=$step")
                for (owner in listOf("p1", "p2", "p3")) assertEquals(
                    player(control.observePlayer(control.world, owner)), player(failed.observePlayer(failed.world, owner)))
                if (step == 8L) {
                    val deflected = failed.observe().projectileEvents.single { it.kind == "deflected" }
                    assertEquals(1.01f, deflected.projectile.deflectionHealth)
                    assertEquals(8f, deflected.projectile.age)
                    assertEquals(PointDefenseObservation(.89f, false), failed.observe().units.single { it.unitId == "d" }.pointDefense)
                }
            }
            assertEquals(2.02f, old.projectiles.single().deflectionHealth, "Published old samples remain immutable")
            assertEquals(8f, old.projectiles.single().age)
        } finally { contexts.forEach { it.close() } }
    }
}
