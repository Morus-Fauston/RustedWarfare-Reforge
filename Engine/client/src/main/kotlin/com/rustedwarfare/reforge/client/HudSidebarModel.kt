package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.UnitObservation
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

enum class HudStatStyle {
    NORMAL,
    PROGRESS_RUST,
    WARNING_AMBER,
}

data class HudStatBar(
    val labelKey: String,
    val currentText: String,
    val maxText: String,
    val ratio: Float,
    val style: HudStatStyle = HudStatStyle.NORMAL,
)

data class HudTypeChipModel(
    val typeId: String,
    val count: Int,
    val isPrimary: Boolean,
    val displayName: String,
)

data class HudTypeChipsSection(
    val chips: List<HudTypeChipModel>,
    val overflowCount: Int = 0,
)

data class HudCargoChipModel(
    val typeId: String,
    val count: Int,
    val displayName: String,
)

data class HudCargoSection(
    val items: List<HudCargoChipModel>,
    val occupiedSlots: Int,
    val capacity: Int,
)

data class HudStockpileSection(
    val current: Int,
    val capacity: Int,
)

data class HudQueueSlot(
    val typeId: String?,
    val progress: Float?,
    val overflowCount: Int = 0,
)

data class HudQueueSection(
    val labelKey: String,
    val slots: List<HudQueueSlot>,
    val progressPercent: Int?,
)

data class HudSelectionCardModel(
    val unitId: String,
    val typeId: String,
    val displayName: String,
    val countText: String,
    val subtitleText: String?,
    val subtitleIsWarning: Boolean,
    val subtitleIsIncome: Boolean,
    val isEnemyOrNeutral: Boolean,
    val ownerStripeRgb: Int?,
    val statBars: List<HudStatBar>,
)

data class HudSnapshot(
    val creditsText: String,
    val typeChips: HudTypeChipsSection? = null,
    val selectionCard: HudSelectionCardModel?,
    val cargoSection: HudCargoSection? = null,
    val stockpileSection: HudStockpileSection? = null,
    val queueSection: HudQueueSection?,
    val showActionsSection: Boolean,
    val menuOpen: Boolean,
    val simPaused: Boolean,
    val activeRate: OfflineRate,
    val language: UiLanguage,
)

object HudSidebarProjection {
    private const val MAX_QUEUE_SLOTS = 5
    const val MAX_TYPE_CHIPS = 6

    /** Computes right-rail minimap height in reference units per Spec §6: clamped to [0.5, 1.0] * sidebarWidth. */
    fun minimapHeightRef(worldWidth: Double, worldHeight: Double, sidebarWidth: Float = UiTokens.SIDEBAR_WIDTH): Float {
        if (worldWidth <= 0.0 || worldHeight <= 0.0) return sidebarWidth * 0.65f
        val aspect = (worldHeight / worldWidth).toFloat()
        return (sidebarWidth * aspect).coerceIn(sidebarWidth * 0.5f, sidebarWidth)
    }

    /** Formats credits with thousands separator (e.g. "$1,450"). */
    fun formatCredits(amount: Long): String {
        val nf = NumberFormat.getIntegerInstance(Locale.US)
        return "$" + nf.format(amount)
    }

    /** Formats a player display title for enemy/neutral units (e.g. "玩家 2 · 敌方" / "Player 2 · Enemy"). */
    fun formatForeignOwnerSubtitle(ownerPlayerId: String, localPlayerId: String, language: UiLanguage): String {
        val isNeutral = ownerPlayerId.equals("neutral", ignoreCase = true) || ownerPlayerId.equals("none", ignoreCase = true)
        if (isNeutral) return UiStrings.get("neutral", language)
        val num = ownerPlayerId.removePrefix("p").removePrefix("P").toIntOrNull()
        val playerLabel = if (num != null) UiStrings.format("playerSlot", "N" to num, language = language) else ownerPlayerId
        return "$playerLabel · ${UiStrings.get("foe", language)}"
    }

