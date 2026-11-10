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
        "c_tank" to Names("Tank"), "landFactory" to Names("Factory", "Land factory"), "repairbay" to Names("Repair bay"),
        "hoverTank" to Names("Hover tank"), "hovercraft" to Names("Landing craft"), "dropship" to Names("Transport", "Air transport"),
        "mechFactory" to Names("Mech factory"), "mechGun" to Names("Gun mech"), "mechEngineer" to Names("Engineer mech"),
        "mechFactoryT2" to Names("Mech factory T2"), "mechHeavyMissile" to Names("Heavy missile mech"),
        "mechBunker" to Names("Mobile turret"), "mechBunkerDeployed" to Names("Deployed turret"),
        "airFactory" to Names("Air factory"), "airFactoryT2" to Names("Air factory T2"),
        "helicopter" to Names("Helicopter"), "amphibiousJet" to Names("Amphibious jet"), "aaBeamGunship" to Names("AA beam gunship"),
        "aaBeamGunship_afterburn" to Names("AA beam (Afterburn)", "AA beam gunship (Afterburn)"),
        "seaFactory" to Names("Sea factory"), "seaFactoryT2" to Names("Sea factory T2"),
        "heavyBattleship" to Names("Heavy battleship"), "attackSubmarine" to Names("Submarine"),
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
        val engineer = definition.maintenance == com.rustedwarfare.reforge.core.content.MaintenanceProfile.MECH_ENGINEER
        ProductionActionView(definition.id, title(definition.id),
            if (engineer) checkNotNull(definition.combat?.turretImagePath) else definition.imagePath,
            if (engineer) 1 else definition.totalFrames,
            build.price, build.rate, definition.maximumHealth, definition.movement.moveSpeed, 0, null, false)
    } }

    private fun production(view: PlayerObservation, queue: FactoryQueueObservation?): List<ProductionActionView> {
        val factory = view.units.singleOrNull { it.unitId == queue?.factoryId } ?: return emptyList()
        val definition = definitions.getValue(factory.typeId)
        if (factory.constructionProgress < 1f && (definition.className == "AuthoredBuilding" || definition.production?.exitProfile in setOf(
            com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_MECH,
            com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_AIR))) return emptyList()
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
        val depthAction = ownSingle?.takeIf { it.constructionProgress >= 1f }?.submarine?.surfaceIntent?.let { intent ->
            val surface = !intent
            SubmarineDepthActionView(surface, if (surface) "Surface" else "Dive",
                if (surface) "Rise to the surface\nMovement orders continue" else "Dive underwater\nMovement orders continue")
        }
        val flightAction = ownSingle?.takeIf { it.constructionProgress >= 1f }?.amphibiousJet?.let { state ->
            state.flyingIntent?.let { intent ->
                val flying = !intent
                val available = flying || state.canDive == true
                AmphibiousFlightActionView(flying, if (flying) "Fly" else "Dive",
                    if (flying) "Return to flight\nMovement orders continue"
                    else if (available) "Dive underwater\nMovement orders continue"
                    else "Dive requires water below the aircraft", available)
            }
        }
        val factory = ownSingle?.let { unit -> view.factoryQueues.firstOrNull { it.factoryId == unit.unitId } }
        val transportAction = ownSingle?.takeIf { it.constructionProgress >= 1f }?.transport?.let {
            TransportUnloadActionView(!it.unloading, if (it.unloading) "Cancel unload" else "Unload", it.unloading || it.canUnload)
        }
        val unitActions = immutable(ownSingle?.unitActions?.actions.orEmpty().map { action ->
            val title = when (action.name) { "afterBurn" -> "Afterburn"; "cancelBurn" -> "Cancel burn"
                "deploy" -> "Deploy"; "undeploy" -> "Undeploy"; else -> action.name }
            val status = if (action.queueIndex != null) "Queued ${(action.progress!! * 100f).toInt()}%" else if (action.cooldownRemaining > 0)
                String.format(Locale.ROOT, "Cooling %.1fs", action.cooldownRemaining / 1000f) else "Ready"
            val motion = when (action.name) { "deploy" -> "Movement locked while deploying\nCannot cancel"
                "undeploy" -> "Fixed until undeployed\nCannot cancel"; else -> "Movement orders continue" }
            CustomUnitActionView(action.name, title, "$title\n$status\n$motion",
                action.queueIndex != null, action.cooldownRemaining, action.available, action.progress)
        })
        val profiles = ownSingle?.takeUnless { it.constructionProgress < 1f &&
            definitions.getValue(it.typeId).className == "AuthoredBuilding" }
            ?.let { definitions.getValue(it.typeId).upgrades?.actions }.orEmpty()
        val upgradeQueue = if (profiles.isEmpty()) null else view.upgradeQueues.firstOrNull { it.unitId == primary }
        val upgrades = immutable(profiles.map { profile ->
            val item = upgradeQueue?.items?.firstOrNull { it.actionKey == profile.actionKey }
            val seconds = nominalSeconds[selected?.typeId]
            val authored = selected?.let { definitions.getValue(it.typeId).production != null } == true
            val kind = if (authored) UpgradePresentationKind.FACTORY
                else if (seconds != null) UpgradePresentationKind.ECONOMIC else UpgradePresentationKind.DEFENSE
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
                else (if (kind == UpgradePresentationKind.FACTORY)
                    String.format(Locale.ROOT, "%.1fs\n", 1.0 / profile.rate / 60.0) else "") +
                    (item?.let { "Queue 1\n${(it.progress * 100f).toInt()}%\n" } ?: "") +
                    "Left: upgrade\n" + (if (kind == UpgradePresentationKind.FACTORY) "Right: cancel one" else "Cancel: cancel one")
            UpgradeActionView(profile, kind, title, cardText, tooltip, item?.progress, affordable)
        })
        val workers = view.units.filter { it.unitId in ids }
        val validWorkers = workers.isNotEmpty() && workers.size == ids.size && workers.all { controllable(it) }
        val buildings = if (!validWorkers || workers.any { it.typeId != workers.first().typeId }) emptyList()
            else immutable(definitions.getValue(workers.first().typeId).builder?.buildingTypeIds.orEmpty()
                .map { checkNotNull(construction[it]) })
        val nativeMaintenance = validWorkers && workers.all { it.constructionProgress >= 1f && it.carriedBy == null &&
            definitions.getValue(it.typeId).maintenance != null }
        val workerText = if (buildings.isEmpty() && !nativeMaintenance) null else workerText(workers.sortedBy { it.unitId }, view)
        val info = if (ids.isEmpty()) null else if (ids.size > 1) workerText else selected?.let { unit ->
            "${unit.health?.toInt() ?: 0} / ${unit.maxHealth?.toInt() ?: 0}" +
                (unit.transport?.let { "\nCargo ${it.occupiedSlots} / ${it.capacity}\nPassengers ${it.passengerCount}" } ?: "") +
                (if (unit.transport != null && unit.flightPose != null) String.format(Locale.ROOT, "\nHeight %.2f", unit.height) else "") +
                (unit.shield?.let { "\nShield ${it.current.toInt()} / ${it.maximum.toInt()}" } ?: "") +
                (unit.energy?.let { String.format(Locale.ROOT, "\nEnergy %.3f / %.3f%s", it.current, it.maximum,
                    if (it.recharging) " LOCKED" else "") } ?: "") +
                (if (unit.typeId == "aaBeamGunship_afterburn") "\nAfterburn / weapons disabled" else "") +
                (unit.submarine?.let { String.format(Locale.ROOT, "\n%s\nDepth %.2f\nSpeed %.2f\nRange %.0f",
                    if (it.underwater) "Underwater" else "Surface", unit.height, it.maximumSpeed, it.attackRange) } ?: "") +
                (unit.amphibiousJet?.let { String.format(Locale.ROOT, "\n%s / %s\nHeight %.2f\nSpeed %.2f\nRange %.0f",
                    if (it.underwater) "Underwater" else "Above water", it.movementType.name,
                    unit.height, it.maximumSpeed, it.attackRange) } ?: "") +
                (nominalIncome(unit.typeId)?.let { "\n+\$${it.toInt()}" } ?: "") +
                (if (unit.constructionProgress < 1f) "\nBuilding ${(unit.constructionProgress * 100f).toInt()}%"
                    else if (unit.typeId.startsWith("extractorT") || unit.typeId.startsWith("fabricatorT")) "\nActive" else "") +
                (unit.pointDefense?.let { String.format(Locale.ROOT, "\nEnergy %.3f%s", it.energy,
                    if (it.exhausted) " LOCKED" else "") } ?: "") + (workerText?.let { "\n$it" } ?: "")
        }
        return ActionMenuView(view.credits, production(view, factory), buildings, upgrades, factory, upgradeQueue,
            validWorkers && workers.all { definitions.getValue(it.typeId).maintenance != null ||
                maintenance && it.typeId == "builder" && definitions.getValue(it.typeId).builder != null },
            workerText, info, selected?.let { title(it.typeId, panel = true) },
            view.units.filter { it.alive && it.ownerPlayerId == view.playerId && it.constructionProgress >= 1f }
                .sumOf { nominalIncome(it.typeId)?.toDouble() ?: 0.0 }.toFloat(),
            upgradeQueue?.items?.joinToString("") { item ->
                val name = if (upgrades.any { it.kind == UpgradePresentationKind.FACTORY && it.actionKey == item.actionKey })
                    title(item.targetTypeId, panel = true) else if (item.actionKey == "upgrade_gun") "Gun" else "Art"
                "  $name ${(item.progress * 100f).toInt()}%"
            }.orEmpty(), view.balance, depthAction, flightAction, transportAction, unitActions, nativeMaintenance,
            if (nativeMaintenance) workers.minOf { definitions.getValue(it.typeId).builder?.range?.toDouble() ?: 230.0 } else null)
    }

    private fun workerText(workers: List<UnitObservation>, view: PlayerObservation): String {
        if (workers.size == 1) {
            val tasks = workers.single().tasks; val head = tasks.firstOrNull()
            val type = head?.buildingTypeId ?: head?.targetUnitId?.let { id -> view.units.firstOrNull { it.unitId == id }?.typeId }
            return "Tasks ${tasks.size}\n" + taskLabel(head, view) +
                (type?.let { " ${title(it)}" } ?: "")
        }
        val low = workers.minOf { it.tasks.size }; val high = workers.maxOf { it.tasks.size }
        val range = if (low == high) "$low" else "$low-$high"
        val kinds = workers.groupingBy { it.tasks.firstOrNull().let { task -> task?.kind to taskLabel(task, view) } }
            .eachCount().entries.sortedBy { it.key.first?.ordinal ?: -1 }.joinToString("\n") { (kind, count) ->
                "${kind.second} x$count"
            }
        return "Tasks $range each\n$kinds"
    }
}

