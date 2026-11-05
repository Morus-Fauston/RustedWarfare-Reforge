package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResourcePackPreparationTest {
    @Test
    fun unreferenced_package_members_do_not_acquire_the_declared_media_size_limit() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val pack = fixture.create("owned:large-member", listOf(OwnPackImage("reforge:units/c_tank/body")))
            java.nio.file.Files.newOutputStream(pack.manifest.parent.resolve("unreferenced-backup.bin")).use { stream ->
                val chunk = ByteArray(1024 * 1024)
                repeat(17) { stream.write(chunk) }
            }
            val backend = fixture.backend()
            val selection = VisualAssetSelection(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val prepared = backend.prepare(selection)
            prepared.verifyForPublication()
            assertSame(prepared, backend.prepare(selection))
        }
    }

    @Test
    fun warm_reuse_rejects_changed_declared_bytes_broken_outputs_and_cancellation() {
        for (case in listOf("source", "output", "cancel")) {
            ResourceImagePackApplicationFixture().use { fixture ->
                val pack = fixture.create("owned:failure", listOf(OwnPackImage("reforge:units/c_tank/body")))
                val backend = fixture.backend()
                val selection = VisualAssetSelection(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
                val prepared = backend.prepare(selection)
                when (case) {
                    "source" -> {
                        val path = pack.manifest
                        val bytes = java.nio.file.Files.readAllBytes(path)
                        val time = java.nio.file.Files.getLastModifiedTime(path)
                        java.nio.file.Files.write(path, bytes.toString(Charsets.UTF_8).replace("Actual", "actual").toByteArray())
                        java.nio.file.Files.setLastModifiedTime(path, time)
                        assertFailsWith<IllegalArgumentException> { backend.prepare(selection) }
                        java.nio.file.Files.write(path, bytes)
                    }
                    "output" -> {
                        val path = prepared.units.getValue("c_tank").body
                        val bytes = java.nio.file.Files.readAllBytes(path)
                        java.nio.file.Files.write(path, bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                        assertFailsWith<IllegalArgumentException> { backend.prepare(selection) }
                        java.nio.file.Files.write(path, bytes)
                    }
                    else -> {
                        Thread.currentThread().interrupt()
                        try { assertFailsWith<java.util.concurrent.CancellationException> { backend.prepare(selection) } }
                        finally { Thread.interrupted() }
                    }
                }
                backend.prepare(selection).verifyForPublication()
            }
        }
    }

    @Test
    fun package_membership_and_order_changes_require_a_new_audit() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val first = fixture.create("owned:first", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val second = fixture.create("owned:second", listOf(OwnPackImage("reforge:units/c_tank/body", color = 0xff2040d0.toInt())))
            val backend = fixture.backend()
            fun selection(order: List<String>) = VisualAssetSelection(packs = ResourcePackSelection(listOf(first.catalogEntry, second.catalogEntry), order))
            val order = listOf(first.metadata.id.value, second.metadata.id.value)
            val prepared = backend.prepare(selection(order))
            java.nio.file.Files.write(first.manifest.parent.resolve("new-member"), byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { backend.prepare(selection(order)) }
            val changed = backend.prepare(selection(order))
            assertNotSame(prepared, changed)
            val reversed = backend.prepare(selection(order.reversed()))
            assertEquals(order.reversed() + "reforge:base", reversed.resolvedImages?.packOrder)
            assertEquals(second.images.values.first().path, reversed.units.getValue("c_tank").body)
            assertNotSame(changed, reversed)
        }
    }

    @Test
    fun publication_rechecks_qualification_members_outside_the_image_declarations() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val pack = fixture.create("owned:proof", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val proof = pack.manifest.parent.resolve("audit-input.bin")
            java.nio.file.Files.write(proof, byteArrayOf(1, 2, 3))
            val backend = fixture.backend()
            val selection = VisualAssetSelection(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val prepared = backend.prepare(selection)
            val time = java.nio.file.Files.getLastModifiedTime(proof)
            java.nio.file.Files.write(proof, byteArrayOf(1, 2, 4))
            java.nio.file.Files.setLastModifiedTime(proof, time)
            prepared.verify() // GL recovery needs only the frozen output, not mutable inputs.
            assertFailsWith<IllegalArgumentException> { prepared.verifyForPublication() }
        }
    }

    @Test
    fun unchanged_selection_reuses_the_audited_non_graphical_description() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val pack = fixture.create("owned:reuse", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val backend = fixture.backend()
            val selection = VisualAssetSelection(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val first = backend.prepare(selection)
            val second = backend.prepare(selection)
            assertSame(first, second, "An unchanged same-process selection must reuse its audited description")
            second.verifyForPublication()
        }
    }

    @Test
    fun ready_diagnostics_record_the_prepared_pack_and_resource_addresses_with_legacy_fields_intact() {
        val prepared = PcPlaceholderVisualAssets().prepare(VisualAssetSelection())
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val evidence = ClientEvidence { kind, values -> events += kind to values }
        evidence.recordVisualAssetsReady(prepared, 37L)
        val (kind, values) = events.single()
        assertEquals("visual-assets-ready", kind)
        assertEquals("placeholder", values["source"])
        assertEquals(prepared.profileId, values["profile"])
        assertEquals(prepared.identity, values["identity"])
        assertEquals(37L, values["generation"])
        assertEquals(prepared.units.keys.toList(), values["units"])
        assertEquals(prepared.terrain.keys.toList(), values["terrain"])
        val pack = values["resourcePack"] as Map<*, *>
        assertEquals("reforge:base", pack["id"])
        assertEquals(1, pack["formatVersion"])
        assertEquals(2, pack["requiresResourceFormat"])
        assertEquals("reforge:units/c_tank/body", pack["icon"])
        val resources = pack["resources"] as Map<*, *>
        assertEquals(prepared.resourcePack.resources.mapKeys { it.key.value }.mapValues { it.value.toString() }, resources)
        assertEquals(prepared.units.getValue("c_tank").body.toString(), resources["reforge:units/c_tank/body"])
        evidence.recordVisualAssetsReady(prepared)
        assertTrue("generation" !in events.last().second, "Demo keeps its generation-free legacy shape")
    }

    @Test
    fun both_existing_preparers_publish_namespaced_binding_tables_without_changing_their_prepared_paths() {
        val placeholder = PcPlaceholderVisualAssets().prepare(VisualAssetSelection())
        assertEquals("reforge:base", placeholder.resourcePack.metadata.id.value)
        assertEquals(placeholder.units.getValue("c_tank").body,
            placeholder.resourcePack.resolve(placeholder.resourcePack.metadata.icon))
        checkBindings(placeholder)
        VisualAssetTestFixture().use { fixture ->
            val original = fixture.service().prepareVisuals(VisualAssetSelection(VisualAssetSource.ORIGINAL, fixture.root))
            assertEquals("reforge:original-android-1.15-176", original.resourcePack.metadata.id.value)
            assertEquals("self-owned", original.profileId)
            assertEquals(1, original.units.getValue("own").totalFrames)
            checkBindings(original)
            original.verify()
        }
        placeholder.verify()
    }

    private fun checkBindings(prepared: PreparedVisualAssets) {
        val pack = prepared.resourcePack
        assertEquals(prepared.selection.resourcePackId, pack.metadata.id.value)
        for ((id, images) in prepared.units) {
            val binding = pack.unitBindings.getValue(id)
            assertEquals(images.body, pack.resolve(binding.body))
            assertEquals(images.back, binding.back?.let(pack::resolve))
            assertEquals(images.turret, binding.turret?.let(pack::resolve))
            assertEquals(images.dead, binding.dead?.let(pack::resolve))
            assertEquals(images.totalFrames, binding.totalFrames)
            assertEquals("reforge:units/${id.lowercase(java.util.Locale.ROOT)}/body", binding.body.value)
        }
        for ((layout, image) in prepared.terrain) {
            val id = pack.terrainBindings.getValue(layout)
            assertEquals("reforge:terrain/$layout", id.value)
            assertEquals(image, pack.resolve(id))
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { pack.resolve(ResourceId.parse("reforge:missing")) }
            .message.orEmpty().contains(pack.metadata.id.value))
        assertFailsWith<UnsupportedOperationException> { (pack.resources as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (pack.unitBindings as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (pack.terrainBindings as MutableMap).clear() }
    }
}
