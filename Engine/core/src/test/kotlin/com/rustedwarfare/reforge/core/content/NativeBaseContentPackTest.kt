package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeBaseContentPackTest {
    @Test
    fun a_land_factory_entry_cannot_admit_a_mech_factory_profile() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val root = Files.createTempDirectory("p008-native-base-pack-").toRealPath()
            try {
                val source = fixtures.resolve("heavy-mech-production/$format/production-package/units")
                for (name in listOf("mechFactory.$format", "factory.png", "factory_back.png", "factory_dead.png"))
                    Files.copy(source.resolve(name), root.resolve(name))
                Files.writeString(root.resolve("pack.json"), """{"format":"rw-reforge.content-pack.v1","id":"native.base","name":"Native base","units":[{"type":"land-factory","file":"mechFactory.$format"}]}""")
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
                assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, failure.code)
                assertEquals("format", failure.field)
            } finally {
                require(root.isAbsolute && root.fileName.toString().startsWith("p008-native-base-pack-"))
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun a_center_cannot_read_an_image_outside_its_pack_and_repair_restores_a_complete_pack() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = Files.createTempDirectory("p008-native-base-pack-").toRealPath()
        val outside = root.resolveSibling(root.fileName.toString() + "-outside.png")
        try {
            val source = fixtures.resolve("three-format-economy/construction/json/units")
            for (name in listOf("commandCenter.json", "base.png", "base_back.png", "base_dead.png"))
                Files.copy(source.resolve(name), root.resolve(name))
            Files.copy(source.resolve("base.png"), outside)
            Files.writeString(root.resolve("pack.json"), """{"format":"rw-reforge.content-pack.v1","id":"native.base","name":"Native base","units":[{"type":"command-center","file":"commandCenter.json"}]}""")
            val input = root.resolve("commandCenter.json")
            val original = Files.readString(input)
            Files.writeString(input, original.replace("\"base.png\"", "\"${outside.toString().replace('\\', '/')}\""))
            assertEquals(ContentErrorCode.BAD_REFERENCE, assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
            Files.writeString(input, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        } finally {
            require(root.isAbsolute && root.fileName.toString().startsWith("p008-native-base-pack-") && outside.parent == root.parent)
            Files.deleteIfExists(outside)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_native_land_factory_pack_retains_its_build_cost_and_native_production_profile_in_three_formats() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val root = Files.createTempDirectory("p008-native-base-pack-").toRealPath()
            try {
                val source = fixtures.resolve("three-format-economy/construction/$format/units")
                val files = listOf("landFactory.$format", "factory.png", "factory_back.png", "factory_dead.png")
                for (name in files) Files.copy(source.resolve(name), root.resolve(name))
                val manifest = root.resolve("pack.json")
                Files.writeString(manifest, """{"format":"rw-reforge.content-pack.v1","id":"native.base","name":"Native base","units":[{"type":"land-factory","file":"landFactory.$format"}]}""")

                val attempt = ContentLoader.loadContentPack(root)
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, attempt.toString()).value
                val factory = pack.units.getValue("landFactory")
                assertEquals(BuildProfile(700L, .001f), factory.build)
                assertEquals(ProductionProfile(listOf("builder", "c_tank"), FactoryExitProfile.NATIVE_LAND), factory.production)
                assertEquals(setOf(manifest) + files.map(root::resolve), pack.sources.map { it.path }.toSet())
                assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
            } finally {
                require(root.isAbsolute && root.fileName.toString().startsWith("p008-native-base-pack-"))
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun a_native_center_pack_preserves_its_production_income_and_complete_source_identity_in_three_formats() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val root = Files.createTempDirectory("p008-native-base-pack-").toRealPath()
            try {
                val source = fixtures.resolve("three-format-economy/construction/$format/units")
                val files = listOf("commandCenter.$format", "base.png", "base_back.png", "base_dead.png")
                for (name in files) Files.copy(source.resolve(name), root.resolve(name))
                val manifest = root.resolve("pack.json")
                Files.writeString(manifest, """{"format":"rw-reforge.content-pack.v1","id":"native.base","name":"Native base","units":[{"type":"command-center","file":"commandCenter.$format"}]}""")

                val attempt = ContentLoader.loadContentPack(root)
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, attempt.toString()).value
                assertEquals(setOf("commandCenter"), pack.units.keys)
                val center = pack.units.getValue("commandCenter")
                assertEquals(listOf("builder"), center.production!!.unitTypeIds)
                assertEquals(IncomeProfile(10f, 4.5f), center.income)
                assertEquals(setOf(manifest) + files.map(root::resolve), pack.sources.map { it.path }.toSet())
                assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
                Files.write(root.resolve("base.png"), byteArrayOf(1))
                assertIs<ContentResult.Failure>(pack.verifyStable())
            } finally {
                require(root.isAbsolute && root.fileName.toString().startsWith("p008-native-base-pack-"))
                root.toFile().deleteRecursively()
            }
        }
    }
}