    /** Builds a pure immutable HUD snapshot from the current authorized [SandboxSession] state. */
    fun project(
        session: SandboxSession,
        menuOpen: Boolean,
        simPaused: Boolean,
        activeRate: OfflineRate,
        language: UiLanguage = UiStrings.currentLanguage,
    ): HudSnapshot {
        val obs = session.observation
        val menu = session.actionMenu
        val balance = menu.balance ?: menu.credits?.toLong() ?: obs.balance ?: obs.credits?.toLong() ?: 0L
        val selectedIds = session.selectedIds
        val primaryId = session.selectedId
        val selectedUnits = obs.units.filter { it.alive && it.unitId in selectedIds }
        val primary = obs.units.firstOrNull { it.alive && it.unitId == primaryId } ?: selectedUnits.firstOrNull()

        if (primary == null) {
            return HudSnapshot(
                creditsText = formatCredits(balance),
                typeChips = null,
                selectionCard = null,
                cargoSection = null,
                queueSection = null,
                showActionsSection = false,
                menuOpen = menuOpen,
                simPaused = simPaused,
                activeRate = activeRate,
                language = language,
            )
        }

        val isOwn = primary.ownerPlayerId == obs.playerId
        val typeChips = buildTypeChips(selectedUnits, primary.typeId) { typeId -> session.unitTitle(typeId) }
        val distinctTypes = selectedUnits.map { it.typeId }.distinct()
        val countText = when {
            selectedUnits.size <= 1 -> "×1"
            distinctTypes.size > 1 -> UiStrings.format("selected", "N" to selectedUnits.size, language = language)
            else -> "×${selectedUnits.size}"
        }
        val displayName = menu.selectedTitle?.takeIf { it.isNotBlank() } ?: primary.typeId

        // Subtitle priority: foreign owner -> energy recharging warning -> unit state -> nominal income
        var subtitle: String? = null
        var subWarn = false
        var subIncome = false
        if (!isOwn) {
            subtitle = formatForeignOwnerSubtitle(primary.ownerPlayerId, obs.playerId, language)
        } else if (primary.energy?.recharging == true || primary.pointDefense?.exhausted == true) {
            subtitle = "▲ ${UiStrings.get("charging", language)}"
            subWarn = true
        } else {
            val stateLabel = resolveUnitStateSubtitle(primary, session, language)
            if (stateLabel != null) {
                subtitle = stateLabel
            } else {
                val ownIncome = session.nominalIncome(primary.typeId) ?: 0f
                if (ownIncome > 0.01f) {
                    subtitle = "+$${ownIncome.roundToInt()}"
                    subIncome = true
                }
            }
        }

        val bars = buildList {
            val hp = primary.health
            val maxHp = primary.maxHealth
            if (hp != null && maxHp != null && maxHp > 0.0) {
                add(HudStatBar(
                    labelKey = "hp",
                    currentText = hp.roundToInt().toString(),
                    maxText = "/${maxHp.roundToInt()}",
                    ratio = (hp / maxHp).toFloat().coerceIn(0f, 1f),
                    style = HudStatStyle.NORMAL,
                ))
            }
            if (primary.constructionProgress < 1f) {
                val pct = (primary.constructionProgress * 100f).roundToInt().coerceIn(0, 100)
                add(HudStatBar(
                    labelKey = "build",
                    currentText = "$pct%",
                    maxText = "",
                    ratio = primary.constructionProgress.coerceIn(0f, 1f),
                    style = HudStatStyle.PROGRESS_RUST,
                ))
            }
            primary.shield?.let { sh ->
                if (sh.maximum > 0.0) {
                    add(HudStatBar(
                        labelKey = "shield",
                        currentText = sh.current.roundToInt().toString(),
                        maxText = "/${sh.maximum.roundToInt()}",
                        ratio = (sh.current / sh.maximum).coerceIn(0f, 1f),
                        style = HudStatStyle.NORMAL,
                    ))
                }
            }
            primary.energy?.let { en ->
                if (en.maximum > 0.0) {
                    add(HudStatBar(
                        labelKey = "energy",
                        currentText = en.current.roundToInt().toString(),
                        maxText = "/${en.maximum.roundToInt()}",
                        ratio = (en.current / en.maximum).coerceIn(0f, 1f),
                        style = if (en.recharging) HudStatStyle.WARNING_AMBER else HudStatStyle.NORMAL,
                    ))
                }
            } ?: primary.pointDefense?.let { pd ->
                val maxE = 1.0f
                val pct = (pd.energy.coerceIn(0f, maxE) * 100f).roundToInt()
                add(HudStatBar(
                    labelKey = "energy",
                    currentText = "$pct",
                    maxText = "/100",
                    ratio = (pd.energy / maxE).coerceIn(0f, 1f),
                    style = if (pd.exhausted) HudStatStyle.WARNING_AMBER else HudStatStyle.NORMAL,
                ))
            }
        }

        val card = HudSelectionCardModel(
            unitId = primary.unitId,
            typeId = primary.typeId,
            displayName = displayName,
            countText = countText,
            subtitleText = subtitle,
            subtitleIsWarning = subWarn,
            subtitleIsIncome = subIncome,
            isEnemyOrNeutral = !isOwn,
            ownerStripeRgb = if (!isOwn) session.renderResources.playerColorRgb(primary.ownerPlayerId) else null,
            statBars = bars,
        )

        val cargoSection = if (isOwn) buildCargoSection(primary) { typeId -> session.unitTitle(typeId) } else null
        val stockpileSection = if (isOwn && primary.constructionProgress >= 1f) {
            buildStockpileSection(primary, session)
        } else null
        val queueSection = if (isOwn && primary.constructionProgress >= 1f) {
            buildQueueSection(menu, primary, obs.units)
        } else null
        val hasActions = isOwn && (
            session.canPatrol ||
                session.canCancelConstruction() ||
                primary.attachmentOverview != null ||
                menu.battleActions().isNotEmpty() ||
                menu.production.isNotEmpty() ||
                menu.construction.isNotEmpty() ||
                menu.upgrades.isNotEmpty() ||
                menu.unitActions.isNotEmpty() ||
                menu.canMaintain ||
                menu.canRepair ||
                menu.depthAction != null ||
                menu.flightAction != null ||
                menu.transportAction != null
            )

        return HudSnapshot(
            creditsText = formatCredits(balance),
            typeChips = typeChips,
            selectionCard = card,
            cargoSection = cargoSection,
            stockpileSection = stockpileSection,
            queueSection = queueSection,
            showActionsSection = hasActions,
            menuOpen = menuOpen,
            simPaused = simPaused,
            activeRate = activeRate,
            language = language,
        )
    }

