package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class HoverContentTest {
    @Test fun normal_land_factory_exposes_the_hover_product_in_all_three_content_formats() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPacks(listOf(fixtures.resolve("hover-production/$format/package")))
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(result, "$format: $result").value
            assertEquals(setOf("commandCenter", "builder", "landFactory", "c_tank", "hoverTank"), content.units.keys)
            assertEquals(listOf("builder", "c_tank", "hoverTank"), content.units.getValue("landFactory").production!!.unitTypeIds)
            assertEquals(listOf("landFactory"), content.units.getValue("builder").builder!!.buildingTypeIds)
            assertEquals(BuildProfile(450L, .002f), content.units.getValue("hoverTank").build)
            assertEquals(MovementType.HOVER, content.units.getValue("hoverTank").movement.type)
            val hover = content.units.getValue("hoverTank")
            assertEquals(HoverProfile(), hover.movement.hover)
            assertEquals(23.0, hover.combat!!.projectile.directDamage)
            assertEquals(LinearProjectileAcceleration(6.0, .2), hover.combat.projectile.acceleration)
            assertTrue(content.sources.any { it.path.fileName.toString() == "hover.$format" })
            assertTrue(content.sources.any { it.path.fileName.toString() == "shadow.png" })
        }
    }

    @Test fun hover_errors_identify_unknown_capabilities_fields_bad_images_and_missing_inherited_authors() = withHoverPackage { root ->
        val manifest = root.resolve("pack.json")
        val author = root.resolve("hover.json")
        val originalManifest = Files.readString(manifest)
        val originalAuthor = Files.readString(author)
        Files.writeString(manifest, originalManifest.replace("hover-tank", "unknown-hover"))
        assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
        Files.writeString(manifest, originalManifest)
        val unknown = JsonValue.readJSON(originalAuthor).asObject()
        unknown.get("movement").asObject().set("unknownHoverFlag", true)
        Files.writeString(author, unknown.toString())
        val field = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
        assertEquals("unknownHoverFlag", field.field)
        assertTrue(field.file.endsWith("hover.json"))
        val badImage = JsonValue.readJSON(originalAuthor).asObject()
        badImage.get("graphics").asObject().set("image_shadow", "missing-shadow.png")
        Files.writeString(author, badImage.toString())
        val image = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
        assertEquals(ContentErrorCode.BAD_REFERENCE, image.code)
        assertEquals("image_shadow", image.field)
        Files.writeString(author, """{"format":"rw-reforge.unit.v1","copyFrom":["missing.ini"]}""")
        val parent = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
        assertEquals(ContentErrorCode.BAD_REFERENCE, parent.code)
        assertEquals("copyFrom", parent.field)
        assertEquals("missing.ini", parent.objectRef)
    }

    @Test fun a_local_hover_tank_package_loads_its_native_price_motion_and_weapon() = withHoverPackage { root ->
        val result = ContentLoader.loadContentPack(root)
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
        val unit = pack.units.getValue("hoverTank")
        assertEquals(BuildProfile(450L, .002f), unit.build)
        assertEquals(150.0, unit.maximumHealth)
        assertEquals(7.0, unit.radius)
        assertEquals(9.0, unit.displayRadius)
        assertEquals(MovementType.HOVER, unit.movement.type)
        assertTrue(unit.movement.slidingMode)
        assertTrue(unit.movement.ignoringBody)
        assertEquals(1.0, unit.movement.moveSpeed)
        assertEquals(.04, unit.movement.moveAccelerationSpeed)
        assertEquals(.09, unit.movement.moveDecelerationSpeed)
        assertEquals(180.0, unit.movement.maxTurnSpeed)
        assertEquals(.5, unit.movement.turnAcceleration)
        val weapon = assertNotNull(unit.combat)
        assertEquals(23.0, weapon.projectile.directDamage)
        assertEquals(140.0, weapon.attackRange)
        assertEquals(90.0, weapon.shootDelay)
        assertEquals(LinearProjectileAcceleration(6.0, .2), weapon.projectile.acceleration)
        assertNull(weapon.turretImagePath)
        assertTrue(pack.sources.any { it.path == root.resolve("shadow.png") })
        assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
    }
}

internal fun withHoverPackage(check: (Path) -> Unit) {
    val root = Files.createTempDirectory("reforge-hover-content-").toAbsolutePath().normalize()
    try {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("three-format-tanks/json/units")
        val unit = JsonValue.readJSON(Files.readString(source.resolve("tank.json"))).asObject()
        unit.get("core").asObject().apply {
            set("name", "hoverTank"); remove("overrideAndReplace"); set("price", 450); set("maxHp", 150)
            set("radius", 7); set("displayRadius", 9)
        }
        unit.get("graphics").asObject().apply { set("total_frames", 1); set("image_turret", "NONE"); set("image_shadow", "shadow.png") }
        unit.get("attack").asObject().apply { set("turretSize", 2); set("maxAttackRange", 140); set("shootDelay", 90) }
        unit.get("weapons").asArray()[0].asObject().set("turnSpeedAcceleration", .2).set("recoilOffset", 0)
        unit.get("projectiles").asArray()[0].asObject().apply {
            set("directDamage", 23); set("life", 85); set("speed", 2); set("targetSpeed", 6); set("targetSpeedAcceleration", .2)
        }
        unit.get("movement").asObject().apply {
            set("movementType", "HOVER"); set("moveSpeed", 1); set("moveAccelerationSpeed", .04)
            set("moveDecelerationSpeed", .09); set("maxTurnSpeed", 180); set("turnAcceleration", .5)
            set("moveSlidingMode", true); set("moveIgnoringBody", true)
        }
        Files.writeString(root.resolve("hover.json"), unit.toString())
        for (image in listOf("tank.png", "tank_dead.png")) Files.copy(source.resolve(image), root.resolve(image))
        Files.copy(source.resolve("tank.png"), root.resolve("shadow.png"))
        Files.writeString(root.resolve("pack.json"), """{"format":"rw-reforge.content-pack.v1","id":"test.hover","name":"Hover tank",
            "units":[{"type":"hover-tank","file":"hover.json"}]}""")
        check(root)
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
        } }
    }
}
