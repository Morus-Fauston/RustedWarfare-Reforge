package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class NautilusLandFactoryContentTest {
    @Test fun all_formats_offer_the_native_experimental_factory_at_tech_two_without_an_extra_upgrade() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(root.resolve("nautilus-production/$format/production-package"))
            val units = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value.units
            assertEquals(listOf("seaFactory", "experimentalLandFactory"), units.getValue("builder").builder!!.buildingTypeIds)
            val factory = units.getValue("experimentalLandFactory")
            assertEquals(11000L, factory.construction!!.price)
            assertEquals(.00035f, factory.construction.rate)
            assertEquals(2, factory.techLevel)
            assertNull(factory.upgrades)
            assertEquals(3200.0, factory.maximumHealth)
            assertEquals(55.0, factory.radius)
            assertEquals(BuildingFootprint(-2, -2, 2, 2), factory.building!!.footprint)
            assertEquals(BuildingFootprint(-2, -2, 2, 4), factory.building.placementFootprint)
            assertEquals(listOf("nautilusSubmarineLand"), factory.production!!.unitTypeIds)
            assertNotNull(factory.building.backImagePath)
        }
    }
}
