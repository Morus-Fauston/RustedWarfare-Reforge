package com.rustedwarfare.reforge.core.content

internal object ModularSpiderUtilityForms {
    val laserDefense = PointDefenseProfile(muzzleOffsetX = 0.0, muzzleOffsetY = 0.0, recharge = .0003f, cost = .05f,
        attachedBarrel = 1.0)
    const val ANTI_NUKE_AMMO_COST = 3000L
    const val ANTI_NUKE_AMMO_RATE = .0007f
    const val ANTI_NUKE_AMMO_LIMIT = 12
    val antiNukeRanges = ProjectileInterceptionProfile(1000f, 1500f, 50f)
    const val BLINK_RANGE = 700.0
    const val BLINK_COOLDOWN = 1800
    const val BLINK_DURATION = 210

    class Form(
        val id: String,
        price: Long,
        val width: Int,
        val height: Int,
        val income: IncomeProfile? = null,
        upgrades: List<AttachmentUpgrade> = emptyList(),
        automaticAction: AttachmentAutomaticAction? = null,
        additionalSections: Map<String, Map<String, String>> = emptyMap(),
        parentSpeed: AttachmentSpeed? = null,
    ) {
        val module = AttachmentModuleProfile(empty = false, selfBuildRate = .001f,
            cancel = AttachmentRemoval(ModularSpiderRules.EMPTY, price, 120f),
            reclaim = AttachmentRemoval(ModularSpiderRules.EMPTY, price / 2, 180f),
            upgrades = upgrades, automaticAction = automaticAction, parentSpeed = parentSpeed)

        val sections = mapOf(
            "core" to (mapOf("name" to id, "class" to "CustomUnitMetadata", "price" to price.toString(),
                "maxHp" to "3000", "radius" to "15", "displayRadius" to "12", "isUnselectable" to "true",
                "canNotBeDirectlyAttacked" to "false", "selfBuildRate" to "0.001", "selfRegenRate" to "0.2") +
                income?.let { mapOf("generation_credits" to it.credits.toString(),
                    "generation_delay" to it.period.toString()) }.orEmpty()),
            "graphics" to mapOf("total_frames" to "1"),
            "action_cancel" to mapOf("convertTo" to ModularSpiderRules.EMPTY, "refundCredits" to price.toString(),
                "addAllActionCooldownsTime" to "120"),
            "action_reclaim" to mapOf("convertTo" to ModularSpiderRules.EMPTY, "refundCredits" to (price / 2).toString(),
                "buildSpeed" to "180"),
        ) + upgrades.associate { upgrade ->
            "action_${upgrade.action}" to mapOf("convertTo" to upgrade.targetTypeId, "price" to upgrade.cost.toString(),
                "buildSpeed" to upgrade.ticks.toString())
        } + when (automaticAction) {
            is AttachmentAutomaticAction.RestoreParentShield -> mapOf("hiddenAction_regen" to mapOf(
                "autoTrigger" to "true", "takeResources" to "shield=-${automaticAction.amount.toInt()}",
                "takeResources_includeParent" to "true", "takeResources_discardCollected" to "true"))
            is AttachmentAutomaticAction.ConvertWhenBuilt -> mapOf("hiddenAction_speedFinished" to mapOf(
                "autoTrigger" to "if self.completed", "convertTo" to automaticAction.targetTypeId))
            null -> emptyMap()
        } + additionalSections
    }

    val byId = listOf(
        Form(ModularSpiderRules.FABRICATOR, 3000, 20, 19, IncomeProfile(40f, 5f),
            upgrades = listOf(AttachmentUpgrade("upgradeT2", ModularSpiderRules.FABRICATOR_T2, 5000, 480f))),
        Form(ModularSpiderRules.FABRICATOR_T2, 7000, 20, 19, IncomeProfile(40f, 10f)),
        Form(ModularSpiderRules.SHIELD, 6000, 22, 21,
            automaticAction = AttachmentAutomaticAction.RestoreParentShield(30f)),
        Form(ModularSpiderRules.SPEED_INCOMPLETE, 6000, 22, 21,
            automaticAction = AttachmentAutomaticAction.ConvertWhenBuilt(ModularSpiderRules.SPEED)),
        Form(ModularSpiderRules.SPEED, 6000, 22, 21, parentSpeed = AttachmentSpeed(.7, 2, readyOnly = true)),
        Form(ModularSpiderRules.LASER_DEFENSE, 3000, 20, 19, additionalSections = mapOf(
            "turret_laserDefence" to mapOf("x" to "0", "y" to "0", "invisible" to "true", "canShoot" to "false",
                "laserDefenceEnergyUse" to laserDefense.cost.toString(), "limitingRange" to laserDefense.range.toString(),
                "size" to "1"),
            "defenseEnergy" to mapOf("energyMax" to "1", "energyRegen" to laserDefense.recharge.toString()),
        )),
        Form(ModularSpiderRules.ANTI_NUKE, 7000, 22, 21, additionalSections = mapOf(
            "action_buildAntiNuke" to mapOf("price" to ANTI_NUKE_AMMO_COST.toString(),
                "buildSpeed" to ANTI_NUKE_AMMO_RATE.toString(), "ammoLimit" to ANTI_NUKE_AMMO_LIMIT.toString(),
                "addResources" to "ammo=1"),
            "action_isEnabled" to mapOf("buildSpeed" to "0", "addResources" to "setFlag=1",
                "highPriorityQueue" to "true", "canPlayerCancel" to "false", "allowMultipleInQueue" to "false"),
            "action_isDisabled" to mapOf("buildSpeed" to "0", "addResources" to "unsetFlag=1",
                "highPriorityQueue" to "true", "canPlayerCancel" to "false", "allowMultipleInQueue" to "false"),
            "turret_silo" to mapOf("x" to "0", "y" to "0", "size" to "1", "invisible" to "true",
                "interceptProjectiles_withTags" to "nuke",
                "interceptProjectiles_andTargetingGroundUnderDistance" to antiNukeRanges.landingRange.toString(),
                "interceptProjectiles_andUnderDistance" to antiNukeRanges.trackingRange.toString(),
                "interceptProjectiles_andOverHeight" to antiNukeRanges.minimumHeight.toString(),
                "resourceUsage" to "ammo=1, hasMissingFlag=1", "shoot_sound" to "nuke_launch", "shoot_sound_vol" to "0.15"),
        )),
        Form(ModularSpiderRules.BLINK, 15000, 22, 21, additionalSections = mapOf(
            "action_blink" to mapOf("buildSpeed" to "0", "allowMultipleInQueue" to "false",
                "fireTurretXAtGround" to "dummyBlink", "fireTurretXAtGround_withProjectile" to "dummyBlink",
                "fireTurretXAtGround_onlyOverPassableTileOf" to "HOVER",
                "addActionCooldownTime" to BLINK_COOLDOWN.toString(),
                "highPriorityQueue" to "true", "whenBuilding_cannotMove" to "true",
                "sendMessageTo" to "parent", "sendMessageWithTags" to "blink",
                "sendMessageWithData" to "blinkTarget=thisActionTarget"),
            "turret_dummyBlink" to mapOf("x" to "0", "y" to "0", "invisible" to "true",
                "canShoot" to "false", "limitingRange" to BLINK_RANGE.toString(), "size" to "1"),
            "projectile_dummyBlink" to mapOf("instant" to "true", "directDamage" to "0", "targetGround" to "true"),
        )),
    ).associateBy { it.id }
}
