package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import kotlin.math.abs
import kotlin.math.max

/**
 * 固定 8 色对局玩家色板与纯绿通道对局玩家着色规则（UI-P002 补充规格 §4.1–§4.2）。
 *
 * - 统一供战场单位纯绿通道着色、小地图单位点与敌方选中卡左侧 4px 色条读取。
 * - 严格按对局玩家槽位序映射，中立玩家统一使用 `#9AA3A8`。
 * - 着色器同时兼容桌面 OpenGL（LWJGL3）与 Android OpenGL ES 2.0/3.0，无 `#version` 桌面限定语法。
 */
object MatchPlayerColors {
    /** 中立对局玩家灰 `#9AA3A8`。 */
    const val NEUTRAL_RGB: Int = 0x9AA3A8

    /** 固定 8 色对局玩家色板（槽位 0..7 对应 P1..P8）。 */
    val PALETTE_RGB: List<Int> = listOf(
        0x2ECC40, // P1 绿（原版经典己方绿）
        0xE0463E, // P2 红
        0x3B82F6, // P3 蓝
        0xEAB308, // P4 黄
        0x06B6D4, // P5 青
        0xA855F7, // P6 紫
        0xF97316, // P7 橙
        0xEC4899, // P8 粉
    )

    /** 敌方单层选中圈色（沿用 UiTokens.FOE `#E0564A`）。 */
    const val FOE_SELECTION_RGB: Int = 0xE0564A

    /** 纯绿通道判定阈值（与原版 `pureGreenTeamColor.frag` 一致，兼容缩小时线性过滤偏差）。 */
    const val PURE_GREEN_THRESHOLD: Float = 0.04f

    const val UNIFORM_PLAYER_COLOR: String = "u_playerColor"

    val VERTEX_SHADER: String = """
        attribute vec4 ${ShaderProgram.POSITION_ATTRIBUTE};
        attribute vec4 ${ShaderProgram.COLOR_ATTRIBUTE};
        attribute vec2 ${ShaderProgram.TEXCOORD_ATTRIBUTE}0;
        uniform mat4 u_projTrans;
        varying vec4 v_color;
        varying vec2 v_texCoords;

        void main() {
            v_color = ${ShaderProgram.COLOR_ATTRIBUTE};
            v_color.a = v_color.a * (255.0 / 254.0);
            v_texCoords = ${ShaderProgram.TEXCOORD_ATTRIBUTE}0;
            gl_Position = u_projTrans * ${ShaderProgram.POSITION_ATTRIBUTE};
        }
    """.trimIndent()

    val FRAGMENT_SHADER: String = """
        #ifdef GL_ES
        #define LOWP lowp
        precision mediump float;
        #else
        #define LOWP
        #endif
        varying LOWP vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform vec4 $UNIFORM_PLAYER_COLOR;

        void main() {
            vec4 color = texture2D(u_texture, v_texCoords);
            if (color.g > 0.0 && abs(color.r - color.b) <= 0.04) {
                float lightness = color.r;
                float greenness = max(0.0, color.g - lightness);
                color.rgb = clamp(lightness + $UNIFORM_PLAYER_COLOR.rgb * greenness, 0.0, 1.0);
            }
            gl_FragColor = color * v_color;
        }
    """.trimIndent()

    fun createPlayerColorShader(): ShaderProgram {
        val shader = ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (!shader.isCompiled) {
            val log = shader.log
            shader.dispose()
            throw IllegalStateException("PLAYER_COLOR_SHADER_COMPILE_FAILED: $log")
        }
        return shader
    }

    /** 判断玩家 ID 是否为中立玩家。 */
    fun isNeutralPlayer(playerId: String): Boolean {
        val lower = playerId.trim().lowercase()
        return lower == "neutral" || lower.startsWith("neutral-") || lower == "none" || lower == "-1"
    }

