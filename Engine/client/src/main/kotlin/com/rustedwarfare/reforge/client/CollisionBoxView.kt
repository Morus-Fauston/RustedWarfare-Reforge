package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.contactBufferOutline
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class CollisionBoxDrawing(
    val unitId: String,
    val center: Position,
    val corners: List<Position>,
    val bound: Double,
    val bufferCorners: List<Position>,
    val bufferRadius: Double?,
    val headingTip: Position,
    val route: List<Position>,
    val target: Position?,
)

internal object CollisionBoxView {
    fun project(session: SandboxSession, projection: MapProjection): List<CollisionBoxDrawing> {
        fun screen(point: Position) = Position(projection.x(point.x).toDouble(), projection.y(point.y).toDouble())
        return session.observation.units.mapNotNull { unit ->
            val shape = session.movementCollisionForView(unit) ?: return@mapNotNull null
            val radians = Math.toRadians(unit.heading)
            val forwardX = sin(radians)
            val forwardY = -cos(radians)
            fun projectOffset(forward: Double, right: Double = 0.0) = screen(Position(
                unit.position.x + forwardX * forward - forwardY * right,
                unit.position.y + forwardY * forward + forwardX * right,
            ))
            fun corners(outline: MovementCollisionShape): List<Position> = when (outline) {
                is MovementCollisionShape.Circle -> emptyList()
                is MovementCollisionShape.Rectangle -> {
                    val l = outline.length / 2
                    val w = outline.width / 2
                    listOf(projectOffset(l, w), projectOffset(l, -w), projectOffset(-l, -w), projectOffset(-l, w))
                }
            }
            val nose = when (shape) {
                is MovementCollisionShape.Circle -> shape.radius
                is MovementCollisionShape.Rectangle -> shape.length / 2
            }
            val buffer = if (session.hasContactLayerForView(unit)) shape.contactBufferOutline() else null
            CollisionBoxDrawing(unit.unitId, screen(unit.position), corners(shape), shape.boundingRadius * projection.scale,
                buffer?.let(::corners).orEmpty(), buffer?.let { it.boundingRadius * projection.scale },
                projectOffset(nose), unit.pathPoints.map(::screen), unit.moveTarget?.let(::screen))
        }
    }

    fun draw(shapes: ShapeRenderer, session: SandboxSession, projection: MapProjection) {
        for (body in project(session, projection)) {
            fun line(from: Position, to: Position) {
                shapes.line(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat())
            }
            val center = body.center
            val x = center.x.toFloat()
            val y = center.y.toFloat()
            val radius = body.bound.toFloat()
            body.bufferRadius?.let { bufferRadius ->
                shapes.color = BUFFER_COLOR
                if (body.bufferCorners.isEmpty()) shapes.circle(x, y, bufferRadius.toFloat(), CIRCLE_SEGMENTS)
                else body.bufferCorners.forEachIndexed { i, point -> line(point, body.bufferCorners[(i + 1) % body.bufferCorners.size]) }
            }
            shapes.color = Color.ORANGE
            if (body.corners.isEmpty()) shapes.circle(x, y, radius, CIRCLE_SEGMENTS)
            else body.corners.forEachIndexed { i, point -> line(point, body.corners[(i + 1) % body.corners.size]) }
            line(center, body.headingTip)
            val dx = body.headingTip.x - center.x
            val dy = body.headingTip.y - center.y
            line(body.headingTip, Position(body.headingTip.x - dx * .25 - dy * .15,
                body.headingTip.y - dy * .25 + dx * .15))
            line(body.headingTip, Position(body.headingTip.x - dx * .25 + dy * .15,
                body.headingTip.y - dy * .25 - dx * .15))

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

    private const val CIRCLE_SEGMENTS = 32
    private val BUFFER_COLOR = Color(0.45f, 1f, 1f, 1f)
}
