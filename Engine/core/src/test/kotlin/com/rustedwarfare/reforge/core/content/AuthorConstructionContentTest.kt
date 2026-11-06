package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class AuthorConstructionContentTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun invalid_author_graphs_and_fields_fail_without_poisoning_a_repaired_load() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root ->
            val base = root.resolve("units/base-constructor.ini")
            val child = root.resolve("units/spbuilder.$format")
            val building = root.resolve("units/workshop.$format")
            val baseText = Files.readString(base)
            val childText = Files.readString(child)
            val bodyText = Files.readString(building)
            val mutations = listOf(
                base to baseText.replace("maxHp=170", "maxHp=NaN"),
                base to (baseText + "\nunknownAbility=true\n"),
                base to baseText.replace("builder.png", "../../outside.png"),
                child to childText.replace("base-constructor.ini", "spbuilder.$format"),
                child to childText.replace("workshop", "workshop,workshop"),
                child to childText.replace("workshop", "missing_building"),
                building to bodyText.replace("LAND", "AIR"))
            for ((file, invalid) in mutations) {
                val original = Files.readString(file)
                assertNotEquals(original, invalid)
                Files.writeString(file, invalid)
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)), "$format $invalid")
                Files.writeString(file, original)
                assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)))
            }
        }
    }

    @Test fun author_fields_outside_the_existing_finite_construction_envelope_are_rejected_at_load() {
        withPack("ini") { root ->
            val base = root.resolve("units/base-constructor.ini")
            val building = root.resolve("units/workshop.ini")
            val workerText = Files.readString(base)
            val buildingText = Files.readString(building)
            for ((file, original, invalid) in listOf(
                Triple(base, workerText, workerText.replace("moveSpeed=0.8", "moveSpeed=0.81")),
                Triple(base, workerText, workerText.replace("fogOfWarSightRange=15", "fogOfWarSightRange=14")),
                Triple(building, buildingText, buildingText.replace("radius=30", "radius=31")),
                Triple(building, buildingText, buildingText.replace("footprint=-1,-1,1,1", "footprint=0,0,0,0")))) {
                assertNotEquals(original, invalid)
                Files.writeString(file, invalid)
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), invalid)
                Files.writeString(file, original)
                assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
            }
        }
    }

    @Test fun an_invalid_inherited_image_path_is_a_content_failure_and_can_be_repaired() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root ->
            val base = root.resolve("units/base-constructor.ini")
            val text = Files.readString(base)
            Files.writeString(base, text.replace("image=builder.png", "image=bad\u0000.png"))
            assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
            Files.writeString(base, text)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    private fun withPack(format: String, action: (Path) -> Unit) {
        val source = fixtures.resolve("author-construction/$format/package")
        val root = Files.createTempDirectory("rwr-author-construction-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { paths -> paths.forEach { file ->
                val target = root.resolve(source.relativize(file)).normalize()
                require(target.startsWith(root))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            action(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file ->
                require(file.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(file)
            } }
        }
    }
    @Test fun three_format_author_constructor_and_plain_building_share_the_existing_content_graph() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(fixtures.resolve("author-construction/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            val set = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.composeContentPacks(listOf(pack))).value
            assertEquals(listOf("spbuilder", "workshop"), set.units.keys.toList())
            val worker = pack.units.getValue("spbuilder")
            assertEquals(listOf("workshop"), assertNotNull(worker.builder).buildingTypeIds)
            assertEquals(MovementType.LAND, worker.movement.type)
            assertEquals(.8, worker.movement.moveSpeed)
            assertEquals(170.0, worker.maximumHealth)
            val workshop = pack.units.getValue("workshop")
            assertEquals(MovementType.NONE, workshop.movement.type)
            assertEquals(MovementType.LAND, workshop.foundationMovementType)
            assertEquals(BuildingFootprint(-1, -1, 1, 1), assertNotNull(workshop.building).footprint)
            assertEquals(BuildingFootprint(-1, -1, 1, 3), workshop.building.placementFootprint)
            assertEquals(ConstructionProfile(700L, .001f), workshop.construction)
            assertNull(workshop.production)
            assertNull(workshop.upgrades)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