    /**
     * Builds the multi-type selection chip row (HUD Spec §5.2):
     * - Shown only when 2+ distinct unit types are selected
     * - Max 6 type chips; excess types summarized in [HudTypeChipsSection.overflowCount] (+k)
     * - Primary unit's type is guaranteed to be included and highlighted
     */
    internal fun resolveUnitStateSubtitle(
        primary: UnitObservation,
        session: SandboxSession,
        language: UiLanguage,
    ): String? {
        primary.submarine?.let { sub ->
            return UiStrings.get(if (sub.underwater) "submerged" else "surfaced", language)
        }
        primary.amphibiousJet?.let { jet ->
            return UiStrings.get(if (jet.underwater) "submerged" else "flying", language)
        }
        session.bunkerDeployedState(primary.typeId)?.let { deployed ->
            return UiStrings.get(if (deployed) "deployed" else "undeployed", language)
        }
        session.nautilusSurfaceState(primary.typeId)?.let { onSurface ->
            return UiStrings.get(if (onSurface) "surfaced" else "submerged", language)
        }
        if (session.isAfterburnBoosted(primary.typeId)) {
            return UiStrings.get("afterburning", language)
        }
        primary.unitActions?.interceptorOnline?.let { online ->
            return UiStrings.get(if (online) "enabled" else "disabled", language)
        }
        return null
    }

    internal fun buildStockpileSection(
        primary: UnitObservation,
        session: SandboxSession,
    ): HudStockpileSection? {
        val current = primary.unitActions?.stockpile ?: return null
        val selectedSlotId = primary.attachmentOverview?.selectedSlot?.let { num ->
            primary.attachmentOverview?.slots?.firstOrNull { it.number == num }?.typeId
        }
        val cap = session.stockpileCapacity(selectedSlotId ?: primary.typeId) ?: return null
        if (cap <= 0) return null
        return HudStockpileSection(current = current.coerceIn(0, cap), capacity = cap)
    }

    internal fun buildTypeChips(
        selectedUnits: List<UnitObservation>,
        primaryTypeId: String,
        titleOf: (String) -> String,
    ): HudTypeChipsSection? {
        if (selectedUnits.size < 2) return null
        val counts = LinkedHashMap<String, Int>()
        for (u in selectedUnits) {
            counts[u.typeId] = (counts[u.typeId] ?: 0) + 1
        }
        if (counts.size < 2) return null

        val allEntries = counts.entries.toList()
        val chosen = if (allEntries.size <= MAX_TYPE_CHIPS) {
            allEntries
        } else {
            val head = allEntries.take(MAX_TYPE_CHIPS).toMutableList()
            if (head.none { it.key == primaryTypeId }) {
                val primaryEntry = allEntries.firstOrNull { it.key == primaryTypeId }
                if (primaryEntry != null) {
                    head[MAX_TYPE_CHIPS - 1] = primaryEntry
                }
            }
            head
        }
        val overflow = (allEntries.size - chosen.size).coerceAtLeast(0)
        return HudTypeChipsSection(
            chips = chosen.map { (typeId, count) ->
                HudTypeChipModel(
                    typeId = typeId,
                    count = count,
                    isPrimary = typeId == primaryTypeId,
                    displayName = titleOf(typeId),
                )
            },
            overflowCount = overflow,
        )
    }

