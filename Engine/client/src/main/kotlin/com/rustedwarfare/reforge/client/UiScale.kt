package com.rustedwarfare.reforge.client

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Continuous uniform UI scale calculator (HUD Spec §2.1–2.3).
 *
 * - Reference short edge is 640 reference units (at 960x640, 1 ref unit = 1 physical pixel).
 * - Base scale from `r = min(width, height) / 640f`:
 *   - Small windows (`r <= 1`): linear `max(0.5f, r)`.
 *   - Large windows (`r > 1`): gentle nonlinear growth with minimum proportion floor
 *     `max(r^0.55, 0.50f * r)` (caps logical short edge at 1280, keeping 16:9 sidebar >= 10.2%).
 * - Desktop: `scale = sBase` (pure pixel short-edge, no desktop EDID/PPI auto-boost).
 * - Touch: `scale = max(sBase, sTouch)` where `sTouch = 8mm * pxPerMm / 58`
 *   ensures 58-ref-unit touch action keys are physically >= 8 mm tall.
 * - Zero non-uniform stretching; wider windows allocate all extra width to the battlefield while
 *   keeping the right HUD sidebar fixed at `UiTokens.SIDEBAR_WIDTH` (232 ref units).
 */
data class UiMetrics(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val scale: Float,
    val touch: Boolean,
    val pxPerMm: Float,
) {
    /** Total logical reference width of the viewport (`pixelWidth / scale`). */
    val logicalWidth: Float get() = if (scale > 0f) pixelWidth / scale else 960f

    /** Total logical reference height of the viewport (`pixelHeight / scale`). */
    val logicalHeight: Float get() = if (scale > 0f) pixelHeight / scale else 640f

    /** Logical width available to the battlefield outside the right sidebar. */
    val battlefieldLogicalWidth: Float get() = (logicalWidth - UiTokens.SIDEBAR_WIDTH).coerceAtLeast(1f)

    /** Converts reference units to physical pixels (Float). */
    fun px(refUnits: Float): Float = refUnits * scale

    /** Converts reference units to rounded physical pixels (at least 1px for positive values). */
    fun pxInt(refUnits: Float): Int {
        if (refUnits <= 0f) return 0
        return (refUnits * scale).roundToInt().coerceAtLeast(1)
    }

    /** Computes actual pixel size for a font at [refSize] reference units. */
    fun fontPixelSize(refSize: Int): Int = (refSize * scale).roundToInt().coerceIn(8, 128)
}

object UiScale {
    const val REFERENCE_SHORT_EDGE: Float = 640f
    const val MIN_SCALE_FLOOR: Float = 0.5f
    const val LARGE_WINDOW_EXPONENT: Double = 0.55
    const val MIN_LINEAR_PROPORTION_FLOOR: Float = 0.50f
    const val TOUCH_ACTION_KEY_HEIGHT_REF: Float = 58f
    const val TOUCH_MIN_KEY_MM: Float = 8f
    /** Scale change threshold for triggering font atlas rebuild. */
    const val FONT_REBUILD_EPSILON: Float = 0.02f
    /** Debounce window (seconds) after window resize drag settles before rebuilding FreeType fonts. */
    const val FONT_REBUILD_DEBOUNCE_SECONDS: Float = 0.16f

    /**
     * Computes the continuous nonlinear base scale from pixel dimensions:
     * - `r <= 1`: linear `max(0.5, r)`
     * - `r > 1`: `max(r^0.55, 0.50 * r)`
     */
    fun computeBaseScale(widthPixels: Int, heightPixels: Int): Float {
        val safeW = widthPixels.coerceAtLeast(1)
        val safeH = heightPixels.coerceAtLeast(1)
        val ratio = min(safeW, safeH).toFloat() / REFERENCE_SHORT_EDGE
        if (ratio <= 1f) return ratio.coerceAtLeast(MIN_SCALE_FLOOR)
        val nonlinear = ratio.toDouble().pow(LARGE_WINDOW_EXPONENT).toFloat()
        val proportionFloor = MIN_LINEAR_PROPORTION_FLOOR * ratio
        return maxOf(nonlinear, proportionFloor)
    }

