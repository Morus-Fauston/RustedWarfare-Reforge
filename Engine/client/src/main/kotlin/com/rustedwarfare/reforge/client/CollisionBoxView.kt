package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.rustedwarfare.reforge.core.sim.DiagnosticSceneProjector
import com.rustedwarfare.reforge.core.sim.DiagnosticUnitPrimitive
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class CollisionBoxDrawing(
    val unitId: String,
    val center: Position,
    val corners: List<Position>,
    val bound: Double,
    val bufferCorners: List<Position>,
    val bufferRadius: Double?,
    val headingTip: Position,
    val velocityTip: Position?,
    val guidanceTip: Position?,
    val route: List<Position>,
    val target: Position?,
    val stalledHighlight: Boolean,
)

/**
 * Pure per-unit F3 render decision (UI-P002-002) used by [CollisionBoxView.draw] and unit tests.
 */
internal data class CollisionBoxUnitRenderPlan(
    val unitId: String,
    val selected: Boolean,
    val drawHardHullAndArrow: Boolean,
    val stalledHighlight: Boolean,
    val drawSecondaryDetails: Boolean,
    val drawRoute: Boolean,
    val routeAlpha: Float,
)

/**
 * Thin LibGDX F3 screen adapter over [DiagnosticSceneProjector] (ENG-P016-001 / UI-P002-002).
 * All collision, buffer, velocity, guidance, and 2s stall geometry originates in core.
 */
internal object CollisionBoxView {
    fun project(session: SandboxSession, projection: MapProjection): List<CollisionBoxDrawing> {
        fun screen(point: Position) = Position(projection.x(point.x).toDouble(), projection.y(point.y).toDouble())
        return session.diagnosticScene.units.map { unit -> toDrawing(unit, projection, ::screen) }
    }

    /** Computes deterministic render flags for every projected unit under the active F3 sub-layers. */
    fun planUnits(
        drawings: List<CollisionBoxDrawing>,
        selectedIds: Set<String>,
        showBoxes: Boolean,
        showRoutes: Boolean,
        showDetails: Boolean,
    ): List<CollisionBoxUnitRenderPlan> = drawings.map { body ->
        val selected = body.unitId in selectedIds
        val hasActiveRoute = body.route.isNotEmpty() || body.target != null
        CollisionBoxUnitRenderPlan(
            unitId = body.unitId,
            selected = selected,
            drawHardHullAndArrow = showBoxes,
            stalledHighlight = body.stalledHighlight,
            drawSecondaryDetails = showBoxes && (selected || showDetails),
            drawRoute = showRoutes && hasActiveRoute,
            routeAlpha = if (selected) SELECTED_ROUTE_ALPHA else UNSELECTED_ROUTE_ALPHA,
        )
    }

    /**
     * Computes the exterior front chevron/arrow vertices placed just outside the hard hull's front edge
     * along the unit heading so it never crosses or obscures the unit sprite center.
     * Returns Triple(leftWing, tip, rightWing) in screen coordinates.
     */
    fun exteriorHeadingArrow(body: CollisionBoxDrawing): Triple<Position, Position, Position> {
        val rawDx = body.headingTip.x - body.center.x
        val rawDy = body.headingTip.y - body.center.y
        val rawLen = hypot(rawDx, rawDy)
        val (fx, fy) = if (rawLen > 1e-4) (rawDx / rawLen) to (rawDy / rawLen) else 1.0 to 0.0
        val rx = -fy
        val ry = fx

        // Find the forward-most hard-hull extent along (fx, fy) so the arrow starts right outside the bow/front edge.
        val frontExtent = if (body.corners.isNotEmpty()) {
            body.corners.maxOf { (it.x - body.center.x) * fx + (it.y - body.center.y) * fy }.coerceAtLeast(2.0)
        } else {
            body.bound.coerceAtLeast(2.0)
        }

        val arrowGap = 3.0
        val arrowLen = (frontExtent * 0.22).coerceIn(5.0, 9.0)
        val arrowHalfW = (arrowLen * 0.72).coerceIn(3.8, 6.5)

        val baseX = body.center.x + fx * (frontExtent + arrowGap)
        val baseY = body.center.y + fy * (frontExtent + arrowGap)
        val tip = Position(baseX + fx * arrowLen, baseY + fy * arrowLen)
        val left = Position(baseX + rx * arrowHalfW, baseY + ry * arrowHalfW)
        val right = Position(baseX - rx * arrowHalfW, baseY - ry * arrowHalfW)
        return Triple(left, tip, right)
    }

