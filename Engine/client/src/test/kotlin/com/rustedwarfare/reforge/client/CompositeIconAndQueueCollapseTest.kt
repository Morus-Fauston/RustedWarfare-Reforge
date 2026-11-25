package com.rustedwarfare.reforge.client

import com.badlogic.gdx.math.Rectangle
import com.rustedwarfare.reforge.core.content.TurretMount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CompositeIconAndQueueCollapseTest {

    private fun solidBuffer(w: Int, h: Int, rgba: Int): CompositeUnitIconBuilder.RgbaBuffer =
        CompositeUnitIconBuilder.RgbaBuffer(w, h, IntArray(w * h) { rgba })

    @Test
    fun composite_icon_stacks_back_body_and_turret_and_tints_pure_green() {
        val backPath = Path.of("factory_back.png")
        val bodyPath = Path.of("factory_body.png")
        val turretPath = Path.of("tank_turret.png")

        // Back: wide dark steel floor (16x16), Body: narrower pure-green core (8x8), Turret: bright neutral barrel offset upward
        val backRgba = (80 shl 24) or (80 shl 16) or (80 shl 8) or 0xFF
        val bodyGreenRgba = (20 shl 24) or (220 shl 16) or (20 shl 8) or 0xFF
        val turretRgba = (220 shl 24) or (220 shl 16) or (220 shl 8) or 0xFF

        val buffers = mapOf(
            backPath to solidBuffer(16, 16, backRgba),
            bodyPath to solidBuffer(8, 8, bodyGreenRgba),
            turretPath to solidBuffer(4, 12, turretRgba),
        )

        val def = UnitAppearance(
            displayRadius = 20.0,
            imagePath = bodyPath,
            imageScale = 1.0,
            totalFrames = 1,
            turretImagePath = turretPath,
            turretImageScale = 1.0,
            backImagePath = backPath,
            turretMount = TurretMount(localX = 0.0, localY = 4.0),
        )

        val layers = CompositeUnitIconBuilder.planLayers(def) { p, _ -> buffers[p] }
        assertEquals(3, layers.size, "Expected BACK -> BODY -> TURRET layers")

        // Compose with P2 red (#E0463E) so pure green body visibly turns red while steel back stays gray
        val composed = CompositeUnitIconBuilder.compose(layers, playerRgb = 0xE0463E)
        assertNotNull(composed)
        composed!!
        assertEquals(composed.width, composed.height, "Output icon must be square")

        var hasRedTintedBody = false
        var hasSteelBack = false
        var hasBrightTurret = false
        for (px in composed.pixels) {
            val a = px and 0xFF
            if (a < 200) continue
            val r = (px ushr 24) and 0xFF
            val g = (px ushr 16) and 0xFF
            val b = (px ushr 8) and 0xFF
            if (r > 180 && g < 110 && b < 110) hasRedTintedBody = true
            if (r in 75..85 && g in 75..85 && b in 75..85) hasSteelBack = true
            if (r > 210 && g > 210 && b > 210) hasBrightTurret = true
        }
        assertTrue(hasSteelBack, "Factory back floor layer must be preserved in composite icon")
        assertTrue(hasRedTintedBody, "Pure-green body zone must be tinted to target player color")
        assertTrue(hasBrightTurret, "Turret layer must be drawn on top of body")
    }

    @Test
    fun composite_icon_honors_iconWeaponName_module_override() {
        val emptyBody = Path.of("empty.png")
        val weaponPath = Path.of("laser_module.png")
        val weaponRgba = (20 shl 24) or (200 shl 16) or (20 shl 8) or 0xFF

        val def = UnitAppearance(
            displayRadius = 12.0,
            imagePath = emptyBody,
            imageScale = 1.0,
            totalFrames = 1,
            weaponImages = mapOf(
                "laser" to WeaponAppearance(weaponPath, imageScale = 1.0, invisible = false),
            ),
            iconWeaponName = "laser",
        )
        val layers = CompositeUnitIconBuilder.planLayers(def) { p, _ ->
            if (p == weaponPath) solidBuffer(10, 10, weaponRgba) else null
        }
        assertEquals(1, layers.size, "Module with iconWeaponName should render single dedicated weapon layer")
        val composed = CompositeUnitIconBuilder.compose(layers, playerRgb = 0x2ECC40)
        assertNotNull(composed)
    }

    @Test
    fun queue_rle_preserves_interleaved_order_and_sums_overflow_items() {
        // [A, A, A, B, A] -> [A x3] [B x1] [A x1]
        val interleaved = HudSidebarProjection.buildSlots(listOf("tank", "tank", "tank", "heli", "tank"), 0.52f)
        assertEquals(5, interleaved.size)
        assertEquals("tank", interleaved[0].typeId)
        assertEquals(3, interleaved[0].count)
        assertEquals(0.52f, interleaved[0].progress ?: -1f, 1e-4f)

        assertEquals("heli", interleaved[1].typeId)
        assertEquals(1, interleaved[1].count)
        assertNull(interleaved[1].progress)

        assertEquals("tank", interleaved[2].typeId)
        assertEquals(1, interleaved[2].count)

        assertNull(interleaved[3].typeId)
        assertEquals(0, interleaved[3].count)
        assertEquals(0, interleaved[4].overflowCount)

        // 7 runs with multi-counts in overflow tail:
        // runs: [a x2], [b x1], [c x3], [d x1], [e x4], [f x2], [g x1] -> slot 4 = +(4+2+1) = +7
        val manyRuns = HudSidebarProjection.buildSlots(
            listOf("a", "a", "b", "c", "c", "c", "d", "e", "e", "e", "e", "f", "f", "g"),
            0.25f,
        )
        assertEquals("a", manyRuns[0].typeId); assertEquals(2, manyRuns[0].count)
        assertEquals("b", manyRuns[1].typeId); assertEquals(1, manyRuns[1].count)
        assertEquals("c", manyRuns[2].typeId); assertEquals(3, manyRuns[2].count)
        assertEquals("d", manyRuns[3].typeId); assertEquals(1, manyRuns[3].count)
        assertNull(manyRuns[4].typeId)
        assertEquals(7, manyRuns[4].overflowCount)
    }

    @Test
    fun queue_slot_rects_stay_inside_sidebar_and_do_not_overlap() {
        val queueRect = Rectangle(1200f, 300f, UiTokens.SIDEBAR_WIDTH, InGameHudOverlay.QUEUE_ROW_HEIGHT)
        for (lang in listOf(UiLanguage.ZH, UiLanguage.EN)) {
            val rects = InGameHudOverlay.computeQueueSlotRects(queueRect, lang, 5)
            assertEquals(5, rects.size)
            for (i in 0 until rects.size) {
                assertTrue(rects[i].x >= queueRect.x)
                assertTrue(rects[i].y >= queueRect.y)
                assertTrue(rects[i].y + rects[i].height <= queueRect.y + queueRect.height)
                if (i > 0) {
                    assertTrue(rects[i].x > rects[i - 1].x + rects[i - 1].width, "Slots must not overlap ($lang)")
                }
            }
            // Leave room on the right for progress percentage text (e.g. "100%")
            assertTrue(rects.last().x + rects.last().width < queueRect.x + queueRect.width - 26f)
        }
    }

    @Test
    fun composite_icon_handles_transparent_empty_and_oversized_inputs_safely() {
        val transparent = solidBuffer(16, 16, 0x00000000)
        assertNull(
            CompositeUnitIconBuilder.compose(
                listOf(CompositeUnitIconBuilder.CompositeLayer(transparent, 0.0, 0.0, 16.0, 16.0)),
            ),
        )
        assertNull(CompositeUnitIconBuilder.compose(emptyList()))

        val large = solidBuffer(40, 20, (20 shl 24) or (210 shl 16) or (20 shl 8) or 0xFF)
        val composed = CompositeUnitIconBuilder.compose(
            listOf(CompositeUnitIconBuilder.CompositeLayer(large, 0.0, 0.0, 260.0, 130.0)),
        )
        assertNotNull(composed)
        assertTrue(composed!!.width <= 128 && composed.height <= 128)
        assertEquals(composed.width, composed.height)
    }

    @Test
    fun queue_slot_hit_detection_matches_rendered_slot_bounds_and_kind_routing() {
        val queueRect = Rectangle(1200f, 300f, UiTokens.SIDEBAR_WIDTH, InGameHudOverlay.QUEUE_ROW_HEIGHT)
        val rects = InGameHudOverlay.computeQueueSlotRects(queueRect, UiLanguage.ZH, 5)
        val slots = HudSidebarProjection.buildSlots(listOf("builder", "builder", "c_tank"), 0.4f)
        // Slot 0 & 1 occupied with typeId (cancellable), slot 2..4 empty
        assertEquals("builder", slots[0].typeId)
        assertEquals(2, slots[0].count)
        assertEquals("c_tank", slots[1].typeId)
        assertEquals(1, slots[1].count)
        assertNull(slots[2].typeId)

        // Point inside slot 0 center hits only slot 0; point inside slot 1 center hits only slot 1
        val s0cx = rects[0].x + rects[0].width * 0.5f
        val s0cy = rects[0].y + rects[0].height * 0.5f
        assertTrue(rects[0].contains(s0cx, s0cy))
        assertTrue(rects.drop(1).none { it.contains(s0cx, s0cy) })

        val s1cx = rects[1].x + rects[1].width * 0.5f
        val s1cy = rects[1].y + rects[1].height * 0.5f
        assertTrue(rects[1].contains(s1cx, s1cy))
        assertTrue(!rects[0].contains(s1cx, s1cy))
    }
}
