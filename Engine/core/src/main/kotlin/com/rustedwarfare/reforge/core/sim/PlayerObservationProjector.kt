package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot
import com.rustedwarfare.reforge.core.knowledge.VisionSnapshot
import java.util.Collections
import java.util.IdentityHashMap

/** Captured on the simulation owner thread; no mutable world objects or live callbacks escape. */
internal class PlayerObservationFrame(
    val current: Observation,
    val committed: Observation?,
    val vision: VisionSnapshot?,
    val knowledge: KnowledgeSnapshot?,
    val actionRevision: Long,
    val hasAdvanced: Boolean,
    resultOwners: Map<ActionResult, String>,
    reclaimedOwners: Map<String, String>,
    nativeTransportPlacements: Set<String> = emptySet(),
) {
    // Copy by reference identity: equal rejected results can belong to different actors.
    private val owners = IdentityHashMap<ActionResult, String>().also { it.putAll(resultOwners) }
    private val reclaimed = Collections.unmodifiableMap(LinkedHashMap(reclaimedOwners))
    val nativeTransportPlacements: Set<String> = Collections.unmodifiableSet(HashSet(nativeTransportPlacements))
    fun resultOwner(result: ActionResult): String? = owners[result]
    fun reclaimedOwner(unitId: String?): String? = reclaimed[unitId]
}

/** One player's authorization rules and public history, independent of authoritative state ownership. */
internal class PlayerObservationProjector(world: WorldHandle, scenario: Scenario, private val playerId: String) {
    private val worldId = world.id
    private val scenarioId = scenario.scenarioId
    private val localControl = scenario.players.first { it.playerId == playerId }.control == PlayerControl.LOCAL
    private val factoryTypes = scenario.unitDefinitions.filterValues { it.production != null }.keys.toSet()
    private val upgradeTypes = scenario.unitDefinitions.filterValues { it.upgrades != null }.keys.toSet()
    private val map = scenario.map
    private val widthTiles = scenario.map.widthTiles
    private val heightTiles = scenario.map.heightTiles
    private val tileWidth = scenario.map.tileWidth
    private val tileHeight = scenario.map.tileHeight
    private val projectileProjection = ProjectileProjection()
    private var cachedObservation: CachedPlayerObservation? = null

    private data class PlayerObservationKey(val step: Long, val time: Double, val epoch: Long?,
        val knowledgeRevision: Long?, val actionRevision: Long, val units: List<UnitObservation>,
        val events: List<Event>, val results: List<ActionResult>, val projectiles: List<VisibleProjectile>,
        val projectileEvents: List<VisibleProjectileEvent>, val balance: Long?, val factoryQueues: List<FactoryQueueObservation>,
        val upgradeQueues: List<UpgradeQueueObservation>, val credits: Double?, val weapons: List<VisibleUnitWeapon>)

    private data class CachedPlayerObservation(val key: PlayerObservationKey,
        val observation: PlayerObservation, val revision: Long, val source: Observation)

    private fun isVisible(vision: VisionSnapshot?, position: Position): Boolean {
        if (vision == null) return true
        if (!MapBounds.contains(widthTiles.toDouble() * tileWidth,
                heightTiles.toDouble() * tileHeight, position, 0.0)) return false
        return vision.contains(MapBounds.cell(position.x, tileWidth, widthTiles),
            MapBounds.cell(position.y, tileHeight, heightTiles))
    }

