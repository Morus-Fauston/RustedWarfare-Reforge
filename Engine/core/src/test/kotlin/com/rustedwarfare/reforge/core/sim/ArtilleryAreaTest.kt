package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class ArtilleryAreaTest {
    private val impactY = Float.fromBits(0x43160065).toDouble()
    private fun identified(base: Scenario): Scenario = base.copy(
        // Radius 1 makes the ordinary target-offset sampler's integer bound zero; geometry stays fixed.
        unitDefinitions = base.unitDefinitions + ("dummy" to UpgradeTestContent.variant(base.unitDefinitions.getValue("dummy"), radius = 1.0)),
        combatContract = CombatContract(12345, -1,
        base.units.mapIndexed { index, unit -> unit.unitId to (11L + index) }.toMap(), 100L),
        economyContract = EconomyContract(base.players.associate { it.playerId to 1750L }))

    private fun impact(scene: Scenario): Observation {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(identified(scene))).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("fixed-target", "p1", "t", "z", c.world)))
            return c.advance(Step(0, 1.0, "sim-tick"))
        } finally { c.close() }
    }

    @Test fun one_actual_impact_uses_float_center_distance_including_the_radius_edge_and_never_unit_radius() {
        val base = GroundTestContent.scene(5f)
        val extra = listOf(
            UnitSpawn("center", "p2", "dummy", Position(352.0, impactY), 0.0),
            UnitSpawn("mid", "p2", "dummy", Position(379.5, impactY), 0.0),
            UnitSpawn("edge", "p2", "dummy", Position(407.0, impactY), 0.0),
            UnitSpawn("outside", "p2", "dummy", Position(Math.nextUp(407f).toDouble(), impactY), 0.0))
        val row = impact(base.copy(units = base.units + extra))
        assertEquals(Position(352.0, impactY), row.projectileEvents.single { it.kind == "impact" }.projectile.position)
        val losses = row.units.associate { it.unitId to (10000.0 - checkNotNull(it.health)) }
        assertEquals(80.0, losses["center"])
        assertEquals(48.0, losses["mid"])
        assertEquals(8.0, losses["edge"])
        assertEquals(0.0, losses["outside"])
        assertEquals(80.0, losses["z"])
        val event = row.projectileEvents.single { it.kind == "impact" }
        assertEquals(Position(352.0, impactY), event.projectile.position)
        assertNotEquals(event.projectile.ground!!.targetPosition, event.projectile.position)
        assertNull(event.projectile.targetUnitId)
        assertNull(event.damage)
        assertEquals(1, row.projectileEvents.count { it.kind != "born" })
        assertTrue(row.projectiles.isEmpty())
    }

    @Test fun owner_nonnull_team_and_none_control_are_separate_from_the_area_enemy_test() {
        val base = GroundTestContent.scene(5f)
        for ((sourceTeam, sameTeam, expectedAllyLoss) in listOf(
            Triple("one", "one", 0.0), Triple(null, null, 80.0))) {
            val row = impact(base.copy(players = listOf(Player("p1", PlayerControl.LOCAL, sourceTeam),
                Player("p2", PlayerControl.NONE, "two"), Player("p3", PlayerControl.NONE, sameTeam)),
                units = base.units + listOf(
                    UnitSpawn("own", "p1", "dummy", Position(352.0, impactY), 0.0),
                    UnitSpawn("ally", "p3", "dummy", Position(352.0, impactY), 0.0))))
            assertEquals(10000.0, row.units.single { it.unitId == "own" }.health)
            assertEquals(10000.0 - expectedAllyLoss, row.units.single { it.unitId == "ally" }.health)
            assertEquals(9920.0, row.units.single { it.unitId == "z" }.health)
        }
    }

    @Test fun finite_ground_mapping_filters_underwater_and_high_explosions_without_treating_spawn_height_as_air() {
        data class Case(val height: Float, val vertical: Float, val underwater: Double, val ground: Double)
        for (case in listOf(Case(0f, 1.2f, 0.0, 80.0), Case(0f, -10f, 80.0, 80.0), Case(6f, 10f, 0.0, 0.0))) {
            val base = GroundTestContent.scene(5f, height = case.height, vertical = case.vertical, gravity = 0f)
            val row = impact(base.copy(units = base.units + listOf(
                UnitSpawn("underwater", "p2", "dummy", Position(352.0, impactY), 0.0, -6.0),
                UnitSpawn("boundary", "p2", "dummy", Position(352.0, impactY), 0.0, -5.0),
                UnitSpawn("high-ground", "p2", "dummy", Position(352.0, impactY), 0.0, 100.0))))
            assertEquals(1, row.projectileEvents.count { it.kind == "impact" })
            assertEquals(10000.0 - case.underwater, row.units.single { it.unitId == "underwater" }.health)
            for (id in listOf("boundary", "high-ground", "z"))
                assertEquals(10000.0 - case.ground, row.units.single { it.unitId == id }.health, id)
        }
    }

    @Test fun area_death_refunds_both_real_queues_once_and_releases_building_occupancy() {
        val base = GroundTestContent.scene(5f)
        val factory = base.unitDefinitions.getValue("landFactory")
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val fragileFactory = UpgradeTestContent.variant(factory, durability = factory.durability!!.copy(maxHp = 1.0))
        val fragileTower = UpgradeTestContent.variant(tower, combat = tower.combat!!.copy(maxHp = 1.0),
            durability = tower.durability!!.copy(maxHp = 1.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf(factory.id to fragileFactory, tower.id to fragileTower,
                "dummy" to UpgradeTestContent.variant(base.unitDefinitions.getValue("dummy"), radius = 1.0)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + listOf(UnitSpawn("f", "p2", factory.id, Position(350.0, 190.0), 0.0),
                UnitSpawn("u", "p2", tower.id, Position(390.0, 130.0), 90.0),
                UnitSpawn("a", "p1", "c_tank", Position(470.0, 250.0), 270.0)),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 3000L)),
            combatContract = CombatContract(12345, -1,
                mapOf("t" to 11L, "z" to 12L, "f" to 13L, "u" to 14L, "a" to 15L), 20L))
        val initialized = SimulationContext.initialize(scene)
        val c = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p2", "f", "c_tank", "a".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("upgrade", "p2", "u", "upgrade_gun", c.world)))
            assertEquals(1650L, c.observePlayer(c.world, "p2").balance)
            val blocked = assertIs<ActionResult.Accepted>(c.submit(MoveAction("blocked", "p1", "a", Position(390.0, 130.0), c.world)))
            assertTrue(blocked.events.any { it.kind == "path-unreachable" })
            assertIs<ActionResult.Accepted>(c.submit(StopAction("wait", "p1", "a", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("target", "p1", "t", "z", c.world)))
            val old = c.observe()
            val row = c.advance(Step(0, 1.0, "sim-tick"))
            for (id in listOf("f", "u")) {
                assertFalse(row.units.single { it.unitId == id }.alive, id)
                assertEquals(1, row.events.count { it.kind == "unit-died" && it.unitId == id })
            }
            assertEquals(3000L, row.economy!!.balances["p2"])
            assertTrue(row.economy.queuedProduction.getValue("f").isEmpty())
            assertTrue(row.upgrades!!.queues.getValue("u").isEmpty())
            assertEquals(1, row.events.count { it.kind == "production-refunded" })
            assertEquals(1, row.events.count { it.kind == "upgrade-refunded" })
            val released = assertIs<ActionResult.Accepted>(c.submit(MoveAction("released", "p1", "a", Position(390.0, 130.0), c.world)))
            assertTrue(released.events.none { it.kind == "path-unreachable" })
            assertTrue(c.observe().units.single { it.unitId == "a" }.pathPoints.isNotEmpty())
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(3000L, c.observePlayer(c.world, "p2").balance)
            assertTrue(old.units.single { it.unitId == "u" }.alive)
            assertEquals(1, old.upgrades!!.queues.getValue("u").size)
            assertEquals(1, old.economy!!.queuedProduction.getValue("f").size)
        } finally { c.close() }
    }
}
