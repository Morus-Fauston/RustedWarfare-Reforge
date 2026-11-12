package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.*

class NautilusScoutBotTest {
    @Test fun scout_spawn_checks_the_fixed_original_default_capacity_at_each_hit() = WaterTestScene.withMap { map ->
        for ((existingOthers, expectedScouts) in listOf(1299 to 1, 1300 to 0)) {
            val base = NautilusScene.scenario(map)
            val extra = (1..existingOthers).map { index ->
                UnitSpawn("capacity-$index", "p1", "water-test", Position(200.0, 180.0), 90.0)
            }
            val scene = base.copy(units = base.units + extra,
                unitDefinitions = base.unitDefinitions + ("water-test" to WaterTestScene.unit(MovementType.LAND)),
                combatContract = CombatContract(12345, -1, mapOf("mother" to 8L) +
                    extra.mapIndexed { i, unit -> unit.unitId to (9L + i) }, 9L + extra.size))
            val context = NautilusScene.open(scene)
            try {
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("surface", "p1", "mother", "surface", context.world)))
                context.advance(Step(0, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("launch-cap", "p1", "mother", "launchScoutBot",
                    context.world, target = Position(565.0, 180.0))))
                var hits = 0
                for (step in 1L..360L) {
                    val observation = context.advance(Step(step, 1.0, "sim-tick"))
                    hits += observation.projectileEvents.count { it.kind == "impact" && it.projectile.sourceWeaponName == "botTurret" }
                }
                assertTrue(hits >= 2)
                assertEquals(expectedScouts, context.observe().units.count { it.typeId in setOf("robotCrab", "robotCrabWater") })
            } finally { context.close() }
        }
    }

    @Test fun scout_sight_projects_cells_through_the_player_view() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val scene = base.copy(units = listOf(
            UnitSpawn("scout", "p1", "robotCrab", Position(210.0, 180.0), 90.0),
            UnitSpawn("seen", "p2", "hoverTank", Position(610.0, 180.0), 90.0),
            UnitSpawn("out", "p2", "hoverTank", Position(710.0, 180.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("scout" to 1L, "seen" to 2L, "out" to 3L), 4L),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val context = NautilusScene.open(scene)
        try {
            assertEquals(setOf("scout", "seen"), context.observePlayer(context.world, "p1").units.mapTo(mutableSetOf()) { it.unitId })
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("foreign", "p2", "scout", Position(300.0, 180.0), context.world)))
        } finally { context.close() }
    }

    @Test fun scout_repairs_fight_damage_without_replenishing_energy_and_reclaims_for_zero_credits() = WaterTestScene.withMap { map ->
        val scene = NautilusScene.scenario(map).copy(units = listOf(
            UnitSpawn("worker", "p1", "robotCrab", Position(130.0, 180.0), 90.0),
            UnitSpawn("target", "p1", "robotCrab", Position(210.0, 180.0), 90.0),
            UnitSpawn("opponent", "p2", "hoverTank", Position(290.0, 180.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 1L, "target" to 2L, "opponent" to 3L), 4L))
        val context = NautilusScene.open(scene)
        try {
            fun unit(id: String) = context.observe().units.single { it.unitId == id }
            var step = 0L
            var scoutGunHit = false
            while (step <= 800 && unit("opponent").alive) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                scoutGunHit = scoutGunHit || observation.projectileEvents.any { it.kind == "impact" &&
                    it.projectile.sourceUnitId == "target" && it.projectile.sourceWeaponName == "2" && it.damage == 17.0 }
            }
            assertTrue(scoutGunHit)
            assertFalse(unit("opponent").alive)
            val damaged = unit("target")
            assertTrue(damaged.health!! in 1.0..<650.0)
            assertIs<ActionResult.Accepted>(context.submit(RepairUnitAction("repair", "p1", "worker", "target", context.world)))
            val repairDeadline = step + 120
            while (step <= repairDeadline && unit("target").health!! <= damaged.health)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(unit("target").health!! > damaged.health)
            assertTrue(unit("target").energy!!.current < damaged.energy!!.current)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("reclaim", "p1", "worker", "target", context.world)))
            val reclaimDeadline = step + 480
            while (step <= reclaimDeadline && context.observe().units.any { it.unitId == "target" })
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(context.observe().units.none { it.unitId == "target" })
            assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun target_near_the_world_edge_keeps_its_real_impact_without_aborting_the_world() = WaterTestScene.withMap { map ->
        val context = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("surface", "p1", "mother", "surface", context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("edge", "p1", "mother", "launchScoutBot", context.world,
                target = Position(799.0, 180.0))))
            val hits = linkedSetOf<Position>()
            for (step in 1L..360L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                hits += observation.projectileEvents.filter { it.kind == "impact" }.map { it.projectile.position }
            }
            val scouts = context.observe().units.filter { it.typeId == "robotCrabWater" }
            assertTrue(scouts.isNotEmpty())
            assertTrue(scouts.all { it.position in hits })
            val first = scouts.first()
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("edge-return", "p1", first.unitId, Position(730.0, 180.0), context.world)))
            for (step in 361L..530L) context.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single { it.unitId == first.unitId }.position.x < 780.0)
        } finally { context.close() }
    }

    @Test fun pure_split_expiry_starts_the_fast_turn_stage_without_creating_a_scout() = WaterTestScene.withMap { map ->
        val context = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("surface", "p1", "mother", "surface", context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("far", "p1", "mother", "launchScoutBot", context.world,
                target = Position(40.0, 180.0))))
            var fastBirths = emptyList<ProjectileEventObservation>()
            var step = 1L
            while (step <= 240 && fastBirths.isEmpty()) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                fastBirths = observation.projectileEvents.filter { it.kind == "born" && it.projectile.remainingLife == 150.0 }
                if (fastBirths.isNotEmpty()) {
                    val expired = observation.projectileEvents.filter { it.kind == "expired" && it.projectile.age == 90f }
                    assertEquals(2, expired.size)
                    assertEquals(expired.map { it.projectile.position }, fastBirths.map { it.projectile.position })
                    assertEquals(1, observation.units.size)
                    assertFalse(observation.projectileEvents.any { it.kind == "impact" })
                }
            }
            assertEquals(2, fastBirths.size)
        } finally { context.close() }
    }

    @Test fun a_normal_payload_hit_registers_a_controllable_scout_with_its_own_lifetime() = WaterTestScene.withMap { map ->
        val context = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("surface", "p1", "mother", "surface", context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            val target = Position(565.0, 180.0)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("launch", "p1", "mother", "launchScoutBot", context.world, target = target)))
            var step = 1L
            while (step <= 350 && context.observe().units.size == 1) context.advance(Step(step++, 1.0, "sim-tick"))
            val observation = context.observe()
            val first = assertNotNull(observation.units.firstOrNull { it.unitId != "mother" }, "Near payload never registered a scout")
            val hits = observation.projectileEvents.filter { it.kind == "impact" && it.projectile.sourceWeaponName == "botTurret" }
            assertTrue(hits.any { it.projectile.position == first.position })
            assertEquals("robotCrab", first.typeId)
            assertEquals("p1", first.ownerPlayerId)
            assertEquals(650.0, first.health)
            assertEquals(0.0, first.height)
            assertEquals(1f, first.energy!!.current)
            assertNull(first.carriedBy)
            context.advance(Step(step++, 1.0, "sim-tick"))
            val water = context.observe().units.single { it.unitId == first.unitId }
            assertEquals("robotCrabWater", water.typeId)
            assertEquals(1f - .0005f, water.energy!!.current)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("scout-to-land", "p1", first.unitId, Position(370.0, 180.0), context.world)))
            while (step <= 750 && context.observe().units.single { it.unitId == first.unitId }.typeId != "robotCrab")
                context.advance(Step(step++, 1.0, "sim-tick"))
            val land = context.observe().units.single { it.unitId == first.unitId }
            assertEquals("robotCrab", land.typeId)
            assertTrue(land.position.x < 400.0)
            assertTrue(land.energy!!.current < water.energy.current)
            while (step <= 2500 && context.observe().units.single { it.unitId == first.unitId }.alive)
                context.advance(Step(step++, 1.0, "sim-tick"))
            val ended = context.observe().units.single { it.unitId == first.unitId }
            assertFalse(ended.alive)
            assertEquals(0.0, ended.health)
            assertEquals(0f, ended.energy!!.current)
            assertTrue(context.observe().units.single { it.unitId == "mother" }.alive)
        } finally { context.close() }
    }
}
