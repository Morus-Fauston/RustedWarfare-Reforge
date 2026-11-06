package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.UpgradeActionProfile
import com.rustedwarfare.reforge.core.sim.*
import java.util.Collections
import java.util.Locale

/** One fixed catalogue per match. Short panel labels and full feedback labels keep their existing roles. */
internal class ActionCatalogue(private val definitions: Map<String, UnitDefinition>) {
    private data class Names(val panel: String, val full: String = panel)
    private val names = mapOf(
        "commandCenter" to Names("Command", "Command center"), "builder" to Names("Builder"),
        "c_tank" to Names("Tank"), "landFactory" to Names("Factory", "Land factory"),
        "mechFactory" to Names("Mech factory"), "mechGun" to Names("Gun mech"),
        "mechFactoryT2" to Names("Mech factory T2"), "mechHeavyMissile" to Names("Heavy missile mech"),
        "airFactory" to Names("Air factory"), "helicopter" to Names("Helicopter"),
        "seaFactory" to Names("Sea factory"), "seaFactoryT2" to Names("Sea factory T2"),
        "heavyBattleship" to Names("Heavy battleship"),
        "extractorT1" to Names("Extractor T1", "Extractor"), "extractorT2" to Names("Extractor T2", "Unit"),
        "extractorT3" to Names("Extractor T3", "Unit"), "fabricatorT1" to Names("Fabricator T1", "Fabricator"),
        "fabricatorT2" to Names("Fabricator T2", "Unit"), "fabricatorT3" to Names("Fabricator T3", "Unit"),
        "c_turret_t1" to Names("Turret"), "c_turret_t2_gun" to Names("Gun T2", "Unit"),
        "c_turret_t1_artillery" to Names("Artillery", "Unit"), "laserDefence" to Names("Laser Shield"))
    fun title(type: String, panel: Boolean = false): String = names[type]?.let { if (panel) it.panel else it.full }
        ?: type
    fun nominalIncome(type: String): Float? = definitions[type]?.income?.let { it.credits * 40f / it.period }
    private val nominalSeconds = mapOf("extractorT1" to "27.7", "extractorT2" to "55.5",
        "fabricatorT1" to "65.6", "fabricatorT2" to "93.3")
    private val construction = definitions.mapValues { (_, d) -> d.construction?.let { build ->
        ConstructionActionView(d.id, title(d.id), d.imagePath, build.price, checkNotNull(d.maximumHealth), build.rate,
            when (d.id) {
                "c_turret_t1" -> "X ${title(d.id, panel = true)}"
                "laserDefence" -> "J ${title(d.id, panel = true)}"
                else -> title(d.id, panel = true)
            }, "${title(d.id)} (\$${build.price})\n" +
                String.format(Locale.ROOT, "%.1fs\nHP %d\n", 1.0 / build.rate / 60.0, checkNotNull(d.maximumHealth).toInt()) +
                (nominalIncome(d.id)?.let { "Income +\$${it.toInt()}\n" } ?: "") + "Left: choose site\nEsc / right: cancel")
    } }
    private val production = definitions.mapValues { (_, definition) -> definition.build?.let { build ->
        ProductionActionView(definition.id, title(definition.id), definition.imagePath, definition.totalFrames,
            build.price, build.rate, definition.maximumHealth, definition.movement.moveSpeed, 0, null, false)
    } }

    private fun production(view: PlayerObservation, queue: FactoryQueueObservation?): List<ProductionActionView> {
        val factory = view.units.singleOrNull { it.unitId == queue?.factoryId } ?: return emptyList()
        if (factory.constructionProgress < 1f && definitions.getValue(factory.typeId).production?.exitProfile in setOf(
            com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_MECH,
            com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_AIR)) return emptyList()
        return immutable(definitions.getValue(factory.typeId).production?.unitTypeIds.orEmpty().map { type ->
            val action = checkNotNull(production[type]); val items = checkNotNull(queue).items
            val affordable = affordable(view, action.price)
            action.copy(count = items.count { it.typeId == type },
                progress = items.firstOrNull()?.takeIf { it.typeId == type }?.progress,
                affordable = affordable, tooltip = "${action.title} (\$${action.price})\n" +
                    String.format(Locale.ROOT, "%.1fs\nHP %d\nSpeed %.2f\n", 1.0 / action.rate / 60.0,
                        action.maximumHealth?.toInt() ?: 0, action.moveSpeed) +
                    (if (affordable) "Left: produce" else "Insufficient funds") + "\nRight: cancel one")
        })
    }

    private fun affordable(view: PlayerObservation, price: Long): Boolean = view.credits?.let { it >= price.toDouble() }
        ?: (view.balance?.let { it >= price } == true)

