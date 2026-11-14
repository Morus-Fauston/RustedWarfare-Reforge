package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.io.IOException
import java.nio.file.Path

internal object InstantWeaponContent {
    private val images = listOf("beamImage", "beamStartImage", "beamEndImage")
    private val modifiers = setOf("shieldDamage", "shieldDefection", "buildingDamage")
    val booleans = setOf("instant", "lightingEffect")
    val fields = images.toSet() + modifiers + booleans

    fun apply(path: Path, content: UnitSourceReader.EffectiveSections, section: String, base: ProjectileProfile,
              validatePath: (Path) -> Unit): ProjectileProfile {
        val values = content.values.getValue(section)
        if (values.keys.none { it in fields }) return base
        fun invalid(field: String, message: String, code: ContentErrorCode = ContentErrorCode.MALFORMED_SYNTAX): Nothing {
            val error = ContentError(code, path.toString(), section, field, values[field], message)
            val origin = content.origins[section to field]
            throw ContentReadException(origin?.locate(error, "[$section].$field") ?: error)
        }
        if (!content.structured) invalid("instant", "These weapon declarations require a structured unit")
        val lightning = values["lightingEffect"] == "true"
        val beam = if (images.any(values::containsKey)) {
            if (lightning) invalid("lightingEffect", "A beam and an instantaneous lightning segment are separate effects")
            val files = images.map { field ->
                val name = values[field] ?: invalid(field, "Declare all three beam images")
                val source = content.origins[section to field]?.path ?: path
                try {
                    val image = resolveReference(source, name, section, field)
                    validatePath(image)
                    readPngMetadata(image, allowIndexed = true, allowGrayAlpha = true)
                    image
                } catch (failure: ContentReadException) {
                    invalid(field, failure.error.reason, failure.error.code)
                } catch (failure: IOException) {
                    invalid(field, "Failed to read beam PNG: ${failure.message}", ContentErrorCode.BAD_REFERENCE)
                } catch (failure: IllegalArgumentException) {
                    invalid(field, "Invalid beam PNG: ${failure.message}", ContentErrorCode.BAD_REFERENCE)
                } catch (failure: ArithmeticException) {
                    invalid(field, "Beam PNG dimensions exceed the image bounds", ContentErrorCode.BAD_REFERENCE)
                }
            }
            BeamProfile(files[0], files[1], files[2])
        } else null
        val instant = values["instant"] == "true"
        if (!instant || base.ballistic != null || base.retargetOnDeath || base.ground != null || base.splash != null)
            invalid("instant", "Expected an instantaneous direct shot without arc, area or retarget work")
        fun multiplier(field: String): Double = values[field]?.let {
            it.toDoubleOrNull()?.takeIf { multiplier -> multiplier.isFinite() && multiplier in 0.0..10.0 }
                ?: invalid(field, "Expected a finite damage multiplier from zero to ten")
        } ?: 1.0
        return base.copy(instant = true, acceleration = null, lightning = lightning, beam = beam,
            damageModifiers = ProjectileDamageModifiers(multiplier("shieldDamage"), multiplier("shieldDefection"), multiplier("buildingDamage")))
    }
}
