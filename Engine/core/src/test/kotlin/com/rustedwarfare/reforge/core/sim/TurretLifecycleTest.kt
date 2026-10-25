package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class TurretLifecycleTest {
    @Test fun a_real_paid_501_tick_product_attacks_and_kills_a_far_original_health_factory() {
        val base = TurretTestContent.scenario()
        val units = listOf(UnitSpawn("f", "p1", "landFactory", Position(310.0, 210.0), 0.0),
            UnitSpawn("g", "p2", "landFactory", Position(500.0, 310.0), 0.0))
        val scene = base.copy(units = units, combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "g" to 13L), 14L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val nonce = "3".repeat(32); val id = "produced:p1:$nonce"
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("pay", "p1", "f", "c_tank", nonce, c.world)))
            assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
            var next = 0L
            repeat(500) { c.advance(Step(next++, 1.0, "sim-tick")) }
            assertTrue(c.observe().units.none { it.unitId == id })
            c.advance(Step(next++, 1.0, "sim-tick"))
            assertEquals(210.0, c.observe().units.single { it.unitId == id }.health)
            assertEquals(14L, c.observe().production!!.units.single().objectId)
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("product-attack", "p1", id, "g", c.world)))
            var sourceShot = false; var sourceDamage = false
            while (c.observe().units.single { it.unitId == "g" }.alive && next < 5000L) {
                val frame = c.advance(Step(next++, 1.0, "sim-tick"))
                sourceShot = sourceShot || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == id && it.projectile.targetUnitId == "g" }
                sourceDamage = sourceDamage || frame.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == id && it.damage == 25.0 }
            }
            assertTrue(sourceShot); assertTrue(sourceDamage)
            assertFalse(c.observe().units.single { it.unitId == "g" }.alive)
            assertEquals(0.0, c.observe().units.single { it.unitId == "g" }.health)
            assertTrue(c.observe().units.single { it.unitId == "f" }.alive)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("dead-factory-space", "p1", id, Position(500.0, 310.0), c.world)))
            repeat(300) { c.advance(Step(next++, 1.0, "sim-tick")) }
            val entered = c.observe().units.single { it.unitId == id }.position
            assertTrue(entered.x in 480.0..<520.0 && entered.y in 280.0..<340.0)
        } finally { c.close() }
    }

    @Test fun original_700_health_tower_dies_once_stops_firing_and_releases_only_its_own_custom_cells() {
        val base = TurretTestContent.scenario()
        val staging = listOf(Position(330.0, 40.0), Position(320.0, 80.0), Position(320.0, 120.0),
            Position(330.0, 160.0), Position(300.0, 60.0), Position(300.0, 100.0))
        val units = staging.mapIndexed { i, p -> UnitSpawn("a$i", "p1", "c_tank", p, 90.0) } +
            listOf(UnitSpawn("t", "p2", "c_turret_t1", Position(500.0, 100.0), 270.0),
                UnitSpawn("g", "p2", "landFactory", Position(400.0, 300.0), 0.0))
        val map = base.map.let { m -> MapDefinition(m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight,
            m.tileSets, m.groundGids, BlockingGrid(m.widthTiles, m.heightTiles,
                BooleanArray(m.widthTiles * m.heightTiles) { it % m.widthTiles == 27 && it / m.widthTiles == 10 })) }
        val scene = base.copy(map = map, units = units,
            combatContract = CombatContract(12345, -1, units.mapIndexed { i, u -> u.unitId to i + 20L }.toMap(), 30L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (i in staging.indices) assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack-$i", "p1", "a$i", "t", c.world)))
            val old = c.observe()
            var next = 0L; var deaths = 0; var towerShot = false
            while (c.observe().units.single { it.unitId == "t" }.alive && next < 1600L) {
                val frame = c.advance(Step(next++, 1.0, "sim-tick"))
                towerShot = towerShot || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "t" }
                deaths += frame.events.count { it.kind == "unit-died" && it.unitId == "t" }
            }
            assertTrue(towerShot)
            assertFalse(c.observe().units.single { it.unitId == "t" }.alive)
            val survivor = c.observe().units.first { it.ownerPlayerId == "p1" && it.alive }.unitId
            repeat(250) {
                val frame = c.advance(Step(next++, 1.0, "sim-tick"))
                assertTrue(frame.projectileEvents.none { e -> e.kind == "born" && e.projectile.sourceUnitId == "t" })
                deaths += frame.events.count { it.kind == "unit-died" && it.unitId == "t" }
            }
            assertEquals(1, deaths); assertEquals(700.0, old.units.single { it.unitId == "t" }.health)
            assertTrue(c.observe().units.filter { it.alive }.none { it.attackTargetId == "t" })
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("through-dead-tower", "p1", survivor, Position(500.0, 100.0), c.world)))
            repeat(350) { c.advance(Step(next++, 1.0, "sim-tick")) }
            val entered = c.observe().units.single { it.unitId == survivor }.position
            assertTrue(entered.x in 480.0..<520.0 && entered.y in 80.0..<120.0, entered.toString())
            assertTrue(c.observe().units.single { it.unitId == "g" }.alive)
            for ((id, point) in listOf("live-factory" to Position(400.0, 300.0), "hard-cell" to Position(550.0, 210.0))) {
                val result = assertIs<ActionResult.Accepted>(c.submit(MoveAction(id, "p1", survivor, point, c.world)))
                assertTrue(result.events.any { it.kind == "path-unreachable" })
            }
        } finally { c.close() }
    }
}
