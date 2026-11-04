package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class HeavyMechTransactionTest {
    @Test fun a_later_real_projectile_overflow_rolls_back_four_leg_gait_sliding_and_authorized_history_before_retry() {
        val base = GroundTestContent.scene(5f)
        val artillery = base.unitDefinitions.getValue("c_turret_t1_artillery")
        val bad = UpgradeTestContent.variant(artillery, id = "high-flight", combat = artillery.combat!!.let { it.copy(
            turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0,
            projectile = it.projectile.copy(ground = it.projectile.ground!!.copy(initialVerticalSpeed = Float.MAX_VALUE))) })
        val dummy = base.unitDefinitions.getValue("dummy")
        val heavy = HeavyMechTestScene.declared()
        val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf(bad.id to bad, heavy.id to heavy,
                dummy.id to UpgradeTestContent.variant(dummy, radius = 1.0)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + listOf(UnitSpawn("zz", "p2", bad.id, Position(430.0, 150.0), 270.0, Float.MAX_VALUE.toDouble()),
                UnitSpawn("c", "p1", "dummy", Position(430.0, 270.0), 0.0),
                UnitSpawn("heavy", "p1", heavy.id, Position(110.0, 350.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L, "zz" to 13L, "c" to 14L, "heavy" to 15L), 16L),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        try {
            for (context in contexts) {
                context.enableMotionDiagnostics(context.world)
                context.enablePathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("slide", "p1", "heavy", Position(510.0, 350.0), context.world)))
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("target", "p1", "t", "z", context.world)))
            }
            val commandPaths = contexts.map { context -> context.drainPathDiagnostics(context.world).map {
                it.copy(inputPreparationNanos = 0, searchNanos = 0)
            } }
            assertEquals(commandPaths[1], commandPaths[0], "Immediate Move command paths precede the candidate step")
            val failed = contexts[0]
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            val knowledge = failed.knowledgeSnapshot(failed.world, "p1")
            val vision = failed.visionSnapshot(failed.world, "p1")
            repeat(2) {
                val error = assertFailsWith<IllegalArgumentException> { failed.advance(Step(0, 1.0, "sim-tick")) }
                assertTrue(error.message.orEmpty().contains("Projectile"), error.message)
                assertSame(before, failed.observe())
                assertSame(player, failed.observePlayer(failed.world, "p1"))
                assertSame(knowledge, failed.knowledgeSnapshot(failed.world, "p1"))
                assertSame(vision, failed.visionSnapshot(failed.world, "p1"))
                assertTrue(failed.drainMotionDiagnostics(failed.world).isEmpty())
                assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty())
            }
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(AttackAction("correct", "p2", "zz", "c", context.world)))
            for (q in 0L..6L) {
                val expected = contexts[1].advance(Step(q, 1.0, "sim-tick"))
                val actual = failed.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units)
                assertEquals(expected.combat, actual.combat)
                assertEquals(expected.projectiles, actual.projectiles)
                assertEquals(expected.projectileEvents, actual.projectileEvents)
                assertEquals(expected.events, actual.events)
                assertEquals(expected.actionResults, actual.actionResults)
            }
            val advanced = failed.observe().units.single { it.unitId == "heavy" }
            assertTrue(advanced.position.x > 110.0)
            assertTrue(advanced.mechPose!!.legs.any { it.height > .1 })
            assertEquals(before.units.single { it.unitId == "heavy" }.position, Position(110.0, 350.0))
            assertEquals(contexts[1].drainMotionDiagnostics(contexts[1].world), failed.drainMotionDiagnostics(failed.world))
            assertEquals(contexts[1].drainPathDiagnostics(contexts[1].world), failed.drainPathDiagnostics(failed.world))
        } finally { contexts.forEach { it.close() } }
    }
}
