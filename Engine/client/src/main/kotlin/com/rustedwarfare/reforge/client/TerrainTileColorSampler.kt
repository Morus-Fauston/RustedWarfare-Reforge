package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Pixmap
import com.rustedwarfare.reforge.core.content.GroundRules
import com.rustedwarfare.reforge.core.content.MapVisualLayer
import com.rustedwarfare.reforge.core.content.MapVisualLayerKind
import kotlin.math.floor
import kotlin.math.ceil

/** Precomputed RGBA average color and opaque coverage ratio [0..1] for one tile GID. */
data class TileAverageColor(
    val r: Int,
    val g: Int,
    val b: Int,
    val alphaRatio: Float,
)

/** Visible tile-grid bounding box in inclusive [minCol..maxCol] x [minRow..maxRow] coordinates. */
data class VisibleTileBounds(
    val minCol: Int,
    val maxCol: Int,
    val minRow: Int,
    val maxRow: Int,
)

/** Pure math & color-sampling helpers for viewport culling, tile average colors, and minimap compositing. */
object TerrainTileColorSampler {
    /**
     * Computes the inclusive visible tile range for [projection] with [padTiles] margin on each side
     * to prevent edge flicker when panning or zooming.
     */
    fun visibleBounds(
        projection: MapProjection,
        widthTiles: Int,
        heightTiles: Int,
        tileWidth: Int,
        tileHeight: Int,
        padTiles: Int = 1,
    ): VisibleTileBounds {
        if (widthTiles <= 0 || heightTiles <= 0) return VisibleTileBounds(0, -1, 0, -1)
        val area = projection.mapArea
        val topLeft = projection.clippedWorld(area.left, area.bottom + area.height)
        val bottomRight = projection.clippedWorld(area.left + area.width, area.bottom)
        if (topLeft == null || bottomRight == null) {
            return VisibleTileBounds(0, widthTiles - 1, 0, heightTiles - 1)
        }
        val minCol = (floor(minOf(topLeft.x, bottomRight.x) / tileWidth).toInt() - padTiles).coerceIn(0, widthTiles - 1)
        val maxCol = (ceil(maxOf(topLeft.x, bottomRight.x) / tileWidth).toInt() + padTiles).coerceIn(0, widthTiles - 1)
        val minRow = (floor(minOf(topLeft.y, bottomRight.y) / tileHeight).toInt() - padTiles).coerceIn(0, heightTiles - 1)
        val maxRow = (ceil(maxOf(topLeft.y, bottomRight.y) / tileHeight).toInt() + padTiles).coerceIn(0, heightTiles - 1)
        return VisibleTileBounds(minCol, maxCol, minRow, maxRow)
    }

