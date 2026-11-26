package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DiagnosticRenderArtifacts(
    val contactSheetPath: Path,
    val peakZoomPath: Path?,
    val keyframeSteps: List<Long>,
    val peakCongestionStep: Long?,
)

/**
 * Headless BufferedImage offline adapter over [DiagnosticSceneProjector] (ENG-P016-001).
 *
 * Renders 2-second (120-step) + key-event Contact Sheets and Peak Congestion Zoom views
 * directly from committed authoritative projections, with zero runtime overhead during ordinary unit tests.
 */
object DiagnosticContactSheetRenderer {
    fun writeArtifacts(
        sequence: DiagnosticSequenceProjection,
        map: MapDefinition,
        title: String,
        contactSheetPath: Path,
        peakZoomPath: Path? = null,
        maxPanels: Int = 24,
        columns: Int = 4,
    ): DiagnosticRenderArtifacts {
        val selected = selectPanels(sequence.keyframes, maxPanels)
        val sheetImage = renderContactSheet(sequence, selected, map, title, columns)
        Files.createDirectories(contactSheetPath.toAbsolutePath().parent)
        ImageIO.write(sheetImage, "png", contactSheetPath.toFile())

        var writtenZoom: Path? = null
        val focus = sequence.peakCongestionFocus
        if (peakZoomPath != null && focus != null) {
            val peakFrame = sequence.frames.firstOrNull { it.sampleId == focus.sampleId && it.stepIndex == focus.stepIndex }
            if (peakFrame != null) {
                val zoomImage = renderPeakZoom(peakFrame, focus, map, title)
                Files.createDirectories(peakZoomPath.toAbsolutePath().parent)
                ImageIO.write(zoomImage, "png", peakZoomPath.toFile())
                writtenZoom = peakZoomPath
            }
        }

        return DiagnosticRenderArtifacts(
            contactSheetPath = contactSheetPath,
            peakZoomPath = writtenZoom,
            keyframeSteps = selected.map { it.frame.stepIndex },
            peakCongestionStep = sequence.peakCongestionStep,
        )
    }