    private fun toDrawing(
        unit: DiagnosticUnitPrimitive,
        projection: MapProjection,
        screen: (Position) -> Position,
    ): CollisionBoxDrawing {
        val speed = hypot(unit.velocityVector.x, unit.velocityVector.y)
        val velocityTip = if (speed > 1e-3) {
            screen(Position(
                unit.center.x + unit.velocityVector.x * VELOCITY_ARROW_SECONDS,
                unit.center.y + unit.velocityVector.y * VELOCITY_ARROW_SECONDS,
            ))
        } else null
        val guidanceTip = unit.guidanceDirection?.let { dir ->
            val len = unit.boundingRadius + GUIDANCE_ARROW_EXTRA_WORLD
            screen(Position(unit.center.x + dir.x * len, unit.center.y + dir.y * len))
        }
        return CollisionBoxDrawing(
            unitId = unit.unitId,
            center = screen(unit.center),
            corners = unit.hullCorners.map(screen),
            bound = unit.boundingRadius * projection.scale,
            bufferCorners = unit.bufferCorners.map(screen),
            bufferRadius = unit.bufferRadius?.let { it * projection.scale },
            headingTip = screen(unit.headingTip),
            velocityTip = velocityTip,
            guidanceTip = guidanceTip,
            route = unit.route.map(screen),
            target = unit.target?.let(screen),
            stalledHighlight = unit.stalledHighlight,
        )
    }

    /**
     * Draws F3 world layers. Caller must currently be in [ShapeRenderer.ShapeType.Line]; this method
     * temporarily switches to [ShapeRenderer.ShapeType.Filled] for crisp black-outlined warm-yellow
     * hard-hull outlines & exterior arrows, then restores [ShapeRenderer.ShapeType.Line].
     */
    fun draw(
        shapes: ShapeRenderer,
        session: SandboxSession,
        projection: MapProjection,
        selectedIds: Set<String> = emptySet(),
        showBoxes: Boolean = true,
        showRoutes: Boolean = true,
        showDetails: Boolean = false,
        showGrid: Boolean = false,
    ) {
        val wasBlending = Gdx.gl.glIsEnabled(GL20.GL_BLEND)
        fun enableBlend() {
            if (!wasBlending) {
                Gdx.gl.glEnable(GL20.GL_BLEND)
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            }
        }
        fun restoreBlend() {
            if (!wasBlending) Gdx.gl.glDisable(GL20.GL_BLEND)
        }

        if (showGrid) {
            shapes.flush()
            enableBlend()
            drawTileGrid(shapes, session.debugMap, projection)
            shapes.flush()
            restoreBlend()
        }
        if (!showBoxes && !showRoutes) return

        val drawings = project(session, projection)
        val plans = planUnits(drawings, selectedIds, showBoxes, showRoutes, showDetails)

        fun line(from: Position, to: Position) {
            shapes.line(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat())
        }

        // Pass 1 (Line, with alpha blending): secondary details (selected-only or F3+D) and routes (dimmed for unselected).
        shapes.flush()
        enableBlend()
        for (idx in drawings.indices) {
            val body = drawings[idx]
            val plan = plans[idx]
            val center = body.center
            val x = center.x.toFloat()
            val y = center.y.toFloat()
            val radius = body.bound.toFloat()

            if (plan.drawSecondaryDetails) {
                // Contact buffer outline
                body.bufferRadius?.let { bufferRadius ->
                    shapes.color = BUFFER_COLOR
                    if (body.bufferCorners.isEmpty()) shapes.circle(x, y, bufferRadius.toFloat(), CIRCLE_SEGMENTS)
                    else body.bufferCorners.forEachIndexed { i, point -> line(point, body.bufferCorners[(i + 1) % body.bufferCorners.size]) }
                }
                // Coarse bounding circle
                shapes.color = COARSE_BOUND_COLOR
                shapes.circle(x, y, radius, CIRCLE_SEGMENTS)
                // Velocity vector
                body.velocityTip?.let { vTip ->
                    shapes.color = VELOCITY_COLOR
                    line(center, vTip)
                }
                // Guidance vector
                body.guidanceTip?.let { gTip ->
                    shapes.color = GUIDANCE_COLOR
                    line(center, gTip)
                }
            }

            if (plan.drawRoute) {
                shapes.setColor(ROUTE_COLOR.r, ROUTE_COLOR.g, ROUTE_COLOR.b, plan.routeAlpha)
                var from = center
                for (point in body.route) {
                    line(from, point)
                    from = point
                }
                body.target?.let {
                    val tx = it.x.toFloat()
                    val ty = it.y.toFloat()
                    shapes.line(tx - 4f, ty, tx + 4f, ty)
                    shapes.line(tx, ty - 4f, tx, ty + 4f)
                    shapes.circle(tx, ty, 5f, 12)
                }
            }
        }
        shapes.flush()

        // Pass 2 (Filled): crisp black-outlined warm-yellow (#F2C96B) / stalled-red (#FF2929) hard hull + exterior front arrow.
        try {
            if (showBoxes) {
                shapes.end()
                shapes.begin(ShapeRenderer.ShapeType.Filled)
                try {
                    for (idx in drawings.indices) {
                        val body = drawings[idx]
                        val plan = plans[idx]
                        if (!plan.drawHardHullAndArrow) continue
                        val innerColor = if (plan.stalledHighlight) STALLED_COLOR else HARD_HULL_COLOR
                        drawOutlinedHardHull(shapes, body, innerColor)
                        drawOutlinedExteriorArrow(shapes, body, innerColor)
                    }
                } finally {
                    shapes.end()
                    shapes.begin(ShapeRenderer.ShapeType.Line)
                }
            }
        } finally {
            restoreBlend()
        }
    }

