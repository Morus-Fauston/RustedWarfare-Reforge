package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import org.hjson.JsonValue
import kotlin.test.*

class NautilusContentTest {
    @TempDir lateinit var temporary: Path

    @Test fun inherited_sources_unknown_capabilities_bad_prices_and_missing_products_are_checked_in_all_syntaxes() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("nautilus-production/$format/package")
            val root = temporary.resolve(format)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val out = root.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(out) else Files.copy(path, out)
            } }
            val entry = root.resolve("nautilus.$format")
            val parent = root.resolve("base.$format")
            Files.move(entry, parent)
            Files.writeString(entry, if (format == "ini") "[core]\ncopyFrom: base.$format\n" else
                """{"format":"rw-reforge.unit.v2","copyFrom":["base.$format"]}""")
            val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            assertTrue(loaded.sources.any { it.path == parent.toAbsolutePath() })
            assertTrue(loaded.sources.any { it.path == root.resolve("body.png").toAbsolutePath() })
            val original = Files.readString(parent)
            fun changed(field: String, value: String) = if (format == "ini") {
                if (field == "arbitraryScript") original.replace("[core]", "[core]\narbitraryScript: $value")
                else original.replace("nanoFactorySpeed: 2", "nanoFactorySpeed: $value")
            } else JsonValue.readHjson(original).asObject().also {
                it.get("core").asObject().set(field, value.toInt())
            }.toString()
            Files.writeString(parent, changed("nanoFactorySpeed", "3"))
            val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, invalid.code)
            assertEquals(parent.toString(), invalid.file)
            assertEquals("nanoFactorySpeed", invalid.field)
            assertIs<ContentResult.Failure>(loaded.verifyStable())
            Files.writeString(parent, changed("arbitraryScript", "1"))
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD,
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
            Files.writeString(parent, original)
            val metadata = root.resolve("pack.json")
            val json = JsonValue.readJSON(Files.readString(metadata)).asObject()
            json.get("units").asArray().remove(1)
            Files.writeString(metadata, json.toString())
            val missing = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root))).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, missing.code)
            assertEquals("hoverTank", missing.objectRef)
        }
    }

    @Test fun three_syntaxes_load_the_qualified_mobile_producer_and_real_energy_without_granting_ordinary_unload() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(root.resolve("nautilus-production/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            val unit = pack.units.getValue("nautilusSubmarine")
            assertEquals(4000.0, unit.maximumHealth)
            assertEquals(BuildProfile(18000L, .0003f), unit.build)
            assertEquals(.1f, unit.selfRegenRate)
            assertEquals(24, unit.fogOfWarSightRange)
            assertEquals(MovementType.WATER, unit.movement.type)
            assertEquals(.6, unit.movement.reverseSpeedPercentage)
            assertEquals(listOf("hoverTank"), unit.production!!.unitTypeIds)
            assertEquals("CUSTOM_NAUTILUS", unit.production.exitProfile.name)
            assertEquals("NAUTILUS_SUBMERGED", unit.transport!!.name)
            assertEquals(EnergyProfile(6f, .01f, .01f, true), unit.energy)
            assertNotNull(unit.combat)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
