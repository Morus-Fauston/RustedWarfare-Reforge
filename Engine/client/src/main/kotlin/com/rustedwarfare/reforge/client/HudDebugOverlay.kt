package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.sim.DiagnosticUnitPrimitive
import com.rustedwarfare.reforge.core.sim.Position
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure F3 debug layer toggle & sub-layer state machine (HUD Spec §12A.2).
 *
 * - Available ONLY in development mode; defaults to closed and resets on every new match.
 * - Sub-layer defaults: boxes=ON, routes=ON, details=OFF, grid=OFF.
 * - PC: F3 toggles overlay; holding F3 + B / R / D / G toggles sub-layers (or pressing B/R/D/G while F3 held).
 * - Touch: system bar trailing "调试" button toggles overlay; tapping [B]/[R]/[D]/[G] on layers line toggles sub-layers.
 */
data class HudDebugLayerState(
    val enabled: Boolean = false,
    val showBoxes: Boolean = true,
    val showRoutes: Boolean = true,
    val showDetails: Boolean = false,
    val showGrid: Boolean = false,
    val showHotspots: Boolean = false,
) {
    fun toggleEnabled(): HudDebugLayerState = copy(enabled = !enabled)
    fun toggleBoxes(): HudDebugLayerState = if (enabled) copy(showBoxes = !showBoxes) else this
    fun toggleRoutes(): HudDebugLayerState = if (enabled) copy(showRoutes = !showRoutes) else this
    fun toggleDetails(): HudDebugLayerState = if (enabled) copy(showDetails = !showDetails) else this
    fun toggleGrid(): HudDebugLayerState = if (enabled) copy(showGrid = !showGrid) else this
    fun toggleHotspots(): HudDebugLayerState = if (enabled) copy(showHotspots = !showHotspots) else this
    fun reset(): HudDebugLayerState = HudDebugLayerState()
}

data class HudDebugOverlaySnapshot(
    val leftLines: List<String>,
    val rightLines: List<String>,
    val stacked: Boolean,
    val showBoxes: Boolean,
    val showRoutes: Boolean,
    val showDetails: Boolean,
    val showGrid: Boolean,
    val showHotspots: Boolean = false,
    val hotspotLines: List<String> = emptyList(),
)

data class HudDebugFrameMetrics(
    val fps: Int,
    val frameMs: Float,
    val maxFrameMs: Float,
    val rateMultiplier: Int,
    val actualMultiplier: Double?,
    val backlogSteps: Long,
    val mapId: String,
    val cursorWorld: Position?,
    val versionLabel: String = resolveVersionLabel(),
    val hotspotLines: List<String> = emptyList(),
) {
    companion object {
        /** Reads JAR Implementation-Version when packaged, falling back to "dev" so debug readout never drifts. */
        fun resolveVersionLabel(): String =
            HudDebugFrameMetrics::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() } ?: "dev"
    }
}

/**
 * Pure diagnostic text builder for the F3 two-column monospace overlay (HUD Spec §12A.3).
 *
 * Reads strictly from player-authorized [SandboxSession.observation] and [SandboxSession.diagnosticScene];
 * never leaks fog-hidden enemies or unapproved authoritative data.
 */
object HudDebugProjection {
    /** Narrow-window threshold: battlefield width (stageW - sidebarW - margin) < 780 ref units stacks right under left. */
    const val NARROW_STACK_THRESHOLD: Float = 780f

    fun isNarrowStack(stageWidth: Float): Boolean =
        (stageWidth - UiTokens.SIDEBAR_WIDTH - UiTokens.SCREEN_MARGIN) < NARROW_STACK_THRESHOLD

