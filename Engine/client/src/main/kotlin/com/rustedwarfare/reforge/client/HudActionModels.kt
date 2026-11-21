package com.rustedwarfare.reforge.client

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class HudActionKeyState {
    /** Ready bone-white keycap (#DBD5C6, ink text) */
    READY,
    /** Currently active target/placement mode (#F2EDE0, 2px rust inset outline) */
    ACTIVE_MODE,
    /** Insufficient funds (#353B3F dark cell, amber ▲ $price) */
    SHORT_FUNDS,
    /** Unavailable for non-fund reason (#353B3F dark cell, dim reason text ≤4 chars) */
    DISABLED,
}

data class HudTooltipRow(
    val label: String,
    val value: String,
    val isWarning: Boolean = false,
)

data class HudActionTooltipModel(
    val title: String,
    val priceText: String?,
    val description: String,
    val rows: List<HudTooltipRow>,
    val footnote: String,
)

data class HudActionKeyModel(
    val id: String,
    val cancelId: String?,
    val title: String,
    val unitIconTypeId: String?,
    val commandIconName: String?,
    val priceText: String?,
    val noteText: String?,
    val hotkeyLabel: String?,
    val queueBadgeCount: Int,
    val progress: Float?,
    val state: HudActionKeyState,
    val enabled: Boolean,
    val cancelEnabled: Boolean,
    val isTargetAction: Boolean,
    val attachmentContext: Pair<String, String>?,
    val tooltip: HudActionTooltipModel,
)

data class HudCommonCommandModel(
    val id: String,
    val iconName: String,
    val label: String,
    val hotkey: String,
    val active: Boolean,
    val enabled: Boolean,
)

data class HudActionListSnapshot(
    val showCommonBar: Boolean,
    val commonCommands: List<HudCommonCommandModel>,
    val ownerSubtitle: String?,
    val exclusiveKeys: List<HudActionKeyModel>,
) {
    val isEmpty: Boolean get() = !showCommonBar && exclusiveKeys.isEmpty()
}

data class HudTargetModeCardModel(
    val title: String,
    val rightValue: String?,
    val description: String,
    val statusText: String,
    val statusIsWarning: Boolean,
    val cancelHint: String,
)

/**
 * Pure projection from authorized [SandboxSession] / [ActionMenuView] into the redesigned
 * HUD action list, hover tooltip, and top-center target-mode hint card (HUD Spec §7, §8, §8A).
 *
 * Replaces all scenario-whitelist checks (`NativeActionPanel.supports`) with capability-driven layout.
 */
object HudActionProjection {

    fun formatPrice(price: Long): String {
        val nf = NumberFormat.getIntegerInstance(Locale.US)
        return "$" + nf.format(price)
    }

    /** Maps custom/built-in action names to our 65 self-owned `rw-*` command icons (HUD Spec §8A). */
    fun commandIconForAction(actionName: String): String = when (actionName) {
        "setRally" -> "rw-rally"
        "clearRally" -> "rw-rally-clear"
        "reclaim" -> "rw-reclaim"
        "repair" -> "rw-repair"
        "afterBurn", "cancelBurn" -> "rw-afterburn"
        "deploy" -> "rw-deploy"
        "undeploy" -> "rw-undeploy"
        "blink", "blinkQueued" -> "rw-blink"
        "buildNuke" -> "rw-nuke-build"
        "launchNuke" -> "rw-nuke"
        "buildAntiNuke" -> "rw-anti-nuke"
        "launchScoutBot" -> "rw-scout"
        "surface" -> "rw-surface"
        "dive" -> "rw-dive"
        "isEnabled" -> "rw-enable"
        "isDisabled" -> "rw-disable"
        "cancel" -> "rw-cancel"
        "selectSlotBuild" -> "rw-add-module"
        "upgradeT2", "upgradeFlak" -> "rw-upgrade"
        else -> when {
            actionName.startsWith("upgrade") -> "rw-upgrade"
            actionName.startsWith("build") -> "rw-build"
            actionName.startsWith("selectSlot") -> "rw-slot"
            else -> "rw-info"
        }
    }

