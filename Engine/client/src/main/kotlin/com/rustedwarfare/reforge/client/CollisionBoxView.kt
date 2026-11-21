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
 * Thin LibGDX F3 screen adapter over [DiagnosticSceneProjector] (ENG-P016-001).
 * All collision, buffer, velocity, guidance, and 2s stall geometry originates in core.
 */
internal object CollisionBoxView {
    fun project(session: SandboxSession, projection: MapProjection): List<CollisionBoxDrawing> {
        fun screen(point: Position) = Position(projection.x(point.x).toDouble(), projection.y(point.y).toDouble())
        return session.diagnosticScene.units.map { unit -> toDrawing(unit, projection, ::screen) }
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

    fun draw(
        shapes: ShapeRenderer,
        session: SandboxSession,
        projection: MapProjection,
        showBoxes: Boolean = true,
        showRoutes: Boolean = true,
        showGrid: Boolean = false,
    ) {
        if (showGrid) {
            shapes.flush()
            val wasBlending = Gdx.gl.glIsEnabled(GL20.GL_BLEND)
            if (!wasBlending) {
                Gdx.gl.glEnable(GL20.GL_BLEND)
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            }
            drawTileGrid(shapes, session.debugMap, projection)
            shapes.flush()
            if (!wasBlending) Gdx.gl.glDisable(GL20.GL_BLEND)
        }
        if (!showBoxes && !showRoutes) return
        for (body in project(session, projection)) {
            fun line(from: Position, to: Position) {
                shapes.line(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat())
            }
            val center = body.center
            val x = center.x.toFloat()
            val y = center.y.toFloat()
            val radius = body.bound.toFloat()

            if (showBoxes) {
                body.bufferRadius?.let { bufferRadius ->
                    shapes.color = BUFFER_COLOR
                    if (body.bufferCorners.isEmpty()) shapes.circle(x, y, bufferRadius.toFloat(), CIRCLE_SEGMENTS)
                    else body.bufferCorners.forEachIndexed { i, point -> line(point, body.bufferCorners[(i + 1) % body.bufferCorners.size]) }
                }

                if (body.stalledHighlight) {
                    shapes.color = STALLED_COLOR
                    val highlightRadius = radius + 3.5f
                    shapes.circle(x, y, highlightRadius, CIRCLE_SEGMENTS)
                    shapes.circle(x, y, highlightRadius + 1.5f, CIRCLE_SEGMENTS)
                }

                shapes.color = if (body.stalledHighlight) STALLED_COLOR else Color.ORANGE
                if (body.corners.isEmpty()) shapes.circle(x, y, radius, CIRCLE_SEGMENTS)
                else body.corners.forEachIndexed { i, point -> line(point, body.corners[(i + 1) % body.corners.size]) }
                line(center, body.headingTip)
                val dx = body.headingTip.x - center.x
                val dy = body.headingTip.y - center.y
                line(body.headingTip, Position(body.headingTip.x - dx * .25 - dy * .15,
                    body.headingTip.y - dy * .25 + dx * .15))
                line(body.headingTip, Position(body.headingTip.x - dx * .25 + dy * .15,
                    body.headingTip.y - dy * .25 - dx * .15))

                body.velocityTip?.let { vTip ->
                    shapes.color = VELOCITY_COLOR
                    line(center, vTip)
                }

                shapes.color = Color.CYAN
                // A circular hull and its broad-phase bound coincide. Alternate the cyan and orange segments.
                if (body.corners.isEmpty()) {
                    val angle = 2 * PI / CIRCLE_SEGMENTS
                    fun perimeter(i: Int) = Position(center.x + radius * cos(i * angle), center.y + radius * sin(i * angle))
                    for (i in 0 until CIRCLE_SEGMENTS step 4) {
                        line(perimeter(i), perimeter(i + 1))
                        line(perimeter(i + 1), perimeter(i + 2))
                    }
                } else shapes.circle(x, y, radius, CIRCLE_SEGMENTS)
            }

            if (showRoutes) {
                body.guidanceTip?.let { gTip ->
                    shapes.color = GUIDANCE_COLOR
                    line(center, gTip)
                }
                shapes.color = Color.CYAN
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
    private const val VELOCITY_ARROW_SECONDS = 12.0
    private const val GUIDANCE_ARROW_EXTRA_WORLD = 8.0
    private val BUFFER_COLOR = Color(0.45f, 1f, 1f, 1f)
    private val STALLED_COLOR = Color(1f, 0.16f, 0.16f, 1f)
    private val VELOCITY_COLOR = Color(0.25f, 1f, 0.45f, 1f)
    private val GUIDANCE_COLOR = Color(1f, 0.9f, 0.25f, 1f)
    private val GRID_COLOR = Color(1f, 1f, 1f, 0.22f)
}
