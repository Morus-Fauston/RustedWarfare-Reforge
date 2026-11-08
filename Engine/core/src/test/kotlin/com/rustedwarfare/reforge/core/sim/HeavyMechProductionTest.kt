package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class HeavyMechProductionTest {
    @Test fun a_builder_builds_t1_upgrades_t2_and_produces_a_heavy_that_exits_and_fights_ground_and_real_air() {
        for (format in listOf("ini", "json", "hjson")) {
            val base = HeavyMechFactoryScene.scenario(format)
            val helicopter = HelicopterCombatScene.unit()
            val scene = base.copy(unitDefinitions = base.unitDefinitions + ("helicopter" to helicopter),
                units = listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0),
                    UnitSpawn("ground", "p2", "mechGun", Position(650.0, 250.0), 270.0),
                    UnitSpawn("air", "p2", "helicopter", Position(650.0, 450.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "ground" to 9L, "air" to 10L), 11L))
            val context = HeavyMechTestScene.open(scene)
            try {
                var q = 0L
                val factory = "constructed:p1:${"a".repeat(32)}"
                val heavy = "produced:p1:${"b".repeat(32)}"
                val firedAt = mutableSetOf<String>()
                fun tick(): Observation = context.advance(Step(q++, 1.0, "sim-tick")).also { observed ->
                    for (event in observed.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == heavy }) {
                        val shot = event.projectile
                        if (shot.targetUnitId == "ground" && shot.sourceWeaponName == "smallLaser") firedAt += "ground"
                        if (shot.targetUnitId == "air" && shot.sourceWeaponName in setOf("2", "3")) firedAt += "air"
                    }
                }
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "mechFactory", Position(270.0, 150.0), "a".repeat(32), context.world)))
                tick()
                assertEquals(15000L, context.observePlayer(context.world, "p1").balance)
                assertEquals("factory-under-construction", assertIs<ActionResult.Rejected>(context.submit(
                    UpgradeAction("unfinished", "p1", factory, "upgrade_t2", context.world))).error.code)
                assertEquals(15000L, context.observePlayer(context.world, "p1").balance)
                val constructionLimit = q + 1500
                while (context.observe().units.single { it.unitId == factory }.constructionProgress < 1f && q < constructionLimit) tick()
                assertEquals(1f, context.observe().units.single { it.unitId == factory }.constructionProgress)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", factory, "upgrade_t2", context.world)))
                assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
                val upgradeLimit = q + 2700
                while (context.observe().units.single { it.unitId == factory }.typeId != "mechFactoryT2" && q < upgradeLimit) tick()
                assertEquals("mechFactoryT2", context.observe().units.single { it.unitId == factory }.typeId)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce", "p1", factory, "mechHeavyMissile", "b".repeat(32), context.world)))
                assertEquals(2500L, context.observePlayer(context.world, "p1").balance)
                val productionLimit = q + 1900
                while (context.observe().units.none { it.unitId == heavy } && q < productionLimit) tick()
                val born = context.observe().units.single { it.unitId == heavy }
                assertEquals(ShieldObservation(500f, 500f), born.shield,
                    "Normal paid production uses the same full shield initialization as initial spawns")
                assertEquals(Position(277.0, 149.0), born.position)
                assertEquals(4, assertNotNull(born.mechPose).legs.size)
                assertNull(born.flightPose)
                repeat(240) { tick() }
                assertTrue(context.observe().units.single { it.unitId == heavy }.position.y > 210.0)
                assertEquals(0f, context.observe().production!!.units.single { it.unitId == heavy }.birthRemaining)
                assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", heavy, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", heavy, Position(500.0, 350.0), context.world)))
                repeat(20) { tick() }
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", heavy, context.world)))
                repeat(30) { tick() }
                assertEquals(0.0, context.observe().units.single { it.unitId == heavy }.speed)
                for (target in listOf("ground", "air")) {
                    // Multi-targeting may already have killed AIR during the explicit ground attack.
                    if (context.observe().units.single { it.unitId == target }.alive)
                        assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-$target", "p1", heavy, target, context.world)))
                    val limit = q + 3000
                    while (context.observe().units.single { it.unitId == target }.alive && q < limit) {
                        tick()
                    }
                    assertTrue(target in firedAt, "$format $target must receive its corresponding weapon")
                    assertFalse(context.observe().units.single { it.unitId == target }.alive, "$format $target")
                }
                assertTrue(context.observe().units.single { it.unitId == heavy }.alive)
                assertEquals(2500L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }
}