    fun project(
        session: SandboxSession,
        projection: MapProjection?,
        state: HudDebugLayerState,
        metrics: HudDebugFrameMetrics,
        stageWidth: Float,
    ): HudDebugOverlaySnapshot {
        val obs = session.observation
        val diag = session.diagnosticScene
        val map = session.debugMap
        val fogLabel = if (session.options.mode != SandboxMode.NO_FOG) "on" else "off"

        val camX = projection?.camera?.center?.x?.roundToInt() ?: 0
        val camY = projection?.camera?.center?.y?.roundToInt() ?: 0
        val camZoom = projection?.camera?.zoom ?: 1.0

        val cursorLine = buildCursorLine(metrics.cursorWorld, map)

        val visibleAlive = obs.units.count { it.alive }
        val totalVisibleUnits = obs.units.size
        val projectiles = obs.projectiles.size

        val actualStr = metrics.actualMultiplier?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "--"

        val left = listOf(
            "RustedWarfare-Reforge ${metrics.versionLabel} · development",
            String.format(Locale.ROOT, "fps %d · frame %.1f ms (max %.1f)", metrics.fps, metrics.frameMs, metrics.maxFrameMs),
            "sim step ${obs.stepIndex} · rate ${metrics.rateMultiplier}× · actual ${actualStr}× · backlog ${metrics.backlogSteps}",
            "map ${metrics.mapId} · ${map.widthTiles}×${map.heightTiles} tiles · fog $fogLabel",
            String.format(Locale.ROOT, "camera %d, %d · zoom %.2f", camX, camY, camZoom),
            cursorLine,
            "units visible $visibleAlive / $totalVisibleUnits · projectiles $projectiles",
            buildLayersLine(state),
            "LEGEND", // Rendered with yellow, stalled-red & cyan swatch squares by HudDebugOverlay
        )

        val selectedId = session.selectedId
        val selectedUnit = selectedId?.let { id -> obs.units.firstOrNull { it.unitId == id && it.alive } }
        val right = if (selectedUnit != null) {
            val diagUnit = diag.units.firstOrNull { it.unitId == selectedUnit.unitId }
            buildSelectedUnitLines(selectedUnit, diagUnit, diag.units, session)
        } else {
            emptyList()
        }

        return HudDebugOverlaySnapshot(
            leftLines = left,
            rightLines = right,
            stacked = isNarrowStack(stageWidth),
            showBoxes = state.showBoxes,
            showRoutes = state.showRoutes,
            showDetails = state.showDetails,
            showGrid = state.showGrid,
            showHotspots = state.showHotspots,
            hotspotLines = if (state.showHotspots) metrics.hotspotLines else emptyList(),
        )
    }

    fun buildLayersLine(state: HudDebugLayerState): String {
        val b = if (state.showBoxes) "ON" else "off"
        val r = if (state.showRoutes) "ON" else "off"
        val d = if (state.showDetails) "ON" else "off"
        val g = if (state.showGrid) "ON" else "off"
        val suffix = if (state.showHotspots) " · [H] hotspots ON" else ""
        return "layers [B] boxes $b · [R] routes $r · [D] details $d · [G] grid $g$suffix"
    }

    private fun buildCursorLine(cursor: Position?, map: com.rustedwarfare.reforge.core.content.MapDefinition): String {
        if (cursor == null) return "cursor world -- · tile -- · --"
        val tx = (cursor.x / map.tileWidth).toInt().coerceIn(0, (map.widthTiles - 1).coerceAtLeast(0))
        val ty = (cursor.y / map.tileHeight).toInt().coerceIn(0, (map.heightTiles - 1).coerceAtLeast(0))
        val rawGid = map.groundGids.getOrNull(ty * map.widthTiles + tx) ?: 0
        val gid = rawGid and 0x1FFFFFFF
        val terrain = when {
            map.isResourcePool(tx, ty) -> "resource"
            map.waterBlocking.isBlocked(tx, ty).not() && map.blocking.isBlocked(tx, ty) -> "water"
            map.blocking.isBlocked(tx, ty) -> "blocked"
            else -> "ground"
        }
        return String.format(Locale.ROOT, "cursor world %d, %d · tile %d, %d · %s gid %d", cursor.x.roundToInt(), cursor.y.roundToInt(), tx, ty, terrain, gid)
    }

