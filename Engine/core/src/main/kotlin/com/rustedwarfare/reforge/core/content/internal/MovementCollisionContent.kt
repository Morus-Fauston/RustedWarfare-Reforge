package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.reject
import java.nio.file.Path

internal object MovementCollisionContent {
    const val shapeField = "collisionShape"
    private const val radiusField = "collisionRadius"
    private const val lengthField = "collisionLength"
    private const val widthField = "collisionWidth"
    val dimensions = setOf(radiusField, lengthField, widthField)
    val fields = dimensions + shapeField

    fun read(path: Path, source: UnitSourceReader.EffectiveSections, defaultRadius: Double): MovementCollisionShape =
        readDeclared(path, source) ?: MovementCollisionShape.Circle(defaultRadius)

    fun readDeclared(path: Path, source: UnitSourceReader.EffectiveSections): MovementCollisionShape? {
        val section = "movement"
        val values = source.values[section].orEmpty()
        fun invalid(field: String, reason: String): Nothing =
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, field, reason = reason)
        fun dimension(field: String): Double {
            val value = values[field] ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, field,
                reason = "Required movement collision dimension")
            return value.toDoubleOrNull()?.takeIf { it.isFinite() && it / 2.0 > 0.0 }
                ?: invalid(field, "Expected a positive finite dimension in map coordinates")
        }
        fun forbid(vararg names: String) {
            names.firstOrNull { it in values }?.let { invalid(it, "Dimension does not apply to the movement collision shape") }
        }
        return when (values[shapeField]) {
            null -> {
                forbid(radiusField, lengthField, widthField)
                null
            }
            "circle" -> {
                forbid(lengthField, widthField)
                MovementCollisionShape.Circle(dimension(radiusField))
            }
            "rectangle" -> {
                forbid(radiusField)
                MovementCollisionShape.Rectangle(dimension(lengthField), dimension(widthField))
            }
            else -> invalid(shapeField, "Expected circle or rectangle")
        }
    }
}
