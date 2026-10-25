package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class TurretProfileValidationTest {
    @Test
    fun a_real_body_turn_cannot_publish_overflowed_geometry_and_the_same_step_can_retry_after_stop() {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
            val base = TurretTestContent.scenario()
            val tank = base.unitDefinitions.getValue("c_tank")
            val extreme = TurretTestContent.variant(tank,
                movement = tank.movement.copy(maxTurnSpeed = 45.0, turnAcceleration = 0.0),
                combat = tank.combat!!.copy(mount = TurretMount(Double.MAX_VALUE * .9, Double.MAX_VALUE * .9)))
            val input = base.copy(unitDefinitions = mapOf(tank.id to extreme),
                units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L), 9L),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val worlds = List(2) {
                val result = SimulationContext.initialize(input)
                assertIs<InitResult.Accepted>(result, result.toString()).context
            }
            try {
                worlds.forEach { context ->
                    context.enablePathDiagnostics(context.world)
                    assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("turn", "p1", "a", Position(210.0, 110.0), context.world)))
                    assertTrue(context.observe().combat!!.weapons.single().let {
                        it.pivot!!.x.isFinite() && it.pivot.y.isFinite() &&
                            it.muzzle!!.x.isFinite() && it.muzzle.y.isFinite()
                    }, "The starting combination is finite and must be accepted")
                    context.drainPathDiagnostics(context.world)
                }
                val failed = worlds[0]
                val oldHost = failed.observe()
                val oldPlayers = worlds.map { context ->
                    listOf("p1", "p2").map { context.observePlayer(context.world, it) }
                }.first()
                val oldUnits = oldHost.units.toList()
                val attempted = runCatching { failed.advance(Step(0, 1.0, "sim-tick")) }
                if (attempted.isSuccess) {
                    val published = attempted.getOrThrow()
                    assertTrue(published.units.single().heading in 1.0..89.0,
                        "The public Move must really turn the body before exercising the candidate gate")
                    assertFalse(published.combat!!.weapons.single().pivot!!.x.isFinite(),
                        "The unfixed public candidate must contain the reproduced overflow")
                    fail("A real body turn published an infinite weapon pivot instead of rolling back")
                }
                assertIs<IllegalArgumentException>(attempted.exceptionOrNull())
                assertSame(oldHost, failed.observe())
                oldPlayers.forEach { assertSame(it, failed.observePlayer(failed.world, it.playerId)) }
                assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty())
                worlds.forEach { context ->
                    assertIs<ActionResult.Accepted>(context.submit(StopAction("correct", "p1", "a", context.world)))
                    context.advance(Step(0, 1.0, "sim-tick"))
                }
                for (step in 0L..25L) {
                    if (step > 0) worlds.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                    val views = worlds.map { it.observe() }
                    assertEquals(views[1].units, views[0].units)
                    assertEquals(views[1].combat, views[0].combat)
                    assertEquals(views[1].events, views[0].events)
                    assertEquals(views[1].actionResults, views[0].actionResults)
                    assertEquals(views[1].sampleId, views[0].sampleId)
                    for (player in listOf("p1", "p2")) {
                        val players = worlds.map { it.observePlayer(it.world, player) }
                        assertEquals(players[1].units, players[0].units)
                        assertEquals(players[1].knowledge?.cells, players[0].knowledge?.cells)
                        assertEquals(players[1].vision?.effectiveCells, players[0].vision?.effectiveCells)
                        assertEquals(players[1].sampleId, players[0].sampleId)
                    }
                }
                assertEquals(oldUnits, oldHost.units)
                assertEquals(0.0, failed.observe().units.single().heading)
            } finally { worlds.forEach { it.close() } }
        }
    }

    @Test
    fun a_finite_pivot_with_an_overflowed_muzzle_is_rejected_before_initial_observation() {
        val base = TurretTestContent.scenario()
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val extreme = TurretTestContent.variant(tower,
            combat = tower.combat!!.copy(mount = TurretMount(Double.MAX_VALUE, 0.0), turretSize = Double.MAX_VALUE))
        val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (tower.id to extreme),
            units = listOf(base.units.single().copy(heading = 45.0))))
        try {
            assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result).error.code)
        } finally { if (result is InitResult.Accepted) result.context.close() }
    }

    @Test
    fun a_large_but_actually_finite_mount_is_accepted_without_an_arbitrary_numeric_cap() {
        val base = TurretTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val extreme = TurretTestContent.variant(tank,
            combat = tank.combat!!.copy(mount = TurretMount(Double.MAX_VALUE, 0.0)))
        val input = base.copy(unitDefinitions = mapOf(tank.id to extreme),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L), 9L))
        val result = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(result, result.toString()).context
        try {
            for (step in 0L..25L) {
                val observation = if (step == 0L) context.observe() else context.advance(Step(step, 1.0, "sim-tick"))
                val geometry = observation.combat!!.weapons.single()
                assertEquals(Double.MAX_VALUE, geometry.pivot!!.x)
                assertTrue(listOf(geometry.pivot.x, geometry.pivot.y, geometry.muzzle!!.x, geometry.muzzle.y).all { it.isFinite() })
            }
        } finally { context.close() }
    }

    @Test
    fun individually_finite_mount_parts_cannot_publish_an_infinite_initial_pivot() {
        val base = TurretTestContent.scenario()
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val extreme = TurretTestContent.variant(tower,
            combat = tower.combat!!.copy(mount = TurretMount(Double.MAX_VALUE, Double.MAX_VALUE)))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (tower.id to extreme),
            units = listOf(base.units.single().copy(heading = 45.0)))
        val result = SimulationContext.initialize(input)
        try {
            assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result).error.code)
        } finally {
            if (result is InitResult.Accepted) result.context.close()
        }
    }

    @Test
    fun trusted_mount_recoil_and_idle_profiles_are_validated_before_a_world_is_published() {
        val base = TurretTestContent.scenario()
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val weapon = tower.combat!!
        val idle = weapon.idleSweep!!
        val bad = listOf(
            weapon.copy(mount = TurretMount(Double.NaN, 6.0)),
            weapon.copy(mount = TurretMount(0.0, Double.POSITIVE_INFINITY)),
            weapon.copy(recoil = WeaponRecoil(Double.NaN, 6.0, 12.0)),
            weapon.copy(recoil = WeaponRecoil(-.5, 0.0, 12.0)),
            weapon.copy(recoil = WeaponRecoil(-.5, 6.0, -1.0)),
            weapon.copy(recoil = WeaponRecoil(-.5, Double.MAX_VALUE, Double.MAX_VALUE)),
            weapon.copy(idleSweep = idle.copy(angle = Double.NaN)),
            weapon.copy(idleSweep = idle.copy(angle = 0.0)),
            weapon.copy(idleSweep = idle.copy(delay = Float.POSITIVE_INFINITY)),
            weapon.copy(idleSweep = idle.copy(delay = 0f)),
            weapon.copy(idleSweep = idle.copy(speedFactor = Float.NaN)),
            weapon.copy(idleSweep = idle.copy(speedFactor = -1f)),
            weapon.copy(idleSweep = idle.copy(randomDelayExclusive = 0)))
        for ((index, combat) in bad.withIndex()) {
            val definition = TurretTestContent.variant(tower, combat = combat)
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (tower.id to definition)))
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result, "case=$index").error.code)
        }
    }
}
