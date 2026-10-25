package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.*

class CombatTransactionTest {
    private fun playerValues(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId,
        view.scenarioId, view.playerId, view.stepIndex, view.simulationTime, view.units, view.projectiles,
        view.projectileEvents, view.events, view.actionResults, view.vision?.worldId, view.vision?.playerId,
        view.vision?.epoch, view.vision?.basicCells, view.vision?.effectiveCells, view.knowledge?.worldId,
        view.knowledge?.playerId, view.knowledge?.width, view.knowledge?.height, view.knowledge?.revision, view.knowledge?.cells)

    @Test
    fun prior_public_projectile_history_survives_a_later_failed_frame_retry_and_close_reopen_clears_the_world() {
        val scene = input().let { it.copy(combatContract = checkNotNull(it.combatContract).copy(nextObjectId = Long.MAX_VALUE - 3)) }
        val contexts = listOf(0, 1).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        try {
            contexts.forEach { near(it); it.observePlayer(it.world, "p1") }
            val tokens = mutableSetOf<String>()
            var old: PlayerObservation? = null
            for (step in 0L..74L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(playerValues(views[0]), playerValues(views[1]))
                tokens += views[0].projectiles.map { it.token }
                tokens += views[0].projectileEvents.map { it.token }
                if (step == 0L) old = views[0]
            }
            val before = contexts[0].observePlayer(contexts[0].world, "p1")
            assertFailsWith<ArithmeticException> { contexts[0].advance(Step(75, 1.0, "sim-tick")) }
            assertSame(before, contexts[0].observePlayer(contexts[0].world, "p1"))
            contexts.forEach { correct(it); it.advance(Step(75, 1.0, "sim-tick")) }
            val retried = contexts.map { it.observePlayer(it.world, "p1") }
            assertEquals(playerValues(retried[0]), playerValues(retried[1]))
            assertTrue(retried[0].projectiles.isNotEmpty())
            assertTrue(retried[0].projectiles.all { it.token !in tokens }, "A failed candidate must not consume or recycle public aliases")
            val oldProjectiles = checkNotNull(old).projectiles.toList()
            val oldWorld = contexts[0].world
            contexts[0].close()
            assertIs<InitResult.Accepted>(contexts[0].reopen(scene))
            assertTrue(contexts[0].observePlayer(contexts[0].world, "p1").projectiles.isEmpty())
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(contexts[0].submit(
                SelectAction("world-probe", "p1", "a", oldWorld))).error.code)
            assertIs<ActionResult.Accepted>(contexts[0].submit(SelectAction("world-probe", "p1", "a", contexts[0].world)))
            contexts[0].advance(Step(0, 1.0, "sim-tick"))
            val fresh = contexts[0].observePlayer(contexts[0].world, "p1")
            assertEquals(setOf("shot-1", "shot-2"), fresh.projectiles.map { it.token }.toSet())
            assertEquals(0, checkNotNull(contexts[0].observe().combat).frameCounter)
            assertTrue(fresh.units.all { it.alive && it.health == 210.0 })
            assertEquals(oldProjectiles, checkNotNull(old).projectiles)
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun a_real_pursuit_search_between_two_shooters_is_not_published_when_the_second_shot_overflows() {
        val base = input()
        val scene = base.copy(units = base.units + UnitSpawn("aa", "p1", "witness", Position(50.0, 50.0), 180.0),
            combatContract = checkNotNull(base.combatContract).let { it.copy(unitObjectIds = it.unitObjectIds + ("aa" to 11L)) })
        val failed = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (context in listOf(failed, control)) {
                context.enablePathDiagnostics(context.world)
                near(context)
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("pursue", "p1", "aa", "b", context.world)))
            }
            val before = failed.observe()
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty(), "A failed candidate must not publish its real pursuit search")
            correct(failed); correct(control)
            val actual = failed.advance(Step(0, 1.0, "sim-tick"))
            val expected = control.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(expected.units, actual.units)
            assertEquals(expected.projectiles, actual.projectiles)
            assertEquals(expected.projectileEvents, actual.projectileEvents)
            assertEquals(expected.combat, actual.combat)
            val diagnostics = failed.drainPathDiagnostics(failed.world)
            assertEquals(listOf("pursue"), diagnostics.map { it.actionId })
            assertEquals(1, control.drainPathDiagnostics(control.world).size)
        } finally { failed.close(); control.close() }
    }

    private fun input(): Scenario {
        val base = CombatTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = UnitDefinition("witness", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, tank.fogOfWarSightRange,
            checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        return base.copy(unitDefinitions = base.unitDefinitions + ("witness" to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(base.units.first(), base.units.last().copy(position = Position(220.0, 210.0)),
                UnitSpawn("c", "p1", "witness", Position(220.0, 330.0), 180.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), Long.MAX_VALUE - 1))
    }

    private fun near(context: SimulationContext) {
        assertIs<ActionResult.Accepted>(context.submit(AttackAction("near", "p2", "b", "a", context.world)))
        assertIs<ActionResult.Accepted>(context.submit(SelectAction("queued", "p1", "c", context.world, 0)))
    }

    private fun correct(context: SimulationContext) {
        // A legal in-range target at a right angle changes the ordinary aim gate, not hold-fire policy.
        assertIs<ActionResult.Accepted>(context.submit(AttackAction("side", "p2", "b", "c", context.world)))
    }

    @Test
    fun real_second_shot_overflow_rolls_back_first_shot_frame_ids_rng_commands_knowledge_and_retry() {
        val failed = assertIs<InitResult.Accepted>(SimulationContext.initialize(input())).context
        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(input())).context
        try {
            near(failed); near(control)
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            control.observe()
            control.observePlayer(control.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertEquals(-1, checkNotNull(failed.observe().combat).frameCounter)
            assertEquals(Long.MAX_VALUE - 1, checkNotNull(failed.observe().combat).nextObjectId)
            assertTrue(checkNotNull(failed.observe().combat).weapons.all { it.randomCounter == 0 })
            assertTrue(failed.observe().projectiles.isEmpty())
            correct(failed); correct(control)
            val actual = failed.advance(Step(0, 1.0, "sim-tick"))
            val expected = control.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(expected.units, actual.units)
            assertEquals(expected.events, actual.events)
            assertEquals(expected.actionResults, actual.actionResults)
            assertEquals(expected.projectiles, actual.projectiles)
            assertEquals(expected.combat, actual.combat)
            assertEquals(listOf(Long.MAX_VALUE - 1), actual.projectiles.map { it.objectId })
            assertEquals("a", actual.projectiles.single().sourceUnitId)
            assertEquals(0, checkNotNull(actual.combat).frameCounter)
            assertTrue(checkNotNull(actual.combat).objectIdsExhausted)
            assertEquals(listOf("near", "side", "queued"), actual.actionResults.map { it.actionId })
            val expectedPlayer = control.observePlayer(control.world, "p1")
            val actualPlayer = failed.observePlayer(failed.world, "p1")
            assertEquals(expectedPlayer.units, actualPlayer.units)
            assertEquals(expectedPlayer.projectiles, actualPlayer.projectiles)
            assertEquals(expectedPlayer.projectileEvents, actualPlayer.projectileEvents)
            assertEquals(expectedPlayer.events, actualPlayer.events)
            assertEquals(expectedPlayer.actionResults, actualPlayer.actionResults)
            assertEquals(expectedPlayer.sampleId, actualPlayer.sampleId)
            assertEquals(expectedPlayer.knowledge?.cells, actualPlayer.knowledge?.cells)
            // Exhaustion is a legitimate runtime terminal cursor: non-firing steps still commit.
            assertEquals(Long.MAX_VALUE, checkNotNull(failed.advance(Step(1, 1.0, "sim-tick")).combat).nextObjectId)
            assertIs<ActionResult.Rejected>(failed.submit(AttackAction("near", "p2", "b", "a", failed.world)))
        } finally { failed.close(); control.close() }
    }
}
