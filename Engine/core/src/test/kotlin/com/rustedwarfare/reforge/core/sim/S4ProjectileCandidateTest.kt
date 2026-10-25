package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class S4ProjectileCandidateTest {
    private fun scene(): Scenario {
        val base = CombatTestContent.scenario(rules = KnowledgeRules(TerrainRetention.MEMORY,
            PathInformationSource.PLAYER_KNOWLEDGE, false))
        val original = base.unitDefinitions.getValue("c_tank")
        // Legal finite positive Double content domain; 26-unit muzzle starts at the enemy,
        // without overlapping the radius11 body circles. No production fault switch.
        val tiny = UnitDefinition("tiny", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, checkNotNull(original.combat).let {
                it.copy(turretSize = 26.0, projectile = it.projectile.copy(speed = Double.MIN_VALUE)) })
        return base.copy(unitDefinitions = base.unitDefinitions + ("tiny" to tiny), units = listOf(
            UnitSpawn("a", "p1", "tiny", Position(110.0, 210.0), 90.0),
            UnitSpawn("b", "p2", "c_tank", Position(136.0, 210.0), 90.0),
            UnitSpawn("c", "p2", "c_tank", Position(110.0, 310.0), 180.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
    }

    @Test
    fun a_legal_positive_underflow_speed_at_zero_distance_commits_a_finite_hit_instead_of_a_numeric_failure() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            context.submit(SelectAction("queued", "p1", "c", context.world, 0))
            val before = context.observePlayer(context.world, "p1")
            val oldKnowledge = context.knowledgeSnapshot(context.world, "p1")
            val outcome = runCatching { context.advance(Step(0, 1.0, "sim-tick")) }
            assertTrue(outcome.isSuccess, "A legal zero-distance shot must be numerically safe: ${outcome.exceptionOrNull()}")
            val host = outcome.getOrThrow()
            assertEquals(185.0, host.units.single { it.unitId == "b" }.health)
            assertTrue(host.projectiles.isEmpty())
            assertEquals(listOf("born", "impact"), host.projectileEvents.map { it.kind })
            assertTrue(host.projectileEvents.all { event ->
                event.projectile.position == Position(136.0, 210.0) && event.projectile.heading.isFinite() && event.projectile.height.isFinite()
            })
            assertEquals(listOf("queued"), host.actionResults.map { it.actionId })
            assertEquals(0, checkNotNull(host.combat).frameCounter)
            assertEquals(12L, checkNotNull(host.combat).nextObjectId)
            val after = context.observePlayer(context.world, "p1")
            assertEquals(listOf("born", "impact"), after.projectileEvents.map { it.kind })
            assertTrue(before.units.all { it.health == 210.0 })
            assertNotSame(oldKnowledge, context.knowledgeSnapshot(context.world, "p1"))
        } finally { context.close() }
    }
}