    fun renderContactSheet(
        sequence: DiagnosticSequenceProjection,
        panels: List<DiagnosticKeyframe>,
        map: MapDefinition,
        title: String,
        columns: Int = 4,
    ): BufferedImage {
        val worldW = (map.widthTiles * map.tileWidth).toDouble()
        val worldH = (map.heightTiles * map.tileHeight).toDouble()
        val panelMapW = 420
        val panelMapH = (panelMapW * (worldH / max(1.0, worldW))).roundToInt().coerceIn(200, 380)
        val panelHeaderH = if (sequence.gateSummaries.isNotEmpty()) 52 else 38
        val panelW = panelMapW + 16
        val panelH = panelMapH + panelHeaderH + 12

        val effectiveCols = columns.coerceIn(1, max(1, panels.size))
        val rows = max(1, ceil(panels.size.toDouble() / effectiveCols).toInt())
        val topBannerH = if (sequence.gateSummaries.isNotEmpty()) 88 else 68
        val pad = 14
        val imgW = pad * 2 + effectiveCols * panelW + (effectiveCols - 1) * pad
        val imgH = topBannerH + pad + rows * panelH + (rows - 1) * pad

        val image = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        configureGraphics(g)

        g.color = BG_DARK
        g.fillRect(0, 0, imgW, imgH)

        g.color = HEADER_BG
        g.fillRect(0, 0, imgW, topBannerH)
        g.color = TEXT_PRIMARY
        g.font = Font(Font.MONOSPACED, Font.BOLD, 16)
        g.drawString(title, pad, 26)

        g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val maxStalled = sequence.frames.maxOfOrNull { it.stalledCount } ?: 0
        val maxContacts = sequence.frames.maxOfOrNull { it.hardContactCount } ?: 0
        val finalArrived = sequence.frames.lastOrNull()?.arrivedCount ?: 0
        val totalMobile = sequence.frames.firstOrNull()?.units?.size ?: 0
        val summaryLine = String.format(
            Locale.ROOT,
            "Frames: %d | Keyframes: %d | FirstMove: %s | FirstContact: %s | PeakCongestionStep: %s (MaxStalled=%d, MaxContacts=%d) | FinalArrived: %d/%d",
            sequence.frames.size,
            panels.size,
            sequence.firstAutonomousMoveStep?.toString() ?: "-",
            sequence.firstHardContactStep?.toString() ?: "-",
            sequence.peakCongestionStep?.toString() ?: "-",
            maxStalled,
            maxContacts,
            finalArrived,
            totalMobile,
        )
        g.color = TEXT_MUTED
        g.drawString(summaryLine, pad, 46)

        val legendLine = "Legend: [RED RING+FILL] = Stalled >= 2s (120 steps) unarrived | [BLUE/GREEN] = P1/P2 hulls | [CYAN] = Buffer/Arrived | [YELLOW] = Velocity"
        g.drawString(legendLine, pad, 62)

        if (sequence.gateSummaries.isNotEmpty()) {
            g.color = Color(135, 225, 210)
            val gateBanner = sequence.gateSummaries.joinToString(" | ") { gs ->
                String.format(
                    Locale.ROOT,
                    "%s: in=%d out=%d set=%d (%.1f/s, gap=%d)",
                    compactGateId(gs.gateId),
                    gs.enteredCount,
                    gs.sternExitedCount,
                    gs.targetSideSettledCount,
                    gs.throughputUnitsPerSec,
                    gs.maxExitGapSteps,
                )
            }
            g.drawString("Gates: $gateBanner", pad, 79)
        }

        val terrainLayer = buildTerrainImage(map, panelMapW, panelMapH, 0.0, 0.0, worldW, worldH)

        panels.forEachIndexed { index, kf ->
            val col = index % effectiveCols
            val row = index / effectiveCols
            val px = pad + col * (panelW + pad)
            val py = topBannerH + row * (panelH + pad)

            g.color = PANEL_BG
            g.fillRoundRect(px, py, panelW, panelH, 8, 8)
            g.color = if (kf.frame.stalledCount > 0) STALL_BORDER else PANEL_BORDER
            g.stroke = BasicStroke(if (kf.frame.stalledCount > 0) 2.0f else 1.0f)
            g.drawRoundRect(px, py, panelW, panelH, 8, 8)

            g.font = Font(Font.MONOSPACED, Font.BOLD, 11)
            g.color = TEXT_PRIMARY
            val tags = kf.reasons.joinToString(",") { it.label }
            g.drawString(
                String.format(Locale.ROOT, "step=%d (%.1fs) [%s]", kf.frame.stepIndex, kf.frame.elapsedSeconds, tags),
                px + 8, py + 15
            )
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            g.color = if (kf.frame.stalledCount > 0) STALL_TEXT else TEXT_MUTED
            g.drawString(
                String.format(
                    Locale.ROOT,
                    "arrived=%d active=%d contacts=%d stalled2s=%d",
                    kf.frame.arrivedCount, kf.frame.activeMoveCount, kf.frame.hardContactCount, kf.frame.stalledCount
                ),
                px + 8, py + 31
            )
            if (kf.frame.gateStats.isNotEmpty()) {
                g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                g.color = Color(135, 225, 210)
                val panelGateLine = kf.frame.gateStats.joinToString(" ") { gs ->
                    "${compactGateId(gs.gateId)}:${gs.enteredCount}/${gs.sternExitedCount}"
                }
                g.drawString(panelGateLine.take(64), px + 8, py + 45)
            }

            val mapX = px + 8
            val mapY = py + panelHeaderH
            g.drawImage(terrainLayer, mapX, mapY, null)
            drawFrameScene(g, kf.frame, mapX, mapY, panelMapW, panelMapH, 0.0, 0.0, worldW, worldH, detailed = false)
        }

        g.dispose()
        return image
    }

    fun renderPeakZoom(
        frame: DiagnosticFrameScene,
        focus: DiagnosticFocusRegion,
        map: MapDefinition,
        title: String,
    ): BufferedImage {
        val worldW = (map.widthTiles * map.tileWidth).toDouble()
        val worldH = (map.heightTiles * map.tileHeight).toDouble()
        val rawMinX = focus.minX.coerceIn(0.0, worldW)
        val rawMaxX = focus.maxX.coerceIn(0.0, worldW)
        val rawMinY = focus.minY.coerceIn(0.0, worldH)
        val rawMaxY = focus.maxY.coerceIn(0.0, worldH)

        val cx = (rawMinX + rawMaxX) * 0.5
        val cy = (rawMinY + rawMaxY) * 0.5
        val halfSpan = max(120.0, max(rawMaxX - rawMinX, rawMaxY - rawMinY) * 0.5)
        val viewW = min(worldW, halfSpan * 2.0)
        val viewH = min(worldH, halfSpan * 2.0)
        val viewMinX = (cx - viewW * 0.5).coerceIn(0.0, max(0.0, worldW - viewW))
        val viewMinY = (cy - viewH * 0.5).coerceIn(0.0, max(0.0, worldH - viewH))

        val canvasSize = 760
        val headerH = 82
        val pad = 16
        val imgW = canvasSize + pad * 2
        val imgH = canvasSize + headerH + pad * 2

        val image = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        configureGraphics(g)

        g.color = BG_DARK
        g.fillRect(0, 0, imgW, imgH)

        g.color = HEADER_BG
        g.fillRect(0, 0, imgW, headerH)
        g.color = TEXT_PRIMARY
        g.font = Font(Font.MONOSPACED, Font.BOLD, 14)
        g.drawString(title, pad, 22)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 13)
        g.drawString(
            String.format(Locale.ROOT, "Peak Congestion Zoom @ step=%d (%.2fs)", frame.stepIndex, frame.elapsedSeconds),
            pad, 43
        )

