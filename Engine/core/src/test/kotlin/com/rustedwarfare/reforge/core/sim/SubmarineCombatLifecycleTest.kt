package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineCombatLifecycleTest {
    @Test fun diving_changes_incoming_order_eligibility_but_does_not_erase_an_already_launched_hit_then_surfacing_restores_eligibility() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val sub = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("attackSubmarine")
            val shot = ProjectileProfile(1.0,300.0,1.3)
            val weapon = NamedWeaponDefinition("1","surface-only",shot,TurretMount(),10000.0,0.0,360.0,0.0,null,1.0)
            val instrument = UnitDefinition("instrument","OwnedInstrument",6.0,6.0,1,sub.imagePath,1.0,
                MovementProfile(MovementType.HOVER,0.0,0.0,0.0,0.0,0.0,false,false),emptySet(),
                combat=CombatProfile(100000.0,200.0,10000.0,0.0,360.0,0.0,shot,null,null,1.0,
                    namedWeapons=WeaponCatalog(listOf(weapon))))
            val scene = WaterTestScene.scenario(map,MovementType.WATER,Position(500.0,200.0)).copy(
                unitDefinitions=mapOf("attackSubmarine" to sub,"instrument" to instrument),
                players=listOf(Player("p1",PlayerControl.LOCAL),Player("p2",PlayerControl.LOCAL)),
                units=listOf(UnitSpawn("sub","p1","attackSubmarine",Position(500.0,200.0),90.0),
                    UnitSpawn("gun","p2","instrument",Position(650.0,200.0),270.0)),
                combatContract=CombatContract(12345,-1,mapOf("sub" to 8L,"gun" to 9L),10L))
            val result=SimulationContext.initialize(scene)
            val context=assertIs<InitResult.Accepted>(result,result.toString()).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("surface-target","p2","gun","sub",context.world)))
                var tick=0L
                val first=context.advance(Step(tick++,1.0,"sim-tick"))
                assertTrue(first.projectileEvents.any { it.kind=="born" && it.projectile.sourceUnitId=="gun" })
                while (!context.observe().units.single { it.unitId=="sub" }.submarine!!.underwater && tick<60L)
                    context.advance(Step(tick++,1.0,"sim-tick"))
                assertTrue(context.observe().units.single { it.unitId=="sub" }.submarine!!.underwater)
                assertEquals("invalid-attack-target",assertIs<ActionResult.Rejected>(context.submit(
                    AttackAction("underwater-target","p2","gun","sub",context.world))).error.code)
                while (tick<140L) context.advance(Step(tick++,1.0,"sim-tick"))
                val hit=context.observe().units.single { it.unitId=="sub" }
                assertEquals(259.0,hit.health,"A pre-transition projectile retains its hit; there is no blanket underwater immunity")
                assertTrue(hit.submarine!!.underwater)
                assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("raise","p1","sub",true,context.world)))
                repeat(260) { context.advance(Step(tick++,1.0,"sim-tick")) }
                assertEquals(1.0,context.observe().units.single { it.unitId=="sub" }.height)
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("surface-again","p2","gun","sub",context.world)))
            } finally { context.close() }
        } }
}
