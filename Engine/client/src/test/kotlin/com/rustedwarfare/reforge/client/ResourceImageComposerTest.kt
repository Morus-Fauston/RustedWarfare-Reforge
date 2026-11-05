package com.rustedwarfare.reforge.client

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceImageComposerTest {
    private val body = ResourceId.parse("reforge:units/c_tank/body")
    private val turret = ResourceId.parse("reforge:units/c_tank/turret")
    private val dead = ResourceId.parse("reforge:units/c_tank/dead")

    @Test
    fun explicit_high_to_low_order_resolves_each_resource_and_freezes_actual_providers() {
        val base = layer("reforge:base", mapOf(body to image("base-body", 3), turret to image("base-turret", 1), dead to image("base-dead", 1)))
        val amber = layer("owned:amber", mapOf(body to image("amber", 3, scale = 2)))
        val blue = layer("owned:blue", mapOf(body to image("blue", 3, scale = 3), turret to image("blue-turret", 1, scale = 2)))
        val input = mutableListOf(blue, amber)
        val resolved = ResourceImageComposer.compose(base, input)
        input.clear()
        assertEquals(listOf("owned:blue", "owned:amber", "reforge:base"), resolved.packOrder)
        assertEquals(blue.images.getValue(body), resolved.images.getValue(body))
        assertEquals(blue.images.getValue(turret), resolved.images.getValue(turret))
        assertEquals(base.images.getValue(dead), resolved.images.getValue(dead))
        assertEquals(mapOf(body to "owned:blue", turret to "owned:blue", dead to "reforge:base"), resolved.providers)
        val reverse = ResourceImageComposer.compose(base, listOf(amber, blue))
        assertEquals(amber.images.getValue(body), reverse.images.getValue(body))
        assertEquals(blue.images.getValue(turret), reverse.images.getValue(turret))
        assertEquals(20, reverse.images.getValue(body).declaration.frameWidth)
        assertEquals(120, reverse.images.getValue(body).pixelWidth)
        assertFailsWith<UnsupportedOperationException> { (resolved.images as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (resolved.providers as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (resolved.packOrder as MutableList).clear() }
    }

    @Test
    fun a_hidden_bad_layout_and_duplicate_enabled_id_reject_the_whole_combination() {
        val base = layer("reforge:base", mapOf(body to image("base", 3)))
        val valid = layer("owned:valid", mapOf(body to image("valid", 3)))
        for (wrong in listOf(image("wrong-frames", 1), image("wrong-width", 3, width = 21))) {
            val bad = layer("owned:bad", mapOf(body to wrong))
            val failure = assertFailsWith<IllegalArgumentException> { ResourceImageComposer.compose(base, listOf(valid, bad)) }
            assertTrue(failure.message.orEmpty().contains("owned:bad"), failure.message)
            assertTrue(failure.message.orEmpty().contains(body.value), failure.message)
        }
        assertFailsWith<IllegalArgumentException> { ResourceImageComposer.compose(base, listOf(valid, valid)) }
        assertFailsWith<IllegalArgumentException> { ResourceImageComposer.compose(base, listOf(base)) }
    }

    @Test
    fun original_static_contract_and_custom_content_base_survive_user_reordering_and_partial_fallback() {
        val custom = ResourceId.parse("reforge:units/amber_scout/body")
        val future = ResourceId.parse("owned:future/picture")
        val base = layer("reforge:base", mapOf(body to image("animated-base", 3), custom to image("custom-base", 2, width = 12)))
        val original = layer("reforge:original-android-1.15-176", mapOf(body to image("static-original", 1)))
        val user = layer("owned:static", mapOf(body to image("static-hd", 1, scale = 2), future to image("future", 1)))
        val high = ResourceImageComposer.compose(base, listOf(user, original))
        assertEquals("owned:static", high.providers.getValue(body))
        assertEquals(1, high.images.getValue(body).declaration.totalFrames)
        assertEquals("reforge:base", high.providers.getValue(custom))
        assertEquals("owned:static", high.providers.getValue(future))
        val low = ResourceImageComposer.compose(base, listOf(original, user))
        assertEquals("reforge:original-android-1.15-176", low.providers.getValue(body))
        val animated = layer("owned:animated", mapOf(body to image("animated", 3)))
        assertFailsWith<IllegalArgumentException> { ResourceImageComposer.compose(base, listOf(original, animated)) }
        assertEquals("owned:animated", ResourceImageComposer.compose(base, listOf(animated)).providers.getValue(body))
    }

    private fun image(name: String, frames: Int, width: Int = 20, height: Int = 20, scale: Int = 1) =
        PreparedResourceImage(Path.of("does-not-exist/$name.png").toAbsolutePath(),
            ResourceImageDeclaration("$name.png", width, height, frames), width * frames * scale, height * scale)

    private fun layer(id: String, images: Map<ResourceId, PreparedResourceImage>): ResourceImageLayer {
        val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"$id","name":"$id","description":"Own pure composition fixture","icon":"${images.keys.first()}"}""".toByteArray(), "pack.json")
        return ResourceImageLayer(metadata, images)
    }
}
