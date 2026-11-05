package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SeaFactoryContentTest {
    @Test
    fun three_formats_load_explicit_t1_upgrade_and_t2_heavy_product_without_inventing_t1_products() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) for (tier in listOf(1, 2)) {
            val id = if (tier == 1) "seaFactory" else "seaFactoryT2"
            val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(
                fixtures.resolve("naval-production/$format/production-package/units/$id.$format"))).value
            assertEquals(id, factory.id)
            assertEquals(1000.0, factory.maximumHealth)
            assertEquals(45.0, factory.radius)
            assertEquals(DirectHitTargetKind.NATIVE_BUILDING, factory.directHitTargetKind)
            assertEquals(BuildProfile(if (tier == 1) 1000L else 3000L, .0007f), factory.build)
            assertEquals(ConstructionProfile(if (tier == 1) 1000L else 3000L, .0007f), factory.construction)
            assertEquals(BuildingFootprint(-1, -1, 1, 2), factory.building!!.footprint)
            assertEquals(BuildingFootprint(-2, -1, 2, 4), factory.building!!.placementFootprint)
            assertNull(factory.building!!.backImagePath)
            assertEquals("NATIVE_SEA", factory.production!!.exitProfile.name)
            assertEquals(if (tier == 1) emptyList() else listOf("heavyBattleship"), factory.production!!.unitTypeIds)
            assertEquals(if (tier == 1) listOf(UpgradeActionProfile("upgrade_t2", "seaFactoryT2", 2000L, .0004f)) else emptyList(),
                factory.upgrades?.actions.orEmpty())
            for (image in listOf(factory.imagePath, factory.durability!!.deadImagePath!!)) {
                val png = PngImages.inspect(Files.readAllBytes(image), allowRgb = true)
                assertEquals(90 to 99, png.width to png.height)
            }
        }
    }
}