    fun projectActionList(
        session: SandboxSession,
        activeConstructionTypeId: String?,
        reclaimMode: Boolean,
        repairMode: Boolean,
        patrolMode: Boolean,
        activeGroundActionName: String?,
        touchControls: Boolean,
        language: UiLanguage = UiStrings.currentLanguage,
    ): HudActionListSnapshot {
        val obs = session.observation
        val menu = session.actionMenu
        val selectedUnits = obs.units.filter { it.alive && it.unitId in session.selectedIds }
        val primary = obs.units.firstOrNull { it.alive && it.unitId == session.selectedId } ?: selectedUnits.firstOrNull()
        if (primary == null || primary.ownerPlayerId != obs.playerId) {
            return HudActionListSnapshot(
                showCommonBar = false,
                commonCommands = emptyList(),
                ownerSubtitle = null,
                exclusiveKeys = emptyList(),
            )
        }

        val balance = menu.balance ?: menu.credits?.toLong() ?: obs.balance ?: obs.credits?.toLong() ?: 0L
        val showCommon = session.canPatrol
        val commonCommands = if (showCommon) {
            listOf(
                HudCommonCommandModel(
                    id = "stop",
                    iconName = "rw-stop",
                    label = UiStrings.get("stop", language),
                    hotkey = if (touchControls) "" else "S",
                    active = false,
                    enabled = true,
                ),
                HudCommonCommandModel(
                    id = "choose-patrol",
                    iconName = "rw-patrol",
                    label = UiStrings.get("patrol", language),
                    hotkey = if (touchControls) "" else "P",
                    active = patrolMode,
                    enabled = true,
                ),
                HudCommonCommandModel(
                    id = "choose-guard",
                    iconName = "rw-guard",
                    label = UiStrings.get("guard", language),
                    hotkey = if (touchControls) "" else "G",
                    active = false,
                    enabled = true,
                ),
            )
        } else {
            emptyList()
        }

        val distinctTypes = selectedUnits.map { it.typeId }.distinct()
        val primaryName = menu.selectedTitle?.takeIf { it.isNotBlank() } ?: primary.typeId

        val keys = ArrayList<HudActionKeyModel>(16)

        // 1. Target-type exclusive actions placed FIRST per Spec §7.1–7.2 (Rally, Reclaim, Repair)
        val rallyAction = menu.unitActions.firstOrNull { it.name == "setRally" }
        if (rallyAction != null) {
            val active = activeGroundActionName == "setRally"
            keys += HudActionKeyModel(
                id = "unit-action:setRally",
                cancelId = if (rallyAction.cancellable) "cancel-unit-action:setRally" else null,
                title = UiStrings.get("rally", language),
                unitIconTypeId = null,
                commandIconName = "rw-rally",
                priceText = null,
                noteText = null,
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = if (active) HudActionKeyState.ACTIVE_MODE else if (rallyAction.available) HudActionKeyState.READY else HudActionKeyState.DISABLED,
                enabled = rallyAction.available,
                cancelEnabled = rallyAction.cancellable,
                isTargetAction = true,
                attachmentContext = rallyAction.attachmentContext,
                tooltip = HudActionTooltipModel(
                    title = UiStrings.get("rally", language),
                    priceText = null,
                    description = cleanDescription(rallyAction.tooltip),
                    rows = emptyList(),
                    footnote = UiStrings.get("leftTarget", language),
                ),
            )
        }

        if (menu.canMaintain) {
            keys += HudActionKeyModel(
                id = "choose-reclaim",
                cancelId = null,
                title = UiStrings.get("reclaim", language),
                unitIconTypeId = null,
                commandIconName = "rw-reclaim",
                priceText = null,
                noteText = null,
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = if (reclaimMode) HudActionKeyState.ACTIVE_MODE else HudActionKeyState.READY,
                enabled = true,
                cancelEnabled = false,
                isTargetAction = true,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = UiStrings.get("reclaim", language),
                    priceText = null,
                    description = cleanDescription(menu.reclaimTooltip),
                    rows = emptyList(),
                    footnote = UiStrings.get("leftTarget", language),
                ),
            )
        }