        g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        g.color = if (frame.stalledCount > 0) STALL_TEXT else TEXT_MUTED
        g.drawString(
            String.format(
                Locale.ROOT,
                "stalled2s=%d | hardContacts=%d | arrived=%d | active=%d | crop=[%.0f..%.0f, %.0f..%.0f] | [MAGENTA]=Guidance [YELLOW]=Vel",
                frame.stalledCount, frame.hardContactCount, frame.arrivedCount, frame.activeMoveCount,
                viewMinX, viewMinX + viewW, viewMinY, viewMinY + viewH
            ),
            pad, 65
        )

        val mapX = pad
        val mapY = headerH + pad / 2
        val terrain = buildTerrainImage(map, canvasSize, canvasSize, viewMinX, viewMinY, viewW, viewH)
        g.drawImage(terrain, mapX, mapY, null)
        drawFrameScene(g, frame, mapX, mapY, canvasSize, canvasSize, viewMinX, viewMinY, viewW, viewH, detailed = true)

        g.color = if (frame.stalledCount > 0) STALL_BORDER else PANEL_BORDER
        g.stroke = BasicStroke(2.0f)
        g.drawRect(mapX, mapY, canvasSize, canvasSize)

        g.dispose()
        return image
    }

    private fun selectPanels(keyframes: List<DiagnosticKeyframe>, maxPanels: Int): List<DiagnosticKeyframe> {
        if (keyframes.size <= maxPanels) return keyframes
        val mustKeep = keyframes.filter { kf ->
            kf.reasons.any { it != DiagnosticKeyframeKind.INTERVAL }
        }.toSet()
        val intervals = keyframes.filter { it !in mustKeep }
        val budget = max(2, maxPanels - mustKeep.size)
        val stride = max(1, ceil(intervals.size.toDouble() / budget).toInt())
        val sampledIntervals = intervals.filterIndexed { idx, _ -> idx % stride == 0 || idx == intervals.lastIndex }
        return (mustKeep + sampledIntervals).sortedBy { it.frame.stepIndex }.take(maxPanels)
    }

    private fun buildTerrainImage(
        map: MapDefinition,
        pixelW: Int,
        pixelH: Int,
        viewMinX: Double,
        viewMinY: Double,
        viewW: Double,
        viewH: Double,
    ): BufferedImage {
        val img = BufferedImage(pixelW, pixelH, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = TERRAIN_OPEN
        g.fillRect(0, 0, pixelW, pixelH)

        val scaleX = pixelW / max(1.0, viewW)
        val scaleY = pixelH / max(1.0, viewH)
        val minCol = max(0, (viewMinX / map.tileWidth).toInt() - 1)
        val maxCol = min(map.widthTiles - 1, ((viewMinX + viewW) / map.tileWidth).toInt() + 1)
        val minRow = max(0, (viewMinY / map.tileHeight).toInt() - 1)
        val maxRow = min(map.heightTiles - 1, ((viewMinY + viewH) / map.tileHeight).toInt() + 1)

        val landBlocking = map.blocking
        val waterBlocking = map.waterBlocking
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                val landBlocked = landBlocking.isBlocked(col, row)
                val waterBlocked = waterBlocking.isBlocked(col, row)
                if (!landBlocked) continue
                val wx = col * map.tileWidth.toDouble()
                val wy = row * map.tileHeight.toDouble()
                val sx = (wx - viewMinX) * scaleX
                val sy = (wy - viewMinY) * scaleY
                val sw = max(1.0, map.tileWidth * scaleX)
                val sh = max(1.0, map.tileHeight * scaleY)
                g.color = if (!waterBlocked) TERRAIN_WATER else TERRAIN_BLOCKED
                g.fill(Rectangle2D.Double(sx, sy, sw + 0.5, sh + 0.5))
            }
        }

        g.color = Color(255, 255, 255, 16)
        g.stroke = BasicStroke(1.0f)
        var gx = (minCol / 5) * 5
        while (gx <= maxCol) {
            val sx = (gx * map.tileWidth - viewMinX) * scaleX
            g.draw(Line2D.Double(sx, 0.0, sx, pixelH.toDouble()))
            gx += 5
        }
        var gy = (minRow / 5) * 5
        while (gy <= maxRow) {
            val sy = (gy * map.tileHeight - viewMinY) * scaleY
            g.draw(Line2D.Double(0.0, sy, pixelW.toDouble(), sy))
            gy += 5
        }

        g.dispose()
        return img
    }

    private fun drawFrameScene(
        g: Graphics2D,
        frame: DiagnosticFrameScene,
        offsetX: Int,
        offsetY: Int,
        pixelW: Int,
        pixelH: Int,
        viewMinX: Double,
        viewMinY: Double,
        viewW: Double,
        viewH: Double,
        detailed: Boolean,
    ) {
        val oldClip = g.clip
        g.clipRect(offsetX, offsetY, pixelW, pixelH)

        val scaleX = pixelW / max(1.0, viewW)
        val scaleY = pixelH / max(1.0, viewH)
        val scale = min(scaleX, scaleY)

        fun sx(wx: Double): Double = offsetX + (wx - viewMinX) * scaleX
        fun sy(wy: Double): Double = offsetY + (wy - viewMinY) * scaleY

        g.stroke = BasicStroke(if (detailed) 1.2f else 0.8f)
        for (unit in frame.units) {
            if (detailed && unit.route.isNotEmpty()) {
                g.color = Color(80, 210, 235, 65)
                var prevX = sx(unit.center.x)
                var prevY = sy(unit.center.y)
                for (pt in unit.route) {
                    val nx = sx(pt.x)
                    val ny = sy(pt.y)
                    g.draw(Line2D.Double(prevX, prevY, nx, ny))
                    prevX = nx
                    prevY = ny
                }
            }
            unit.target?.let { tgt ->
                g.color = Color(90, 220, 240, if (detailed) 110 else 65)
                val tx = sx(tgt.x)
                val ty = sy(tgt.y)
                g.draw(Line2D.Double(tx - 3.0, ty, tx + 3.0, ty))
                g.draw(Line2D.Double(tx, ty - 3.0, tx, ty + 3.0))
            }
        }

        if (detailed) {
            g.color = Color(110, 245, 255, 85)
            g.stroke = BasicStroke(1.0f)
            for (unit in frame.units) {
                val bRad = unit.bufferRadius ?: continue
                if (unit.bufferCorners.isEmpty()) {
                    val r = max(2.0, bRad * scale)
                    g.draw(Ellipse2D.Double(sx(unit.center.x) - r, sy(unit.center.y) - r, r * 2.0, r * 2.0))
                } else {
                    drawPolygon(g, unit.bufferCorners, ::sx, ::sy, fill = false)
                }
            }
        }

        val ordered = frame.units.sortedBy { if (it.stalledHighlight) 1 else 0 }
        for (unit in ordered) {
            val cx = sx(unit.center.x)
            val cy = sy(unit.center.y)
            val r = max(if (detailed) 3.5 else 2.2, unit.boundingRadius * scale)

            val baseFill = when {
                unit.stalledHighlight -> Color(245, 28, 28, 235)
                unit.arrived -> Color(65, 225, 215, 195)
                unit.ownerPlayerId == "p1" -> Color(70, 165, 255, 205)
                unit.ownerPlayerId == "p2" -> Color(80, 235, 110, 215)
                else -> Color(210, 175, 85, 205)
            }
            val strokeColor = when {
                unit.stalledHighlight -> Color(255, 40, 40, 255)
                unit.arrived -> Color(140, 255, 245, 230)
                else -> if (unit.ownerPlayerId == "p1") Color(160, 215, 255, 225) else if (unit.ownerPlayerId == "p2") Color(170, 255, 180, 225) else Color(255, 170, 55, 225)
            }

            if (unit.stalledHighlight) {
                g.color = Color(255, 20, 20, 255)
                g.stroke = BasicStroke(if (detailed) 2.8f else 2.0f)
                val ring = r + if (detailed) 4.5 else 3.0
                g.draw(Ellipse2D.Double(cx - ring, cy - ring, ring * 2.0, ring * 2.0))
            }

            g.color = baseFill
            if (unit.hullCorners.isEmpty()) {
                g.fill(Ellipse2D.Double(cx - r, cy - r, r * 2.0, r * 2.0))
                g.color = strokeColor
                g.stroke = BasicStroke(if (detailed) 1.5f else 1.0f)
                g.draw(Ellipse2D.Double(cx - r, cy - r, r * 2.0, r * 2.0))
            } else {
                drawPolygon(g, unit.hullCorners, ::sx, ::sy, fill = true)
                g.color = strokeColor
                g.stroke = BasicStroke(if (detailed) 1.6f else 1.1f)
                drawPolygon(g, unit.hullCorners, ::sx, ::sy, fill = false)
            }

            val tipX = sx(unit.headingTip.x)
            val tipY = sy(unit.headingTip.y)
            g.color = if (unit.stalledHighlight) Color(255, 235, 235, 240) else Color(255, 245, 210, 220)
            g.stroke = BasicStroke(if (detailed) 1.5f else 1.0f)
            g.draw(Line2D.Double(cx, cy, tipX, tipY))

            if (detailed && unit.guidanceDirection != null) {
                val gLen = unit.boundingRadius + 9.0
                val gx = sx(unit.center.x + unit.guidanceDirection!!.x * gLen)
                val gy = sy(unit.center.y + unit.guidanceDirection!!.y * gLen)
                g.color = GUIDANCE_ARROW_COLOR
                g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, floatArrayOf(3.0f, 2.5f), 0.0f)
                g.draw(Line2D.Double(cx, cy, gx, gy))
            }

            val speed = hypot(unit.velocityVector.x, unit.velocityVector.y)
            if (speed > 0.02) {
                val vScale = if (detailed) 14.0 else 10.0
                val vx = sx(unit.center.x + unit.velocityVector.x * vScale)
                val vy = sy(unit.center.y + unit.velocityVector.y * vScale)
                g.color = VELOCITY_ARROW_COLOR
                g.stroke = BasicStroke(if (detailed) 1.6f else 1.1f)
                g.draw(Line2D.Double(cx, cy, vx, vy))
            }

            if (detailed && unit.stalledHighlight) {
                g.font = Font(Font.MONOSPACED, Font.BOLD, 10)
                g.color = Color(255, 220, 220, 240)
                g.drawString(unit.unitId, (cx + r + 3.0).toFloat(), (cy + 3.0).toFloat())
            }
        }

        g.clip = oldClip
    }

    private fun drawPolygon(
        g: Graphics2D,
        corners: List<Position>,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        fill: Boolean,
    ) {
        if (corners.isEmpty()) return
        val path = Path2D.Double()
        path.moveTo(sx(corners[0].x), sy(corners[0].y))
        for (i in 1 until corners.size) {
            path.lineTo(sx(corners[i].x), sy(corners[i].y))
        }
        path.closePath()
        if (fill) g.fill(path) else g.draw(path)
    }

    private fun compactGateId(gateId: String): String = gateId
        .replace("wall1-narrow-y12", "W1N")
        .replace("wall1-wide-y23..28", "W1W")
        .replace("wall2-narrow-y15", "W2N")
        .replace("wall2-wide-y20..25", "W2W")
        .replace("wall3-narrow-y11", "W3N")
        .replace("wall3-wide-y25..30", "W3W")
        .take(12)

    private fun configureGraphics(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private val BG_DARK = Color(18, 22, 26)
    private val HEADER_BG = Color(26, 32, 38)
    private val PANEL_BG = Color(24, 30, 36)
    private val PANEL_BORDER = Color(58, 70, 82)
    private val STALL_BORDER = Color(235, 50, 50)
    private val TEXT_PRIMARY = Color(235, 240, 245)
    private val TEXT_MUTED = Color(170, 182, 195)
    private val STALL_TEXT = Color(255, 105, 105)
    private val TERRAIN_OPEN = Color(34, 42, 38)
    private val TERRAIN_BLOCKED = Color(92, 86, 78)
    private val TERRAIN_WATER = Color(32, 62, 92)
    private val VELOCITY_ARROW_COLOR = Color(255, 225, 75, 215)
    private val GUIDANCE_ARROW_COLOR = Color(255, 115, 235, 195)
}