    fun menu(view: PlayerObservation, ids: Set<String>, primary: String?, localControl: Boolean,
             maintenance: Boolean): ActionMenuView {
        val selected = view.units.singleOrNull { it.unitId == primary }
        fun controllable(unit: UnitObservation) = localControl && unit.alive && unit.ownerPlayerId == view.playerId
        val ownSingle = selected?.takeIf { ids.size == 1 && controllable(it) }
        val factory = ownSingle?.let { unit -> view.factoryQueues.firstOrNull { it.factoryId == unit.unitId } }
        val profiles = ownSingle?.let { definitions.getValue(it.typeId).upgrades?.actions }.orEmpty()
        val upgradeQueue = if (profiles.isEmpty()) null else view.upgradeQueues.firstOrNull { it.unitId == primary }
        val upgrades = immutable(profiles.map { profile ->
            val item = upgradeQueue?.items?.firstOrNull { it.actionKey == profile.actionKey }
            val seconds = nominalSeconds[selected?.typeId]
            val kind = if (seconds != null) UpgradePresentationKind.ECONOMIC else UpgradePresentationKind.DEFENSE
            val title = if (kind == UpgradePresentationKind.ECONOMIC) "Upgrade - T${profile.targetTypeId.last()}"
                else title(profile.targetTypeId, panel = true)
            val affordable = affordable(view, profile.price)
            val cardText = if (kind == UpgradePresentationKind.ECONOMIC)
                "U $title${if (item != null) " (1)" else ""}\n" +
                    (item?.let { "${(it.progress * 100f).toInt()}%" } ?: "\$${profile.price}")
                else title + (item?.let { "\n(1) ${(it.progress * 100f).toInt()}%" } ?: "\n\$${profile.price}")
            val tooltip = "$title (\$${profile.price})\n" + if (kind == UpgradePresentationKind.ECONOMIC)
                "${seconds}s\nIncome +\$${nominalIncome(checkNotNull(selected).typeId)?.toInt() ?: 0} -> +\$${nominalIncome(profile.targetTypeId)?.toInt() ?: 0}\n" +
                    (if (affordable) "Left: upgrade" else "Insufficient funds") + "\nRight: cancel one"
                else (item?.let { "Queue 1\n${(it.progress * 100f).toInt()}%\n" } ?: "") + "Left: upgrade\nCancel: cancel one"
            UpgradeActionView(profile, kind, title, cardText, tooltip, item?.progress, affordable)
        })
        val workers = view.units.filter { it.unitId in ids }
        val validWorkers = workers.isNotEmpty() && workers.size == ids.size && workers.all { controllable(it) }
        val buildings = if (!validWorkers || workers.any { it.typeId != workers.first().typeId }) emptyList()
            else immutable(definitions.getValue(workers.first().typeId).builder?.buildingTypeIds.orEmpty()
                .map { checkNotNull(construction[it]) })
        val workerText = if (buildings.isEmpty()) null else workerText(workers.sortedBy { it.unitId }, view)
        val info = if (ids.isEmpty()) null else if (ids.size > 1) workerText else selected?.let { unit ->
            "${unit.health?.toInt() ?: 0} / ${unit.maxHealth?.toInt() ?: 0}" +
                (nominalIncome(unit.typeId)?.let { "\n+\$${it.toInt()}" } ?: "") +
                (if (unit.constructionProgress < 1f) "\nBuilding ${(unit.constructionProgress * 100f).toInt()}%"
                    else if (unit.typeId.startsWith("extractorT") || unit.typeId.startsWith("fabricatorT")) "\nActive" else "") +
                (unit.pointDefense?.let { String.format(Locale.ROOT, "\nEnergy %.3f%s", it.energy,
                    if (it.exhausted) " LOCKED" else "") } ?: "") + (workerText?.let { "\n$it" } ?: "")
        }
        return ActionMenuView(view.credits, production(view, factory), buildings, upgrades, factory, upgradeQueue,
            maintenance && validWorkers && workers.all { it.typeId == "builder" && definitions.getValue(it.typeId).builder != null },
            workerText, info, selected?.let { title(it.typeId, panel = true) },
            view.units.filter { it.alive && it.ownerPlayerId == view.playerId && it.constructionProgress >= 1f }
                .sumOf { nominalIncome(it.typeId)?.toDouble() ?: 0.0 }.toFloat(),
            upgradeQueue?.items?.joinToString("") { item ->
                "  ${if (item.actionKey == "upgrade_gun") "Gun" else "Art"} ${(item.progress * 100f).toInt()}%"
            }.orEmpty(), view.balance)
    }

    private fun workerText(workers: List<UnitObservation>, view: PlayerObservation): String {
        if (workers.size == 1) {
            val tasks = workers.single().tasks; val head = tasks.firstOrNull()
            val type = head?.buildingTypeId ?: head?.targetUnitId?.let { id -> view.units.firstOrNull { it.unitId == id }?.typeId }
            return "Tasks ${tasks.size}\n" + (head?.kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Idle") +
                (type?.let { " ${title(it)}" } ?: "")
        }
        val low = workers.minOf { it.tasks.size }; val high = workers.maxOf { it.tasks.size }
        val range = if (low == high) "$low" else "$low-$high"
        val kinds = workers.groupingBy { it.tasks.firstOrNull()?.kind }.eachCount().entries
            .sortedBy { it.key?.ordinal ?: -1 }.joinToString("\n") { (kind, count) ->
                "${kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Idle"} x$count"
            }
        return "Tasks $range each\n$kinds"
    }
}

private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

/** Published values never retain a mutable work list from a later refresh. */
enum class UpgradePresentationKind { ECONOMIC, DEFENSE }
data class UpgradeActionView(val profile: UpgradeActionProfile, val kind: UpgradePresentationKind,
    val title: String, val cardText: String, val tooltip: String, val progress: Float?, val affordable: Boolean) {
    val actionKey: String get() = profile.actionKey
    val targetTypeId: String get() = profile.targetTypeId
    val price: Long get() = profile.price
}
data class ActionMenuView(val credits: Double? = null, val production: List<ProductionActionView> = emptyList(),
    val construction: List<ConstructionActionView> = emptyList(), val upgrades: List<UpgradeActionView> = emptyList(),
    val factoryQueue: FactoryQueueObservation? = null, val upgradeQueue: UpgradeQueueObservation? = null,
    val canMaintain: Boolean = false, val workerQueueText: String? = null, val selectedUnitInfo: String? = null,
    val selectedTitle: String? = null, val totalNominalIncome: Float = 0f, val upgradeQueueText: String = "",
    val balance: Long? = null) {
    val reclaimTooltip: String get() = if (canMaintain)
        "Reclaim own building\nCredits returned on completion\nLeft: choose target\nEsc / right: cancel" else ""
}