    private fun buildSelectedUnitLines(
        unit: com.rustedwarfare.reforge.core.sim.UnitObservation,
        diagUnit: DiagnosticUnitPrimitive?,
        allDiagUnits: List<DiagnosticUnitPrimitive>,
        session: SandboxSession,
    ): List<String> {
        val deg = ((unit.heading % 360.0) + 540.0) % 360.0 - 180.0
        val cmdSummary = when {
            unit.moveTarget != null -> String.format(Locale.ROOT, "Move -> %d, %d", unit.moveTarget!!.x.roundToInt(), unit.moveTarget!!.y.roundToInt())
            unit.movementState.name != "IDLE" -> unit.movementState.name.lowercase(Locale.ROOT)
            else -> "idle"
        }

        val unitDef = session.unitDefinitionForView(unit.typeId)
        val mass = unitDef?.mass ?: 0.0
        val shapeLine = if (diagUnit != null) {
            val shapeDesc = when (val s = diagUnit.shape) {
                is MovementCollisionShape.Circle -> String.format(Locale.ROOT, "circle r %.1f", s.radius)
                is MovementCollisionShape.Rectangle -> String.format(Locale.ROOT, "rect %.0f×%.0f", s.length, s.width)
            }
            String.format(Locale.ROOT, "shape %s · coarse r %.1f · mass %.0f", shapeDesc, diagUnit.boundingRadius, mass)
        } else {
            String.format(Locale.ROOT, "shape -- · coarse r %.1f · mass %.0f", unitDef?.radius ?: 0.0, mass)
        }

        // Count visible contacts & buffer overlaps strictly among player-visible diagnostic units
        var contacts = 0
        var bufferCount = 0
        if (diagUnit != null) {
            for (other in allDiagUnits) {
                if (other.unitId == diagUnit.unitId) continue
                val d = hypot(other.center.x - diagUnit.center.x, other.center.y - diagUnit.center.y)
                val hardSum = diagUnit.boundingRadius + other.boundingRadius
                if (d <= hardSum + 1e-3) {
                    contacts++
                }
                val bufSum = (diagUnit.bufferRadius ?: diagUnit.boundingRadius) + (other.bufferRadius ?: other.boundingRadius)
                if (d <= bufSum + 1e-3) {
                    bufferCount++
                }
            }
        }

        val routePoints = diagUnit?.route ?: unit.pathPoints
        var remaining = 0.0
        var prev = unit.position
        for (pt in routePoints) {
            remaining += hypot(pt.x - prev.x, pt.y - prev.y)
            prev = pt
        }
        val target = diagUnit?.target ?: unit.moveTarget
        val routeLine = if (routePoints.isNotEmpty() || target != null) {
            val tStr = target?.let { String.format(Locale.ROOT, "%d, %d", it.x.roundToInt(), it.y.roundToInt()) } ?: "--"
            String.format(Locale.ROOT, "route %d nodes · remaining %.1f · target %s", routePoints.size, remaining, tStr)
        } else {
            "route 0 nodes · remaining 0.0 · target --"
        }

        return listOf(
            "selected ${unit.unitId} · ${unit.typeId} · ${unit.ownerPlayerId}",
            String.format(Locale.ROOT, "pos %.1f, %.1f · heading %.1f° · speed %.2f", unit.position.x, unit.position.y, deg, unit.speed),
            "state ${unit.movementState.name.lowercase(Locale.ROOT)} · cmd $cmdSummary",
            shapeLine,
            "contacts $contacts · buffer $bufferCount",
            routeLine,
        )
    }
}

/**
 * Scene2D overlay for the F3 two-column monospace debug text readout (HUD Spec §12A.3).
 *
 * - Non-blocking on PC (`Touchable.disabled` except on touch sub-layer toggle chips on the layers line).
 * - Exposes [leftColumnBottomY] so [InGameHudOverlay] shifts top-left event notices neatly underneath with zero overlap.
 */
