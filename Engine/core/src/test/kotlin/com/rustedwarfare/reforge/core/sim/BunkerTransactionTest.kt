package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class BunkerTransactionTest {
    @Test fun a_real_later_shot_overflow_rolls_back_completed_deployment_height_feet_shield_queue_and_player_history() = WaterTestScene.withMap { map ->
        val base = BunkerScene.scenario(map)
        val image = base.unitDefinitions.getValue("mechBunker").imagePath
        val movement = MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false)
        val shot = ProjectileProfile(1.0, 100.0, 2.0)
        val weapon = NamedWeaponDefinition("one", "probe", shot, TurretMount(), 10000.0, 0.0, 0.0, 0.0, null, 1.0)
        val gun = UnitDefinition("gun", "OwnedProbe", 6.0, 6.0, 1, image, 1.0, movement, emptySet(),
            combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 0.0, 0.0, shot, null, null, 1.0,
                namedWeapons = WeaponCatalog(listOf(weapon, weapon.copy(name = "two")))))
        val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, image, 1.0,
            movement, emptySet(), durability = DurabilityProfile(1000.0, null))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf("gun" to gun, "witness" to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("bunker", "p1", "mechBunker", Position(100.0, 200.0), 90.0),
                UnitSpawn("gun", "p2", "gun", Position(350.0, 200.0), 180.0),
                UnitSpawn("west", "p1", "witness", Position(300.0, 200.0), 0.0),
                UnitSpawn("south", "p1", "witness", Position(350.0, 300.0), 0.0)),
            combatContract = CombatContract(12345, -1,
                mapOf("bunker" to 8L, "gun" to 9L, "west" to 10L, "south" to 11L), Long.MAX_VALUE - 1))
        val contexts = List(2) { AaBeamScene.open(scene) }
        try {
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
                repeat(166) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("fire", "p2", "gun", "south", context.world)))
            }
            val failed = contexts.first()
            val before = failed.observe()
            val owner = failed.observePlayer(failed.world, "p1")
            val enemy = failed.observePlayer(failed.world, "p2")
            repeat(2) {
                assertFailsWith<ArithmeticException> { failed.advance(Step(166, 1.0, "sim-tick")) }
                assertSame(before, failed.observe())
                assertSame(owner, failed.observePlayer(failed.world, "p1"))
                assertSame(enemy, failed.observePlayer(failed.world, "p2"))
            }
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                AttackAction("correct", "p2", "gun", "west", context.world)))
            for (tick in 166L..172L) {
                val expected = contexts.last().advance(Step(tick, 1.0, "sim-tick"))
                val actual = failed.advance(Step(tick, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units)
                assertEquals(expected.combat, actual.combat)
                assertEquals(expected.events, actual.events)
                assertEquals(expected.projectiles, actual.projectiles)
                assertEquals(expected.actionResults, actual.actionResults)
            }
            assertEquals("mechBunkerDeployed", failed.observe().units.single { it.unitId == "bunker" }.typeId)
        } finally { contexts.forEach { it.close() } }
    }
}
