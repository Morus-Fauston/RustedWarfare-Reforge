package com.rustedwarfare.reforge.core.content

internal object ModularSpiderUtilityForms {
    class Form(
        val id: String,
        price: Long,
        val width: Int,
        val height: Int,
        val income: IncomeProfile? = null,
        upgrades: List<AttachmentUpgrade> = emptyList(),
        automaticAction: AttachmentAutomaticAction? = null,
    ) {
        val module = AttachmentModuleProfile(empty = false, selfBuildRate = .001f,
            cancel = AttachmentRemoval(ModularSpiderRules.EMPTY, price, 120f),
            reclaim = AttachmentRemoval(ModularSpiderRules.EMPTY, price / 2, 180f),
            upgrades = upgrades, automaticAction = automaticAction)

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
        }
    }

    val byId = listOf(
        Form(ModularSpiderRules.FABRICATOR, 3000, 20, 19, IncomeProfile(40f, 5f),
            upgrades = listOf(AttachmentUpgrade("upgradeT2", ModularSpiderRules.FABRICATOR_T2, 5000, 480f))),
        Form(ModularSpiderRules.FABRICATOR_T2, 7000, 20, 19, IncomeProfile(40f, 10f)),
        Form(ModularSpiderRules.SHIELD, 6000, 22, 21,
            automaticAction = AttachmentAutomaticAction.RestoreParentShield(30f)),
        Form(ModularSpiderRules.SPEED_INCOMPLETE, 6000, 22, 21,
            automaticAction = AttachmentAutomaticAction.ConvertWhenBuilt(ModularSpiderRules.SPEED)),
        Form(ModularSpiderRules.SPEED, 6000, 22, 21),
    ).associateBy { it.id }
}
