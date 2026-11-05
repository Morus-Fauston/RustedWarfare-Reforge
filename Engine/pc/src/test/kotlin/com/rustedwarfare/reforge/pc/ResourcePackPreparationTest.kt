package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcePackPreparationTest {
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
