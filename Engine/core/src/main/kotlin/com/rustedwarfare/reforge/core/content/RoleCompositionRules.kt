package com.rustedwarfare.reforge.core.content

/** Qualified finite mobile production, maintenance and transport combinations. */
internal object RoleCompositionRules {
    fun usesRoles(unit: UnitDefinition): Boolean = unit.className == "AuthoredRoleUnit"

    fun supports(unit: UnitDefinition, definitions: Map<String, UnitDefinition>): Boolean {
        if (!usesRoles(unit) || unit.building != null || unit.maximumHealth == null ||
            unit.attachmentLayout != null || unit.attachmentModule != null || unit.energy != null ||
            unit.deathExplosion != null || unit.nuclearSilo != null || unit.rotor != null || unit.mech != null ||
            unit.movement.type !in setOf(MovementType.LAND, MovementType.HOVER, MovementType.NONE,
                MovementType.AIR, MovementType.WATER) || unit.radius !in 1.0..19.0) return false
        val production = unit.production
        val productMedia = if (unit.movement.type == MovementType.WATER) setOf(MovementType.AIR)
            else setOf(MovementType.LAND, MovementType.HOVER)
        if (production != null && (production.exitProfile != FactoryExitProfile.AUTHORED_MOBILE ||
            production.speedMultiplier !in .01f..20f || production.unitTypeIds.isEmpty() ||
            production.unitTypeIds.distinct().size != production.unitTypeIds.size ||
            production.unitTypeIds.any { id ->
                val product = definitions[id] ?: return@any true
                val build = product.build ?: return@any true
                product.movement.type !in productMedia || build.price < 0L || !build.rate.isFinite() || build.rate <= 0f
            })) return false
        if ((unit.transport == null) != (unit.transportSettings == null) ||
            unit.transportSettings?.let { unit.transport != TransportProfile.AUTHORED || !it.isValid() } == true)
            return false
        val builder = unit.builder
        if (builder != null && (builder.range !in 1f..500f || builder.repairRate !in .01f..10f ||
            builder.independentNanoTurnSpeed?.let { it !in .01f..180f || builder.mainNanoTurret != null } == true ||
            unit.movement.type == MovementType.NONE && builder.independentNanoTurnSpeed == null ||
            builder.buildSpeedMultiplier !in .01f..20f || builder.buildingTypeIds.isEmpty() ||
            builder.buildingTypeIds.distinct().size != builder.buildingTypeIds.size ||
            !builder.buildingTypeIds.containsAll(builder.nanoConstructionTypes) ||
            builder.buildingTypeIds.any { id -> definitions[id]?.let(unit::canConstruct) != true })) return false
        if ((unit.maintenance != null) != (unit.authoredMaintenance != null) ||
            unit.authoredMaintenance?.let { unit.maintenance != MaintenanceProfile.AUTHORED || builder == null || !it.isValid() } == true)
            return false
        if (unit.customActions?.actions.orEmpty().map { it.actionId }.let { it.distinct().size != it.size }) return false
        return unit.customActions?.actions.orEmpty().all { action ->
            val target = action.targetTypeId?.let { definitions[it] }
            action.targetTypeId == null || target != null && compatibleConversion(unit, target)
        }
    }

    private fun compatibleConversion(source: UnitDefinition, target: UnitDefinition): Boolean {
        if (!usesRoles(target) || source.id == target.id || source.radius != target.radius ||
            source.production != target.production || source.transport != target.transport ||
            source.transportSettings != target.transportSettings || source.builder?.buildingTypeIds != target.builder?.buildingTypeIds ||
            source.builder?.nanoConstructionTypes != target.builder?.nanoConstructionTypes ||
            source.builder?.independentNanoTurnSpeed != target.builder?.independentNanoTurnSpeed ||
            (source.blink == null) != (target.blink == null) || source.combat != target.combat ||
            setOf(source.movement.type, target.movement.type) != setOf(MovementType.HOVER, MovementType.NONE)) return false
        return target.customActions?.actions?.any { it.targetTypeId == source.id } == true
    }
}
