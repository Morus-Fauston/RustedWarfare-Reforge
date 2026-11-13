package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepairBayContentTest {
    @Test fun explicit_zero_reclaim_cost_is_valid_owned_content_in_all_three_syntaxes() {
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(root().resolve("repairbay-production/$format/reclaim-zero-package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
            assertEquals(350L, pack.units.getValue("c_tank").build!!.price)
            assertEquals(0L, pack.units.getValue("c_tank").reclaimPrice)
        }
    }

    @Test fun three_syntaxes_load_the_same_native_buildable_repair_bay_with_checked_sources() {
        for (format in listOf("ini", "json", "hjson")) {
            val path = root().resolve("repairbay-production/$format/package")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(path)).value
            val unit = pack.units.getValue("repairbay")
            assertEquals(1000.0, unit.maximumHealth)
            assertEquals(30.0, unit.radius)
            assertEquals(MovementType.NONE, unit.movement.type)
            assertEquals(BuildingFootprint(-1, -1, 1, 1), unit.building!!.footprint)
            assertEquals(ConstructionProfile(1500L, .001f), unit.construction)
            assertNull(unit.combat)
            assertNull(unit.production)
            assertEquals(setOf("pack.json", "repairbay.$format", "body.png", "dead.png"),
                pack.sources.map { it.path.fileName.toString() }.toSet())
        }
    }

    private fun root(): Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("SimulationTest/public")) }.resolve("SimulationTest/public/inputs")
}
