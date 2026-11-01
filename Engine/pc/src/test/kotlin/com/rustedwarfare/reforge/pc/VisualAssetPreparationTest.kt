package com.rustedwarfare.reforge.pc

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource

/** Self-authored tiny images. No original files or private directories are needed. */
class VisualAssetPreparationTest {
    @Test fun cache_output_inside_the_original_source_is_rejected_before_any_directory_is_created() {
        VisualAssetTestFixture().use { fixture ->
            val forbidden = fixture.root.resolve("must-stay-absent")
            val failure = assertFailsWith<IllegalArgumentException> { PcVisualAssetService(forbidden, fixture.profile).prepare(fixture.root) }
            assertTrue(failure.message.orEmpty().contains("SOURCE_ROOT_INVALID"))
            assertTrue(Files.notExists(forbidden))
        }
    }
    @Test fun prepares_the_same_generation_as_an_immutable_render_binding() {
        VisualAssetTestFixture().use { fixture ->
            val selected = VisualAssetSelection(VisualAssetSource.ORIGINAL, fixture.root, temporary = true)
            val visuals = fixture.service().prepareVisuals(selected)
            assertEquals(selected, visuals.selection)
            assertEquals("self-owned", visuals.profileId)
            assertEquals(1, visuals.units.getValue("own").totalFrames)
            assertEquals(fixture.service().prepare(fixture.root).outputs.getValue("body"), visuals.units.getValue("own").body)
            visuals.verify()
        }
    }
    @Test fun refuses_wrong_version_corruption_dimensions_and_a_non_directory() {
        VisualAssetTestFixture().use { fixture ->
            Files.writeString(fixture.root.resolve("AndroidManifest.xml"), "wrong version")
            assertTrue(assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                .message.orEmpty().contains("SOURCE_VERSION_UNSUPPORTED"))
            assertTrue(assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.source) }
                .message.orEmpty().contains("SOURCE_ROOT_INVALID"))
        }
        VisualAssetTestFixture(transformProfile = { it.replace("\"width\":4", "\"width\":5") }).use { fixture ->
            assertTrue(assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                .message.orEmpty().contains("dimensions"))
        }
        VisualAssetTestFixture(transformImage = { byteArrayOf(1, 2, 3) }).use { fixture ->
            assertTrue(assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                .message.orEmpty().contains("SOURCE_ASSET_MISMATCH"))
        }
        VisualAssetTestFixture().use { fixture ->
            val bytes = Files.readAllBytes(fixture.source)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(fixture.source, bytes)
            assertTrue(assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                .message.orEmpty().contains("SHA-256"))
        }
    }

    @Test fun rejects_a_linked_child_directory_even_when_its_target_is_inside_the_selected_root() {
        VisualAssetTestFixture(path = "assets/own.png").use { fixture ->
            val link = fixture.root.resolve("assets")
            val target = fixture.root.resolve("real-assets")
            Files.move(link, target)
            try {
                try { Files.createSymbolicLink(link, target) }
                catch (failure: java.io.IOException) {
                    if (!System.getProperty("os.name").startsWith("Windows")) throw failure
                    val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true).start()
                    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                    assertEquals(0, process.waitFor(), output)
                }
                assertTrue(assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                    .message.orEmpty().contains("SOURCE_ASSET_MISMATCH"))
            } finally { Files.deleteIfExists(link) }
        }
    }
    @Test fun production_profile_is_fixed_complete_metadata_without_machine_paths() {
        val profile = VisualAssetProfile.bundled()
        assertEquals("rw.android.1.15.176.visual.v1", profile.id)
        assertEquals(30, profile.json.get("sources").size)
        assertEquals(27, profile.json.get("unitOutputs").size)
        assertEquals(5, profile.json.get("terrainOutputs").size)
        assertEquals(16, profile.json.get("unitBindings").size)
        assertEquals(32, profile.recipes.size)
        assertEquals("815e383faa17ba46dc42ee596b299d14b95da696e604cc21fd97a11bad630251",
            profile.json.get("identity").getString("manifestSha256"))
        assertEquals(null, profile.json.get("sourceRootObserved"))
        assertEquals(null, profile.json.get("baseline"))
    }
    @Test fun reports_all_missing_members_and_never_uses_a_cache_with_invalid_source() {
        VisualAssetTestFixture().use { fixture ->
            fixture.service().prepare(fixture.root)
            Files.delete(fixture.source)
            Files.delete(fixture.root.resolve("AndroidManifest.xml"))
            val failure = assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
            assertTrue(failure.message.orEmpty().contains("AndroidManifest.xml"))
            assertTrue(failure.message.orEmpty().contains("own.png"))
            assertTrue(failure.message.orEmpty().contains("SOURCE_ASSET_MISSING"))
        }
    }
    @Test fun strip_composites_nearest_cutouts_over_grass_without_losing_alpha_background() {
        VisualAssetTestFixture(""""unitOutputs":{},"terrainOutputs":{"strip":{
          "file":"terrain/strip.png","operation":"compose-strip","target":[4,1],
          "background":{"source":"own","rect":[0,0,2,1],"target":[2,1],"filter":"nearest"},
          "cells":[{"source":"own","rect":[2,0,1,1],"target":[2,1],"filter":"nearest"},
                   {"source":"own","rect":[3,0,1,1],"target":[2,1],"filter":"nearest"}],"blend":"source-over"}}""").use { fixture ->
            val prepared = fixture.service().prepare(fixture.root)
            val output = ImageIO.read(prepared.outputs.getValue("strip").toFile())
            assertEquals(4, output.width)
            assertEquals(0xffff0000.toInt(), output.getRGB(0, 0))
            assertEquals(0xff00ff00.toInt(), output.getRGB(1, 0))
            assertEquals(0xff7f0080.toInt(), output.getRGB(2, 0))
            assertEquals(0xff007f80.toInt(), output.getRGB(3, 0))
        }
    }
    @Test fun prepares_first_frame_without_changing_source_and_reuses_complete_generation() {
        val temporary = Files.createTempDirectory("visual-assets-中文 source-")
        val root = Files.createDirectory(temporary.resolve("source"))
        try {
            val manifest = "self-owned test manifest".toByteArray()
            Files.write(root.resolve("AndroidManifest.xml"), manifest)
            val image = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, 0xffff0000.toInt())
            image.setRGB(1, 0, 0x8000ff00.toInt())
            image.setRGB(2, 0, 0xff0000ff.toInt())
            image.setRGB(3, 0, 0xffffffff.toInt())
            ImageIO.write(image, "png", root.resolve("own.png").toFile())
            val original = Files.readAllBytes(root.resolve("own.png"))
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val profile = VisualAssetProfile.parse("""{
              "schemaVersion":1,"profileId":"self-owned-test",
              "identity":{"manifestPath":"AndroidManifest.xml","manifestBytes":${manifest.size},"manifestSha256":"${hash(manifest)}"},
              "sources":[{"id":"own","path":"own.png","bytes":${original.size},"sha256":"${hash(original)}","width":4,"height":1}],
              "unitOutputs":{"body":{"file":"units/body.png","operation":"crop","source":"own","rect":[0,0,2,1],"target":[2,1],"frames":1}},
              "unitBindings":{"own":{"body":"body"}},"terrainOutputs":{}
            }""".toByteArray())
            val service = PcVisualAssetService(temporary.resolve("private-cache"), profile)
            val prepared = service.prepare(root)
            val output = ImageIO.read(prepared.outputs.getValue("body").toFile())
            assertEquals(2, output.width)
            assertEquals(1, output.height)
            assertEquals(0xffff0000.toInt(), output.getRGB(0, 0))
            assertEquals(0x8000ff00.toInt(), output.getRGB(1, 0))
            assertContentEquals(original, Files.readAllBytes(root.resolve("own.png")))
            assertEquals(prepared.generation, service.prepare(root).generation)
            // A running match must detect corruption of its fixed generation; next start repairs into a new one.
            Files.write(prepared.outputs.getValue("body"), byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalArgumentException> { prepared.verify() }
            val repaired = service.prepare(root)
            assertNotEquals(prepared.generation, repaired.generation)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(prepared.outputs.getValue("body")))
            assertEquals(0xffff0000.toInt(), ImageIO.read(repaired.outputs.getValue("body").toFile()).getRGB(0, 0))
        } finally { temporary.toFile().deleteRecursively() }
    }
}
