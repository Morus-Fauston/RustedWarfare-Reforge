package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class NuclearSiloContentTest {
    @Test fun three_formats_reject_unknown_capabilities_bad_door_references_and_changed_effective_values() {
        val root=Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini","json","hjson")) for (fault in listOf("capability","door","value","field")) {
            val source=root.resolve("nuclear-silo/$format/package")
            val copy=Files.createTempDirectory("nuclear-content-$format-$fault-")
            Files.walk(source).use { paths -> paths.forEach { p ->
                val target=copy.resolve(source.relativize(p))
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p,target)
            } }
            val declaration=copy.resolve("nukeLauncherC.$format")
            val text=Files.readString(declaration)
            when (fault) {
                "capability" -> {
                    val manifest=copy.resolve("pack.json")
                    Files.writeString(manifest,Files.readString(manifest).replace("nuclear-silo\"","unqualified-nuclear\""))
                }
                "door" -> Files.writeString(declaration,text.replace("arm1.png","missing-door.png"))
                "value" -> Files.writeString(declaration,text.replace("1500","1501"))
                "field" -> Files.writeString(declaration,if (format=="ini") text+"nuclearBonus=1\n" else text.trimEnd().dropLast(1)+",\"nuclearBonus\":1}")
            }
            assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(copy),"$format $fault")
        }
    }

    @Test fun three_syntaxes_load_the_effective_buildable_silo_and_complete_source_closure() {
        for (format in listOf("ini", "json", "hjson")) {
            val path = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("nuclear-silo/$format/package")
            val result = ContentLoader.loadContentPack(path)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
            val unit = pack.units.getValue("nukeLauncherC")
            assertEquals(1500.0, unit.maximumHealth)
            assertEquals(35.0, unit.radius)
            assertEquals(MovementType.NONE, unit.movement.type)
            assertEquals(ConstructionProfile(45000L, .001f), unit.construction)
            assertEquals(BuildingFootprint(-1, -1, 1, 1), unit.building!!.footprint)
            assertEquals(BuildingFootprint(-2, -1, 2, 2), unit.building.placementFootprint)
            assertNull(unit.combat) // Explicit launch never grants automatic attack.
            assertNull(unit.production)
            assertEquals(setOf("pack.json", "nukeLauncherC.$format", "body.png", "back.png", "dead.png", "arm1.png", "arm2.png"),
                pack.sources.map { it.path.fileName.toString() }.toSet())
        }
    }
}
