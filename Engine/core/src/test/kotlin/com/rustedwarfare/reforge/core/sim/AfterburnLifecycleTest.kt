package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AfterburnLifecycleTest {
    @TempDir lateinit var temporary: Path

    @Test fun entering_while_recharging_keeps_the_lock_until_the_next_energy_update_and_never_heals_or_clears_the_target() = WaterTestScene.withMap { map ->
        val scene = AfterburnScene.scenario(map, temporary, listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0)))
        val context = AaBeamScene.open(scene)
        try {
            var tick = 0L
            while (context.observe().units.single { it.unitId == "aa" }.energy!!.recharging.not() && tick < 300)
                context.advance(Step(tick++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("focus", "p1", "aa", "enemy", context.world)))
            val before = context.observe().units.single { it.unitId == "aa" }
            assertTrue(before.energy!!.recharging)
            assertTrue(before.health!! < 2500.0)
            val id = context.observe().combat!!.weapons.first { it.unitId == "aa" }.objectId
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                UnitAction("foreign", "p2", "aa", "afterBurn", context.world))).error.code)
            assertEquals("unsupported-unit-action", assertIs<ActionResult.Rejected>(context.submit(
                UnitAction("unknown", "p1", "aa", "arbitrary-script", context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            val changed = context.advance(Step(tick++, 1.0, "sim-tick"))
            val burn = changed.units.single { it.unitId == "aa" }
            assertEquals(EnergyObservation(6f, 6f, true), burn.energy)
            assertTrue(burn.health!! <= before.health!!)
            assertEquals("enemy", burn.attackTargetId)
            assertEquals(before.tasks, burn.tasks)
            assertEquals(id, changed.combat!!.weapons.first { it.unitId == "aa" }.objectId)
            val unlock = context.advance(Step(tick++, 1.0, "sim-tick"))
            assertEquals(EnergyObservation(6f, 6f, false), unlock.units.single { it.unitId == "aa" }.energy)
            assertTrue(unlock.events.none { it.kind == "projectile-fired" && it.unitId == "aa" })
            val draining = context.advance(Step(tick, 1.0, "sim-tick"))
            assertEquals(5.98f, draining.units.single { it.unitId == "aa" }.energy!!.current)
            assertTrue(draining.events.none { it.kind == "projectile-fired" && it.unitId == "aa" })
        } finally { context.close() }
    }

    @Test fun a_later_real_shot_failure_rolls_back_the_scheduled_conversion_queue_resources_clock_and_player_history() = WaterTestScene.withMap { map ->
        val base = AfterburnScene.scenario(map, temporary)
        val aa = base.unitDefinitions.getValue("aaBeamGunship")
        val shot = ProjectileProfile(1.0, 100.0, 2.0)
        val weapon = NamedWeaponDefinition("gun", "probe", shot, TurretMount(), 10000.0, 0.0, 0.0, 0.0,
            null, 1.0, canAttackFlyingUnits = true)
        val gun = UnitDefinition("gun", "OwnedProbe", 6.0, 6.0, 1, aa.imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 0.0, 0.0, shot, null, null, 1.0,
                namedWeapons = WeaponCatalog(listOf(weapon, weapon.copy(name = "gun2")))))
        val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, aa.imagePath, 1.0,
            gun.movement, emptySet(), durability = DurabilityProfile(1000.0, null))
        val input = base.copy(unitDefinitions = base.unitDefinitions + mapOf("gun" to gun, "witness" to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("aa", "p1", aa.id, Position(500.0, 200.0), 90.0),
                UnitSpawn("gun", "p2", "gun", Position(650.0, 200.0), 270.0),
                UnitSpawn("witness", "p1", "witness", Position(650.0, 300.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("aa" to 8L, "gun" to 9L, "witness" to 10L), Long.MAX_VALUE - 1))
        val contexts = List(2) { AaBeamScene.open(input) }
        try {
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                UnitAction("burn", "p1", "aa", "afterBurn", context.world, atStep = 0)))
            val failed = contexts[0]
            val before = failed.observe()
            val owner = failed.observePlayer(failed.world, "p1")
            val enemy = failed.observePlayer(failed.world, "p2")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(owner, failed.observePlayer(failed.world, "p1"))
            assertSame(enemy, failed.observePlayer(failed.world, "p2"))
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                AttackAction("correct", "p2", "gun", "witness", context.world)))
            for (tick in 0L..5L) {
                val expected = contexts[1].advance(Step(tick, 1.0, "sim-tick"))
                val actual = failed.advance(Step(tick, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units)
                assertEquals(expected.events, actual.events)
                assertEquals(expected.actionResults, actual.actionResults)
                assertEquals(expected.combat, actual.combat)
                assertEquals(expected.projectiles, actual.projectiles)
            }
            assertEquals("aaBeamGunship_afterburn", failed.observe().units.single { it.unitId == "aa" }.typeId)
        } finally { contexts.forEach { it.close() } }
    }
}
