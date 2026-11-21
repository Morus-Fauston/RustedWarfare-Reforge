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

data class HudAttachmentSlotModel(
    val slotNumber: Int,
    val unitId: String,
    val typeId: String,
    val empty: Boolean,
    val selected: Boolean,
    val progress: Float,
    val title: String,
    val tooltip: HudActionTooltipModel,
)

data class HudActionListSnapshot(
    val showCommonBar: Boolean,
    val commonCommands: List<HudCommonCommandModel>,
    val ownerSubtitle: String?,
    val attachmentSlots: List<HudAttachmentSlotModel> = emptyList(),
    val exclusiveKeys: List<HudActionKeyModel>,
) {
    val isEmpty: Boolean get() = !showCommonBar && attachmentSlots.isEmpty() && exclusiveKeys.isEmpty()
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
    fun localizeUnitActionTitle(actionName: String, fallback: String, language: UiLanguage): String = when (actionName) {
        "buildNuke" -> UiStrings.get("buildNuke", language)
        "launchNuke" -> UiStrings.get("launchNuke", language)
        "buildAntiNuke" -> UiStrings.get("buildAntiNuke", language)
        "dive" -> UiStrings.get("dive", language)
        "surface" -> UiStrings.get("surface", language)
        "deploy" -> UiStrings.get("deploy", language)
        "undeploy" -> UiStrings.get("undeploy", language)
        "isEnabled" -> UiStrings.get("disable", language)
        "isDisabled" -> UiStrings.get("enable", language)
        "afterBurn" -> UiStrings.get("afterburn", language)
        "cancelBurn" -> UiStrings.get("cancelBurn", language)
        else -> fallback
    }

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
        "isEnabled" -> "rw-disable"
        "isDisabled" -> "rw-enable"
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
                attachmentSlots = emptyList(),
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

        val attachmentOverview = primary.attachmentOverview
        val attachmentSlots = attachmentOverview?.slots?.map { slot ->
            val isEmpty = session.isEmptyAttachmentModule(slot.typeId)
            val title = if (isEmpty) UiStrings.get("empty", language) else session.unitTitle(slot.typeId)
            val statusLine = when {
                isEmpty -> UiStrings.get("empty", language)
                slot.progress < 0.999f -> "${(slot.progress * 100f).roundToInt().coerceIn(0, 100)}%"
                else -> title
            }
            HudAttachmentSlotModel(
                slotNumber = slot.number,
                unitId = slot.unitId,
                typeId = slot.typeId,
                empty = isEmpty,
                selected = attachmentOverview.selectedSlot == slot.number,
                progress = slot.progress,
                title = title,
                tooltip = HudActionTooltipModel(
                    title = "${UiStrings.format("slotPrefix", "N" to slot.number, language = language)} · $title",
                    priceText = null,
                    description = statusLine,
                    rows = emptyList(),
                    footnote = UiStrings.get("leftUse", language),
                ),
            )
        } ?: emptyList()

        val keys = ArrayList<HudActionKeyModel>(16)

        // 0. Under-construction own building: hide production/upgrade keys until complete; show Cancel build -> Reclaim (Spec §7.2 Q1)
        if (primary.constructionProgress < 1f && session.isBuilding(primary.typeId)) {
            val refundPrice = session.siteConstructionPrice(primary.typeId)
            val refundRows = if (refundPrice != null && refundPrice > 0L) {
                listOf(HudTooltipRow(UiStrings.get("cost", language), formatPrice(refundPrice)))
            } else emptyList()
            val cancelTitle = UiStrings.get("cancelBuild", language)
            keys += HudActionKeyModel(
                id = "cancel-construction",
                cancelId = null,
                title = cancelTitle,
                unitIconTypeId = null,
                commandIconName = "rw-cancel",
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
                tooltip = HudActionTooltipModel(
                    title = cancelTitle,
                    priceText = null,
                    description = UiStrings.get("cancelBuildDesc", language),
                    rows = refundRows,
                    footnote = UiStrings.get("leftUse", language),
                ),
            )
            val canReclaimSite = session.hasMaintainerInSelection()
            val reclaimState = when {
                reclaimMode -> HudActionKeyState.ACTIVE_MODE
                canReclaimSite -> HudActionKeyState.READY
                else -> HudActionKeyState.DISABLED
            }
            keys += HudActionKeyModel(
                id = "choose-reclaim",
                cancelId = null,
                title = UiStrings.get("reclaim", language),
                unitIconTypeId = null,
                commandIconName = "rw-reclaim",
                priceText = null,
                noteText = if (canReclaimSite) null else UiStrings.get("reclaimNeedWorker", language),
                hotkeyLabel = null,
                queueBadgeCount = 0,
                progress = null,
                state = reclaimState,
                enabled = canReclaimSite,
                cancelEnabled = false,
                isTargetAction = true,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = UiStrings.get("reclaim", language),
                    priceText = null,
                    description = UiStrings.get("reclaimSiteDesc", language),
                    rows = emptyList(),
                    footnote = UiStrings.get(if (canReclaimSite) "leftTarget" else "reclaimNeedWorkerFull", language),
                ),
            )
            val ownerSubtitle = if (distinctTypes.size > 1) {
                UiStrings.format("owner", "NAME" to primaryName, language = language)
            } else null
            return HudActionListSnapshot(
                showCommonBar = showCommon,
                commonCommands = commonCommands,
                ownerSubtitle = ownerSubtitle,
                attachmentSlots = emptyList(),
                exclusiveKeys = keys,
            )
        }

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

        val slotPageActive = attachmentOverview?.selectedSlot != null
        if (menu.canMaintain && !slotPageActive) {
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

        if (menu.canRepair && !slotPageActive) {
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

        // 4. Upgrades (target unit artwork for branching turret upgrades, rw-upgrade otherwise; branch-locked state per Spec §7.2 Q5)
        val activeUpgradeKeys = menu.upgradeQueue?.items?.map { it.actionKey }?.toSet().orEmpty()
        val hasMultiBranchUpgrade = menu.upgrades.size > 1
        for (upg in menu.upgrades) {
            val lack = (upg.price - balance).coerceAtLeast(0L)
            val queued = upg.progress != null
            val branchLocked = hasMultiBranchUpgrade && !queued && activeUpgradeKeys.isNotEmpty()
            val shortFunds = !branchLocked && !upg.affordable && !queued && lack > 0L
            val state = when {
                branchLocked -> HudActionKeyState.DISABLED
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
            val useTargetUnitIcon = upg.kind == UpgradePresentationKind.DEFENSE
            keys += HudActionKeyModel(
                id = "upgrade:${upg.actionKey}",
                cancelId = "cancel-upgrade:${upg.actionKey}",
                title = upg.title,
                unitIconTypeId = if (useTargetUnitIcon) upg.targetTypeId else null,
                commandIconName = if (useTargetUnitIcon) null else "rw-upgrade",
                priceText = if (branchLocked) null else formatPrice(upg.price),
                noteText = if (branchLocked) UiStrings.get("branchTaken", language) else null,
                hotkeyLabel = hotkey,
                queueBadgeCount = 0,
                progress = upg.progress,
                state = state,
                enabled = !branchLocked && upg.affordable && !queued,
                cancelEnabled = queued,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(
                    title = upg.title,
                    priceText = formatPrice(upg.price),
                    description = cleanDescription(upg.tooltip),
                    rows = rows,
                    footnote = if (branchLocked) UiStrings.get("branchFull", language) else UiStrings.get("leftQueue", language),
                ),
            )
        }

        // 5. Depth / Flight / Transport toggles (single next-step verb + transition progress guard per Spec §7.2 Q3)
        menu.depthAction?.let { depth ->
            val sub = primary.submarine
            val transitioning = sub != null && sub.surfaceIntent != null && sub.surfaceIntent == sub.underwater
            val transitionProgress = if (transitioning) {
                val h = primary.height.toFloat()
                if (sub.surfaceIntent == true) ((h + 8f) / 9f).coerceIn(0.05f, 0.95f)
                else ((1f - h) / 9f).coerceIn(0.05f, 0.95f)
            } else null
            // While transitioning, show the target action verb being executed and disable duplicate clicks
            val showSurface = if (transitioning) (sub.surfaceIntent == true) else depth.surface
            val icon = if (showSurface) "rw-surface" else "rw-dive"
            val label = UiStrings.get(if (showSurface) "surface" else "dive", language)
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
                progress = transitionProgress,
                state = if (transitioning) HudActionKeyState.DISABLED else HudActionKeyState.READY,
                enabled = !transitioning,
                cancelEnabled = false,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(label, null, cleanDescription(depth.tooltip), emptyList(), UiStrings.get("leftUse", language)),
            )
        }

        menu.flightAction?.let { flight ->
            val jet = primary.amphibiousJet
            val transitioning = jet != null && jet.flyingIntent != null && jet.flyingIntent == jet.underwater
            val transitionProgress = if (transitioning) {
                val h = primary.height.toFloat()
                if (jet.flyingIntent == true) ((h + 8f) / 28f).coerceIn(0.05f, 0.95f)
                else ((20f - h) / 28f).coerceIn(0.05f, 0.95f)
            } else null
            val showTakeoff = if (transitioning) (jet.flyingIntent == true) else flight.flying
            val icon = if (showTakeoff) "rw-takeoff" else "rw-land"
            val label = UiStrings.get(if (showTakeoff) "takeoff" else "land", language)
            val canClick = flight.available && !transitioning
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
                progress = transitionProgress,
                state = if (canClick) HudActionKeyState.READY else HudActionKeyState.DISABLED,
                enabled = canClick,
                cancelEnabled = false,
                isTargetAction = false,
                attachmentContext = null,
                tooltip = HudActionTooltipModel(label, null, cleanDescription(flight.tooltip), emptyList(), UiStrings.get("leftUse", language)),
            )
        }

        menu.transportAction?.let { tr ->
            val rows = listOf(
                HudTooltipRow(UiStrings.get("cargo", language), "${tr.occupiedSlots}/${tr.capacity}"),
            )
            val movingToUnload = session.hasPendingUnload(primary.unitId)
            if (tr.unloading && !movingToUnload) {
                val targetAvailable = session.canTargetUnload()
                val unloadActive = activeGroundActionName == "transport-unload-target"
                val unloadLabel = UiStrings.get("unload", language)
                val unloadState = when {
                    unloadActive -> HudActionKeyState.ACTIVE_MODE
                    targetAvailable -> HudActionKeyState.READY
                    else -> HudActionKeyState.DISABLED
                }
                keys += HudActionKeyModel(
                    id = "transport-unload-target",
                    cancelId = null,
                    title = unloadLabel,
                    unitIconTypeId = null,
                    commandIconName = "rw-unload",
                    priceText = null,
                    noteText = if (!targetAvailable && tr.passengerCount == 0) UiStrings.get("empty", language) else null,
                    hotkeyLabel = null,
                    queueBadgeCount = 0,
                    progress = null,
                    state = unloadState,
                    enabled = targetAvailable,
                    cancelEnabled = false,
                    isTargetAction = true,
                    attachmentContext = null,
                    tooltip = HudActionTooltipModel(unloadLabel, null, tr.title, rows, UiStrings.get("leftTarget", language)),
                )
                val hereLabel = UiStrings.get("unloadHere", language)
                keys += HudActionKeyModel(
                    id = "transport-unload",
                    cancelId = null,
                    title = hereLabel,
                    unitIconTypeId = null,
                    commandIconName = "rw-load",
                    priceText = null,
                    noteText = if (!tr.available && tr.passengerCount == 0) UiStrings.get("empty", language) else null,
                    hotkeyLabel = null,
                    queueBadgeCount = 0,
                    progress = null,
                    state = if (tr.available) HudActionKeyState.READY else HudActionKeyState.DISABLED,
                    enabled = tr.available,
                    cancelEnabled = false,
                    isTargetAction = false,
                    attachmentContext = null,
                    tooltip = HudActionTooltipModel(hereLabel, null, tr.title, rows, UiStrings.get("leftUse", language)),
                )
            } else {
                val cancelLabel = UiStrings.get("cancelBtn", language)
                val cancelAvailable = movingToUnload || tr.available
                keys += HudActionKeyModel(
                    id = "transport-cancel-unload",
                    cancelId = null,
                    title = cancelLabel,
                    unitIconTypeId = null,
                    commandIconName = "rw-cancel",
                    priceText = null,
                    noteText = "${tr.occupiedSlots}/${tr.capacity}",
                    hotkeyLabel = null,
                    queueBadgeCount = 0,
                    progress = null,
                    state = if (cancelAvailable) HudActionKeyState.READY else HudActionKeyState.DISABLED,
                    enabled = cancelAvailable,
                    cancelEnabled = false,
                    isTargetAction = false,
                    attachmentContext = null,
                    tooltip = HudActionTooltipModel(cancelLabel, null, tr.title, rows, UiStrings.get("leftUse", language)),
                )
            }
        }

        // 6. Remaining custom unit actions (excluding setRally promoted to front & selectSlotN rendered in 3x2 slot grid)
        val slotActionNames = attachmentSlots.map { "selectSlot${it.slotNumber}" }.toSet()
        val orderedUnitActions = menu.unitActions
            .filter { it.name != "setRally" && it.name !in slotActionNames }
            .sortedBy { act ->
                when (act.name) {
                    "buildNuke", "buildAntiNuke" -> 0
                    "launchNuke" -> 1
                    else -> 2
                }
            }
        val selectedSlotTypeId = attachmentOverview?.selectedSlot?.let { num ->
            attachmentOverview.slots.firstOrNull { it.number == num }?.typeId
        }
        val stockCurrent = primary.unitActions?.stockpile
        val stockCap = session.stockpileCapacity(selectedSlotTypeId ?: primary.typeId)
        val unitActionQueue = primary.unitActions?.queue.orEmpty()
        val energyLocked = session.isEnergyLocked(primary)

        for (act in orderedUnitActions) {
            val active = activeGroundActionName == act.name
            val coolSec = ceil(act.cooldownRemaining / 1000.0).toInt()
            val parsedCost = parseActionCost(act.tooltip)
            val lack = if (parsedCost != null) (parsedCost - balance).coerceAtLeast(0L) else 0L
            val queuedCount = unitActionQueue.count { it.name == act.name }.let { c ->
                if (c > 0) c else if (act.queued) 1 else 0
            }
            val isStockBuild = act.name == "buildNuke" || act.name == "buildAntiNuke"
            val isStockFull = isStockBuild && stockCurrent != null && stockCap != null &&
                (stockCurrent + queuedCount >= stockCap)
            val isLaunchNuke = act.name == "launchNuke"
            val isToggleAction = act.name in setOf("deploy", "undeploy", "surface", "dive", "isEnabled", "isDisabled", "afterBurn", "cancelBurn")
            val isEnergyAction = act.name == "afterBurn" || act.name == "blink"
            val blockedByEnergy = energyLocked && isEnergyAction && !act.queued
            val blockedByTransition = isToggleAction && act.queued
            val shortFunds = !isStockFull && !blockedByEnergy && !blockedByTransition &&
                !act.available && !act.queued && parsedCost != null && lack > 0L

            val localizedTitle = localizeUnitActionTitle(act.name, act.title, language)
            val effectiveAvailable = act.available && !isStockFull && !blockedByEnergy && !blockedByTransition

            val launchAmmo = if (isLaunchNuke) {
                session.selectedNuclearLauncher()?.unitActions?.stockpile ?: (stockCurrent ?: 0)
            } else (stockCurrent ?: 0)
            val note = when {
                isLaunchNuke -> UiStrings.format("avail", "N" to launchAmmo, language = language)
                isStockFull -> UiStrings.get("stockFull", language)
                blockedByEnergy -> UiStrings.get("charging", language)
                shortFunds -> null
                coolSec > 0 -> UiStrings.format("cool", "N" to coolSec, language = language)
                !effectiveAvailable && act.tooltip.contains("charging", ignoreCase = true) -> UiStrings.get("charging", language)
                !effectiveAvailable && (act.tooltip.contains("full", ignoreCase = true) ||
                    act.tooltip.contains("limit reached", ignoreCase = true)) -> UiStrings.get("stockFull", language)
                else -> null
            }
            val state = when {
                active -> HudActionKeyState.ACTIVE_MODE
                isStockFull || blockedByEnergy || blockedByTransition -> HudActionKeyState.DISABLED
                shortFunds -> HudActionKeyState.SHORT_FUNDS
                effectiveAvailable -> HudActionKeyState.READY
                else -> HudActionKeyState.DISABLED
            }
            val priceText = if (state == HudActionKeyState.DISABLED && note != null) null else parsedCost?.let(::formatPrice)
            val rows = buildList {
                if (parsedCost != null) add(HudTooltipRow(UiStrings.get("cost", language), formatPrice(parsedCost)))
                if ((isStockBuild || isLaunchNuke) && stockCurrent != null && stockCap != null) {
                    add(HudTooltipRow(UiStrings.get("stock", language), "$stockCurrent / $stockCap"))
                }
                if (isStockBuild && queuedCount > 0) {
                    add(HudTooltipRow(UiStrings.get("queued", language), queuedCount.toString()))
                }
                if (shortFunds) add(HudTooltipRow(UiStrings.get("noFunds", language), UiStrings.format("short", "N" to lack, language = language), isWarning = true))
                if (blockedByEnergy) add(HudTooltipRow(UiStrings.get("energy", language), UiStrings.get("charging", language), isWarning = true))
                if (isStockFull) add(HudTooltipRow(UiStrings.get("stock", language), UiStrings.get("stockFull", language), isWarning = true))
            }
            val useCmdIcon = act.iconTypeId == null || act.name in setOf("reclaim", "cancel", "isEnabled", "isDisabled", "blink", "blinkQueued", "buildNuke", "buildAntiNuke", "launchNuke")
            keys += HudActionKeyModel(
                id = "unit-action:${act.name}",
                cancelId = if (act.cancellable) "cancel-unit-action:${act.name}" else null,
                title = localizedTitle,
                unitIconTypeId = if (useCmdIcon) null else act.iconTypeId,
                commandIconName = if (useCmdIcon) commandIconForAction(act.name) else null,
                priceText = priceText,
                noteText = note,
                hotkeyLabel = null,
                queueBadgeCount = if (isToggleAction) 0 else queuedCount,
                progress = act.progress,
                state = state,
                enabled = effectiveAvailable,
                cancelEnabled = act.cancellable,
                isTargetAction = act.groundTargeted,
                attachmentContext = act.attachmentContext,
                tooltip = HudActionTooltipModel(
                    title = localizedTitle,
                    priceText = parsedCost?.let(::formatPrice),
                    description = cleanDescription(act.tooltip),
                    rows = rows,
                    footnote = UiStrings.get(if (act.groundTargeted) "leftTarget" else if (act.cancellable) "leftQueue" else "leftUse", language),
                ),
            )
        }

        val ownerSubtitle = if (distinctTypes.size > 1 && (keys.isNotEmpty() || attachmentSlots.isNotEmpty())) {
            UiStrings.format("owner", "NAME" to primaryName, language = language)
        } else {
            null
        }

        return HudActionListSnapshot(
            showCommonBar = showCommon,
            commonCommands = commonCommands,
            ownerSubtitle = ownerSubtitle,
            attachmentSlots = attachmentSlots,
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

        if (groundActionName == "transport-unload-target") {
            val tr = session.actionMenu.transportAction
            val title = UiStrings.get("unload", language)
            val rightVal = tr?.let { "${it.occupiedSlots}/${it.capacity}" }
            val desc = if (language == UiLanguage.ZH) "移动至目标位置并卸载全部乘员" else "Move to target location and unload all passengers"
            return HudTargetModeCardModel(
                title = title,
                rightValue = rightVal,
                description = desc,
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
                val ammo = session.selectedNuclearLauncher()?.unitActions?.stockpile ?: 0
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
                description = if (groundActionName == "launchNuke") {
                    UiStrings.get("launchNukeDesc", language)
                } else {
                    cleanDescription(action?.tooltip.orEmpty()).ifBlank { title }
                },
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

    private val costPattern = Regex("""(?:Cost\s+|\$)(\d+)""", RegexOption.IGNORE_CASE)
    private fun parseActionCost(tooltip: String): Long? = costPattern.find(tooltip)?.groupValues?.getOrNull(1)?.toLongOrNull()

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
