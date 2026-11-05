package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class MechFactoryContentTest {
    @Test
    fun all_three_complete_packages_retain_builder_factory_and_mech_production_references() {
        for (format in listOf("ini", "json", "hjson")) {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("mech-production/$format/production-package"))).value
            val set = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.composeContentPacks(listOf(pack))).value
            assertEquals(setOf("mechGun", "mechFactory", "builder"), set.units.keys)
            val factory = set.units.getValue("mechFactory")
            assertEquals(DirectHitTargetKind.ORDINARY, factory.directHitTargetKind)
            assertEquals(1800.0, factory.maximumHealth)
            assertEquals(35.0, factory.radius)
            assertEquals(ConstructionProfile(1000L, .001f), factory.construction)
            assertEquals(BuildProfile(1000L, .001f), factory.build)
            assertEquals(BuildingFootprint(-1, -1, 1, 3), factory.building!!.placementFootprint)
            assertEquals(listOf("builder", "mechGun"), factory.production!!.unitTypeIds)
            assertEquals(listOf("mechFactory"), set.units.getValue("builder").builder!!.buildingTypeIds)
            assertEquals(BuildProfile(600L, .0012f), set.units.getValue("mechGun").build)
            val body = PngImages.inspect(Files.readAllBytes(factory.imagePath), allowRgb = true)
            val back = PngImages.inspect(Files.readAllBytes(factory.building!!.backImagePath!!), allowRgb = true)
            val dead = PngImages.inspect(Files.readAllBytes(factory.durability!!.deadImagePath!!), allowRgb = true)
            assertEquals(74 to 106, body.width to body.height)
            assertEquals(74 to 106, back.width to back.height)
            assertEquals(72 to 88, dead.width to dead.height)
            assertTrue(pack.sources.any { it.path.fileName.toString() == "factory_back.png" })
            assertTrue(pack.sources.any { it.path.fileName.toString() == "builder_dead.png" })
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