    /** Computes non-transparent average RGB + opaque coverage ratio of a tile region in a Pixmap. */
    fun samplePixmapTile(pixmap: Pixmap, x0: Int, y0: Int, w: Int, h: Int): TileAverageColor {
        val xEnd = (x0 + w).coerceAtMost(pixmap.width)
        val yEnd = (y0 + h).coerceAtMost(pixmap.height)
        val xStart = x0.coerceAtLeast(0)
        val yStart = y0.coerceAtLeast(0)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var aSum = 0L
        var opaqueCount = 0
        var total = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                total++
                val rgba = pixmap.getPixel(x, y)
                val a = rgba and 0xFF
                if (a > 16) {
                    val r = (rgba ushr 24) and 0xFF
                    val g = (rgba ushr 16) and 0xFF
                    val b = (rgba ushr 8) and 0xFF
                    rSum += r * a
                    gSum += g * a
                    bSum += b * a
                    aSum += a
                    opaqueCount++
                }
            }
        }
        if (aSum == 0L || total == 0) {
            return TileAverageColor(0, 0, 0, 0f)
        }
        return TileAverageColor(
            r = (rSum / aSum).toInt().coerceIn(0, 255),
            g = (gSum / aSum).toInt().coerceIn(0, 255),
            b = (bSum / aSum).toInt().coerceIn(0, 255),
            alphaRatio = (opaqueCount.toFloat() / total.toFloat()).coerceIn(0f, 1f),
        )
    }

    /**
     * Composites bottom-to-top tile average colors for a single cell, applying alpha-weighted blending
     * for upper visual layers (GroundDetails, GroundDetails2, Items) and highlighting resource pools.
     */
    fun compositeCellRgb(
        cellIndex: Int,
        groundGid: Int,
        visualLayers: List<MapVisualLayer>,
        tileColors: Map<Int, TileAverageColor>,
        isResourcePool: Boolean = false,
    ): Int {
        val base = tileColors[GroundRules.stripFlipFlags(groundGid)] ?: TileAverageColor(82, 114, 70, 1f)
        var r = base.r.toFloat()
        var g = base.g.toFloat()
        var b = base.b.toFloat()

        for (layer in visualLayers) {
            if (layer.kind == MapVisualLayerKind.GROUND) continue
            val clean = layer.cleanGidAt(cellIndex)
            if (clean == 0) continue
            val overlay = tileColors[clean] ?: continue
            val w = overlay.alphaRatio
            if (w > 0.05f) {
                r = r * (1f - w) + overlay.r * w
                g = g * (1f - w) + overlay.g * w
                b = b * (1f - w) + overlay.b * w
            }
        }
        if (isResourcePool) {
            // Subtle high-readability amber/gold boost on minimap for mineral pools
            r = (r * 0.35f + 226f * 0.65f)
            g = (g * 0.35f + 186f * 0.65f)
            b = (b * 0.35f + 62f * 0.65f)
        }
        val ri = r.toInt().coerceIn(0, 255)
        val gi = g.toInt().coerceIn(0, 255)
        val bi = b.toInt().coerceIn(0, 255)
        return (ri shl 24) or (gi shl 16) or (bi shl 8) or 0xFF
    }

    /** Darkens an RGBA8888 color by [factor] (0.45f for explored fogged minimap cells) while keeping alpha 255. */
    fun darkenRgba(rgba: Int, factor: Float): Int {
        val r = (((rgba ushr 24) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((rgba ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = (((rgba ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (r shl 24) or (g shl 16) or (b shl 8) or 0xFF
    }

    /**
     * Resolves Tiled's 3-bit flip/rotate flags into (rotationDegreesCCW, scaleX, scaleY)
     * for libGDX SpriteBatch.draw(..., originX, originY, width, height, scaleX, scaleY, rotation).
     *
     * Tiled convention (y-down screen coordinates):
     * - H (0x80000000): flip horizontally
     * - V (0x40000000): flip vertically
     * - D (0x20000000): anti-diagonal transpose (swap x and y) applied before H/V
     */
    fun decodeTiledTransform(flipFlags: Int): Triple<Float, Float, Float> {
        val h = (flipFlags and GroundRules.FLIP_HORIZONTAL_FLAG) != 0
        val v = (flipFlags and GroundRules.FLIP_VERTICAL_FLAG) != 0
        val d = (flipFlags and GroundRules.FLIP_DIAGONAL_FLAG) != 0
        return when {
            !d && !h && !v -> Triple(0f, 1f, 1f)
            !d && h && !v -> Triple(0f, -1f, 1f)
            !d && !h && v -> Triple(0f, 1f, -1f)
            !d && h && v -> Triple(180f, 1f, 1f)
            // With diagonal transpose (anti-diagonal in Tiled y-down = 90 CW / -90 CCW + flip):
            d && h && !v -> Triple(-90f, 1f, 1f)   // 90° CW
            d && !h && v -> Triple(90f, 1f, 1f)    // 90° CCW (270° CW)
            d && !h && !v -> Triple(90f, -1f, 1f)  // transpose (x<->y)
            else -> Triple(-90f, -1f, 1f)          // d && h && v -> anti-transpose
        }
    }
}
