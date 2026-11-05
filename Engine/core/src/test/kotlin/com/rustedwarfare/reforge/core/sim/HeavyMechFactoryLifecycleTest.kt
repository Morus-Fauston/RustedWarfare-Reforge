package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechFactoryLifecycleTest {
    private fun SimulationContext.closed(block: (SimulationContext) -> Unit) {
        try { block(this) } finally { close() }
    }
    /** Explicit owned ordinary-shot instrument; the factory definitions and rates remain unchanged. */
    private fun armedScene(damage: Double): Scenario {
        val base = HeavyMechFactoryScene.scenario()
        val source = HeavyMechTestScene.declared()
        val shooter = UnitDefinition("instrument", source.className, 1.0, 1.0, 1, source.imagePath, 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            combat = CombatProfile(10000.0, 300.0, 1000000.0, 0.0, 360.0, 0.0,
                ProjectileProfile(damage, 70.0, 7.0), null, null, 1.0,
                recoil = WeaponRecoil(0.0, 4.0, 7.0)))
        return base.copy(unitDefinitions = base.unitDefinitions + (shooter.id to shooter),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("instrument", "p2", shooter.id, Position(450.0, 150.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L, "instrument" to 9L), 10L))
    }

    @Test fun half_health_upgrade_preserves_identity_and_converts_the_actual_damage_ratio() {
        HeavyMechTestScene.open(armedScene(900.0)).closed { context ->
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("damage", "p2", "instrument", "factory", context.world)))
            var q = 0L
            while (context.observe().units.single { it.unitId == "factory" }.health == 1800.0 && q < 80L)
                context.advance(Step(q++, 1.0, "sim-tick"))
            val before = context.observe().units.single { it.unitId == "factory" }
            assertEquals(900.0, before.health, "public weapons=${context.observe().combat?.weapons}; shots=${context.observe().projectiles}")
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world)))
            for (step in q..q + 2600L) context.advance(Step(step, 1.0, "sim-tick"))
            val after = context.observe().units.single { it.unitId == before.unitId }
            assertEquals("mechFactoryT2", after.typeId)
            assertEquals(1550.0, after.health)
            assertEquals(3100.0, after.maxHealth)
            assertEquals(before.position, after.position)
            assertEquals(before.ownerPlayerId, after.ownerPlayerId)
            assertEquals(12000L, context.observePlayer(context.world, "p1").balance)
        }
    }

    @Test fun factory_death_refunds_all_mixed_pending_work_once_and_never_creates_a_tail_product() {
        HeavyMechTestScene.open(armedScene(2000.0)).closed { context ->
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("builder", "p1", "factory", "builder", "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("mech", "p1", "factory", "mechGun", "b".repeat(32), context.world)))
            assertEquals(10900L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("destroy", "p2", "instrument", "factory", context.world)))
            var q = 0L
            while (context.observe().units.single { it.unitId == "factory" }.alive && q < 80L)
                context.advance(Step(q++, 1.0, "sim-tick"))
            val ended = context.observe()
            assertFalse(ended.units.single { it.unitId == "factory" }.alive,
                "public weapons=${ended.combat?.weapons}; shots=${ended.projectiles}")
            assertEquals(16000L, context.observePlayer(context.world, "p1").balance)
            assertTrue(ended.economy!!.queuedProduction.values.all { it.isEmpty() })
            assertTrue(ended.upgrades!!.queues.values.all { it.isEmpty() })
            assertIs<ActionResult.Rejected>(context.submit(CancelUpgradeAction("late-cancel", "p1", "factory", "upgrade_t2", context.world)))
            for (step in q..q + 2700L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(16000L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().units.none { it.unitId.startsWith("produced:") })
            assertEquals("mechFactory", context.observe().units.single { it.unitId == "factory" }.typeId)
        }
    }

    @Test fun insufficient_upgrade_is_atomic_and_cancelling_a_partly_worked_head_releases_only_its_price() {
        val base = HeavyMechFactoryScene.scenario()
        HeavyMechTestScene.open(base.copy(economyContract = EconomyContract(mapOf("p1" to 3999L, "p2" to 0L)))).closed { context ->
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(context.submit(
                UpgradeAction("poor", "p1", "factory", "upgrade_t2", context.world))).error.code)
            assertEquals(3999L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().upgrades!!.queues.getValue("factory").isEmpty())
        }
        HeavyMechTestScene.open(base).closed { context ->
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("mech", "p1", "factory", "mechGun", "c".repeat(32), context.world)))
            val snapshot = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(.0004f, snapshot.upgrades!!.queues.getValue("factory").single().progress)
            assertEquals(0f, snapshot.economy!!.queuedProduction.getValue("factory").single().progress)
            assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel", "p1", "factory", "upgrade_t2", context.world)))
            assertEquals(15400L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Rejected>(context.submit(CancelUpgradeAction("again", "p1", "factory", "upgrade_t2", context.world)))
            val next = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(.0012f, next.economy!!.queuedProduction.getValue("factory").single().progress)
            assertEquals(.0004f, snapshot.upgrades.queues.getValue("factory").single().progress)
            assertEquals(15400L, context.observePlayer(context.world, "p1").balance)
        }
    }
}
