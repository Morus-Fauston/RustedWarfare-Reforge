package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MatchPlayerColorsTest {

    @Test
    fun eight_player_palette_and_neutral_mapping_follow_spec() {
        val expected = listOf(
            "p1" to 0x2ECC40,
            "p2" to 0xE0463E,
            "p3" to 0x3B82F6,
            "p4" to 0xEAB308,
            "p5" to 0x06B6D4,
            "p6" to 0xA855F7,
            "p7" to 0xF97316,
            "p8" to 0xEC4899,
        )
        for ((pid, rgb) in expected) {
            assertEquals(rgb, MatchPlayerColors.rgbForPlayer(pid), "Mismatch for $pid")
        }
        // Wrap around after 8 slots
        assertEquals(0x2ECC40, MatchPlayerColors.rgbForPlayer("p9"))
        assertEquals(0xE0463E, MatchPlayerColors.rgbForPlayer("p10"))

        // Neutral variants
        assertEquals(0x9AA3A8, MatchPlayerColors.rgbForPlayer("neutral"))
        assertEquals(0x9AA3A8, MatchPlayerColors.rgbForPlayer("neutral-a"))
        assertEquals(0x9AA3A8, MatchPlayerColors.rgbForPlayer("none"))
        assertEquals(0x9AA3A8, MatchPlayerColors.rgbForPlayer("-1"))

        // Ordered fallback for non-p<N> player IDs
        val order = listOf("neutral", "alpha", "bravo", "charlie")
        assertEquals(0x2ECC40, MatchPlayerColors.rgbForPlayer("alpha", order))
        assertEquals(0xE0463E, MatchPlayerColors.rgbForPlayer("bravo", order))
        assertEquals(0x3B82F6, MatchPlayerColors.rgbForPlayer("charlie", order))
        assertEquals(0x9AA3A8, MatchPlayerColors.rgbForPlayer("unknown", order))
    }

    @Test
    fun pure_green_tint_recolors_green_channels_while_preserving_neutral_steel_and_alpha() {
        val p2 = MatchPlayerColors.rgbToColor(0xE0463E) // P2 red

        // Pure green pixel (0, 1, 0, 1) -> becomes exact player color
        val pureGreen = MatchPlayerColors.applyPureGreenTint(0f, 1f, 0f, 1f, p2.r, p2.g, p2.b)
        assertEquals(p2.r, pureGreen[0], 1e-4f)
        assertEquals(p2.g, pureGreen[1], 1e-4f)
        assertEquals(p2.b, pureGreen[2], 1e-4f)
        assertEquals(1f, pureGreen[3], 1e-4f)

        // Bright green with lightness (0.2, 0.9, 0.2) -> lightness + player * 0.7
        val shadedGreen = MatchPlayerColors.applyPureGreenTint(0.2f, 0.9f, 0.2f, 0.8f, p2.r, p2.g, p2.b)
        assertEquals((0.2f + p2.r * 0.7f).coerceIn(0f, 1f), shadedGreen[0], 1e-4f)
        assertEquals((0.2f + p2.g * 0.7f).coerceIn(0f, 1f), shadedGreen[1], 1e-4f)
        assertEquals((0.2f + p2.b * 0.7f).coerceIn(0f, 1f), shadedGreen[2], 1e-4f)
        assertEquals(0.8f, shadedGreen[3], 1e-4f)

        // Pure neutral metallic gray (0.5, 0.5, 0.5) -> greenness == 0, completely untouched
        val steelGray = MatchPlayerColors.applyPureGreenTint(0.5f, 0.5f, 0.5f, 1f, p2.r, p2.g, p2.b)
        assertEquals(0.5f, steelGray[0], 1e-4f)
        assertEquals(0.5f, steelGray[1], 1e-4f)
        assertEquals(0.5f, steelGray[2], 1e-4f)

        // Non-green colored accent (e.g. cyan laser core: R=0.43, G=0.86, B=0.94 -> |R-B| = 0.51 > 0.04) -> untouched
        val cyanCore = MatchPlayerColors.applyPureGreenTint(0.43f, 0.86f, 0.94f, 1f, p2.r, p2.g, p2.b)
        assertEquals(0.43f, cyanCore[0], 1e-4f)
        assertEquals(0.86f, cyanCore[1], 1e-4f)
        assertEquals(0.94f, cyanCore[2], 1e-4f)

        // Fog / construction tint multiplier (v_color) preserved
        val dimmed = MatchPlayerColors.applyPureGreenTint(
            0f, 1f, 0f, 1f, p2.r, p2.g, p2.b,
            tintR = 0.5f, tintG = 0.5f, tintB = 0.5f, tintA = 0.45f,
        )
        assertEquals(p2.r * 0.5f, dimmed[0], 1e-4f)
        assertEquals(p2.g * 0.5f, dimmed[1], 1e-4f)
        assertEquals(p2.b * 0.5f, dimmed[2], 1e-4f)
        assertEquals(0.45f, dimmed[3], 1e-4f)
    }

    @Test
    fun shader_source_is_gles2_and_desktop_compatible_without_desktop_version_directive() {
        assertFalse(MatchPlayerColors.VERTEX_SHADER.contains("#version"))
        assertFalse(MatchPlayerColors.FRAGMENT_SHADER.contains("#version"))
        assertTrue(MatchPlayerColors.FRAGMENT_SHADER.contains("#ifdef GL_ES"))
        assertTrue(MatchPlayerColors.FRAGMENT_SHADER.contains("precision mediump float;"))
        assertTrue(MatchPlayerColors.FRAGMENT_SHADER.contains(MatchPlayerColors.UNIFORM_PLAYER_COLOR))
    }

    @Test
    fun on_demand_selection_ring_and_status_bars_hide_full_unselected_units() {
        val selected = setOf("u-selected")
        assertFalse(MatchPlayerColors.shouldDrawSelectionRing("u-idle", selected))
        assertTrue(MatchPlayerColors.shouldDrawSelectionRing("u-selected", selected))

        // Full health + unselected -> hidden
        assertFalse(MatchPlayerColors.shouldDrawHealthBar(alive = true, emptyAttachment = false, health = 200.0, maxHealth = 200.0, selected = false))
        // Damaged + unselected -> shown
        assertTrue(MatchPlayerColors.shouldDrawHealthBar(alive = true, emptyAttachment = false, health = 199.0, maxHealth = 200.0, selected = false))
        // Full health + selected -> shown
        assertTrue(MatchPlayerColors.shouldDrawHealthBar(alive = true, emptyAttachment = false, health = 200.0, maxHealth = 200.0, selected = true))
        // Dead or empty slot -> hidden even if selected
        assertFalse(MatchPlayerColors.shouldDrawHealthBar(alive = false, emptyAttachment = false, health = 0.0, maxHealth = 200.0, selected = true))
        assertFalse(MatchPlayerColors.shouldDrawHealthBar(alive = true, emptyAttachment = true, health = 50.0, maxHealth = 200.0, selected = true))

        // Shield bar
        assertFalse(MatchPlayerColors.shouldDrawShieldBar(alive = true, current = 100f, maximum = 100f, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawShieldBar(alive = true, current = 75f, maximum = 100f, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawShieldBar(alive = true, current = 100f, maximum = 100f, selected = true))

        // Construction bar
        assertTrue(MatchPlayerColors.shouldDrawConstructionBar(alive = true, constructionProgress = 0.6f))
        assertFalse(MatchPlayerColors.shouldDrawConstructionBar(alive = true, constructionProgress = 1.0f))

        // Point defense & energy bars
        assertFalse(MatchPlayerColors.shouldDrawPointDefenseBar(alive = true, energy = 1f, exhausted = false, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawPointDefenseBar(alive = true, energy = 0.4f, exhausted = false, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawPointDefenseBar(alive = true, energy = 1f, exhausted = false, selected = true))

        assertFalse(MatchPlayerColors.shouldDrawEnergyBar(alive = true, current = 10f, maximum = 10f, recharging = false, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawEnergyBar(alive = true, current = 10f, maximum = 10f, recharging = true, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawEnergyBar(alive = true, current = 5f, maximum = 10f, recharging = false, selected = false))
        assertTrue(MatchPlayerColors.shouldDrawEnergyBar(alive = true, current = 10f, maximum = 10f, recharging = false, selected = true))
    }

    @Test
    fun render_resources_player_and_selection_ring_colors_sync_with_palette() {
        val tank = UnitDefinition("tank", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet(), 1)
        val scenario = Scenario(
            "player-color-test",
            MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25) { 1 }, BlockingGrid(5, 5, BooleanArray(25))),
            mapOf("tank" to tank),
            listOf(
                Player("p1", PlayerControl.LOCAL, "team-1"),
                Player("p2", PlayerControl.NONE, "team-2"),
                Player("p3", PlayerControl.NONE, "team-1"),
                Player("neutral", PlayerControl.NONE, "neutral"),
            ),
            listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            MovementContract(16.0, 4.0),
        )
        val res = SandboxRenderResources.from(scenario)
        assertEquals(0x2ECC40, res.playerColorRgb("p1"))
        assertEquals(0xE0463E, res.playerColorRgb("p2"))
        assertEquals(0x3B82F6, res.playerColorRgb("p3"))
        assertEquals(0x9AA3A8, res.playerColorRgb("neutral"))

        // Own & ally rings take their player color; foe takes FOE red; neutral takes neutral gray
        assertEquals(MatchPlayerColors.rgbToColor(0x2ECC40), res.selectionRingColor("p1", "p1"))
        assertEquals(MatchPlayerColors.rgbToColor(0x3B82F6), res.selectionRingColor("p1", "p3"))
        assertEquals(MatchPlayerColors.rgbToColor(MatchPlayerColors.FOE_SELECTION_RGB), res.selectionRingColor("p1", "p2"))
        assertEquals(MatchPlayerColors.rgbToColor(MatchPlayerColors.NEUTRAL_RGB), res.selectionRingColor("p1", "neutral"))
    }
}
