package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class PointDefenseLifecycleTest {
    @Test fun real_enemy_fire_to_a_third_party_qualifies_but_own_or_allied_fire_does_not_and_null_teams_are_distinct() {
        for ((sourceOwner, sourceTeam, defenseTeam, expected) in listOf(
            listOf("p2", "two", "one", true), listOf("p2", "one", "one", false),
            listOf("p1", "one", "one", false), listOf("p2", null, null, true))) {
            val base = PointDefenseTestContent.scene()
            val scene = base.copy(players = base.players.map { when (it.playerId) {
                "p1" -> it.copy(teamId = defenseTeam as String?)
                "p2" -> it.copy(teamId = sourceTeam as String?)
                else -> it
            } }, units = base.units.map { when (it.unitId) {
                "s" -> it.copy(ownerPlayerId = sourceOwner as String)
                "t" -> it.copy(ownerPlayerId = "p3")
                else -> it
            } })
            val rows = PointDefenseTestContent.trace(scene, 12)
            assertTrue(rows.first().projectileEvents.any { it.kind == "born" })
            assertEquals(expected, rows.any { row -> row.projectileEvents.any { it.kind == "deflected" } },
                "source=$sourceOwner sourceTeam=$sourceTeam defenseTeam=$defenseTeam")
        }
    }

    @Test fun a_real_fired_shot_retains_its_enemy_relationship_after_its_source_dies() {
        val base = PointDefenseTestContent.scene()
        val original = base.unitDefinitions.getValue("shooter")
        val killer = PointDefenseTestContent.variant(original, id = "killer", combat = original.combat!!.let {
            it.copy(projectile = it.projectile.copy(speed = 100.0, directDamage = 1000.0)) })
        val scene = PointDefenseTestContent.identities(base.copy(unitDefinitions = base.unitDefinitions + (killer.id to killer),
            units = base.units + UnitSpawn("z", "p1", killer.id, Position(50.0, 210.0), 90.0)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            PointDefenseTestContent.aim(c)
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill-source", "p1", "z", "s", c.world)))
            val rows = (0L..9L).map { c.advance(Step(it, 1.0, "sim-tick")) }
            assertFalse(rows.first().units.single { it.unitId == "s" }.alive)
            val event = rows.flatMap { it.projectileEvents }.single { it.kind == "deflected" }
            assertEquals("s", event.projectile.sourceUnitId)
            assertEquals(8f, event.projectile.age)
            assertTrue(rows.last().projectiles.isEmpty())
        } finally { c.close() }
    }

    @Test fun real_defense_death_stops_recharge_and_interception_and_releases_only_its_own_footprint() {
        val base = CombatTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val defense = PointDefenseTestContent.native()
        val profile = tank.combat!!
        // The native defense keeps its original HP, energy and footprint. Fast lethal shots are explicit trusted input.
        val killer = PointDefenseTestContent.variant(tank, id = "killer", combat = profile.copy(attackRange = 1000.0,
            turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0,
            projectile = profile.projectile.copy(speed = 100.0, directDamage = 1000.0)))
        val map = base.map.let { MapDefinition(it.widthTiles, it.heightTiles, it.tileWidth, it.tileHeight,
            it.tileSets, it.groundGids, BlockingGrid(it.widthTiles, it.heightTiles,
                BooleanArray(it.widthTiles * it.heightTiles) { index -> index % it.widthTiles == 24 && index / it.widthTiles == 10 })) }
        val scene = PointDefenseTestContent.identities(base.copy(map = map,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = mapOf(tank.id to tank, defense.id to defense, killer.id to killer),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("d", "p1", defense.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("d2", "p1", defense.id, Position(310.0, 90.0), 0.0),
                UnitSpawn("z", "p2", killer.id, Position(430.0, 210.0), 270.0))))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val before = c.observe()
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("before", "p1", "a", Position(430.0, 210.0), c.world)))
            assertTrue(c.observe().units.single { it.unitId == "a" }.pathPoints.any { it.y < 200.0 || it.y >= 240.0 })
            assertIs<ActionResult.Accepted>(c.submit(StopAction("wait", "p1", "a", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill-defense", "p2", "z", "d", c.world)))
            var step = 0L
            while (c.observe().units.single { it.unitId == "d" }.alive && step < 30L) c.advance(Step(step++, 1.0, "sim-tick"))
            val dead = c.observe().units.single { it.unitId == "d" }
            assertFalse(dead.alive)
            assertEquals(500.0, before.units.single { it.unitId == "d" }.health)
            assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(c.submit(StopAction("dead-stop", "p1", "d", c.world))).error.code)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("through", "p1", "a", Position(430.0, 210.0), c.world)))
            assertTrue(c.observe().units.single { it.unitId == "a" }.pathPoints.all { it.y in 200.0..<240.0 })
            repeat(40) {
                val row = c.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(dead.pointDefense, row.units.single { it.unitId == "d" }.pointDefense)
                assertTrue(row.projectileEvents.none { it.kind == "deflected" && it.interceptorUnitId == "d" })
            }
            for ((id, target) in listOf("other-building" to Position(310.0, 90.0), "wall" to Position(490.0, 210.0))) {
                val result = assertIs<ActionResult.Accepted>(c.submit(MoveAction(id, "p1", "a", target, c.world)))
                assertTrue(result.events.any { it.kind == "path-unreachable" })
            }
            assertTrue(c.observe().units.single { it.unitId == "d2" }.alive)
            assertFalse(map.blocking.isBlocked(15, 10))
            assertTrue(map.blocking.isBlocked(24, 10))
        } finally { c.close() }
    }

    @Test fun a_real_dead_target_keeps_its_relationship_and_a_same_step_intercepted_hit_reports_zero_damage_once() {
        val base = PointDefenseTestContent.scene()
        val original = base.unitDefinitions.getValue("shooter")
        val tiny = PointDefenseTestContent.variant(base.unitDefinitions.getValue("target"), radius = 1.0)
        val distance = original.combat!!.turretSize + original.combat!!.projectile.speed * 8 + 6.0 - 1.0
        val targetX = 110.0 + distance
        val killer = PointDefenseTestContent.variant(original, id = "killer", combat = original.combat!!.let {
            it.copy(projectile = it.projectile.copy(speed = 1000.0, directDamage = 1000.0)) })
        val scene = PointDefenseTestContent.identities(base.copy(
            unitDefinitions = base.unitDefinitions + mapOf(tiny.id to tiny, killer.id to killer),
            units = base.units.map { if (it.unitId == "t") it.copy(position = Position(targetX, 210.0)) else it } +
                UnitSpawn("z", "p2", killer.id, Position(targetX, 90.0), 180.0)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            PointDefenseTestContent.aim(c)
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill-target", "p2", "z", "t", c.world)))
            val rows = (0L..10L).map { c.advance(Step(it, 1.0, "sim-tick")) }
            assertFalse(rows.first().units.single { it.unitId == "t" }.alive)
            val shot = rows.first().projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "s" }.projectile.objectId
            val events = rows.flatMap { it.projectileEvents }.filter { it.projectile.objectId == shot }
            assertEquals(listOf("born", "deflected", "impact"), events.map { it.kind })
            assertEquals(0.0, events.last().damage)
            assertEquals(1, rows.sumOf { row -> row.events.count { it.kind == "unit-died" && it.unitId == "t" } })
        } finally { c.close() }
    }
}
