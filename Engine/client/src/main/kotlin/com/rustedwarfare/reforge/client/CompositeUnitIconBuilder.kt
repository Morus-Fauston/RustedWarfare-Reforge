package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Pixmap
import com.rustedwarfare.reforge.core.content.NuclearSiloKind
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 离线 CPU 复合多图层单位图标合成器（UI-P002 补充规格 §5.1）。
 *
 * - 将 `BACK -> BODY(frame 0) -> ARM1/ARM2 -> TURRET/NAMED_TURRET -> ROTOR` 按单位真实逻辑尺寸与挂点偏移
 *   在纯 CPU 像素缓冲上叠合，按本地玩家色（默认 P1 绿 `#2ECC40`）执行与着色器同源的纯绿通道着色，
 *   最后按非透明外接包围盒自动裁切并保留适度边距，生成跨平台桌面/Android 暂停恢复确定性一致的单张图标。
 * - 若 `UnitAppearance.iconWeaponName` 显式指定模块图标（如蜘蛛武器模块），直接单独渲染该武器图层。
 */
object CompositeUnitIconBuilder {

    /** 单个图层的纯内存 RGBA8888 图像缓冲（无 GL 上下文依赖，便于单元测试与跨平台复现）。 */
    class RgbaBuffer(val width: Int, val height: Int, val pixels: IntArray) {
        init {
            require(width > 0 && height > 0) { "Invalid buffer size: ${width}x${height}" }
            require(pixels.size == width * height) { "Pixel array length mismatch" }
        }

        fun getRgba(x: Int, y: Int): Int = pixels[y * width + x]
        fun setRgba(x: Int, y: Int, rgba: Int) { pixels[y * width + x] = rgba }

