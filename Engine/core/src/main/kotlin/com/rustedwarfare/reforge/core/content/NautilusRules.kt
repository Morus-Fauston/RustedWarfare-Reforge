package com.rustedwarfare.reforge.core.content

/** Qualified forms share direct-world admission and the ordinary mobile production capability. */
internal object NautilusRules {
    val attachments = listOf(-6.0 to 6.0, 6.0 to 6.0, -6.0 to 26.0, 6.0 to 26.0, -2.0 to -20.0, 2.0 to -20.0)
    fun feet(land: Boolean): List<Pair<Double, Double>> = if (land)
        listOf(-20.0 to 0.0, 20.0 to 0.0, -20.0 to 34.0, 20.0 to 34.0, -14.0 to -25.0, 14.0 to -25.0)
        else listOf(-6.0 to 0.0, 6.0 to 0.0, -6.0 to 34.0, 6.0 to 34.0, -4.0 to -25.0, 4.0 to -25.0)

    fun legs(id: String, leg: java.nio.file.Path, foot: java.nio.file.Path): MechDefinition {
        val land = id == "nautilusSubmarineLand"
        return MechDefinition(MechBodyDefinition(0.0, 1.0, 0.0, 180.0, 0.0, 0.0, 0.0, 0),
            feet(land).mapIndexed { index, position ->
                val attach = attachments[index]
                MechLegDefinition(position.first, position.second, attach.first, attach.second,
                    if (land) 1.5 else .3, 0.0, 3.0, .3, 10.0, 20.0, 1.0, leg, foot, 20.0,
                    lockMovement = !land)
            }, MechKind.SIX_LEGGED)
    }

    private fun qualifiedLegs(d: UnitDefinition): Boolean {
        val mech = d.mech ?: return d.id == "nautilusSubmarine"
        if (d.id == "nautilusSubmarine" || mech.legs.isEmpty()) return false
        val expected = legs(d.id, mech.legs.first().legImage, mech.legs.first().footImage)
        return mech.kind == expected.kind && mech.body == expected.body && mech.legs == expected.legs
    }
    fun actions(id: String): CustomActionsProfile {
        val sub = id == "nautilusSubmarine"
        val land = id == "nautilusSubmarineLand"
        return CustomActionsProfile(listOf(
            CustomActionDefinition("surface", "nautilusSubmarineSurface_${if (sub) 5 else 6}", "nautilusSubmarineSurface",
                1000f, null, canPlayerCancel = true, visible = sub, cannotMove = true, highPriority = true),
            CustomActionDefinition("dive", "nautilusSubmarine_${if (sub) 6 else 7}", "nautilusSubmarine",
                1f / 270f, null, canPlayerCancel = true, visible = !sub && !land, cannotMove = true,
                highPriority = true, requiresOverWater = true),
            CustomActionDefinition("launchScoutBot", "claunchScoutBot", null, 1f / 210f, null, cooldown = 29999,
                canPlayerCancel = true, visible = !sub, cannotMove = true,
                groundLaunch = GroundLaunchProfile("botTurret", 250L, 500.0, MovementType.HOVER, ScoutBotRules.LAND))) +
            (if (land) listOf(CustomActionDefinition("trigger_onWater", "nautilusSubmarineSurface_8", "nautilusSubmarineSurface",
                1000f, null, automatic = AutomaticActionCondition.OVER_WATER, visible = false)) else emptyList()) +
            CustomActionDefinition("trigger_onLand", "nautilusSubmarineLand_${if (land) 12 else 11}", "nautilusSubmarineLand",
                1000f, null, automatic = AutomaticActionCondition.NOT_OVER_WATER, visible = false))
    }
    fun acceptsProduct(d: UnitDefinition): Boolean = d.id == "hoverTank" && d.build == BuildProfile(450L, .002f) &&
        d.movement.type == MovementType.HOVER && d.movement.hover != null && d.radius == 7.0 &&
        d.displayRadius == 9.0 && d.maximumHealth == 150.0 && d.combat != null && d.transportSlots == 1 &&
        d.building == null && d.transport == null

    fun supports(d: UnitDefinition): Boolean = d.id in setOf("nautilusSubmarine", "nautilusSubmarineSurface", "nautilusSubmarineLand") &&
        d.className == "CustomUnitMetadata" &&
        d.radius == 36.0 && d.displayRadius == 36.0 && d.totalFrames == 1 && d.imageScale == 1.0 &&
        d.movement == when (d.id) {
            "nautilusSubmarine" -> MovementProfile(MovementType.WATER, .45, .015, .05, .8, .05, false, false, .6)
            "nautilusSubmarineSurface" -> MovementProfile(MovementType.HOVER, .6, .015, .05, .8, .05, false, false, .6)
            else -> MovementProfile(MovementType.OVER_CLIFF_WATER, .35, .05, .05, .5, .2, false, false, .6, 45)
        } &&
        d.durability?.let { it.maxHp == 4000.0 && it.deadImagePath != null && it.maxDamagePerHit == null } == true &&
        d.production == ProductionProfile(listOf("hoverTank"), FactoryExitProfile.CUSTOM_NAUTILUS, 2f) &&
        d.build == BuildProfile(18000L, .0003f) && d.energy == EnergyProfile(6f, .01f, .01f, true) &&
        d.transport == TransportProfile.NAUTILUS_SUBMERGED && d.transportSlots == 1 && d.selfRegenRate == .1f &&
        d.fogOfWarSightRange == 24 && d.techLevel == 2 && NautilusWeapons.supports(d) && d.building == null &&
        d.builder == null && d.construction == null && d.upgrades == null && d.income == null &&
        d.pointDefense == null && d.deathExplosion == null && qualifiedLegs(d) && d.rotor == null &&
        d.fixedUnderwaterTarget == null && d.submarine == null && d.amphibiousJet == null && d.shield == null &&
        d.customActions?.let { it.actions == actions(d.id).actions && it.automaticCooldown == 60f } == true &&
        d.groundHeight == null && d.maintenance == null && d.reclaimPrice == null &&
        !d.authoredWaterCombat && d.directHitTargetKind == DirectHitTargetKind.ORDINARY
}
