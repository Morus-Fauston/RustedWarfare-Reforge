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
    val selectionCard: HudSelectionCardModel?,
    val queueSection: HudQueueSection?,
    val showActionsSection: Boolean,
    val menuOpen: Boolean,
    val simPaused: Boolean,
    val activeRate: OfflineRate,
    val language: UiLanguage,
)

object HudSidebarProjection {
    private const val MAX_QUEUE_SLOTS = 5

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
        val selectedUnits = obs.units.filter { it.alive && it.unitId in session.selectedIds }
        val primary = obs.units.firstOrNull { it.alive && it.unitId == session.selectedId } ?: selectedUnits.firstOrNull()

        if (primary == null) {
            return HudSnapshot(
                creditsText = formatCredits(balance),
                selectionCard = null,
                queueSection = null,
                showActionsSection = false,
                menuOpen = menuOpen,
                simPaused = simPaused,
                activeRate = activeRate,
                language = language,
            )
        }

        val isOwn = primary.ownerPlayerId == obs.playerId
        val distinctTypes = selectedUnits.map { it.typeId }.distinct()
        val countText = when {
            selectedUnits.size <= 1 -> "×1"
            distinctTypes.size <= 1 -> "×${selectedUnits.size}"
            else -> UiStrings.format("selected", "N" to selectedUnits.size, language = language)
        }

        val displayName = menu.selectedTitle?.takeIf { it.isNotBlank() } ?: primary.typeId

        // Determine subtitle: foreign owner > energy locked warning > toggle state > income
        var subtitle: String? = null
        var subWarn = false
        var subIncome = false
        if (!isOwn) {
            subtitle = formatForeignOwnerSubtitle(primary.ownerPlayerId, obs.playerId, language)
        } else {
            val energyLocked = primary.energy?.recharging == true || primary.pointDefense?.exhausted == true
            when {
                energyLocked -> {
                    subtitle = "▲ ${UiStrings.get("charging", language)}"
                    subWarn = true
                }
                primary.submarine != null -> {
                    val sub = primary.submarine
                    val submerged = sub != null && sub.underwater
                    subtitle = UiStrings.get(if (submerged) "submerged" else "surfaced", language)
                }
                primary.amphibiousJet != null -> {
                    val jet = primary.amphibiousJet
                    val flying = jet?.movementType == com.rustedwarfare.reforge.core.content.MovementType.AIR
                    val under = jet?.underwater == true
                    subtitle = UiStrings.get(if (flying) "flying" else if (under) "submerged" else "surfaced", language)
                }
                selectedUnits.size == 1 && (session.nominalIncome(primary.typeId) ?: 0f) > 0.01f -> {
                    val unitIncome = session.nominalIncome(primary.typeId) ?: 0f
                    subtitle = "+${unitIncome.roundToInt()}"
                    subIncome = true
                }
            }
        }

        // Stat bars (HP, under-construction progress, shield, energy)
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
            ownerStripeRgb = if (!isOwn) 0xE0564A else null,
            statBars = bars,
        )

        val queueSection = if (isOwn) buildQueueSection(menu, primary, obs.units) else null
        val hasActions = isOwn && (
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
            selectionCard = card,
            queueSection = queueSection,
            showActionsSection = hasActions,
            menuOpen = menuOpen,
            simPaused = simPaused,
            activeRate = activeRate,
            language = language,
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
            val slots = buildSlots(
                items = uq.items.map { it.targetTypeId },
                activeProgress = activeProgress,
            )
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
