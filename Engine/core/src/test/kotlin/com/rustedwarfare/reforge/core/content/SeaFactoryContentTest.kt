package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SeaFactoryContentTest {
    @Test
    fun all_formats_read_a_new_native_product_declaration_at_both_tiers() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) for (id in listOf("seaFactory", "seaFactoryT2")) {
            val files = fixtures.resolve("naval-production/$format/production-package/units")
            val original = files.resolve("$id.$format")
            val input = Files.readString(original)
            val temp = Files.createTempDirectory("reforge-declared-sea-")
            val declaration = temp.resolve(original.fileName)
            val images = Regex("sea_factory[^\\s\",]*\\.png").findAll(input).map { it.value }.distinct().toList()
            try {
                for (image in images) Files.copy(files.resolve(image), temp.resolve(image))
                val declared = input.replace("heavyBattleship,attackSubmarine", "spcorvette,attackSubmarine")
                    .let { if (id == "seaFactory") it.replace("attackSubmarine", "attackSubmarine,spcorvette") else it }
                Files.writeString(declaration, declared)
                val result = ContentLoader.loadFactory(declaration)
                val factory = assertIs<ContentResult.Success<UnitDefinition>>(result, "$format $id: $result").value
                val products = if (id == "seaFactory") listOf("attackSubmarine", "spcorvette") else listOf("spcorvette", "attackSubmarine")
                assertEquals(products, factory.production?.unitTypeIds)
                assertEquals(FactoryExitProfile.NATIVE_SEA, factory.production?.exitProfile)
                for (malformed in listOf("spcorvette,spcorvette", "spcorvette,", "sp corvette")) {
                    Files.writeString(declaration, declared.replace(products.joinToString(","), malformed))
                    val error = assertIs<ContentResult.Failure>(ContentLoader.loadFactory(declaration), "$format $id $malformed").error
                    assertEquals("productionUnits", error.field)
                }
            } finally {
                Files.deleteIfExists(declaration)
                for (image in images) Files.deleteIfExists(temp.resolve(image))
                Files.delete(temp)
            }
        }
    }

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
            assertEquals(if (tier == 1) listOf("attackSubmarine") else listOf("heavyBattleship", "attackSubmarine"), factory.production!!.unitTypeIds)
            assertEquals(if (tier == 1) listOf(UpgradeActionProfile("upgrade_t2", "seaFactoryT2", 2000L, .0004f)) else emptyList(),
                factory.upgrades?.actions.orEmpty())
            for (image in listOf(factory.imagePath, factory.durability!!.deadImagePath!!)) {
                val png = PngImages.inspect(Files.readAllBytes(image), allowRgb = true)
                assertEquals(90 to 99, png.width to png.height)
            }
        }
    }
}
