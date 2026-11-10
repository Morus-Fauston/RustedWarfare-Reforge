package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class BunkerWeaponTest {
    @Test fun conversion_preserves_attack_cooldown_and_live_shells_and_stop_does_not_cancel_the_queue() = WaterTestScene.withMap { map ->
        val base = BunkerScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 1.0, 1.0, 1,
            base.unitDefinitions.getValue("mechBunker").imagePath, 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(20000.0, null))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            units = base.units + UnitSpawn("target", "p2", target.id, Position(390.0, 200.0), 0.0),
            combatContract = CombatContract(12345, -1, mapOf("bunker" to 8L, "target" to 9L), 10L))
        val context = AaBeamScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "bunker", "target", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            repeat(166) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = context.observe()
            val weapon = before.combat!!.weapons.single { it.unitId == "bunker" && it.weaponName == "2" }
            assertTrue(weapon.cooldown > 1.0)
            assertTrue(before.projectiles.isNotEmpty(), "A previously fired shell is still travelling at the conversion boundary")
            val fixed = context.advance(Step(166, 1.0, "sim-tick"))
            val afterWeapon = fixed.combat!!.weapons.single { it.unitId == "bunker" && it.weaponName == "2" }
            assertEquals(weapon.objectId, afterWeapon.objectId)
            assertEquals(weapon.cooldown - 1.0, afterWeapon.cooldown)
            assertEquals("target", afterWeapon.targetUnitId)
            assertTrue(fixed.units.single { it.unitId == "bunker" }.tasks.any { it.actionId == "attack" })
            for (shell in before.projectiles) {
                val retained = fixed.projectiles.single { it.objectId == shell.objectId }
                assertEquals(shell.remainingLife - 1.0, retained.remainingLife)
                assertEquals(shell.sourceWeaponName, retained.sourceWeaponName)
            }
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("undeploy", "p1", "bunker", "undeploy", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bunker", context.world)))
            val stopped = context.advance(Step(167, 1.0, "sim-tick")).units.single { it.unitId == "bunker" }
            assertTrue(stopped.tasks.isEmpty())
            assertEquals(.01f, stopped.unitActions!!.actions.single().progress)
            for (tick in 168L..267L) context.advance(Step(tick, 1.0, "sim-tick"))
            assertEquals("mechBunker", context.observe().units.single { it.unitId == "bunker" }.typeId)
            assertTrue(context.observe().units.single { it.unitId == "target" }.health!! < 20000.0)
        } finally { context.close() }
    }

    @Test fun fixed_turret_keeps_the_last_aim_as_sweep_base_while_mobile_turret_resets_to_its_body() = WaterTestScene.withMap { map ->
        for (type in listOf("mechBunker", "mechBunkerDeployed")) {
            val base = BunkerScene.scenario(map)
            val target = UnitDefinition("target", "OwnedTarget", 1.0, 1.0, 1,
                base.unitDefinitions.getValue(type).imagePath, 1.0,
                MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                durability = DurabilityProfile(1.0, null))
            val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
                units = listOf(UnitSpawn("bunker", "p1", type, Position(200.0, 200.0), 90.0),
                    UnitSpawn("target", "p2", target.id, Position(200.0, 100.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("bunker" to 8L, "target" to 9L), 10L))
            val context = AaBeamScene.open(scene)
            try {
                repeat(500) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertFalse(context.observe().units.single { it.unitId == "target" }.alive)
                val idle = context.observe().combat!!.weapons.single { it.unitId == "bunker" && it.weaponName == "2" }.idle!!
                assertEquals(if (type == "mechBunker") 90.0 else 0.0, idle.baseHeading, .01)
            } finally { context.close() }
        }
    }

    @Test fun walking_pauses_the_second_turrets_sweep_clock_then_stop_resumes_it() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(BunkerScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("walk", "p1", "bunker", Position(350.0, 200.0), context.world)))
            repeat(220) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single().speed > 0.0)
            val walking = context.observe().combat!!.weapons.single { it.weaponName == "2" }
            assertEquals(0f, walking.idle!!.timer)
            assertEquals(0.0, walking.idle!!.offset)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bunker", context.world)))
            for (tick in 220L..450L) context.advance(Step(tick, 1.0, "sim-tick"))
            assertEquals(20.0, context.observe().combat!!.weapons.single { it.weaponName == "2" }.idle!!.offset)
        } finally { context.close() }
    }

    @Test fun the_stationary_second_turret_uses_its_own_qualified_sweep_clock() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(BunkerScene.scenario(map))
        try {
            repeat(211) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val weapon = context.observe().combat!!.weapons.single { it.weaponName == "2" }
            val idle = assertNotNull(weapon.idle)
            assertEquals(20.0, idle.offset)
            assertTrue(idle.timer <= 0f)
            assertEquals(0f, context.observe().combat!!.weapons.single { it.weaponName == "1" }.idle!!.timer)
        } finally { context.close() }
    }
}