/** The shared construction task also repairs completed units; show the player's current operation. */
internal fun taskLabel(task: UnitTask?, view: PlayerObservation): String =
    if (task?.kind == UnitTaskKind.CONSTRUCT && view.units.any {
        it.unitId == task.targetUnitId && it.constructionProgress >= 1f
    }) "Repair" else task?.kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Idle"

private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

/** Published values never retain a mutable work list from a later refresh. */
enum class UpgradePresentationKind { ECONOMIC, DEFENSE, FACTORY }
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
    val balance: Long? = null, val depthAction: SubmarineDepthActionView? = null,
    val flightAction: AmphibiousFlightActionView? = null, val transportAction: TransportUnloadActionView? = null,
    val unitActions: List<CustomUnitActionView> = emptyList(), val canRepair: Boolean = false,
    val repairRange: Double? = null) {
    val reclaimTooltip: String get() = if (canMaintain)
        "Reclaim own ${if (canRepair) "unit" else "building"}\nCredits returned on completion\nLeft: choose target\nEsc / right: cancel" else ""
    val repairTooltip: String get() = if (canRepair)
        "Repair unit\nNo credit cost\nRange ${(repairRange ?: 230.0).toInt()}\nLeft: choose target\nEsc / right: cancel" else ""
}

data class SubmarineDepthActionView(val surface: Boolean, val title: String, val tooltip: String)
data class AmphibiousFlightActionView(val flying: Boolean, val title: String, val tooltip: String, val available: Boolean)

data class TransportUnloadActionView(val unloading: Boolean, val title: String, val available: Boolean)
data class CustomUnitActionView(val name: String, val title: String, val tooltip: String,
    val queued: Boolean, val cooldownRemaining: Int, val available: Boolean, val progress: Float? = null)
