package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class AuthorWaterContentTest {
    @Test fun a_hidden_water_weapon_needs_no_image_while_an_independent_visible_weapon_keeps_its_image() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root ->
            val child = root.resolve("units/spcruiser.$format")
            Files.writeString(child, Files.readString(child).replace("cannon.png", "NONE"))
            val template = root.resolve("units/base.ini")
            Files.writeString(template, Files.readString(template).replace("[template_gun]", "[template_gun]\ninvisible: true") +
                "\n[turret_port]\ninvisible: false\nimage: cannon.png\n")
            val attempt = ContentLoader.loadContentPack(root)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, "$format: $attempt").value
            val combat = assertNotNull(pack.units.getValue("spcruiser").combat)
            assertNull(combat.turretImagePath)
            val weapons = assertNotNull(combat.namedWeapons).ordered
            assertEquals(listOf("port", "starboard"), weapons.map { it.name })
            assertFalse(weapons[0].invisible)
            assertEquals(root.resolve("units/cannon.png"), weapons[0].imagePath)
            assertTrue(weapons[1].invisible)
            assertNull(weapons[1].imagePath)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }

    @Test fun three_format_author_packs_inherit_two_named_weapons_for_the_same_new_water_id() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val attempt = ContentLoader.loadContentPack(fixtures.resolve("author-water/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, "$format: $attempt").value
            assertEquals(listOf("spcruiser"), pack.units.keys.toList())
            val unit = pack.units.getValue("spcruiser")
            assertEquals(MovementType.WATER, unit.movement.type)
            assertEquals(40.0, unit.radius)
            assertEquals(0.8, unit.movement.moveSpeed)
            assertEquals(4000.0, unit.maximumHealth)
            val combat = assertNotNull(unit.combat)
            val weapons = assertNotNull(combat.namedWeapons).ordered
            assertEquals(listOf("port", "starboard"), weapons.map { it.name })
            assertEquals(listOf(70.0, 100.0), weapons.map { it.shootDelay })
            assertEquals(listOf(TurretMount(-8.0, 0.0), TurretMount(8.0, 0.0)), weapons.map { it.mount })
            assertTrue(weapons.all { it.projectile.directDamage == 25.0 && it.fireSound == FireSoundProfile.NAVAL_MAIN })
            assertEquals(70.0, combat.shootDelay)
            assertEquals(TurretMount(-8.0, 0.0), combat.mount)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
    @Test fun a_second_author_id_and_configurable_target_masks_use_the_same_reader() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root ->
            val child = root.resolve("units/spcruiser.$format")
            Files.writeString(child, Files.readString(child).replace("spcruiser", "harbor_guard"))
            val template = root.resolve("units/base.ini")
            Files.writeString(template, Files.readString(template).replace("canAttackFlyingUnits: false", "canAttackFlyingUnits: true"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            assertEquals(setOf("harbor_guard"), pack.units.keys)
            assertTrue(pack.units.getValue("harbor_guard").authoredWaterCombat)
            assertTrue(pack.units.getValue("harbor_guard").combat!!.namedWeapons!!.ordered.all { it.canAttackFlyingUnits })
        }
    }

    @Test fun author_failures_do_not_publish_partial_packs_and_restoring_the_input_can_reopen() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root ->
            val template = root.resolve("units/base.ini")
            val child = root.resolve("units/spcruiser.$format")
            val original = Files.readString(template)
            val childOriginal = Files.readString(child)
            val manifest = root.resolve("pack.json")
            val manifestOriginal = Files.readString(manifest)
            val mutations = listOf<Pair<Path, String>>(
                template to original.replace("projectile: round", "projectile: missing"),
                template to original.replace("[template_gun]", "[template_gun]\ncopyFromSection:template_gun"),
                template to (original + "\n[unknown_capability]\nenabled:true\n"),
                template to original.replace("speed: 5", "speed: NaN"),
                child to childOriginal.replace("base.ini", "../../outside.ini"),
                manifest to manifestOriginal.replace("water-combat", "unknown-capability"),
                manifest to manifestOriginal.replace("\"units\": [", "\"units\": [{\"type\":\"water-combat\",\"file\":\"units/spcruiser.$format\"},"))
            for ((file, broken) in mutations) {
                val previous = Files.readString(file)
                assertNotEquals(previous, broken, "Mutation must change input: $file")
                Files.writeString(file, broken)
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), "$format $broken")
                Files.writeString(file, previous)
                assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
            }
        }
    }

    private fun withPack(format: String, action: (Path) -> Unit) {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("author-water/$format/package")
        val root = Files.createTempDirectory("rwr-author-water-").toAbsolutePath().normalize()
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
}