    private fun drawOutlinedHardHull(shapes: ShapeRenderer, body: CollisionBoxDrawing, innerColor: Color) {
        val outerWidth = HARD_HULL_OUTER_WIDTH
        val innerWidth = HARD_HULL_INNER_WIDTH
        if (body.corners.size >= 3) {
            // Outer black stroke
            shapes.color = OUTLINE_BLACK
            drawClosedPolyStroke(shapes, body.corners, outerWidth)
            // Inner warm-yellow / stalled-red stroke
            shapes.color = innerColor
            drawClosedPolyStroke(shapes, body.corners, innerWidth)
        } else {
            val cx = body.center.x.toFloat()
            val cy = body.center.y.toFloat()
            val r = body.bound.toFloat().coerceAtLeast(2f)
            val pts = List(CIRCLE_SEGMENTS) { i ->
                val a = 2.0 * PI * i / CIRCLE_SEGMENTS
                Position(cx + r * cos(a), cy + r * sin(a))
            }
            shapes.color = OUTLINE_BLACK
            drawClosedPolyStroke(shapes, pts, outerWidth)
            shapes.color = innerColor
            drawClosedPolyStroke(shapes, pts, innerWidth)
        }
    }

    private fun drawClosedPolyStroke(shapes: ShapeRenderer, pts: List<Position>, width: Float) {
        val half = width * 0.5f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            shapes.rectLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), width)
            // Corner cap circle ensures clean mitered joins on rotated rectangles without notches
            shapes.circle(a.x.toFloat(), a.y.toFloat(), half, 8)
        }
    }

    private fun drawOutlinedExteriorArrow(shapes: ShapeRenderer, body: CollisionBoxDrawing, innerColor: Color) {
        val (left, tip, right) = exteriorHeadingArrow(body)
        // Outer black outline for chevron arms
        shapes.color = OUTLINE_BLACK
        shapes.rectLine(left.x.toFloat(), left.y.toFloat(), tip.x.toFloat(), tip.y.toFloat(), ARROW_OUTER_WIDTH)
        shapes.rectLine(right.x.toFloat(), right.y.toFloat(), tip.x.toFloat(), tip.y.toFloat(), ARROW_OUTER_WIDTH)
        shapes.circle(tip.x.toFloat(), tip.y.toFloat(), ARROW_OUTER_WIDTH * 0.5f, 8)
        shapes.circle(left.x.toFloat(), left.y.toFloat(), ARROW_OUTER_WIDTH * 0.5f, 8)
        shapes.circle(right.x.toFloat(), right.y.toFloat(), ARROW_OUTER_WIDTH * 0.5f, 8)

        // Inner warm-yellow / red chevron arms
        shapes.color = innerColor
        shapes.rectLine(left.x.toFloat(), left.y.toFloat(), tip.x.toFloat(), tip.y.toFloat(), ARROW_INNER_WIDTH)
        shapes.rectLine(right.x.toFloat(), right.y.toFloat(), tip.x.toFloat(), tip.y.toFloat(), ARROW_INNER_WIDTH)
        shapes.circle(tip.x.toFloat(), tip.y.toFloat(), ARROW_INNER_WIDTH * 0.5f, 8)
        shapes.circle(left.x.toFloat(), left.y.toFloat(), ARROW_INNER_WIDTH * 0.5f, 8)
        shapes.circle(right.x.toFloat(), right.y.toFloat(), ARROW_INNER_WIDTH * 0.5f, 8)
    }

    private fun drawTileGrid(
        shapes: ShapeRenderer,
        map: com.rustedwarfare.reforge.core.content.MapDefinition,
        projection: MapProjection,
    ) {
        val tw = map.tileWidth.toDouble()
        val th = map.tileHeight.toDouble()
        if (tw <= 0.0 || th <= 0.0) return
        val worldW = map.widthTiles * tw
        val worldH = map.heightTiles * th
        val halfW = (projection.mapArea.width * 0.5) / projection.scale
        val halfH = (projection.mapArea.height * 0.5) / projection.scale
        val cx = projection.camera.center.x
        val cy = projection.camera.center.y
        val minCol = ((cx - halfW) / tw).toInt().coerceIn(0, map.widthTiles)
        val maxCol = (((cx + halfW) / tw).toInt() + 1).coerceIn(0, map.widthTiles)
        val minRow = ((cy - halfH) / th).toInt().coerceIn(0, map.heightTiles)
        val maxRow = (((cy + halfH) / th).toInt() + 1).coerceIn(0, map.heightTiles)

        shapes.color = GRID_COLOR
        val y0 = projection.y(0.0)
        val y1 = projection.y(worldH)
        for (col in minCol..maxCol) {
            val sx = projection.x(col * tw)
            shapes.line(sx, y0, sx, y1)
        }
        val x0 = projection.x(0.0)
        val x1 = projection.x(worldW)
        for (row in minRow..maxRow) {
            val sy = projection.y(row * th)
            shapes.line(x0, sy, x1, sy)
        }
    }

    private const val CIRCLE_SEGMENTS = 32
    private const val VELOCITY_ARROW_SECONDS = 20.0
    private const val GUIDANCE_ARROW_EXTRA_WORLD = 14.0
    private const val HARD_HULL_OUTER_WIDTH = 4.2f
    private const val HARD_HULL_INNER_WIDTH = 2.2f
    private const val ARROW_OUTER_WIDTH = 3.8f
    private const val ARROW_INNER_WIDTH = 2.0f
    internal const val SELECTED_ROUTE_ALPHA = 1.0f
    internal const val UNSELECTED_ROUTE_ALPHA = 0.35f

    private val OUTLINE_BLACK = Color(0.05f, 0.05f, 0.05f, 0.96f)
    private val HARD_HULL_COLOR = Color(0.949f, 0.788f, 0.420f, 1.0f) // #F2C96B warm yellow
    private val STALLED_COLOR = Color(1.0f, 0.161f, 0.161f, 1.0f)      // #FF2929 bright red
    private val COARSE_BOUND_COLOR = Color(0.30f, 0.88f, 0.96f, 0.72f)
    private val BUFFER_COLOR = Color(0.42f, 0.85f, 0.98f, 0.55f)
    private val VELOCITY_COLOR = Color(0.24f, 0.96f, 0.42f, 0.92f)
    private val GUIDANCE_COLOR = Color(1.0f, 0.90f, 0.18f, 0.92f)
    private val ROUTE_COLOR = Color(0.35f, 0.90f, 1.0f, 1.0f)
    private val GRID_COLOR = Color(1f, 1f, 1f, 0.10f)
}