    /**
     * Computes continuous uniform UI scale from window pixel dimensions and pixel density (`pxPerMm`).
     * Defensive lower clamp `0.5f` only guards degenerate sub-320px windows.
     */
    fun computeScaleFromPxPerMm(widthPixels: Int, heightPixels: Int, touch: Boolean = false, pxPerMm: Float = 0f): Float {
        val base = computeBaseScale(widthPixels, heightPixels)
        if (!touch) return base.coerceAtLeast(MIN_SCALE_FLOOR)
        val sTouch = if (pxPerMm > 0f) (TOUCH_MIN_KEY_MM * pxPerMm) / TOUCH_ACTION_KEY_HEIGHT_REF else 0f
        return maxOf(base, sTouch).coerceAtLeast(MIN_SCALE_FLOOR)
    }

    /**
     * Computes continuous uniform UI scale from window pixel dimensions and optional touch PPI.
     *
     * @param widthPixels client pixel width
     * @param heightPixels client pixel height
     * @param touch whether touch controls are active
     * @param ppi pixels-per-inch (`Gdx.graphics.ppiY` or `density * 160`); ignored when <= 0
     */
    fun computeScale(widthPixels: Int, heightPixels: Int, touch: Boolean = false, ppi: Float = 0f): Float {
        val pxPerMm = if (ppi > 0f) ppi / 25.4f else 0f
        return computeScaleFromPxPerMm(widthPixels, heightPixels, touch, pxPerMm)
    }

    /** Builds [UiMetrics] preferring `ppcY / 10` per HUD Spec §2.2, falling back to `ppiY / 25.4`, with optional user multiplier. */
    fun metricsFromDensity(
        widthPixels: Int,
        heightPixels: Int,
        touch: Boolean = false,
        ppcY: Float = 0f,
        ppiY: Float = 0f,
        userScaleMultiplier: Float = 1.0f,
    ): UiMetrics {
        val pxPerMm = when {
            ppcY > 0f -> ppcY / 10f
            ppiY > 0f -> ppiY / 25.4f
            else -> 0f
        }
        val safeMultiplier = userScaleMultiplier.takeIf(Float::isFinite)?.coerceIn(0.75f, 1.50f) ?: 1.0f
        val effectiveScale = (computeScaleFromPxPerMm(widthPixels, heightPixels, touch, pxPerMm) * safeMultiplier)
            .coerceAtLeast(MIN_SCALE_FLOOR)
        return UiMetrics(
            pixelWidth = widthPixels.coerceAtLeast(1),
            pixelHeight = heightPixels.coerceAtLeast(1),
            scale = effectiveScale,
            touch = touch,
            pxPerMm = pxPerMm,
        )
    }

    fun metrics(
        widthPixels: Int,
        heightPixels: Int,
        touch: Boolean = false,
        ppi: Float = 0f,
        userScaleMultiplier: Float = 1.0f,
    ): UiMetrics =
        metricsFromDensity(widthPixels, heightPixels, touch, ppcY = 0f, ppiY = ppi, userScaleMultiplier = userScaleMultiplier)

    /** True when the scale delta warrants regenerating pixel-crisp FreeType glyph atlases. */
    fun needsFontRebuild(oldScale: Float, newScale: Float): Boolean =
        oldScale <= 0f || abs(newScale - oldScale) >= FONT_REBUILD_EPSILON
}

/**
 * Pure-logic debounce tracker for window-resize font regeneration so dragging window borders
 * does not regenerate FreeType textures on every resize event.
 */
class FontRebuildDebouncer(private val delaySeconds: Float = UiScale.FONT_REBUILD_DEBOUNCE_SECONDS) {
    private var appliedScale: Float = 0f
    private var pendingScale: Float? = null
    private var elapsed: Float = 0f

    val currentAppliedScale: Float get() = appliedScale
    val hasPendingRebuild: Boolean get() = pendingScale != null

    fun markApplied(scale: Float) {
        appliedScale = scale
        pendingScale = null
        elapsed = 0f
    }

    /** Schedules a rebuild if [newScale] differs significantly from [appliedScale]. */
    fun onScaleChanged(newScale: Float) {
        if (!UiScale.needsFontRebuild(appliedScale, newScale)) {
            pendingScale = null
            elapsed = 0f
            return
        }
        pendingScale = newScale
        elapsed = 0f
    }

    /** Advances debounce timer by [deltaSeconds]; returns target scale when ready to rebuild, else null. */
    fun pollReadyScale(deltaSeconds: Float): Float? {
        val target = pendingScale ?: return null
        elapsed += deltaSeconds.coerceAtLeast(0f)
        if (elapsed >= delaySeconds) {
            pendingScale = null
            elapsed = 0f
            appliedScale = target
            return target
        }
        return null
    }
}
