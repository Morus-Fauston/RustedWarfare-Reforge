package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AmphibiousContentFailureTest {
    @Test fun three_formats_reject_unknown_fields_capabilities_bad_images_and_missing_t2_references_with_sources() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val root = Files.createTempDirectory("reforge-amphibious-failure-").toRealPath()
            try {
                val source = fixtures.resolve("amphibious-production/$format/production-package")
                Files.walk(source).use { paths -> paths.forEach { path ->
                    val target = root.resolve(source.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
                } }
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
                assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
                val composed = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root))).value
                assertEquals(root.resolve("units/amphibiousJet.$format"), composed.provenance.getValue("amphibiousJet").entryPath)
                val unit = root.resolve("units/amphibiousJet.$format")
                val original = Files.readString(unit)
                val unknown = if (format == "ini") original + "\nunsupportedJetAbility=true\n"
                    else original.replaceFirst("{", "{\"unsupportedJetAbility\":true,")
                for ((text, field, code) in listOf(
                    Triple(original.replace("530", "531"), "maxHp", ContentErrorCode.UNSUPPORTED_FEATURE),
                    Triple(original.replace("jet_left.png", "missing-wing.png"), "image_wing_left", ContentErrorCode.BAD_REFERENCE),
                    Triple(unknown, "unsupportedJetAbility", ContentErrorCode.UNSUPPORTED_FIELD))) {
                    assertNotEquals(original, text)
                    Files.writeString(unit, text)
                    val result = ContentLoader.loadContentPack(root)
                    val error = assertIs<ContentResult.Failure>(result, "$format/$field: $result").error
                    assertEquals(code, error.code)
                    assertEquals(field, error.field)
                    assertEquals(unit.toString(), error.file)
                }
                Files.writeString(unit, original)
                val manifest = root.resolve("pack.json")
                val originalManifest = Files.readString(manifest)
                Files.writeString(manifest, originalManifest.replace("\"amphibious-jet\"", "\"unbounded-amphibious\""))
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
                Files.writeString(manifest, originalManifest)
                val factory = root.resolve("units/airFactoryT2.$format")
                Files.writeString(factory, Files.readString(factory).replace("amphibiousJet", "missingJet"))
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
                assertIs<ContentResult.Failure>(pack.verifyStable())
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    require(path.toAbsolutePath().normalize().startsWith(root)); Files.delete(path)
                } }
            }
        }
    }
}
