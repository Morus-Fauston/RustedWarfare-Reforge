package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class BunkerLifecycleTest {
    @Test fun actual_damage_during_deployment_is_not_healed_and_the_new_shield_recovers_then_disappears_on_undeploy() = WaterTestScene.withMap { map ->
        val base = BunkerScene.scenario(map)
        val bunker = base.unitDefinitions.getValue("mechBunker")
        val gun = UnitDefinition("probe", "OwnedProbe", 6.0, 6.0, 1, bunker.imagePath, 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            combat = CombatProfile(20000.0, 280.0, 10000.0, 0.0, 1000.0, 0.0,
                ProjectileProfile(40.0, 60.0, 1000.0, instant = true), null, null, 1.0, recoil = WeaponRecoil(0.0)))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (gun.id to gun),
            units = base.units + UnitSpawn("probe", "p2", gun.id, Position(330.0, 200.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("bunker" to 8L, "probe" to 9L), 10L))
        val context = AaBeamScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            repeat(166) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = context.observe().units.single { it.unitId == "bunker" }
            assertEquals(760.0, before.health)
            val identity = context.observe().combat!!.weapons.filter { it.unitId == "bunker" }.map { it.objectId }
            val fixed = context.advance(Step(166, 1.0, "sim-tick"))
            assertEquals(760.0, fixed.units.single { it.unitId == "bunker" }.health)
            assertEquals(0f, fixed.units.single { it.unitId == "bunker" }.shield!!.current)
            assertEquals(identity, fixed.combat!!.weapons.filter { it.unitId == "bunker" }.map { it.objectId })
            val next = context.advance(Step(167, 1.0, "sim-tick"))
            assertEquals(.15f, next.units.single { it.unitId == "bunker" }.shield!!.current)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("undeploy", "p1", "bunker", "undeploy", context.world)))
            for (i in 168L..268L) context.advance(Step(i, 1.0, "sim-tick"))
            val restored = context.observe().units.single { it.unitId == "bunker" }
            assertNull(restored.shield)
            assertEquals(760.0, restored.health)
            assertEquals("mechBunker", restored.typeId)
        } finally { context.close() }
    }

    @Test fun enemy_observation_hides_queue_and_reopening_rejects_the_old_world_and_resets_pending_conversion() = WaterTestScene.withMap { map ->
        val scene = BunkerScene.scenario(map)
        val context = AaBeamScene.open(scene)
        try {
            val oldWorld = context.world
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                UnitAction("foreign", "p2", "bunker", "deploy", oldWorld))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", oldWorld)))
            repeat(50) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(context.observePlayer(oldWorld, "p1").units.single().unitActions!!.actions.single().progress!! > 0f)
            assertNull(context.observePlayer(oldWorld, "p2").units.single().unitActions)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                UnitAction("old", "p1", "bunker", "deploy", oldWorld))).error.code)
            val fresh = context.observe().units.single()
            assertEquals("mechBunker", fresh.typeId)
            assertEquals(800.0, fresh.health)
            assertNull(fresh.unitActions!!.actions.single().queueIndex)
        } finally { context.close() }
    }
}
