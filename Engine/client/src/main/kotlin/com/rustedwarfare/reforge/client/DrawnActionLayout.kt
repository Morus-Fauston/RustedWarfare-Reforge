package com.rustedwarfare.reforge.client

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable

internal data class DrawnAction(val actionId: String, val actor: Actor, val text: String,
    val button: String = "left", val enabled: Boolean = true, val localBounds: Rectangle? = null)

internal data class ActionLayoutFrame(val generation: Long, val selectionRevision: Long,
    val selectedIds: List<String>, val selectedTypes: Map<String, String>, val menuEpoch: Long,
    val viewport: Map<String, Int>, val projection: Map<String, Double>? = null)

/** Observe the actors actually submitted by this Stage; never dispatch an action. */
internal class DrawnActionLayout {
    private var previous: Map<String, Any?>? = null
    private var revision = 0L

    fun invalidate() { previous = null }

    fun afterDraw(root: Group, frame: ActionLayoutFrame, entries: List<DrawnAction>, drawnNanos: Long): Map<String, Any?>? {
        val actions = entries.mapNotNull { entry ->
            var parent: Actor? = entry.actor
            var attached = false
            while (parent != null) {
                if (!parent.isVisible) return@mapNotNull null
                if (parent === root) { attached = true; break }
                parent = parent.parent
            }
            if (!attached || entry.actor.width <= 0f || entry.actor.height <= 0f) return@mapNotNull null
            val actor = entry.actor
            val local = entry.localBounds ?: Rectangle(0f, 0f, actor.width, actor.height)
            if (listOf(local.x, local.y, local.width, local.height).any { !it.isFinite() } ||
                local.width <= 0f || local.height <= 0f || local.x < 0f || local.y < 0f ||
                local.x + local.width > actor.width || local.y + local.height > actor.height) return@mapNotNull null
            val corners = listOf(Vector2(local.x, local.y), Vector2(local.x + local.width, local.y),
                Vector2(local.x, local.y + local.height), Vector2(local.x + local.width, local.y + local.height))
                .map { actor.localToStageCoordinates(it) }
            val x = corners.minOf { it.x }; val y = corners.minOf { it.y }
            val width = corners.maxOf { it.x } - x; val height = corners.maxOf { it.y } - y
            val center = root.stageToLocalCoordinates(Vector2(x + width / 2, y + height / 2))
            var hit = root.hit(center.x, center.y, true)
            while (hit != null && hit !== actor) hit = hit.parent
            mapOf("actionId" to entry.actionId, "button" to entry.button, "text" to entry.text,
                "enabled" to (entry.enabled && actor.touchable != Touchable.disabled && hit === actor),
                "bounds" to mapOf("x" to x, "y" to y, "width" to width, "height" to height))
        }
        val snapshot = mapOf("layoutVersion" to 1, "generation" to frame.generation,
            "selectionRevision" to frame.selectionRevision, "selectedIds" to frame.selectedIds.sorted(),
            "selectedTypes" to frame.selectedTypes.toMap(), "menuEpoch" to frame.menuEpoch,
            "viewport" to frame.viewport.toMap(), "projection" to frame.projection?.toMap(), "actions" to actions)
        if (snapshot == previous) return null
        previous = snapshot
        return snapshot + mapOf("layoutRevision" to ++revision, "drawnNanos" to drawnNanos,
            "boundary" to "Stage draw submitted; native pixels and input receipts are separate evidence")
    }
}