        companion object {
            fun fromPixmap(pixmap: Pixmap, srcX: Int = 0, srcY: Int = 0, w: Int = pixmap.width, h: Int = pixmap.height): RgbaBuffer {
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        out[y * w + x] = pixmap.getPixel(srcX + x, srcY + y)
                    }
                }
                return RgbaBuffer(w, h, out)
            }
        }
    }

    /** 一个待合成的图层，坐标中心为 (0, 0)，X 向右，Y 向下（与原版贴图/屏幕像素坐标一致）。 */
    data class CompositeLayer(
        val buffer: RgbaBuffer,
        val centerX: Double,
        val centerY: Double,
        val drawWidth: Double,
        val drawHeight: Double,
        val tintPlayerColor: Boolean = true,
    )

    private const val CANVAS_SUPERSAMPLE: Double = 2.0
    private const val MAX_OUTPUT_EDGE: Int = 128
    private const val PADDING_RATIO: Double = 0.08

    /**
     * 按单位定义与图像加载器组装有序合成图层列表：
     * 1. 若 `iconWeaponName` 非空且对应武器图像存在，直接单层居中展示该模块武器。
     * 2. 否则依次叠加：`BACK` -> `BODY(frame 0)` -> `ARM1/ARM2`（核弹/反核井门闭合态）->
     *    `TURRET` 或可见 `NAMED_TURRET`（按挂点偏移）-> `ROTOR`。
     */
    fun planLayers(
        definition: UnitAppearance,
        loadImage: (Path, Int) -> RgbaBuffer?,
    ): List<CompositeLayer> {
        definition.iconWeaponName?.let { weaponName ->
            val weapon = definition.weaponImages[weaponName]
            val path = weapon?.imagePath
            if (weapon != null && path != null) {
                loadImage(path, 1)?.let { buf ->
                    val logical = weapon.logicalSize
                    val dw = (logical?.width ?: buf.width) * weapon.imageScale
                    val dh = (logical?.height ?: buf.height) * weapon.imageScale
                    return listOf(CompositeLayer(buf, 0.0, 0.0, dw, dh, tintPlayerColor = true))
                }
            }
        }

        val layers = mutableListOf<CompositeLayer>()

        // 1. BACK（工厂底板、基地底座、采矿器底座等）
        definition.backImagePath?.let { path ->
            loadImage(path, 1)?.let { buf ->
                val size = definition.drawSize(UnitImageSlot.BACK, buf.width, buf.height)
                layers += CompositeLayer(buf, 0.0, 0.0, size.width, size.height)
            }
        }

        // 2. BODY（第 0 帧主体）
        loadImage(definition.imagePath, definition.totalFrames)?.let { buf ->
            val size = definition.drawSize(UnitImageSlot.BODY, buf.width, buf.height)
            layers += CompositeLayer(buf, 0.0, 0.0, size.width, size.height)
        }

        // 3. ARM1 / ARM2（核弹井/反核井闭合门）
        if (definition.arm1ImagePath != null && definition.arm2ImagePath != null) {
            val offsets = if (definition.nuclearSiloKind == NuclearSiloKind.INTERCEPTOR) {
                listOf(Pair(1.0, 12.0), Pair(-2.0, 11.0))
            } else {
                listOf(Pair(3.0, -1.0), Pair(-5.0, 9.0))
            }
            listOf(
                Triple(UnitImageSlot.ARM1, definition.arm1ImagePath, offsets[0]),
                Triple(UnitImageSlot.ARM2, definition.arm2ImagePath, offsets[1]),
            ).forEach { (slot, path, offset) ->
                loadImage(path, 1)?.let { buf ->
                    val size = definition.drawSize(slot, buf.width, buf.height)
                    // 默认朝向 heading=0°（向上 -Y）：worldOffset = (localX, -localY)
                    layers += CompositeLayer(buf, offset.first, -offset.second, size.width, size.height)
                }
            }
        }

        // 4. TURRET / 可见 NAMED_TURRET（默认朝向 0° 按挂点与图像偏移叠放）
        if (definition.weaponImages.isNotEmpty()) {
            for ((_, weapon) in definition.weaponImages) {
                if (weapon.invisible) continue
                val path = weapon.imagePath ?: continue
                loadImage(path, 1)?.let { buf ->
                    val logical = weapon.logicalSize
                        ?: definition.logicalImages?.turret?.takeIf { path == definition.turretImagePath }
                    val dw = (logical?.width ?: buf.width) * weapon.imageScale
                    val dh = (logical?.height ?: buf.height) * weapon.imageScale
                    // heading = 0°: forward = (0, -1), side = (1, 0) -> cx = mountX + offsetX, cy = -(mountY + offsetY)
                    val cx = weapon.mountX + weapon.offsetX
                    val cy = -(weapon.mountY + weapon.offsetY)
                    layers += CompositeLayer(buf, cx, cy, dw, dh)
                }
            }
        } else {
            definition.turretImagePath?.let { path ->
                loadImage(path, 1)?.let { buf ->
                    val size = definition.drawSize(UnitImageSlot.TURRET, buf.width, buf.height)
                    val cx = definition.turretMount.localX
                    val cy = -definition.turretMount.localY
                    layers += CompositeLayer(buf, cx, cy, size.width, size.height)
                }
            }
        }

        // 5. ROTOR（直升机旋翼）
        definition.rotorImagePath?.let { path ->
            loadImage(path, 1)?.let { buf ->
                val size = definition.drawSize(UnitImageSlot.ROTOR, buf.width, buf.height)
                layers += CompositeLayer(buf, 0.0, 0.0, size.width, size.height)
            }
        }

        return layers
    }

    /**
     * 将有序图层列表合成为一张自动裁切、等比居中带边距的 RGBA 缓冲，并执行纯绿通道着色。
     */
    fun compose(
        layers: List<CompositeLayer>,
        playerRgb: Int = MatchPlayerColors.PALETTE_RGB[0],
    ): RgbaBuffer? {
        if (layers.isEmpty()) return null

        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (layer in layers) {
            if (layer.drawWidth <= 0.0 || layer.drawHeight <= 0.0) continue
            minX = min(minX, layer.centerX - layer.drawWidth * 0.5)
            maxX = max(maxX, layer.centerX + layer.drawWidth * 0.5)
            minY = min(minY, layer.centerY - layer.drawHeight * 0.5)
            maxY = max(maxY, layer.centerY + layer.drawHeight * 0.5)
        }
        if (!minX.isFinite() || maxX <= minX || maxY <= minY) return null

        val spanW = maxX - minX
        val spanH = maxY - minY
        val maxSpan = max(spanW, spanH)
        val scale = min(CANVAS_SUPERSAMPLE, (MAX_OUTPUT_EDGE - 8).toDouble() / maxSpan).coerceAtLeast(0.5)

        val canvasW = ceil(spanW * scale).toInt().coerceIn(2, MAX_OUTPUT_EDGE)
        val canvasH = ceil(spanH * scale).toInt().coerceIn(2, MAX_OUTPUT_EDGE)
        val canvas = IntArray(canvasW * canvasH)

        val pr = ((playerRgb ushr 16) and 0xFF) / 255f
        val pg = ((playerRgb ushr 8) and 0xFF) / 255f
        val pb = (playerRgb and 0xFF) / 255f

        for (layer in layers) {
            if (layer.drawWidth <= 0.0 || layer.drawHeight <= 0.0) continue
            val lx0 = (layer.centerX - layer.drawWidth * 0.5 - minX) * scale
            val ly0 = (layer.centerY - layer.drawHeight * 0.5 - minY) * scale
            val lw = layer.drawWidth * scale
            val lh = layer.drawHeight * scale
            if (lw <= 0.0 || lh <= 0.0) continue

            val px0 = floor(lx0).toInt().coerceIn(0, canvasW - 1)
            val px1 = ceil(lx0 + lw).toInt().coerceIn(0, canvasW)
            val py0 = floor(ly0).toInt().coerceIn(0, canvasH - 1)
            val py1 = ceil(ly0 + lh).toInt().coerceIn(0, canvasH)

            val src = layer.buffer
            for (dy in py0 until py1) {
                val v = ((dy + 0.5) - ly0) / lh
                if (v < 0.0 || v >= 1.0) continue
                val sy = (v * src.height).toInt().coerceIn(0, src.height - 1)
                for (dx in px0 until px1) {
                    val u = ((dx + 0.5) - lx0) / lw
                    if (u < 0.0 || u >= 1.0) continue
                    val sx = (u * src.width).toInt().coerceIn(0, src.width - 1)
                    val srcRgba = src.getRgba(sx, sy)
                    val srcA = (srcRgba and 0xFF) / 255f
                    if (srcA <= 0.004f) continue

                    var sr = ((srcRgba ushr 24) and 0xFF) / 255f
                    var sg = ((srcRgba ushr 16) and 0xFF) / 255f
                    var sb = ((srcRgba ushr 8) and 0xFF) / 255f
                    if (layer.tintPlayerColor) {
                        val tinted = MatchPlayerColors.applyPureGreenTint(sr, sg, sb, srcA, pr, pg, pb)
                        sr = tinted[0]
                        sg = tinted[1]
                        sb = tinted[2]
                    }

                    val dstIdx = dy * canvasW + dx
                    val dstRgba = canvas[dstIdx]
                    val dstA = (dstRgba and 0xFF) / 255f
                    if (dstA <= 0.004f || srcA >= 0.996f) {
                        canvas[dstIdx] = packRgba(sr, sg, sb, srcA)
                    } else {
                        val dr = ((dstRgba ushr 24) and 0xFF) / 255f
                        val dg = ((dstRgba ushr 16) and 0xFF) / 255f
                        val db = ((dstRgba ushr 8) and 0xFF) / 255f
                        val outA = srcA + dstA * (1f - srcA)
                        val outR = (sr * srcA + dr * dstA * (1f - srcA)) / outA
                        val outG = (sg * srcA + dg * dstA * (1f - srcA)) / outA
                        val outB = (sb * srcA + db * dstA * (1f - srcA)) / outA
                        canvas[dstIdx] = packRgba(outR, outG, outB, outA)
                    }
                }
            }
        }

        // 自动裁切非透明外接包围盒并居中留白（正方形以便各 HUD 容器等比无变形缩放）
        var cropMinX = canvasW
        var cropMaxX = -1
        var cropMinY = canvasH
        var cropMaxY = -1
        for (y in 0 until canvasH) {
            val rowOff = y * canvasW
            for (x in 0 until canvasW) {
                if ((canvas[rowOff + x] and 0xFF) > 6) {
                    if (x < cropMinX) cropMinX = x
                    if (x > cropMaxX) cropMaxX = x
                    if (y < cropMinY) cropMinY = y
                    if (y > cropMaxY) cropMaxY = y
                }
            }
        }
        if (cropMaxX < cropMinX || cropMaxY < cropMinY) return null

        val contentW = cropMaxX - cropMinX + 1
        val contentH = cropMaxY - cropMinY + 1
        val maxContent = max(contentW, contentH)
        val pad = max(2, (maxContent * PADDING_RATIO).roundToInt())
        val side = (maxContent + pad * 2).coerceAtMost(MAX_OUTPUT_EDGE)
        val out = IntArray(side * side)
        val offX = (side - contentW) / 2
        val offY = (side - contentH) / 2

        for (y in 0 until contentH) {
            val dstY = offY + y
            if (dstY !in 0 until side) continue
            val srcRow = (cropMinY + y) * canvasW + cropMinX
            val dstRow = dstY * side + offX
            for (x in 0 until contentW) {
                val dstX = offX + x
                if (dstX !in 0 until side) continue
                out[dstRow + x] = canvas[srcRow + x]
            }
        }
        return RgbaBuffer(side, side, out)
    }

    fun toPixmap(buffer: RgbaBuffer): Pixmap {
        val pixmap = Pixmap(buffer.width, buffer.height, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        for (y in 0 until buffer.height) {
            for (x in 0 until buffer.width) {
                pixmap.drawPixel(x, y, buffer.getRgba(x, y))
            }
        }
        return pixmap
    }

    private fun packRgba(r: Float, g: Float, b: Float, a: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt() and 0xFF
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt() and 0xFF
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt() and 0xFF
        val ai = (a.coerceIn(0f, 1f) * 255f).roundToInt() and 0xFF
        return (ri shl 24) or (gi shl 16) or (bi shl 8) or ai
    }
}
