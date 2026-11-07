package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class PersistedResourceAuditTest {
    @Test fun fresh_independent_qualification_still_rejects_changed_original_bytes_and_mixed_certificate_versions() {
        for (case in listOf("original", "version")) OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            if (case == "original") {
                val file = pack.manifest.parent.resolve("original/assets/picture.png")
                val bytes = Files.readAllBytes(file); val time = Files.getLastModifiedTime(file)
                Files.write(file, bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                Files.setLastModifiedTime(file, time)
            } else {
                backend.applyPrepared(backend.prepare(selected))
                val certificate = pack.manifest.parent.resolve("resource-extraction.json")
                Files.writeString(certificate, Files.readString(certificate).replace("owned.persist.images.v1", "owned.persist.images.v2"))
            }
            val restarted = fixture.backend()
            assertFailsWith<IllegalArgumentException> { restarted.prepare(selected) }
            assertNull(restarted.lastPreparationPath)
        }
    }

    @Test fun an_lf_checkout_cannot_reuse_old_byte_conclusions_but_can_be_checked_and_applied_again() {
        OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val parser = com.badlogic.gdx.utils.JsonReader()
            val pretty = parser.parse(Files.readString(pack.manifest)).prettyPrint(com.badlogic.gdx.utils.JsonWriter.OutputType.json, 0)
            assertTrue(pretty.contains('\n'))
            Files.writeString(pack.manifest, pretty.replace("\r\n", "\n").replace("\n", "\r\n"))
            rehashMetadata(pack.manifest.parent)
            // Preserve coverage of saved legacy byte audits; explicit migration has its own tests.
            val crlf = backend.inspectSavedImagePack(pack.manifest)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(crlf.catalogEntry), listOf(crlf.catalogEntry.id)))
            backend.applyPrepared(backend.prepare(selected))
            val oldAudit = Files.readAllBytes(fixture.audit)
            Files.writeString(pack.manifest, Files.readString(pack.manifest).replace("\r\n", "\n"))
            rehashMetadata(pack.manifest.parent)
            val restarted = fixture.backend()
            assertFailsWith<IllegalArgumentException> { restarted.prepare(restarted.initialSelection) }
            assertContentEquals(oldAudit, Files.readAllBytes(fixture.audit))
            val lf = restarted.inspectSavedImagePack(pack.manifest)
            assertNotEquals(crlf.catalogEntry.identity, lf.catalogEntry.identity)
            val checked = selected.copy(packs = ResourcePackSelection(listOf(lf.catalogEntry), listOf(lf.catalogEntry.id)))
            val prepared = restarted.prepare(checked)
            assertEquals(PcResourcePreparationPath.FULL_AUDIT, restarted.lastPreparationPath)
            restarted.applyPrepared(prepared)
            val next = fixture.backend()
            next.prepare(next.initialSelection).verifyForPublication()
            assertEquals(PcResourcePreparationPath.DISK_REUSE, next.lastPreparationPath)
        }
    }

    private fun rehashMetadata(root: java.nio.file.Path) {
        val parser = com.badlogic.gdx.utils.JsonReader()
        for (name in listOf("extraction.json", "resource-extraction.json")) {
            val certificate = root.resolve(name)
            val proof = parser.parse(Files.readString(certificate))
            if (name == "resource-extraction.json") {
                proof.remove("imageExtractionSha256")
                proof.addChild("imageExtractionSha256", com.badlogic.gdx.utils.JsonValue(visualHash(Files.readAllBytes(root.resolve("extraction.json")))))
            }
            for (row in proof.get("outputs")) {
                val bytes = Files.readAllBytes(root.resolve(row.getString("file")))
                row.remove("bytes"); row.addChild("bytes", com.badlogic.gdx.utils.JsonValue(bytes.size.toLong()))
                row.remove("sha256"); row.addChild("sha256", com.badlogic.gdx.utils.JsonValue(visualHash(bytes)))
            }
            Files.writeString(certificate, proof.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
        }
    }

    @Test fun cancellation_drops_an_unaccepted_pending_publication() {
        OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val unaccepted = backend.prepare(selected)
            Thread.currentThread().interrupt()
            try { assertFailsWith<java.util.concurrent.CancellationException> { backend.prepare(selected) } }
            finally { Thread.interrupted() }
            backend.onPublished(unaccepted)
            assertFalse(Files.exists(fixture.audit))
            backend.applyPrepared(backend.prepare(selected))
            assertTrue(Files.isRegularFile(fixture.audit))
        }
    }

    @Test fun a_restored_descriptor_still_rechecks_original_members_and_fixed_outputs_before_publication() {
        for (case in listOf("original", "fixed")) OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            backend.applyPrepared(backend.prepare(selected))
            val restarted = fixture.backend()
            val restored = restarted.prepare(restarted.initialSelection)
            assertEquals(PcResourcePreparationPath.DISK_REUSE, restarted.lastPreparationPath)
            val rawId = ResourceId.parse("reforge:original/pc/6173736574732f706963747572652e706e67")
            val member = if (case == "original") pack.manifest.parent.resolve("original/assets/picture.png")
                else assertNotNull(restored.resolvedImages).images.getValue(rawId).path
            val timestamp = Files.getLastModifiedTime(member)
            val bytes = Files.readAllBytes(member)
            Files.write(member, bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            Files.setLastModifiedTime(member, timestamp)
            assertFailsWith<IllegalArgumentException> { restored.verifyForPublication() }
        }
    }

    @Test fun missing_independent_facts_fall_back_to_full_audit_despite_an_existing_publication() {
        OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            backend.applyPrepared(backend.prepare(selected))
            assertTrue(Files.exists(fixture.audit))
            val restarted = fixture.backend(available = false)
            restarted.prepare(restarted.initialSelection).verifyForPublication()
            assertEquals(PcResourcePreparationPath.FULL_AUDIT, restarted.lastPreparationPath)
            assertTrue(restarted.lastDiskReuseReason.orEmpty().contains("fixture unavailable"))
        }
    }

    @Test fun extra_or_missing_members_cannot_reuse_a_published_qualified_pack() {
        OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            backend.applyPrepared(backend.prepare(selected))
            val extra = pack.manifest.parent.resolve("unaccounted.bin")
            Files.write(extra, byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalArgumentException> { fixture.backend().prepare(selected) }
            assertTrue(Files.isRegularFile(extra))
            Files.move(extra, fixture.source.resolve("retained-extra.bin"))
            val raw = pack.manifest.parent.resolve("original/assets/picture.png")
            Files.move(raw, fixture.source.resolve("retained-picture.png"))
            assertFailsWith<IllegalArgumentException> { fixture.backend().prepare(selected) }
        }
    }

    @Test fun preparation_alone_does_not_publish_a_disk_audit() {
        OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val prepared = backend.prepare(selected)
            assertFalse(Files.exists(fixture.audit))
            val restarted = fixture.backend()
            restarted.prepare(selected)
            assertEquals(PcResourcePreparationPath.FULL_AUDIT, restarted.lastPreparationPath)
            assertFalse(Files.exists(fixture.audit))
            backend.onPublished(prepared)
            assertTrue(Files.isRegularFile(fixture.audit))
        }
    }

    @Test fun forged_output_and_all_local_hashes_are_rebuilt_from_the_independent_source() {
        OwnedAuditFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.extractOriginalResourcePack(fixture.source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val prepared = backend.prepare(selected)
            backend.applyPrepared(prepared)
            val json = com.badlogic.gdx.utils.JsonReader()
            val audit = json.parse(Files.readString(fixture.audit))
            val row = audit.get("packs").first()
            val generation = java.nio.file.Path.of(row.getString("generation"))
            val fixedImage = generation.resolve("images/0.png")
            val wrong = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 8) for (y in 0 until 6) wrong.setRGB(x, y, 0xffff0000.toInt())
            assertTrue(ImageIO.write(wrong, "png", fixedImage.toFile()))
            val wrongBytes = Files.readAllBytes(fixedImage)
            fun replace(value: com.badlogic.gdx.utils.JsonValue, key: String, text: String) {
                value.remove(key); value.addChild(key, com.badlogic.gdx.utils.JsonValue(text))
            }
            val manifestFile = generation.resolve("manifest.json")
            val manifest = json.parse(Files.readString(manifestFile))
            val image = manifest.get("outputs").get("image0")
            image.remove("bytes"); image.addChild("bytes", com.badlogic.gdx.utils.JsonValue(wrongBytes.size.toLong()))
            replace(image, "sha256", visualHash(wrongBytes))
            val manifestBytes = manifest.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json).toByteArray()
            Files.write(manifestFile, manifestBytes)
            val readyFile = generation.parent.parent.resolve("ready.json")
            val ready = json.parse(Files.readString(readyFile))
            replace(ready, "manifestSha256", visualHash(manifestBytes))
            Files.writeString(readyFile, ready.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
            replace(row, "manifestSha256", visualHash(manifestBytes))
            Files.writeString(fixture.audit, audit.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
            val restarted = fixture.backend()
            val repaired = restarted.prepare(restarted.initialSelection)
            assertEquals(PcResourcePreparationPath.FULL_AUDIT, restarted.lastPreparationPath)
            assertNotNull(restarted.lastDiskReuseReason)
            val id = ResourceId.parse("reforge:original/pc/6173736574732f706963747572652e706e67")
            val actual = assertNotNull(repaired.resolvedImages).images.getValue(id).path
            assertEquals(0xff2050a0.toInt(), ImageIO.read(actual.toFile()).getRGB(0, 0))
            assertNotEquals(fixedImage, actual)
            assertContentEquals(wrongBytes, Files.readAllBytes(fixedImage))
        }
    }

    @Test fun an_applied_complete_audit_can_be_independently_restored_by_a_new_backend() {
        OwnedAuditFixture().use { fixture ->
            val first = fixture.backend()
            val pack = first.extractOriginalResourcePack(fixture.source)
            val selection = first.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val prepared = first.prepare(selection)
            assertEquals(PcResourcePreparationPath.FULL_AUDIT, first.lastPreparationPath)
            first.applyPrepared(prepared)
            val restarted = fixture.backend()
            val restored = restarted.prepare(restarted.initialSelection)
            assertEquals(PcResourcePreparationPath.DISK_REUSE, restarted.lastPreparationPath)
            val rawId = ResourceId.parse("reforge:original/pc/6173736574732f706963747572652e706e67")
            val raw = assertNotNull(restored.resolvedImages).images.getValue(rawId)
            assertEquals(0xff2050a0.toInt(), ImageIO.read(raw.path.toFile()).getRGB(0, 0))
            assertEquals(assertNotNull(prepared.resolvedImages).images.getValue(rawId).path, raw.path)
            val sound = assertNotNull(assertNotNull(restored.resolvedSounds).resolve(ResourceId.parse("reforge:events/tank-fire")))
            assertEquals("e513e421c5541bfd4b00ad6084903d4b951ecfa1b4c5d20836afb95817c551dd", sound.sha256)
            assertEquals(2, sound.frames)
            assertSame(restored, restarted.prepare(restarted.initialSelection))
            assertEquals(PcResourcePreparationPath.PROCESS_REUSE, restarted.lastPreparationPath)
        }
    }

    /** Independent tiny authored PNG and two literal PCM samples; no original inputs. */
    private class OwnedAuditFixture : AutoCloseable {
        private val owned = ResourceImagePackApplicationFixture()
        val audit get() = owned.cache.resolve("checked-audit.json")
        val source = Files.createDirectory(owned.root.resolve("trusted source"))
        val profile: PcOriginalResourceProfile
        private val outputs: PcTrustedOutputIndex
        init {
            for (folder in listOf("assets", "res/raw", "font")) Files.createDirectories(source.resolve(folder))
            val jar = "Self-owned qualification identity, not executable".toByteArray()
            Files.write(source.resolve("game-lib.jar"), jar)
            val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 8) for (y in 0 until 6) image.setRGB(x, y, 0xff2050a0.toInt())
            assertTrue(ImageIO.write(image, "png", source.resolve("assets/picture.png").toFile()))
            val png = Files.readAllBytes(source.resolve("assets/picture.png"))
            val wav = Base64.getDecoder().decode("UklGRigAAABXQVZFZm10IBAAAAABAAEAIlYAAESsAAACABAAZGF0YQQAAABkAJz/")
            Files.write(source.resolve("res/raw/beep.wav"), wav)
            val images = PcOriginalImageProfile("owned.persist.images.v1", jar.size, visualHash(jar), listOf(
                PcOriginalImageSource("assets/picture.png", png.size, visualHash(png), "PNG", 8, 6)))
            profile = PcOriginalResourceProfile("owned.persist.resources.v1", images, listOf(
                PcOriginalSoundSource("res/raw/beep.wav", 48, "e513e421c5541bfd4b00ad6084903d4b951ecfa1b4c5d20836afb95817c551dd", "PCM16_WAV", 1, 22050)),
                mapOf("reforge:events/tank-fire" to "res/raw/beep.wav"))
            val producer = mapOf("owned-fixture-v1" to "f".repeat(64))
            val json = """{"schemaVersion":1,"producers":{"owned-fixture-v1":"${"f".repeat(64)}"},"profiles":[{
                "id":"${profile.id}","basis":"${PcTrustedOutputIndex.basis(profile)}",
                "images":[{"file":"renderable/${visualHash("assets/picture.png".toByteArray())}.png","bytes":${png.size},
                "sha256":"${visualHash(png)}","pixelSha256":"ed7db6bc779c44674076d57601d682f827550c61c13894d570d7ac9acede6e3f","width":8,"height":6}],"mapped":[],
                "sounds":[{"file":"sounds/${visualHash("res/raw/beep.wav".toByteArray())}.wav","bytes":48,
                "sha256":"e513e421c5541bfd4b00ad6084903d4b951ecfa1b4c5d20836afb95817c551dd","channels":1,"sampleRate":22050,"frames":2}]}]}"""
            outputs = PcTrustedOutputIndex.fromReviewed(json.toByteArray(), producer)
            assertNotNull(outputs.forProfile(profile))
        }
        fun backend(available: Boolean = true) = PcVisualAssetSettings(owned.store, owned.cache, owned.root,
            WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), owned.root), resourceExtractionProfile = profile,
            trustedOutputs = if (available) outputs else PcTrustedOutputIndex.unavailable("fixture unavailable"))
        override fun close() = owned.close()
    }
}