    fun observe(frame: PlayerObservationFrame): PlayerObservation {
        val observation = frame.current
        val vision = frame.vision
        val knowledge = frame.knowledge
        val cached = cachedObservation
        if (cached?.source === observation && cached.key.epoch == vision?.epoch &&
            cached.key.knowledgeRevision == knowledge?.revision &&
            cached.key.actionRevision == frame.actionRevision) return cached.observation
        val visibleUnits = observation.units.filter { it.carriedBy == null && isVisible(vision,
            Transport.queryPosition(it.position, it.unitId in frame.nativeTransportPlacements, map)) }
        val visibleIds = visibleUnits.map { it.unitId }.toSet()
        val visible = visibleUnits.map {
            val ownerTasks = immutable(it.tasks.map { task ->
                if (task.targetUnitId != null && task.targetUnitId !in visibleIds) task.copy(targetUnitId = null) else task
            })
            if (it.ownerPlayerId != playerId) {
                it.copy(moveTarget = null, pathPoints = emptyList(), attackTargetId = null, cooldown = null, pointDefense = null,
                    tasks = emptyList(), constructionTargetId = null, transportExit = null,
                    transport = null, unitActions = null, attachmentOverview = null, factoryRally = null,
                    attachment = it.attachment?.takeIf { relation -> relation.parentUnitId in visibleIds },
                    submarine = it.submarine?.copy(surfaceIntent = null, verticalSpeed = null),
                    amphibiousJet = it.amphibiousJet?.copy(flyingIntent = null, verticalSpeed = null, canDive = null))
            } else if (it.attackTargetId != null && it.attackTargetId !in visibleIds) {
                it.copy(moveTarget = null, pathPoints = emptyList(), attackTargetId = null, tasks = ownerTasks,
                    constructionTargetId = it.constructionTargetId?.takeIf { target -> target in visibleIds })
            } else it.copy(tasks = ownerTasks, constructionTargetId = it.constructionTargetId?.takeIf { target -> target in visibleIds })
        }
        val ids = visible.map { it.unitId }.toSet()
        val visibleById = visible.associateBy { it.unitId }
        val weapons = observation.combat?.weapons.orEmpty().filter {
            it.weaponName != null && visibleById[it.unitId]?.alive == true
        }.map { state ->
            val own = visibleById.getValue(state.unitId).ownerPlayerId == playerId
            VisibleUnitWeapon(state.unitId, checkNotNull(state.weaponName), checkNotNull(state.turretHeading),
                checkNotNull(state.pivot), checkNotNull(state.muzzle), state.cooldown.takeIf { own },
                state.warmup.takeIf { own }, state.targetUnitId?.takeIf { own && it in ids })
        }.sortedBy { it.unitId }
        fun visibleEvents(events: List<Event>): List<Event> {
            val allowed = events.filter { event -> if (event.kind == "building-reclaimed") {
                frame.reclaimedOwner(event.unitId) == playerId
            } else event.unitId != null && event.unitId in ids &&
                (!(event.kind.startsWith("production-") || event.kind.startsWith("upgrade-") || event.kind.startsWith("unit-action-") ||
                    event.kind == "rally-set" || event.kind == "rally-cleared" ||
                    event.kind in setOf("construction-unavailable", "construction-insufficient-funds", "construction-queue-full", "maintenance-completed",
                        "submarine-surface-requested", "submarine-dive-requested",
                        "amphibious-fly-requested", "amphibious-dive-requested",
                        "unit-loaded", "unload-started", "unload-cancelled", "loading-cancelled", "unload-blocked")) ||
                    visible.any { it.unitId == event.unitId && it.ownerPlayerId == playerId }) }
            val combatKinds = setOf("projectile-fired", "unit-damaged", "shield-damaged", "unit-died",
                "production-queued", "production-cancelled", "production-refunded", "unit-produced",
                "upgrade-queued", "upgrade-cancelled", "upgrade-completed", "upgrade-refunded")
            // S1 command events retain submission order. Combat order is public-value canonical,
            // never the hidden source/host projectile allocation order used by the simulator.
            return allowed.filter { it.kind !in combatKinds } + allowed.filter { it.kind in combatKinds }
                .sortedWith(compareBy<Event> { it.kind }.thenBy { it.unitId })
        }
        // Keep the committed step's events when immediate actions replace the host observation.
        // Hidden actions must neither erase these events nor advance the player's sample identity.
        val committed = frame.committed?.takeIf { it !== observation }
        val events = visibleEvents(committed?.events.orEmpty() + observation.events)
        val results = (committed?.actionResults.orEmpty() + observation.actionResults).filter { frame.resultOwner(it) == playerId }.map {
            if (it is ActionResult.Accepted) ActionResult.Accepted(it.actionId, visibleEvents(it.events), it.queuedForStep) else it
        }
        val rawProjectileEvents = committed?.projectileEvents.orEmpty() + observation.projectileEvents
        val projectedEvents = rawProjectileEvents.mapNotNull { event ->
            val value = event.projectile
            if ((!value.alwaysVisibleInFog && !isVisible(vision, value.position)) ||
                value.lightning?.let { !isVisible(vision, it.end) } == true ||
                value.beam?.let { !isVisible(vision, it.end) } == true) return@mapNotNull null
            val interceptor = event.interceptorUnitId.takeIf { it in ids }
            val publicInterception = event.kind == "intercepted" &&
                value.appearance == ProjectileAppearance.NUCLEAR && value.alwaysVisibleInFog
            if (event.interceptorUnitId != null && interceptor == null && !publicInterception) return@mapNotNull null
            val target = value.targetUnitId.takeIf { it in ids }
            val source = value.sourceUnitId.takeIf { it in ids }
            val beam = if (source == null) value.beam?.copy(resourceUnitType = null) else value.beam
            ProjectedProjectileEvent(value.objectId, event.kind, value.position, value.heading, value.height,
                source, target, event.damage.takeIf { target != null }, interceptor,
                value.areaExpansion?.radius, value.sourceWeaponName?.takeIf { source != null }, value.appearance, value.lightning, beam)
        }
        val projection = projectileProjection.project(
            observation.projectiles.filter { (it.alwaysVisibleInFog || isVisible(vision, it.position)) &&
                (it.lightning?.let { line -> isVisible(vision, line.end) } ?: true) &&
                (it.beam?.let { line -> isVisible(vision, line.end) } ?: true) }.map { shot ->
                val beam = shot.beam
                if (beam?.resourceUnitType == null) shot
                else if (shot.sourceUnitId in ids) shot.copy(beam = beam.copy(visibleSourceUnitId = shot.sourceUnitId))
                else shot.copy(beam = beam.copy(resourceUnitType = null))
            }, projectedEvents,
            observation.projectiles.mapTo(mutableSetOf()) { it.objectId }.also { retained ->
                rawProjectileEvents.forEach { retained += it.projectile.objectId }
            })
        val balance = observation.economy?.balances?.getValue(playerId)
        val credits = observation.economy?.preciseBalances?.getValue(playerId)
        val factoryQueues = if (localControl)
            visible.filter { it.ownerPlayerId == playerId && it.alive &&
                it.typeId in factoryTypes }.sortedBy { it.unitId }.map { factory ->
                    FactoryQueueObservation(factory.unitId, observation.economy?.queuedProduction?.getValue(factory.unitId).orEmpty()
                        .map { ProductionQueueItem(it.typeId, it.progress) }, factory.factoryRally)
                } else emptyList()
        val playerUpgrades = if (localControl)
            visible.filter { it.ownerPlayerId == playerId && it.alive && it.typeId in upgradeTypes }
                .sortedBy { it.unitId }.map { unit -> UpgradeQueueObservation(unit.unitId,
                    observation.upgrades?.queues?.getValue(unit.unitId).orEmpty().map {
                        UpgradeQueueItem(it.actionKey, it.targetTypeId, it.progress) }) }
            else emptyList()
        val key = PlayerObservationKey(observation.stepIndex, observation.simulationTime, vision?.epoch, knowledge?.revision,
            frame.actionRevision, visible, events, results, projection.projectiles, projection.events, balance, factoryQueues, playerUpgrades, credits, weapons)
        if (cached?.key == key) {
            cachedObservation = cached.copy(source = observation)
            return cached.observation
        }
        val playerRevision = (cached?.revision ?: -1L) + 1L
        val result = PlayerObservation("${worldId}:$playerId:${if (frame.hasAdvanced) "step${observation.stepIndex}" else "initial"}:${vision?.epoch ?: 0L}:$playerRevision",
            worldId, scenarioId, playerId, observation.stepIndex, observation.simulationTime, vision, knowledge,
            visible, events, results, projection.projectiles, projection.events, balance, factoryQueues, playerUpgrades, credits, weapons)
        cachedObservation = CachedPlayerObservation(key, result, playerRevision, observation)
        return result
    }
}