    /**
     * Builds the transport cargo manifest row (HUD Spec §7.2):
     * - Shown for own units with [UnitObservation.transport] != null
     * - Merges passengers by [TransportObservation.passengerTypeIds] preserving order
     */
    internal fun buildCargoSection(
        primary: UnitObservation,
        titleOf: (String) -> String,
    ): HudCargoSection? {
        val tr = primary.transport ?: return null
        val counts = LinkedHashMap<String, Int>()
        for (typeId in tr.passengerTypeIds) {
            counts[typeId] = (counts[typeId] ?: 0) + 1
        }
        val items = counts.entries.map { (typeId, count) ->
            HudCargoChipModel(typeId = typeId, count = count, displayName = titleOf(typeId))
        }
        return HudCargoSection(
            items = items,
            occupiedSlots = tr.occupiedSlots,
            capacity = tr.capacity,
        )
    }

    internal fun buildQueueSection(
        menu: ActionMenuView,
        primary: UnitObservation? = null,
        visibleUnits: List<UnitObservation> = emptyList(),
    ): HudQueueSection? {
        val fq = menu.factoryQueue
        if (fq != null && fq.items.isNotEmpty()) {
            val activeProgress = fq.items.first().progress.coerceIn(0f, 1f)
            val slots = buildSlots(
                items = fq.items.map { it.typeId },
                activeProgress = activeProgress,
            )
            return HudQueueSection(
                labelKey = "queue",
                slots = slots,
                progressPercent = (activeProgress * 100f).roundToInt().coerceIn(0, 100),
            )
        }
        val uq = menu.upgradeQueue
        if (uq != null && uq.items.isNotEmpty()) {
            val activeProgress = uq.items.first().progress.coerceIn(0f, 1f)
            val slots = uq.items.take(MAX_QUEUE_SLOTS).mapIndexed { idx, item ->
                HudQueueSlot(typeId = item.targetTypeId, progress = if (idx == 0) activeProgress else null)
            }
            return HudQueueSection(
                labelKey = "upgradeQ",
                slots = slots,
                progressPercent = (activeProgress * 100f).roundToInt().coerceIn(0, 100),
            )
        }
        // Builder construction / build task queue (HUD Spec §5.3: label "施工" / "Build")
        if (primary != null) {
            val buildTasks = primary.tasks.filter {
                it.kind == com.rustedwarfare.reforge.core.sim.UnitTaskKind.BUILD ||
                    it.kind == com.rustedwarfare.reforge.core.sim.UnitTaskKind.CONSTRUCT
            }
            if (buildTasks.isNotEmpty()) {
                val typeIds = buildTasks.mapNotNull { task ->
                    task.buildingTypeId ?: task.targetUnitId?.let { tid ->
                        visibleUnits.firstOrNull { it.unitId == tid }?.typeId
                    }
                }
                if (typeIds.isNotEmpty()) {
                    val headTargetId = buildTasks.first().targetUnitId ?: primary.constructionTargetId
                    val headProgress = headTargetId?.let { tid ->
                        visibleUnits.firstOrNull { it.unitId == tid }?.constructionProgress?.coerceIn(0f, 1f)
                    } ?: 0f
                    return HudQueueSection(
                        labelKey = "build",
                        slots = buildSlots(typeIds, headProgress),
                        progressPercent = (headProgress * 100f).roundToInt().coerceIn(0, 100),
                    )
                }
            }
        }
        return null
    }

    internal fun buildSlots(items: List<String>, activeProgress: Float): List<HudQueueSlot> {
        val total = items.size
        return List(MAX_QUEUE_SLOTS) { idx ->
            when {
                idx < total && (idx < MAX_QUEUE_SLOTS - 1 || total <= MAX_QUEUE_SLOTS) ->
                    HudQueueSlot(typeId = items[idx], progress = if (idx == 0) activeProgress else null)
                idx == MAX_QUEUE_SLOTS - 1 && total > MAX_QUEUE_SLOTS ->
                    HudQueueSlot(typeId = null, progress = null, overflowCount = total - (MAX_QUEUE_SLOTS - 1))
                else ->
                    HudQueueSlot(typeId = null, progress = null)
            }
        }
    }
}
