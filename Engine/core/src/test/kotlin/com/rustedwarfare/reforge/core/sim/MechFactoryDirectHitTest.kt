package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechFactoryDirectHitTest {
    @Test
    fun a_real_custom_factory_keeps_the_strict_six_contact_gate_in_all_formats_without_native_radius_expansion() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val root = fixtures.resolve("mech-production/$format")
            val definitions = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root.resolve("production-package")))).value.units
            val tank = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(
                fixtures.resolve("production-combat/production/units/tank.ini"))).value
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
            val scene = Scenario("p008-mech-factory-hit", map, definitions + (tank.id to tank),
                listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
                listOf(UnitSpawn("a", "p1", tank.id, Position(190.0, 210.0), 90.0),
                    UnitSpawn("f", "p2", "mechFactory", Position(310.0, 210.0), 0.0)),
                CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
                TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
                combatContract = CombatContract(0, -1, mapOf("a" to 8L, "f" to 10L), 11L),
                economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack", "p1", "a", "f", c.world)))
                var checkedOutside = false
                var checkedImpact = false
                for (step in 0L..60L) {
                    val before = c.observe()
                    val shot = before.projectiles.singleOrNull { it.sourceUnitId == "a" }
                    val target = before.units.single { it.unitId == "f" }
                    val squared = shot?.let {
                        val dx = target.position.x + it.offset.x - it.position.x
                        val dy = target.position.y + it.offset.y - it.position.y
                        dx * dx + dy * dy
                    }
                    val after = c.advance(Step(step, 1.0, "sim-tick"))
                    if (shot == null || squared == null) continue
                    val impact = after.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == shot.objectId }
                    if (squared > 36.0 && squared < 784.0) {
                        checkedOutside = true
                        assertFalse(impact, "$format custom factory must not acquire native r35x.8=28 contact; pre-plane=$squared")
                        assertEquals(target.health, after.units.single { it.unitId == "f" }.health)
                    }
                    if (impact) {
                        assertTrue(squared < 36.0, "Strict ordinary gate, including the existing speed5 overshoot rule")
                        assertEquals(checkNotNull(target.health) - 25.0, after.units.single { it.unitId == "f" }.health)
                        checkedImpact = true
                        break
                    }
                }
                assertTrue(checkedOutside, "Actual public projectile must cross the nonempty (6,28) contact window")
                assertTrue(checkedImpact, "Actual public projectile must eventually cause damage")
            } finally { c.close() }
        }
    }
}