internal class HudDebugOverlay(
    private val skin: Skin,
    private val touchControls: Boolean,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onToggleBoxes: () -> Unit,
    private val onToggleRoutes: () -> Unit,
    private val onToggleDetails: () -> Unit = {},
    private val onToggleGrid: () -> Unit,
    private val themeProvider: UiThemeProvider = UiThemeProvider { UiTheme.DEFAULT },
) : Group() {
    private val colors: UiThemeColors get() = themeProvider.currentTheme().colors

    private var snapshot: HudDebugOverlaySnapshot? = null
    private var stageW: Float = 960f
    private var stageH: Float = 640f
    private var leftTopY: Float = 596f
    private val glyphLayout = GlyphLayout()

    /** Bottom Y (in stage coordinates) of the rendered left column (including stacked right column on narrow screens). */
    var leftColumnBottomY: Float = 640f
        private set

    private val btnToggleBoxes = LayerHitActor(onToggleBoxes).also(::addActor)
    private val btnToggleRoutes = LayerHitActor(onToggleRoutes).also(::addActor)
    private val btnToggleDetails = LayerHitActor(onToggleDetails).also(::addActor)
    private val btnToggleGrid = LayerHitActor(onToggleGrid).also(::addActor)

    init {
        isVisible = false
        touchable = if (touchControls) Touchable.childrenOnly else Touchable.disabled
    }

    private class LayerHitActor(private val onClick: () -> Unit) : Actor() {
        init {
            touchable = Touchable.disabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean =
                    pointer == 0 && button == Input.Buttons.LEFT

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer == 0 && button == Input.Buttons.LEFT && x in 0f..width && y in 0f..height && !event.isTouchFocusCancel) {
                        onClick()
                    }
                }
            })
        }

        fun setActive(x: Float, y: Float, w: Float, h: Float) {
            setBounds(x, y, w, h)
            touchable = Touchable.enabled
        }

        fun disable() {
            touchable = Touchable.disabled
            setBounds(0f, 0f, 0f, 0f)
        }
    }

    fun update(
        stageWidth: Float,
        stageHeight: Float,
        systemBarBottomY: Float,
        snap: HudDebugOverlaySnapshot?,
    ) {
        stageW = stageWidth.coerceAtLeast(1f)
        stageH = stageHeight.coerceAtLeast(1f)
        snapshot = snap
        setBounds(0f, 0f, stageW, stageH)
        if (snap == null) {
            isVisible = false
            leftColumnBottomY = systemBarBottomY
            btnToggleBoxes.disable(); btnToggleRoutes.disable(); btnToggleDetails.disable(); btnToggleGrid.disable()
            return
        }
        isVisible = true
        leftTopY = systemBarBottomY - 6f

        val totalLeftRows = snap.leftLines.size + snap.hotspotLines.size + if (snap.stacked) snap.rightLines.size else 0
        leftColumnBottomY = leftTopY - totalLeftRows * (ROW_H + ROW_GAP) - if (snap.hotspotLines.isNotEmpty()) 4f else 0f

        // Position touch sub-layer toggle hitboxes on the "layers" row (index 7)
        if (touchControls) {
            val font = monoFont()
            val layersRowIdx = 7
            val rowY = leftTopY - (layersRowIdx + 1) * ROW_H - layersRowIdx * ROW_GAP
            val baseX = UiTokens.SCREEN_MARGIN + PAD_X
            val prefix = "layers "
            val segB = "[B] boxes ${if (snap.showBoxes) "ON" else "off"}"
            val sep = " · "
            val segR = "[R] routes ${if (snap.showRoutes) "ON" else "off"}"
            val segD = "[D] details ${if (snap.showDetails) "ON" else "off"}"
            val segG = "[G] grid ${if (snap.showGrid) "ON" else "off"}"

            val sepW = textWidth(font, sep)
            val xB = baseX + textWidth(font, prefix)
            val wB = textWidth(font, segB)
            val xR = xB + wB + sepW
            val wR = textWidth(font, segR)
            val xD = xR + wR + sepW
            val wD = textWidth(font, segD)
            val xG = xD + wD + sepW
            val wG = textWidth(font, segG)

            btnToggleBoxes.setActive(xB - 4f, rowY - 2f, wB + 8f, ROW_H + 4f)
            btnToggleRoutes.setActive(xR - 4f, rowY - 2f, wR + 8f, ROW_H + 4f)
            btnToggleDetails.setActive(xD - 4f, rowY - 2f, wD + 8f, ROW_H + 4f)
            btnToggleGrid.setActive(xG - 4f, rowY - 2f, wG + 8f, ROW_H + 4f)
        } else {
            btnToggleBoxes.disable(); btnToggleRoutes.disable(); btnToggleDetails.disable(); btnToggleGrid.disable()
        }
    }

    fun drawnActions(): List<DrawnAction> {
        if (!isVisible || !touchControls || snapshot == null) return emptyList()
        return listOf(
            DrawnAction("debug-sublayer-boxes", btnToggleBoxes, "Boxes", enabled = btnToggleBoxes.touchable == Touchable.enabled),
            DrawnAction("debug-sublayer-routes", btnToggleRoutes, "Routes", enabled = btnToggleRoutes.touchable == Touchable.enabled),
            DrawnAction("debug-sublayer-details", btnToggleDetails, "Details", enabled = btnToggleDetails.touchable == Touchable.enabled),
            DrawnAction("debug-sublayer-grid", btnToggleGrid, "Grid", enabled = btnToggleGrid.touchable == Touchable.enabled),
        )
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val snap = snapshot ?: return
        if (!isVisible) return
        val white = skin.getRegion("white")
        val orig = batch.packedColor
        val alpha = parentAlpha * color.a
        val font = monoFont()

        // 1. Left column (top-left below system bar)
        val combinedLeft = if (snap.stacked) snap.leftLines + snap.rightLines else snap.leftLines
        var cy = leftTopY
        for (line in combinedLeft) {
            val rowY = cy - ROW_H
            if (line == "LEGEND") {
                drawLegendRow(batch, white, font, UiTokens.SCREEN_MARGIN, rowY, alpha)
            } else {
                drawMonoPillLeft(batch, white, font, line, UiTokens.SCREEN_MARGIN, rowY, alpha)
            }
            cy -= (ROW_H + ROW_GAP)
        }
        if (snap.hotspotLines.isNotEmpty()) {
            cy -= 4f
            for (line in snap.hotspotLines) {
                val rowY = cy - ROW_H
                drawMonoPillLeft(batch, white, font, line, UiTokens.SCREEN_MARGIN, rowY, alpha)
                cy -= (ROW_H + ROW_GAP)
            }
        }

        // 2. Right column (right-aligned to left of sidebar, top=8) when not stacked
        if (!snap.stacked && snap.rightLines.isNotEmpty()) {
            val rightEdge = stageW - UiTokens.SIDEBAR_WIDTH - UiTokens.SCREEN_MARGIN
            var ry = stageH - UiTokens.SCREEN_MARGIN
            for (line in snap.rightLines) {
                val rowY = ry - ROW_H
                drawMonoPillRight(batch, white, font, line, rightEdge, rowY, alpha)
                ry -= (ROW_H + ROW_GAP)
            }
        }

        batch.packedColor = orig
        super.draw(batch, parentAlpha)
    }

    private fun drawMonoPillLeft(batch: Batch, white: TextureRegion, font: BitmapFont, text: String, x: Float, y: Float, alpha: Float) {
        val w = ceil(textWidth(font, text) + PAD_X * 2f)
        fillRect(batch, white, x, y, w, ROW_H, colors.debugBg, alpha)
        drawText(batch, font, text, x + PAD_X, y + ROW_H * 0.5f, colors.debugText, alpha)
    }

    private fun drawMonoPillRight(batch: Batch, white: TextureRegion, font: BitmapFont, text: String, rightX: Float, y: Float, alpha: Float) {
        val w = ceil(textWidth(font, text) + PAD_X * 2f)
        val x = rightX - w
        fillRect(batch, white, x, y, w, ROW_H, colors.debugBg, alpha)
        drawText(batch, font, text, x + PAD_X, y + ROW_H * 0.5f, colors.debugText, alpha)
    }

    private fun drawLegendRow(batch: Batch, white: TextureRegion, font: BitmapFont, x: Float, y: Float, alpha: Float) {
        val part1 = " hard shape / heading   "
        val part2 = " stalled ≥2s   "
        val part3 = " selected / [D] details & routes"
        val sq = 7f
        val w1 = textWidth(font, part1)
        val w2 = textWidth(font, part2)
        val w3 = textWidth(font, part3)
        val totalW = ceil(PAD_X * 2f + sq + w1 + sq + w2 + sq + w3)
        fillRect(batch, white, x, y, totalW, ROW_H, colors.debugBg, alpha)

        var cx = x + PAD_X
        val midY = y + ROW_H * 0.5f
        fillRect(batch, white, cx, midY - sq * 0.5f, sq, sq, colors.debugLegendHard, alpha)
        cx += sq
        drawText(batch, font, part1, cx, midY, colors.debugText, alpha)
        cx += w1
        fillRect(batch, white, cx, midY - sq * 0.5f, sq, sq, colors.debugLegendStalled, alpha)
        cx += sq
        drawText(batch, font, part2, cx, midY, colors.debugText, alpha)
        cx += w2
        fillRect(batch, white, cx, midY - sq * 0.5f, sq, sq, colors.debugLegendDetail, alpha)
        cx += sq
        drawText(batch, font, part3, cx, midY, colors.debugText, alpha)
    }

    private fun monoFont(): BitmapFont = fontsProvider()?.smallMono ?: fallbackFontProvider()

    private fun textWidth(font: BitmapFont, text: String): Float {
        glyphLayout.setText(font, text)
        return glyphLayout.width
    }

    private fun fillRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(c.r, c.g, c.b, c.a * alpha)
        batch.draw(white, x, y, w, h)
    }

    private fun drawText(batch: Batch, font: BitmapFont, text: String, x: Float, centerY: Float, c: Color, alpha: Float) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text)
        font.setColor(c.r, c.g, c.b, c.a * alpha)
        font.draw(batch, text, x, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    companion object {
        const val ROW_H: Float = 17f
        const val ROW_GAP: Float = 2f
        const val PAD_X: Float = 6f
    }
}
