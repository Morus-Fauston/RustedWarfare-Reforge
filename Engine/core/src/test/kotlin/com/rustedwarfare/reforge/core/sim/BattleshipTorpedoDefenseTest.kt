package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipTorpedoDefenseTest {
    @Test fun the_stock_torpedo_ignores_a_real_eligible_defense_while_the_explicit_deflectable_control_is_intercepted() = WaterTestScene.withMap { map ->
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("naval-production/ini/production-package"))).value
        val original = pack.units.getValue("heavyBattleship")
        val defense = PointDefenseTestContent.native()
        for (nonDeflectable in listOf(true, false)) {
            val source = original.combat!!
            // The counterfactual changes this one projectile flag; the real defense and flight are unchanged.
            val ship = UnitDefinition(original.id, original.className, original.radius, original.displayRadius, original.totalFrames,
                original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed, 0,
                combat = source.copy(namedWeapons = WeaponCatalog(source.namedWeapons!!.ordered.map {
                    if (it.name == "3") it.copy(projectile = it.projectile.copy(nonDeflectable = nonDeflectable))
                    else it.copy(canShoot = false) })))
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = mapOf("ship" to ship, "defense" to defense, "target" to pack.units.getValue("navalUnderwaterTarget")),
                units = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0),
                    UnitSpawn("defense", "p2", "defense", Position(370.0, 200.0), 0.0),
                    UnitSpawn("target", "p2", "target", Position(650.0, 200.0), 0.0, -8.0)),
                combatContract = CombatContract(12345, -1, mapOf("ship" to 8L, "defense" to 9L, "target" to 10L), 11L),
                economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val born = mutableListOf<ProjectileObservation>()
                val deflections = mutableListOf<ProjectileEventObservation>()
                repeat(170) { q ->
                    val frame = context.advance(Step(q.toLong(), 1.0, "sim-tick"))
                    born += frame.projectileEvents.filter { it.kind == "born" }.map { it.projectile }
                    deflections += frame.projectileEvents.filter { it.kind == "deflected" }
                }
                assertEquals(listOf("3"), born.map { it.sourceWeaponName })
                assertEquals(if (nonDeflectable) 935.0 else 1000.0, context.observe().units.single { it.unitId == "target" }.health)
                if (nonDeflectable) assertTrue(deflections.isEmpty()) else {
                    val event = deflections.single()
                    assertEquals("defense", event.interceptorUnitId)
                    assertEquals("3", event.projectile.sourceWeaponName)
                    assertTrue(event.projectile.intercepted)
                    assertTrue(event.projectile.height >= -1.0)
                }
            } finally { context.close() }
        }
    }
}
