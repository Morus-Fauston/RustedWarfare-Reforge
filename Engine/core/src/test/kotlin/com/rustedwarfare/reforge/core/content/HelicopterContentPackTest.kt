package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelicopterContentPackTest {
    @Test fun all_three_formats_load_the_same_finite_air_and_production_capabilities() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val reference = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(
            listOf(fixtures.resolve("helicopter-production/json/production-package")))).value
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPacks(listOf(fixtures.resolve("helicopter-production/$format/production-package")))
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, "$format: $loaded").value
            assertEquals(reference.units.keys, content.units.keys)
            val helicopter = content.units.getValue("helicopter")
            val original = reference.units.getValue("helicopter")
            assertEquals(original.movement, helicopter.movement)
            assertEquals(original.build, helicopter.build)
            assertEquals(30.0, assertNotNull(helicopter.rotor).spinRate)
            assertEquals(17.0, assertNotNull(helicopter.combat).projectile.directDamage)
            assertEquals(150.0, helicopter.maximumHealth)
            assertNull(helicopter.combat!!.turretImagePath)
            val factory = content.units.getValue("airFactory")
            assertEquals(reference.units.getValue("airFactory").building, factory.building)
            assertEquals(reference.units.getValue("airFactory").production, factory.production)
            assertEquals(BuildProfile(1000L, .001f), factory.build)
            assertEquals(DirectHitTargetKind.NATIVE_BUILDING, factory.directHitTargetKind)
            assertEquals(listOf("airFactory"), content.units.getValue("builder").builder!!.buildingTypeIds)
        }
    }

    @Test
    fun player_can_load_the_complete_builder_air_factory_and_helicopter_package() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("helicopter-production/json/production-package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        assertEquals(setOf("builder", "airFactory", "helicopter"), pack.units.keys)
        val factory = pack.units.getValue("airFactory")
        assertEquals("NONE", factory.movement.type.name)
        assertEquals(1000.0, factory.maximumHealth)
        assertEquals(30.0, factory.radius)
        assertEquals(BuildProfile(1000L, .001f), factory.build)
        assertEquals(ConstructionProfile(1000L, .001f), factory.construction)
        assertEquals(DirectHitTargetKind.NATIVE_BUILDING, factory.directHitTargetKind)
        val building = assertNotNull(factory.building)
        assertEquals(BuildingFootprint(-1, -1, 1, 1), building.footprint)
        assertEquals(BuildingFootprint(-1, -1, 1, 2), building.placementFootprint)
        assertNull(building.backImagePath)
        val production = assertNotNull(factory.production)
        assertEquals(listOf("helicopter"), production.unitTypeIds)
        assertEquals("NATIVE_AIR", production.exitProfile.name)
        assertEquals(listOf("airFactory"), assertNotNull(pack.units.getValue("builder").builder).buildingTypeIds)
        val composed = ContentLoader.composeContentPacks(listOf(pack))
        assertIs<ContentResult.Success<LoadedContentSet>>(composed, composed.toString())
    }

    @Test
    fun player_can_load_the_helicopter_package_with_real_air_movement_price_health_and_gun() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("helicopter-production/json/package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        val helicopter = pack.units.getValue("helicopter")
        assertEquals("AIR", helicopter.movement.type.name)
        assertTrue(helicopter.movement.slidingMode)
        assertTrue(helicopter.movement.ignoringBody)
        assertEquals(1.8, helicopter.movement.moveSpeed)
        assertEquals(.03, helicopter.movement.moveAccelerationSpeed)
        assertEquals(.06, helicopter.movement.moveDecelerationSpeed)
        assertEquals(BuildProfile(700L, .0012f), helicopter.build)
        assertEquals(150.0, helicopter.maximumHealth)
        assertEquals(15.0, helicopter.radius)
        assertEquals(9.0, helicopter.displayRadius)
        val combat = assertNotNull(helicopter.combat)
        assertEquals(130.0, combat.attackRange)
        val gun = assertNotNull(combat.namedWeapons).byName.getValue("gun")
        assertEquals(60.0, gun.shootDelay)
        assertEquals(17.0, gun.projectile.directDamage)
        assertEquals(10.0, gun.projectile.speed)
        assertNull(combat.turretImagePath)
        assertNotNull(combat.deadImagePath)
    }
}
