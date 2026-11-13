package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NuclearSiloSafetyTest {
    @Test fun authorization_duplicate_identity_scheduling_and_insufficient_funds_preserve_paid_state() = WaterTestScene.withMap { map ->
        val scene=NuclearScene.scenario(map)
        val c=NuclearScene.open(scene);val other=NuclearScene.open(scene)
        try {
            for (action in listOf(UnitAction("foreign","p2","silo","buildNuke",c.world),
                UnitAction("stale","p1","silo","buildNuke",other.world),
                UnitAction("missing","p1","missing","buildNuke",c.world),
                UnitAction("no-ammo","p1","silo","launchNuke",c.world,target=Position(440.0,180.0)),
                UnitAction("bad-build","p1","silo","buildNuke",c.world,target=Position(440.0,180.0)))) {
                assertIs<ActionResult.Rejected>(c.submit(action))
                assertEquals(100000L,c.observePlayer(c.world,"p1").balance)
                assertTrue(c.observe().units.single().unitActions!!.queue.isEmpty())
            }
            val scheduled=UnitAction("scheduled","p1","silo","buildNuke",c.world,atStep=2)
            assertIs<ActionResult.Accepted>(c.submit(scheduled))
            assertIs<ActionResult.Rejected>(c.submit(scheduled))
            repeat(2) { c.advance(Step(it.toLong(),1.0,"sim-tick")) }
            assertEquals(100000L,c.observePlayer(c.world,"p1").balance)
            c.advance(Step(2,1.0,"sim-tick"))
            assertEquals(89000L,c.observePlayer(c.world,"p1").balance)
            assertEquals(.0003f,c.observe().units.single().unitActions!!.queue.single().progress)
        } finally { c.close();other.close() }
        val poor=NuclearScene.open(scene.copy(economyContract=EconomyContract(mapOf("p1" to 10999L,"p2" to 0L))))
        try {
            assertEquals("insufficient-funds",assertIs<ActionResult.Rejected>(poor.submit(
                UnitAction("unaffordable","p1","silo","buildNuke",poor.world))).error.code)
            assertEquals(10999L,poor.observePlayer(poor.world,"p1").balance)
            assertTrue(poor.observe().units.single().unitActions!!.queue.isEmpty())
        } finally { poor.close() }
    }

    @Test fun two_simultaneous_launches_roll_back_stock_queue_doors_sounds_and_identity_when_the_second_id_overflows() = WaterTestScene.withMap { map ->
        val base=NuclearScene.scenario(map)
        val scene=base.copy(units=base.units+UnitSpawn("silo-z","p1","nukeLauncherC",Position(180.0,280.0),90.0),
            combatContract=CombatContract(12345,-1,mapOf("silo" to 1L,"silo-z" to 2L),Long.MAX_VALUE-1))
        val c=NuclearScene.open(scene)
        try {
            var step=0L
            for (id in listOf("silo","silo-z")) assertIs<ActionResult.Accepted>(c.submit(UnitAction("build-$id","p1",id,"buildNuke",c.world)))
            repeat(3334) { c.advance(Step(step++,1.0,"sim-tick")) }
            for (id in listOf("silo","silo-z")) assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch-$id","p1",id,"launchNuke",c.world,target=Position(600.0,180.0))))
            var failed=false
            repeat(220) {
                if (!failed) {
                    val before=c.observe();val player=c.observePlayer(c.world,"p1")
                    try { c.advance(Step(step,1.0,"sim-tick"));step++ }
                    catch (_: ArithmeticException) {
                        failed=true
                        assertSame(before,c.observe());assertSame(player,c.observePlayer(c.world,"p1"))
                        assertTrue(before.projectiles.isEmpty())
                        assertTrue(before.units.all { it.unitActions!!.stockpile==0 && it.unitActions.queue.single().name=="launchNuke" })
                        assertFailsWith<ArithmeticException> { c.advance(Step(step,1.0,"sim-tick")) }
                        assertSame(before,c.observe())
                    }
                }
            }
            assertTrue(failed)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("cancel-second","p1","silo-z","launchNuke",c.world,true)))
            val next=c.advance(Step(step,1.0,"sim-tick"))
            assertEquals(listOf(Long.MAX_VALUE-1),next.projectiles.map { it.objectId })
            assertEquals(1,next.units.single { it.unitId=="silo-z" }.unitActions!!.stockpile)
        } finally { c.close() }
    }

    @Test fun nuclear_expansion_keeps_the_exact_minus_five_water_boundary_and_uses_the_common_shield_and_hit_cap() = WaterTestScene.withMap { map ->
        val base=NuclearScene.scenario(map)
        val target=UnitDefinition("target","OwnedTarget",6.0,6.0,1,base.unitDefinitions.values.single().imagePath,1.0,
            MovementProfile(MovementType.WATER,0.0,0.0,0.0,0.0,0.0,false,false),emptySet(),durability=DurabilityProfile(10000.0,null))
        val capped=UnitDefinition("capped","OwnedTarget",6.0,6.0,1,target.imagePath,1.0,target.movement,emptySet(),
            durability=DurabilityProfile(10000.0,null,2500.0),shield=ShieldProfile(1000f,0f))
        val scene=base.copy(unitDefinitions=base.unitDefinitions+mapOf("target" to target,"capped" to capped),
            units=base.units+listOf(UnitSpawn("edge","p2","target",Position(440.0,180.0),90.0,-5.0),
                UnitSpawn("deep","p2","target",Position(440.0,190.0),90.0,-5.01),
                UnitSpawn("shield","p1","capped",Position(450.0,180.0),90.0)),
            combatContract=CombatContract(12345,-1,mapOf("silo" to 1L,"edge" to 2L,"deep" to 3L,"shield" to 4L),5L))
        val c=NuclearScene.open(scene)
        try {
            var step=0L
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("build","p1","silo","buildNuke",c.world)))
            repeat(3334) { c.advance(Step(step++,1.0,"sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch","p1","silo","launchNuke",c.world,target=Position(440.0,180.0))))
            repeat(1000) { c.advance(Step(step++,1.0,"sim-tick")) }
            val units=c.observe().units.associateBy { it.unitId }
            assertEquals(4600.0,units.getValue("edge").health)
            assertEquals(10000.0,units.getValue("deep").health)
            assertEquals(8500.0,units.getValue("shield").health)
            assertEquals(0f,units.getValue("shield").shield!!.current)
        } finally { c.close() }
    }
}
