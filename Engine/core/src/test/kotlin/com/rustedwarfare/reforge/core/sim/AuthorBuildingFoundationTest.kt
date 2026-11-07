package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class AuthorBuildingFoundationTest {
    @Test fun a_loaded_author_water_foundation_constructs_on_water_and_rejects_dry_land() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("author-construction/water/maps/naval-shore.tmx"))).value
        val base = AuthorConstructionScene.load("water").copy(map = map,
            units = listOf(UnitSpawn("a", "p1", "spbuilder", Position(390.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L), 9L))
        assertEquals(MovementType.WATER, base.unitDefinitions.getValue("workshop").foundationMovementType)
        for (water in listOf(false, true)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "a", "workshop",
                    Position(if (water) 450.0 else 350.0, 150.0), "a".repeat(32), context.world)))
                var q = 0L
                repeat(if (water) 1150 else 30) { context.advance(Step(q++, 1.0, "sim-tick")) }
                val sites = context.observe().units.filter { it.typeId == "workshop" }
                if (water) {
                    assertEquals(1f, sites.single().constructionProgress)
                    assertEquals(Position(450.0, 150.0), sites.single().position)
                    assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
                } else {
                    assertTrue(sites.isEmpty())
                    assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
                }
            } finally { context.close() }
        }
    }

    @Test fun a_legacy_native_air_factory_cannot_override_its_land_foundation() {
        val base = HelicopterFactoryScene.scenario()
        val normal = assertIs<InitResult.Accepted>(SimulationContext.initialize(base))
        normal.context.close()
        val old = base.unitDefinitions.getValue("airFactory")
        val bad = UnitDefinition(old.id, old.className, old.radius, old.displayRadius, old.totalFrames,
            old.imagePath, old.imageScale, old.movement, old.retainedUnconsumed, old.fogOfWarSightRange,
            durability = old.durability, building = old.building!!.copy(foundationMovementType = MovementType.WATER),
            production = old.production, build = old.build, construction = old.construction,
            directHitTargetKind = old.directHitTargetKind)
        val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (bad.id to bad)))
        if (result is InitResult.Accepted) result.context.close()
        assertIs<InitResult.Rejected>(result, "The new author foundation field must not relax native air factory qualification")
    }
}