        if (menu.canRepair) {
            keys += HudActionKeyModel(
                id = "choose-repair",
                cancelId = null,
                title = UiStrings.get("repair", language),
                unitIconTypeId = null,
                commandIconName = "rw-repair",
                priceText = null,
                noteText = null,
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = if (repairMode) HudActionKeyState.ACTIVE_MODE else HudActionKeyState.READY,
                enabled = true,
                cancelEnabled = false,
                isTargetAction = true,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = UiStrings.get("repair", language),
                    priceText = null,
                    description = cleanDescription(menu.repairTooltip),
                    rows = emptyList(),
                    footnote = UiStrings.get("leftTarget", language),
                ),
            )
        }

        // 2. Production actions (unit artwork + price + queue badge + bottom rust progress bar)
        for (prod in menu.production) {
            val lack = (prod.price - balance).coerceAtLeast(0L)
            val shortFunds = !prod.affordable && lack > 0L
            val state = when {
                shortFunds -> HudActionKeyState.SHORT_FUNDS
                prod.affordable -> HudActionKeyState.READY
                else -> HudActionKeyState.DISABLED
            }
            val rows = buildList {
                add(HudTooltipRow(UiStrings.get("cost", language), formatPrice(prod.price)))
                add(HudTooltipRow(UiStrings.get("queued", language), prod.count.toString()))
                if (shortFunds) {
                    add(HudTooltipRow(UiStrings.get("noFunds", language), UiStrings.format("short", "N" to lack, language = language), isWarning = true))
                }
            }
            keys += HudActionKeyModel(
                id = "produce:${prod.typeId}",
                cancelId = "cancel-production:${prod.typeId}",
                title = prod.title,
                unitIconTypeId = prod.typeId,
                commandIconName = null,
                priceText = formatPrice(prod.price),
                noteText = null,
                hotkeyLabel = null,
                queueBadgeCount = prod.count,
                progress = prod.progress,
                state = state,
                enabled = prod.affordable,
                cancelEnabled = prod.count > 0,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = prod.title,
                    priceText = formatPrice(prod.price),
                    description = cleanDescription(prod.tooltip),
                    rows = rows,
                    footnote = UiStrings.get("leftQueue", language),
                ),
            )
        }

        // 3. Construction actions (unit artwork + price + active mode highlight + existing hotkeys X/J only)
        for (build in menu.construction) {
            val active = activeConstructionTypeId == build.typeId
            val lack = (build.price - balance).coerceAtLeast(0L)
            val shortFunds = lack > 0L && !active
            val state = when {
                active -> HudActionKeyState.ACTIVE_MODE
                shortFunds -> HudActionKeyState.SHORT_FUNDS
                else -> HudActionKeyState.READY
            }
            val hotkey = if (touchControls) null else when (build.typeId) {
                "c_turret_t1" -> "X"
                "laserDefence" -> "J"
                else -> null
            }
            val rows = buildList {
                add(HudTooltipRow(UiStrings.get("cost", language), formatPrice(build.price)))
                if (shortFunds) {
                    add(HudTooltipRow(UiStrings.get("noFunds", language), UiStrings.format("short", "N" to lack, language = language), isWarning = true))
                }
            }
            keys += HudActionKeyModel(
                id = "choose-construction:${build.typeId}",
                cancelId = null,
                title = build.title,
                unitIconTypeId = build.typeId,
                commandIconName = null,
                priceText = formatPrice(build.price),
                noteText = null,
                hotkeyLabel = hotkey,
                queueBadgeCount = 0,
                progress = null,
                state = state,
                enabled = !shortFunds,
                cancelEnabled = false,
                isTargetAction = true,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = build.title,
                    priceText = formatPrice(build.price),
                    description = cleanDescription(build.tooltip),
                    rows = rows,
                    footnote = UiStrings.get("leftTarget", language),
                ),
            )
        }

        // 4. Upgrades (upgrade icon or target unit icon + price + existing hotkey U for economic/single upgrade)
        for (upg in menu.upgrades) {
            val lack = (upg.price - balance).coerceAtLeast(0L)
            val queued = upg.progress != null
            val shortFunds = !upg.affordable && !queued && lack > 0L
            val state = when {
                queued -> HudActionKeyState.READY
                shortFunds -> HudActionKeyState.SHORT_FUNDS
                upg.affordable -> HudActionKeyState.READY
                else -> HudActionKeyState.DISABLED
            }
            val hotkey = if (!touchControls && upg.kind == UpgradePresentationKind.ECONOMIC) "U" else null
            val rows = buildList {
                add(HudTooltipRow(UiStrings.get("cost", language), formatPrice(upg.price)))
                if (shortFunds) {
                    add(HudTooltipRow(UiStrings.get("noFunds", language), UiStrings.format("short", "N" to lack, language = language), isWarning = true))
                }
            }
            keys += HudActionKeyModel(
                id = "upgrade:${upg.actionKey}",
                cancelId = "cancel-upgrade:${upg.actionKey}",
                title = upg.title,
                unitIconTypeId = null,
                commandIconName = "rw-upgrade",
                priceText = formatPrice(upg.price),
                noteText = null,
                hotkeyLabel = hotkey,
                queueBadgeCount = if (queued) 1 else 0,
                progress = upg.progress,
                state = state,
                enabled = upg.affordable && !queued,
                cancelEnabled = queued,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = upg.title,
                    priceText = formatPrice(upg.price),
                    description = cleanDescription(upg.tooltip),
                    rows = rows,
                    footnote = UiStrings.get("leftQueue", language),
                ),
            )
        }

        // 5. Depth / Flight / Transport toggles
        menu.depthAction?.let { depth ->
            val icon = if (depth.surface) "rw-surface" else "rw-dive"
            val label = UiStrings.get(if (depth.surface) "surface" else "dive", language)
            keys += HudActionKeyModel(
                id = if (depth.surface) "submarine-surface" else "submarine-dive",
                cancelId = null,
                title = label,
                unitIconTypeId = null,
                commandIconName = icon,
                priceText = null,
                noteText = null,
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = HudActionKeyState.READY,
                enabled = true,
                cancelEnabled = false,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(label, null, cleanDescription(depth.tooltip), emptyList(), UiStrings.get("leftUse", language)),
            )
        }

        menu.flightAction?.let { flight ->
            val icon = if (flight.flying) "rw-takeoff" else "rw-land"
            val label = UiStrings.get(if (flight.flying) "takeoff" else "land", language)
            keys += HudActionKeyModel(
                id = if (flight.flying) "amphibious-fly" else "amphibious-dive",
                cancelId = null,
                title = label,
                unitIconTypeId = null,
                commandIconName = icon,
                priceText = null,
                noteText = null,
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = if (flight.available) HudActionKeyState.READY else HudActionKeyState.DISABLED,
                enabled = flight.available,
                cancelEnabled = false,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(label, null, cleanDescription(flight.tooltip), emptyList(), UiStrings.get("leftUse", language)),
            )
        }

        menu.transportAction?.let { tr ->
            val id = if (tr.unloading) "transport-unload" else "transport-cancel-unload"
            val label = if (tr.unloading) UiStrings.get("unload", language) else UiStrings.get("cancelBtn", language)
            val rows = listOf(
                HudTooltipRow(UiStrings.get("cargo", language), "${tr.occupiedSlots}/${tr.capacity}"),
            )
            keys += HudActionKeyModel(
                id = id,
                cancelId = null,
                title = label,
                unitIconTypeId = null,
                commandIconName = if (tr.unloading) "rw-unload" else "rw-cancel",
                priceText = null,
                noteText = "${tr.occupiedSlots}/${tr.capacity}",
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = if (tr.available) HudActionKeyState.READY else HudActionKeyState.DISABLED,
                enabled = tr.available,
                cancelEnabled = false,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(label, null, tr.title, rows, UiStrings.get("leftUse", language)),
            )
        }

        // 6. Remaining custom unit actions (excluding setRally already promoted to front)
        for (act in menu.unitActions) {
            if (act.name == "setRally") continue
            val active = activeGroundActionName == act.name
            val coolSec = ceil(act.cooldownRemaining / 1000.0).toInt()
            val note = when {
                coolSec > 0 -> UiStrings.format("cool", "N" to coolSec, language = language)
                !act.available && act.tooltip.contains("charging", ignoreCase = true) -> UiStrings.get("charging", language)
                !act.available && act.tooltip.contains("full", ignoreCase = true) -> UiStrings.get("stockFull", language)
                else -> null
            }
            val state = when {
                active -> HudActionKeyState.ACTIVE_MODE
                act.available -> HudActionKeyState.READY
                else -> HudActionKeyState.DISABLED
            }
            keys += HudActionKeyModel(
                id = "unit-action:${act.name}",
                cancelId = if (act.cancellable) "cancel-unit-action:${act.name}" else null,
                title = act.title,
                unitIconTypeId = act.iconTypeId,
                commandIconName = if (act.iconTypeId == null) commandIconForAction(act.name) else null,
                priceText = null,
                noteText = note,
                hotkeyLabel = null,
                queueBadgeCount = if (act.queued) 1 else 0,
                progress = act.progress,
                state = state,
                enabled = act.available,
                cancelEnabled = act.cancellable,
                isTargetAction = act.groundTargeted,
                attachmentContext = act.attachmentContext,
                tooltip = HudActionTooltipModel(
                    title = act.title,
                    priceText = null,
                    description = cleanDescription(act.tooltip),
                    rows = emptyList(),
                    footnote = UiStrings.get(if (act.groundTargeted) "leftTarget" else if (act.cancellable) "leftQueue" else "leftUse", language),
                ),
            )
        }

        val ownerSubtitle = if (distinctTypes.size > 1 && keys.isNotEmpty()) {
            UiStrings.format("owner", "NAME" to primaryName, language = language)
        } else {
            null
        }

        return HudActionListSnapshot(
            showCommonBar = showCommon,
            commonCommands = commonCommands,
            ownerSubtitle = ownerSubtitle,
            exclusiveKeys = keys,
        )
    }

    /** Projects the battlefield top-center 300-wide target-mode hint card (HUD Spec §8.2). */
    internal fun projectTargetModeCard(
        session: SandboxSession,
        constructionTypeId: String?,
        constructionPreview: ConstructionPreview?,
        reclaimMode: Boolean,
        repairMode: Boolean,
        patrolMode: Boolean,
        patrolPointCount: Int,
        groundActionName: String?,
        groundActionPreview: GroundActionPreview?,
        sandboxPick: SandboxPick?,
        touchControls: Boolean,
        language: UiLanguage = UiStrings.currentLanguage,
    ): HudTargetModeCardModel? {
        val cancelHint = UiStrings.get("cancelHint", language)
        val pickActionHint = UiStrings.get(if (touchControls) "pickTargetTouch" else "pickTarget", language)

        if (constructionTypeId != null) {
            val action = session.actionMenu.construction.firstOrNull { it.typeId == constructionTypeId }
            val title = action?.title ?: session.unitTitle(constructionTypeId)
            val priceText = action?.let { formatPrice(it.price) }
            val desc = cleanDescription(action?.tooltip.orEmpty()).ifBlank { title }
            val (status, warn) = when (constructionPreview?.state) {
                PlacementState.CLEAR -> UiStrings.get("placeOk", language) to false
                PlacementState.BLOCKED -> "▲ " + UiStrings.get("placeBlocked", language) to true
                PlacementState.UNEXPLORED -> "▲ " + (if (language == UiLanguage.ZH) "未探索区域" else "Unexplored area") to true
                null -> pickActionHint to false
            }
            return HudTargetModeCardModel(
                title = title,
                rightValue = priceText,
                description = desc,
                statusText = status,
                statusIsWarning = warn,
                cancelHint = UiStrings.get("placeKey", language),
            )
        }

        if (reclaimMode) {
            return HudTargetModeCardModel(
                title = UiStrings.get("reclaim", language),
                rightValue = null,
                description = cleanDescription(session.actionMenu.reclaimTooltip).ifBlank { UiStrings.get("reclaim", language) },
                statusText = pickActionHint,
                statusIsWarning = false,
                cancelHint = cancelHint,
            )
        }

        if (repairMode) {
            return HudTargetModeCardModel(
                title = UiStrings.get("repair", language),
                rightValue = null,
                description = cleanDescription(session.actionMenu.repairTooltip).ifBlank { UiStrings.get("repair", language) },
                statusText = pickActionHint,
                statusIsWarning = false,
                cancelHint = cancelHint,
            )
        }

        if (patrolMode) {
            val countText = if (patrolPointCount > 0) {
                if (language == UiLanguage.ZH) "已选 $patrolPointCount 点" else "$patrolPointCount pts"
            } else {
                null
            }
            return HudTargetModeCardModel(
                title = UiStrings.get("patrol", language),
                rightValue = countText,
                description = if (language == UiLanguage.ZH) "依次点选地图路径点进行巡逻" else "Click map waypoints to patrol",
                statusText = pickActionHint,
                statusIsWarning = false,
                cancelHint = cancelHint,
            )
        }

        if (groundActionName != null) {
            val action = session.actionMenu.unitActions.firstOrNull { it.name == groundActionName }
            val title = when (groundActionName) {
                "setRally" -> UiStrings.get("rally", language)
                "launchNuke" -> UiStrings.get("launchNuke", language)
                else -> action?.title ?: groundActionName
            }
            val primary = session.observation.units.firstOrNull { it.unitId == session.selectedId }
            val rightVal = if (groundActionName == "launchNuke") {
                val ammo = primary?.unitActions?.stockpile ?: 0
                UiStrings.format("nukeLeft", "N" to ammo, language = language)
            } else {
                null
            }
            val (status, warn) = when {
                groundActionPreview != null && !groundActionPreview.valid -> {
                    val reason = groundActionPreview.rejection?.let { UiStrings.rejectionReason(it, language) }
                        ?: UiStrings.get("blocked", language)
                    "▲ $reason" to true
                }
                else -> pickActionHint to false
            }
            return HudTargetModeCardModel(
                title = title,
                rightValue = rightVal,
                description = cleanDescription(action?.tooltip.orEmpty()).ifBlank { title },
                statusText = status,
                statusIsWarning = warn,
                cancelHint = cancelHint,
            )
        }

        if (sandboxPick != null) {
            val (title, desc) = when (sandboxPick) {
                is SandboxPick.Spawn -> UiStrings.get("pick.spawn", language) to "${sandboxPick.setup.typeId} (${sandboxPick.setup.ownerPlayerId})"
                SandboxPick.Destroy -> UiStrings.get("pick.destroy", language) to (if (language == UiLanguage.ZH) "点选单位将其摧毁" else "Click a unit to destroy")
                is SandboxPick.Health -> UiStrings.get("pick.health", language) to "${if (sandboxPick.amount >= 0) "+" else ""}${sandboxPick.amount}"
            }
            return HudTargetModeCardModel(
                title = title,
                rightValue = null,
                description = desc,
                statusText = pickActionHint,
                statusIsWarning = false,
                cancelHint = cancelHint,
            )
        }

        return null
    }

    private fun cleanDescription(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("Left:", ignoreCase = true) &&
                    !it.startsWith("Right:", ignoreCase = true) &&
                    !it.startsWith("Esc", ignoreCase = true) &&
                    !it.startsWith("Insufficient", ignoreCase = true)
            }
            .take(2)
            .joinToString(" · ")
    }
}