    /**
     * 解析对局玩家槽位序对应的 24-bit RGB。
     *
     * 优先按显式编号 `p1..pN`（1-based -> 槽位 `N - 1`）映射，使 `p1` 恒为绿、`p2` 恒为红、`p3` 恒为蓝；
     * 若玩家 ID 非 `p<N>` 形式，则按 `orderedPlayerIds` 中的非中立顺序槽位映射；中立玩家固定返回 `#9AA3A8`。
     */
    fun rgbForPlayer(playerId: String, orderedPlayerIds: List<String> = emptyList()): Int {
        if (isNeutralPlayer(playerId)) return NEUTRAL_RGB
        parseIndexedPlayerSlot(playerId)?.let { slot ->
            return PALETTE_RGB[Math.floorMod(slot, PALETTE_RGB.size)]
        }
        val nonNeutral = orderedPlayerIds.filterNot(::isNeutralPlayer)
        val slot = nonNeutral.indexOf(playerId)
        if (slot >= 0) {
            return PALETTE_RGB[Math.floorMod(slot, PALETTE_RGB.size)]
        }
        return NEUTRAL_RGB
    }

    fun colorForPlayer(playerId: String, orderedPlayerIds: List<String> = emptyList(), alpha: Float = 1f): Color =
        rgbToColor(rgbForPlayer(playerId, orderedPlayerIds), alpha)

    fun rgbToColor(rgb: Int, alpha: Float = 1f): Color = Color(
        ((rgb ushr 16) and 0xFF) / 255f,
        ((rgb ushr 8) and 0xFF) / 255f,
        (rgb and 0xFF) / 255f,
        alpha,
    )

    internal fun parseIndexedPlayerSlot(playerId: String): Int? {
        val trimmed = playerId.trim()
        if (trimmed.length < 2) return null
        val prefix = trimmed[0]
        if (prefix != 'p' && prefix != 'P') return null
        val num = trimmed.substring(1).toIntOrNull() ?: return null
        if (num < 1) return null
        return num - 1
    }

    /**
     * CPU 端纯绿通道着色参考实现（与片元着色器逐像素等价）。
     * 用于单元测试验证以及后续复合多图层图标的离线 CPU 着色。
     */
    fun applyPureGreenTint(
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        playerR: Float,
        playerG: Float,
        playerB: Float,
        tintR: Float = 1f,
        tintG: Float = 1f,
        tintB: Float = 1f,
        tintA: Float = 1f,
    ): FloatArray {
        var outR = r
        var outG = g
        var outB = b
        if (g > 0f && abs(r - b) <= PURE_GREEN_THRESHOLD) {
            val lightness = r
            val greenness = max(0f, g - lightness)
            outR = (lightness + playerR * greenness).coerceIn(0f, 1f)
            outG = (lightness + playerG * greenness).coerceIn(0f, 1f)
            outB = (lightness + playerB * greenness).coerceIn(0f, 1f)
        }
        return floatArrayOf(
            (outR * tintR).coerceIn(0f, 1f),
            (outG * tintG).coerceIn(0f, 1f),
            (outB * tintB).coerceIn(0f, 1f),
            (a * tintA).coerceIn(0f, 1f),
        )
    }

    /**
     * 按需状态条与选中圈可见性判定（UI-P002 补充规格 §4.3）。
     */
    fun shouldDrawSelectionRing(unitId: String, selectedIds: Set<String>): Boolean = unitId in selectedIds

    fun shouldDrawHealthBar(
        alive: Boolean,
        emptyAttachment: Boolean,
        health: Double?,
        maxHealth: Double?,
        selected: Boolean,
    ): Boolean {
        if (!alive || emptyAttachment || health == null || maxHealth == null || maxHealth <= 0.0) return false
        return selected || health < maxHealth - 1e-6
    }

    fun shouldDrawShieldBar(alive: Boolean, current: Float, maximum: Float, selected: Boolean): Boolean {
        if (!alive || maximum <= 0f) return false
        return selected || current < maximum - 1e-4f
    }

    fun shouldDrawConstructionBar(alive: Boolean, constructionProgress: Float): Boolean =
        alive && constructionProgress < 1f

    fun shouldDrawPointDefenseBar(alive: Boolean, energy: Float, exhausted: Boolean, selected: Boolean): Boolean {
        if (!alive) return false
        return selected || exhausted || energy < 1f - 1e-4f
    }

    fun shouldDrawEnergyBar(alive: Boolean, current: Float, maximum: Float, recharging: Boolean, selected: Boolean): Boolean {
        if (!alive || maximum <= 0f) return false
        return selected || recharging || current < maximum - 1e-4f
    }
}
