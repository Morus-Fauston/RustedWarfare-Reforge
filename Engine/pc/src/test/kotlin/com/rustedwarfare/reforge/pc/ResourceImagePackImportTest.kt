package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceImagePackImportTest {
    @Test fun a_forged_self_consistent_disk_generation_cannot_replace_the_checked_source_pixels_after_restart() {
        fixture { _, pack, cache ->
            val first = PcResourcePackImporter(cache).load(pack)
            val id = ResourceId.parse("reforge:units/c_tank/body")
            val oldImage = first.images.getValue(id).path
            val generation = oldImage.parent.parent
            val manifestPath = generation.resolve("manifest.json")
            val readyPath = generation.parent.parent.resolve("ready.json")
            writePng(oldImage, 0xff2040d0.toInt())
            val forgedBytes = Files.readAllBytes(oldImage)
            val json = com.badlogic.gdx.utils.JsonReader()
            val manifest = json.parse(Files.readString(manifestPath))
            val output = manifest.get("outputs").get("image0")
            output.remove("bytes"); output.addChild("bytes", com.badlogic.gdx.utils.JsonValue(forgedBytes.size.toLong()))
            output.remove("sha256"); output.addChild("sha256", com.badlogic.gdx.utils.JsonValue(visualHash(forgedBytes)))
            val forgedManifest = manifest.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json).toByteArray()
            Files.write(manifestPath, forgedManifest)
            val ready = json.parse(Files.readString(readyPath))
            ready.remove("manifestSha256"); ready.addChild("manifestSha256", com.badlogic.gdx.utils.JsonValue(visualHash(forgedManifest)))
            Files.writeString(readyPath, ready.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
            val restarted = PcResourcePackImporter(cache).load(pack)
            assertContentEquals(Files.readAllBytes(pack.resolve("images/body.png")), Files.readAllBytes(restarted.images.getValue(id).path))
            assertNotEquals(oldImage, restarted.images.getValue(id).path, "Untrusted self-consistency must rebuild a new fixed generation")
            assertContentEquals(forgedBytes, Files.readAllBytes(oldImage), "The old bad generation remains available for diagnosis")
        }
    }

    @Test fun aliases_share_png_bytes_but_each_logical_layout_must_be_valid() {
        fixture { _, pack, cache ->
            val declaration = pack.resolve("images.json")
            val original = Files.readString(declaration)
            val body = "\"reforge:units/c_tank/body\""
            val row = "\"file\":\"images/body.png\",\"frameWidth\":20,\"frameHeight\":20,\"totalFrames\":3"
            Files.writeString(declaration, """{"formatVersion":1,"images":{$body:{$row},"owned:alias":{$row}}}""")
            val loaded = PcResourcePackImporter(cache).load(pack)
            assertEquals(2, loaded.images.size)
            assertContentEquals(Files.readAllBytes(loaded.images.getValue(ResourceId.parse("owned:alias")).path),
                Files.readAllBytes(loaded.images.getValue(ResourceId.parse("reforge:units/c_tank/body")).path))
            Files.writeString(declaration, Files.readString(declaration).replace("\"owned:alias\":{\"file\":\"images/body.png\",\"frameWidth\":20",
                "\"owned:alias\":{\"file\":\"images/body.png\",\"frameWidth\":21"))
            val error = assertFailsWith<IllegalArgumentException> { PcResourcePackImporter(cache).load(pack) }
            assertTrue(error.message.orEmpty().contains("aspect"), error.message)
            Files.writeString(declaration, original)
            loaded.verify()
        }
    }

    @Test
    fun frozen_audit_bytes_are_shared_but_cache_publication_rechecks_the_actual_sources() {
        fixture { _, pack, cache ->
            PcFrozenResourceInputs.capture(listOf(pack)).use { inputs ->
                val image = pack.resolve("images/body.png")
                inputs.read(image, 16 * 1024 * 1024)
                val bytes = Files.readAllBytes(image)
                val modified = Files.getLastModifiedTime(image)
                Files.write(image, bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                Files.setLastModifiedTime(image, modified)
                val failure = assertFailsWith<IllegalArgumentException> { PcResourcePackImporter(cache).load(pack, inputs) }
                assertTrue(failure.message.orEmpty().contains("Source changed after checking"), failure.message)
                assertTrue(failure.message.orEmpty().contains("body.png"), failure.message)
            }
        }
    }

    @Test
    fun a_missing_checked_source_is_a_package_diagnostic_while_the_old_generation_stays_valid() {
        fixture { _, pack, cache ->
            val loaded = PcResourcePackImporter(cache).load(pack)
            Files.delete(pack.resolve("images/body.png"))
            val failure = assertFailsWith<IllegalArgumentException> { loaded.verifySources() }
            assertTrue(failure.message.orEmpty().contains("RESOURCE_PACK"), failure.message)
            assertTrue(failure.message.orEmpty().contains("body.png"), failure.message)
            loaded.verify()
        }
    }

    @Test
    fun a_new_conflicting_image_manifest_invalidates_the_checked_import_without_altering_its_cache() {
        fixture { _, pack, cache ->
            val loaded = PcResourcePackImporter(cache).load(pack)
            Files.copy(pack.resolve("images.json"), pack.resolve("images.hjson"))
            val failure = assertFailsWith<IllegalArgumentException> { loaded.verifySources() }
            assertTrue(failure.message.orEmpty().contains("RESOURCE_PACK"), failure.message)
            assertTrue(failure.message.orEmpty().contains("images"), failure.message)
            loaded.verify()
        }
    }

    @Test
    fun real_png_import_is_read_only_and_cached_bytes_remain_frozen_after_source_changes() {
        fixture { root, pack, cache ->
            val before = snapshot(pack)
            val importer = PcResourcePackImporter(cache)
            val loaded = importer.load(pack.resolve("pack.json"))
            assertEquals("owned:amber", loaded.metadata.id.value)
            assertEquals(pack.resolve("pack.json"), loaded.manifest)
            assertEquals(3, loaded.sources.size)
            assertEquals(before, snapshot(pack))
            val id = ResourceId.parse("reforge:units/c_tank/body")
            val image = loaded.images.getValue(id)
            assertTrue(image.path.startsWith(cache) && !image.path.startsWith(pack))
            assertEquals(120, image.pixelWidth)
            assertEquals(40, image.pixelHeight)
            assertEquals(20, image.declaration.frameWidth)
            assertEquals(20, image.declaration.frameHeight)
            assertEquals(3, image.declaration.totalFrames)
            assertContentEquals(Files.readAllBytes(pack.resolve("images/body.png")), Files.readAllBytes(image.path))
            loaded.verify(); loaded.verifySources()
            val hit = importer.load(pack)
            assertEquals(loaded.identity, hit.identity)
            assertEquals(image.path, hit.images.getValue(id).path)
            val frozen = Files.readAllBytes(image.path)
            writePng(pack.resolve("images/body.png"), 0xff2040d0.toInt())
            loaded.verify()
            assertContentEquals(frozen, Files.readAllBytes(image.path))
            assertTrue(assertFailsWith<IllegalArgumentException> { loaded.verifySources() }.message.orEmpty().contains("body.png"))
            val changed = importer.load(pack)
            assertNotEquals(loaded.identity, changed.identity)
            assertNotEquals(image.path, changed.images.getValue(id).path)
            assertTrue(changed.sources.all { it.path.startsWith(root) })
            assertFailsWith<UnsupportedOperationException> { (loaded.images as MutableMap).clear() }
            assertFailsWith<UnsupportedOperationException> { (loaded.sources as MutableList).clear() }
        }
    }

    @Test
    fun bad_declared_png_layout_icon_and_input_boundaries_are_rejected_instead_of_becoming_missing_overrides() {
        for (case in listOf("png", "crc", "frames", "aspect", "icon", "two-images", "version", "parent", "overlap")) {
            fixture { _, pack, cache ->
                val image = pack.resolve("images/body.png")
                val declaration = pack.resolve("images.json")
                when (case) {
                    "png" -> Files.writeString(image, "Not a PNG")
                    "crc" -> Files.write(image, Files.readAllBytes(image).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                    "frames" -> Files.writeString(declaration, Files.readString(declaration).replace("\"totalFrames\":3", "\"totalFrames\":7"))
                    "aspect" -> Files.writeString(declaration, Files.readString(declaration).replace("\"frameWidth\":20", "\"frameWidth\":21"))
                    "icon" -> Files.writeString(pack.resolve("pack.json"), Files.readString(pack.resolve("pack.json")).replace("reforge:units/c_tank/body", "owned:absent"))
                    "two-images" -> Files.copy(declaration, pack.resolve("images.hjson"))
                    "version" -> Files.writeString(pack.resolve("pack.json"), Files.readString(pack.resolve("pack.json")).replace("\"formatVersion\":1", "\"formatVersion\":9"))
                    "parent" -> Files.writeString(declaration, Files.readString(declaration).replace("images/body.png", "../outside.png"))
                }
                val before = snapshot(pack)
                val error = assertFailsWith<IllegalArgumentException>(case) {
                    PcResourcePackImporter(if (case == "overlap") pack.resolve("bad-cache") else cache).load(pack)
                }
                assertTrue(error.message.orEmpty().contains("RESOURCE_PACK"), error.message)
                if (case in listOf("png", "crc", "frames", "aspect")) assertTrue(error.message.orEmpty().contains("body.png"), error.message)
                assertEquals(before, snapshot(pack), "Rejected import cannot rewrite its source")
            }
        }
    }

    private fun fixture(block: (Path, Path, Path) -> Unit) {
        val root = Files.createTempDirectory("image-pack-自有-").toAbsolutePath().normalize()
        try {
            val pack = Files.createDirectories(root.resolve("pack"))
            Files.createDirectories(pack.resolve("images"))
            writePng(pack.resolve("images/body.png"), 0xffd09020.toInt())
            Files.writeString(pack.resolve("pack.json"), """{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:amber","name":"自有 Amber","description":"Partial high resolution images","icon":"reforge:units/c_tank/body"}""")
            Files.writeString(pack.resolve("images.json"), """{"formatVersion":1,"images":{"reforge:units/c_tank/body":{"file":"images/body.png","frameWidth":20,"frameHeight":20,"totalFrames":3}}}""")
            block(root, pack, root.resolve("cache"))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun writePng(path: Path, color: Int) {
        val image = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) for (y in 0 until image.height) image.setRGB(x, y, color)
        ImageIO.write(image, "png", path.toFile())
    }

    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to visualHash(Files.readAllBytes(it)) }
    }
}
