package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class HoverTargetTest {
    @Test fun hovering_does_not_grant_an_air_weapon_and_an_airborne_enemy_rejects_the_ground_attack() = withHoverPackage { root ->
        val hover = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("hoverTank")
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val helicopter = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("helicopter-production/json/package"))).value.units.getValue("helicopter")
        val base = TestContentFactory.openScenario()
        val input = base.copy(unitDefinitions = mapOf("hover" to hover, "air" to helicopter),
            units = listOf(UnitSpawn("h", "p1", "hover", Position(100.0, 100.0), 90.0),
                UnitSpawn("air", "p2", "air", Position(500.0, 300.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("h" to 8L, "air" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            repeat(120) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == "air" }.height >= 4.0)
            assertIs<ActionResult.Rejected>(context.submit(AttackAction("air-target", "p1", "h", "air", context.world)))
            assertNull(context.observePlayer(context.world, "p1").units.single { it.unitId == "h" }.attackTargetId)
        } finally { context.close() }
    }
}
