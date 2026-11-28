package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.FactoryExitProfile
import com.rustedwarfare.reforge.core.content.AttachmentSlotDefinition
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.ScoutBotRules
import java.util.UUID

internal data class ConstructionRegistration(val unit: MutableUnit, val ledger: ProductionLedger,
    val upgrades: OrdinaryUpgrades?)

/** Creates and inserts units in the current candidate; world publication remains at the caller's commit. */
internal object UnitRegistration {
    fun initial(state: StepState, input: Scenario, spawn: UnitSpawn): MutableUnit {
        val definition = input.unitDefinitions.getValue(spawn.typeId)
        val chassis = definition.attachmentLayout != null
        return register(state, input, spawn.copy(heading = if (chassis) 90.0 else ScreenPlane.normalize(spawn.heading),
            height = if (chassis) UnitAttachments.OPENING_HEIGHT else definition.movement.flight?.startingHeightOffset ?: spawn.height),
            input.combatContract?.unitObjectIds?.getValue(spawn.unitId))
    }

    fun attached(state: StepState, input: Scenario, parent: MutableUnit, slot: AttachmentSlotDefinition): MutableUnit {
        val identity = nextIdentity(state)
        // The public handle stays stable through conversion without encoding the chassis or slot.
        val token = UUID.nameUUIDFromBytes("reforge:attachment:${input.combatContract?.worldSeed}:${identity.objectId}".encodeToByteArray())
        val spawn = UnitSpawn(UnitAttachments.UNIT_PREFIX + token, parent.ownerPlayerId,
            slot.initialTypeId, UnitAttachments.position(parent, slot), UnitAttachments.heading(parent, slot), parent.height)
        return register(state, input, spawn, identity.objectId, identity.updateKey, identity = identity) { child ->
            child.attachment = AttachmentBinding(parent.unitId, slot)
        }
    }

    fun produced(state: StepState, input: Scenario, factory: MutableUnit, completed: ProductionCompletion,
                 map: MapDefinition = input.map, world: WorldHandle? = null): MutableUnit {
        check(completed.ownerPlayerId == factory.ownerPlayerId)
        val definition = input.unitDefinitions.getValue(completed.typeId)
        val publicId = "produced:${factory.ownerPlayerId}:${completed.unitNonce}"
        check(publicId !in state.units) { "A paid production nonce must identify a new unit" }
        val birth = FactoryExit.birth(factory, definition, map)
        val customProducer = factory.definition.production?.exitProfile == FactoryExitProfile.CUSTOM_NAUTILUS
        require(customProducer || MapBounds.containsHull(input.map, birth.position, birth.heading, definition.movementCollision)) {
            val hull = if (definition.movementCollision is MovementCollisionShape.Circle) "collision circle" else "movement hull"
            "Produced unit $hull must remain within the map"
        }
        val identity = nextIdentity(state)
        val spawn = UnitSpawn(publicId, factory.ownerPlayerId, completed.typeId, birth.position, birth.heading,
            if (customProducer) factory.height else definition.movement.flight?.startingHeightOffset ?: 0.0)
        val reachable = MapBounds.containsHull(input.map, birth.target, birth.heading, definition.movementCollision)
        val born = register(state, input, spawn, identity.objectId, identity.updateKey,
            ProducedOrigin(factory.unitId, factory.typeId, completed.typeId), birth.protection, identity) { unit ->
            if (customProducer) unit.nativeTransportPlacement = true
            val rally = factory.factoryRally.takeIf { factory.definition.production?.rallyEnabled == true }
            if (reachable) {
                val exitId = "production-exit:$publicId"
                val hasQueuedExitTask = rally != null ||
                    definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP ||
                    definition.energy != null
                val useNoRallyFanCluster = rally == null && !hasQueuedExitTask && !customProducer &&
                    world != null &&
                    definition.movementCollision is MovementCollisionShape.Circle &&
                    definition.movement.type != com.rustedwarfare.reforge.core.content.MovementType.AIR &&
                    definition.movement.type != com.rustedwarfare.reforge.core.content.MovementType.NONE
                if (useNoRallyFanCluster) {
                    val fanPlan = selectFactoryFanPlan(state, factory, birth, definition, map)
                    val fanTarget = fanPlan.target
                    val stagingGroup = factoryStagingGroup(state, input, factory, birth, definition, world)
                    val fanContract = MotionContract(
                        goal = MotionGoalGeometry.SharedCluster(
                            anchor = fanTarget,
                            completesOnCapture = true,
                        ),
                        allowPassageYield = false,
                        traversalMode = TraversalMode.INDEPENDENT,
                        enableLocalAvoidance = true,
                        allowsManeuverFollow = false,
                        preparesRouteSuccessor = false,
                        tracksRouteStall = false,
                        tracksHardContactLimit = false,
                        gatheringTarget = null,
                    )
                    unit.updateNavigation {
                        it.bindCommandTarget(
                            target = fanTarget,
                            originMoveId = exitId,
                            originAtStep = null,
                            contract = fanContract,
                            landingMember = LandingMember(stagingGroup),
                        )
                    }
                    unit.adoptRoute(fanPlan.waypoints, fanTarget, -1L)
                } else {
                    val exitContract = MotionContract.factoryExit(
                        target = birth.target,
                        hasQueuedExitTask = hasQueuedExitTask,
                        hasRally = rally != null,
                    )
                    unit.updateNavigation {
                        it.bindCommandTarget(
                            target = birth.target,
                            originMoveId = exitId,
                            originAtStep = null,
                            contract = exitContract,
                        )
                    }
                    if (hasQueuedExitTask)
                        unit.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, exitId, birth.target,
                            factoryExit = rally != null, motionContract = exitContract))
                    unit.adoptRoute(listOf(birth.target), birth.target, -1L)
                }
            }
            if (rally != null) unit.taskQueue = unit.taskQueue.append(UnitTask(UnitTaskKind.MOVE, "production-rally:$publicId", rally, startsAfterFactoryExit = true))
        }
        if (factory.definition.production?.exitProfile == FactoryExitProfile.CUSTOM_MECH) {
            factory.productionSide = !factory.productionSide
            if (birth.fallback) state.pendingEvents += Event("production-exit-fallback", publicId)
        }
        if (customProducer) {
            factory.productionSide = !factory.productionSide
            if (factory.typeId != "nautilusSubmarineSurface" || birth.fallback) {
                Transport.retainProduced(factory, born)
                state.spatialIndex.relocate(born)
                state.pendingEvents += Event("unit-loaded", factory.unitId)
            }
            if (birth.fallback) state.pendingEvents += Event("production-exit-fallback", publicId)
        }
        if (!reachable) {
            state.pendingEvents += Event("movement-cancelled", publicId)
            state.pendingEvents += Event("path-unreachable", publicId)
        }
        state.pendingEvents += Event("unit-produced", publicId)
        return born
    }

    private data class FactoryFanPlan(val target: Position, val waypoints: List<Position>)

    /**
     * Selects a deterministic passable fan-out staging point on expanding forward semicircle arcs
     * in front of [factory] for continuous no-rally production (ENG-P017-008).
     *
     * Always tries the exact [birth.target] first so isolated/single-unit exits remain bit-identical;
     * when the doorway anchor is already occupied or claimed by prior units, picks the nearest open
     * passable arc slot alternating left/right with an optional doorway-clearing intermediate waypoint.
     */
    private fun selectFactoryFanPlan(
        state: StepState,
        factory: MutableUnit,
        birth: FactoryBirth,
        product: com.rustedwarfare.reforge.core.content.UnitDefinition,
        map: MapDefinition,
    ): FactoryFanPlan {
        val defaultPlan = FactoryFanPlan(birth.target, listOf(birth.target))
        if (product.movementCollision !is MovementCollisionShape.Circle) return defaultPlan
        if (factory.definition.production?.exitProfile == FactoryExitProfile.NATIVE_SEA) return defaultPlan
        val dx = birth.target.x - birth.position.x
        val dy = birth.target.y - birth.position.y
        val dist = kotlin.math.hypot(dx, dy)
        if (dist <= 1e-4) return defaultPlan
        val dirX = dx / dist
        val dirY = dy / dist
        val rightX = -dirY
        val rightY = dirX

        val prodR = maxOf(4.0, product.movementCollision.radius)
        // Permeable fan spacing (>= 4.3 * R leaves a > 2R navigable lane between staged units so
        // trailing rings glide through inner rings without building a doorway dam).
        val slotSpacing = maxOf(prodR * 4.3, 24.0)
        val occupiedR2 = (slotSpacing * 0.72) * (slotSpacing * 0.72)
        val collisionMap = state.buildingTerrain?.collisionMap ?: map
        val moveType = product.movement.type
        val terrainGrid = HullTerrainGrid.authoritative(collisionMap, moveType)
        val spatialLayer = if (UnitLocomotion.isUnderwater(product, 0.0)) SpatialLayer.SUBMERGED else SpatialLayer.SURFACE
        val claimedTargets = state.units.values.mapNotNull { u ->
            if (!u.alive || u.carriedBy != null || u.definition.building != null) null
            else u.moveTarget?.takeIf { ScreenPlane.distanceSquared(it, birth.target) <= 320.0 * 320.0 }
        }

        fun isUnitClear(cand: Position): Boolean {
            val q = slotSpacing
            val nearby = state.spatialIndex.queryBounds(
                spatialLayer,
                cand.x - q, cand.x + q,
                cand.y - q, cand.y + q,
            )
            for (i in 0 until nearby.size) {
                val other = nearby[i]
                if (!other.alive || other.carriedBy != null || other.definition.building != null) continue
                val minSep = prodR + UnitContact.boundingRadius(other.definition.movementCollision) + 2.5
                if (ScreenPlane.distanceSquared(other.position, cand) < minSep * minSep) return false
            }
            for (i in claimedTargets.indices) {
                if (ScreenPlane.distanceSquared(claimedTargets[i], cand) < occupiedR2) return false
            }
            return true
        }

        fun isSlotOpen(cand: Position): Boolean {
            if (!MapBounds.containsHull(map, cand, birth.heading, product.movementCollision)) return false
            if (!UnitContact.poseFits(product.movementCollision, cand, birth.heading, terrainGrid)) return false
            return isUnitClear(cand)
        }

        // First unit / empty staging anchor always uses exact birth.target (100% preserves single-unit & building-crossing exits).
        if (isUnitClear(birth.target)) return defaultPlan

        val doorClear = minOf(dist * 0.55, factory.definition.radius + prodR + 6.0)
        val doorExit = Position(birth.position.x + dirX * doorClear, birth.position.y + dirY * doorClear)
        val baseArcR = maxOf(dist - doorClear, prodR * 3.5)
        val maxAngleRad = Math.toRadians(60.0)
        for (ring in 0..8) {
            val r = baseArcR + ring * slotSpacing
            val dTheta = maxOf(0.16, slotSpacing / maxOf(r, slotSpacing))
            val maxSteps = kotlin.math.floor(maxAngleRad / dTheta).toInt().coerceAtLeast(1)
            val stepOrder = (0..maxSteps).toList()
            for (step in stepOrder) {
                val signs = if (step == 0) intArrayOf(0) else intArrayOf(1, -1)
                for (sign in signs) {
                    val theta = step * dTheta * sign
                    val c = kotlin.math.cos(theta)
                    val s = kotlin.math.sin(theta)
                    val cand = Position(
                        doorExit.x + (dirX * c + rightX * s) * r,
                        doorExit.y + (dirY * c + rightY * s) * r,
                    )
                    if (!isSlotOpen(cand)) continue
                    return FactoryFanPlan(cand, listOf(cand))
                }
            }
        }
        return defaultPlan
    }

    private fun factoryStagingGroup(
        state: StepState,
        input: Scenario,
        factory: MutableUnit,
        birth: FactoryBirth,
        product: com.rustedwarfare.reforge.core.content.UnitDefinition,
        world: WorldHandle,
    ): GroupMoveRequest {
        val existing = state.factoryStagingGroups[factory.unitId]
        // Reuse stable staging cluster identity per (factory.unitId, owner) so alternating exits
        // (e.g. CUSTOM_MECH productionSide flips) and mixed product sizes share ONE cluster identity.
        if (existing != null && existing.actor == factory.ownerPlayerId) {
            return existing
        }
        val dx = birth.target.x - birth.position.x
        val dy = birth.target.y - birth.position.y
        val dist = kotlin.math.hypot(dx, dy)
        val isCircle = product.movementCollision is MovementCollisionShape.Circle
        val corridor = if (dist > 1e-4 && isCircle) {
            val dirX = dx / dist
            val dirY = dy / dist
            val prodR = maxOf(4.0, product.movementCollision.boundingRadius)
            val clearDist = minOf(maxOf(0.0, dist - maxOf(14.0, prodR * 2.2)), factory.definition.radius + prodR * 1.4)
            val halfWidth = maxOf(prodR * 1.6, 10.0)
            val minSettleAlong = minOf(clearDist, maxOf(0.0, factory.definition.radius + prodR))
            NoParkingCorridor(
                originX = birth.position.x,
                originY = birth.position.y,
                dirX = dirX,
                dirY = dirY,
                clearDist = clearDist,
                halfWidth = halfWidth,
                minSettleAlong = minSettleAlong,
            )
        } else null
        val maxRadii = mutableMapOf<com.rustedwarfare.reforge.core.content.MovementType, Double>()
        maxRadii[product.movement.type] = product.movementCollision.boundingRadius
        factory.definition.production?.unitTypeIds?.forEach { typeId ->
            val def = input.unitDefinitions[typeId] ?: return@forEach
            val mt = def.movement.type
            val r = def.movementCollision.boundingRadius
            val prev = maxRadii[mt]
            if (prev == null || r > prev) maxRadii[mt] = r
        }
        val created = GroupMoveRequest(
            world = world,
            completedStep = state.stepIndex,
            actor = factory.ownerPlayerId,
            click = birth.target,
            members = emptySet(),
            maxRadiusByMovement = maxRadii,
            dynamicCluster = true,
            noParkingCorridor = corridor,
        )
        state.factoryStagingGroups[factory.unitId] = created
        return created
    }

    fun sandboxId(ownerPlayerId: String, generation: Long, sequence: Long): String =
        "sandbox:$ownerPlayerId:$generation:$sequence"

    fun sandboxBatchId(ownerPlayerId: String, generation: Long, sequence: Long, index: Int): String =
        "sandbox-batch:$ownerPlayerId:$generation:$sequence:$index"

    fun scout(state: StepState, input: Scenario, source: MutableUnit, impact: MutableProjectile) {
        check(impact.sourceUnitId == source.unitId)
        val owner = source.ownerPlayerId
        val pending = state.productionLedger?.queuedUnitCount(owner) ?: 0
        var counted = pending
        var total = pending
        for (unit in state.units.values) {
            if (unit.ownerPlayerId != owner || !unit.alive || unit.reclaimed) continue
            total++
            if (unit.definition.building == null) counted++
        }
        if (counted > ScoutBotRules.OFFLINE_UNIT_CAP + 300 || total > ScoutBotRules.OFFLINE_UNIT_CAP + 25000) return
        val identity = nextIdentity(state)
        val id = "scout:$owner:${impact.objectId}"
        val origin = ScoutOrigin(source.unitId, impact.objectId)
        val unit = create(input, UnitSpawn(id, owner, ScoutBotRules.LAND, impact.position,
            ScreenPlane.normalize(impact.heading), 0.0), identity.objectId, identity.updateKey, scoutOrigin = origin)
        unit.nativeTransportPlacement = !MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
            input.map.heightTiles.toDouble() * input.map.tileHeight, unit.position, unit.definition.radius)
        registerUnit(state, input, unit, identity)
        state.pendingEvents += Event("scout-bot-spawned", id)
    }

    fun sandbox(state: StepState, input: Scenario, command: SandboxCommand.Spawn,
                id: String): MutableUnit {
        val definition = input.unitDefinitions.getValue(command.typeId)
        check(id !in state.units) { "Sandbox identity must be new" }
        val identity = nextIdentity(state)
        val unit = create(input, UnitSpawn(id, command.ownerPlayerId, command.typeId, command.position,
            ScreenPlane.normalize(command.heading), definition.movement.flight?.startingHeightOffset
                ?: definition.fixedUnderwaterTarget?.height ?: 0.0), identity.objectId, identity.updateKey)
        if (command.state == SandboxInitialState.DAMAGED) unit.health = checkNotNull(definition.maximumHealth) / 2.0
        registerUnit(state, input, unit, identity)
        return unit
    }

    /** Registers an unfinished site against the caller's prepared payment, without publishing a world step. */
    fun construction(state: StepState, input: Scenario, ownerPlayerId: String, typeId: String,
                     position: Position, nonce: String,
                     paidLedger: ProductionLedger = checkNotNull(state.productionLedger)): ConstructionRegistration {
        val id = "constructed:$ownerPlayerId:$nonce"
        check(id !in state.units) { "Construction identity must be new in this world" }
        val definition = input.unitDefinitions.getValue(typeId)
        val identity = nextIdentity(state)
        val unit = create(input, UnitSpawn(id, ownerPlayerId, typeId, position, 0.0,
            definition.movement.flight?.startingHeightOffset ?: 0.0), identity.objectId, identity.updateKey,
            constructionProgress = 0f)
        registerUnit(state, input, unit, identity, paidLedger)
        return ConstructionRegistration(unit, checkNotNull(state.productionLedger), state.ordinaryUpgrades)
    }

    private data class Identity(val objectId: Long, val updateKey: String, val nextObjectId: Long, val serial: Long)

    private fun nextIdentity(state: StepState): Identity {
        val objectId = state.nextObjectId
        val nextObjectId = Math.addExact(state.nextObjectId, 1L)
        val serial = Math.incrementExact(state.lastBirthSerial)
        val updateKey = "~produced/" + serial.toString().padStart(20, '0')
        check(state.units.values.lastOrNull()?.updateKey?.let { it < updateKey } != false)
        return Identity(objectId, updateKey, nextObjectId, serial)
    }

    private fun create(input: Scenario, spawn: UnitSpawn, objectId: Long?, updateKey: String = spawn.unitId,
                       origin: ProducedOrigin? = null, birthRemaining: Float = 0f,
                       constructionProgress: Float = 1f, scoutOrigin: ScoutOrigin? = null): MutableUnit =
        MutableUnit(spawn.unitId, spawn.ownerPlayerId, spawn.typeId,
            input.unitDefinitions.getValue(spawn.typeId), spawn.position, spawn.heading,
            height = spawn.height, objectId = objectId, updateKey = updateKey,
            productionOrigin = origin, scoutOrigin = scoutOrigin,
            birthRemaining = birthRemaining, constructionProgress = constructionProgress)

    private fun register(state: StepState, input: Scenario, spawn: UnitSpawn, objectId: Long?,
                         updateKey: String = spawn.unitId, origin: ProducedOrigin? = null, birthRemaining: Float = 0f,
                         identity: Identity? = null,
                         beforeInsert: (MutableUnit) -> Unit = {}): MutableUnit {
        check(spawn.unitId !in state.units) { "Unit identity must be new in this world" }
        val unit = create(input, spawn, objectId, updateKey, origin, birthRemaining)
        beforeInsert(unit)
        registerUnit(state, input, unit, identity)
        return unit
    }

    private fun registerUnit(state: StepState, input: Scenario, unit: MutableUnit, identity: Identity?,
                             paidLedger: ProductionLedger? = state.productionLedger) {
        check(unit.unitId !in state.units) { "Unit identity must be new in this world" }
        val ledger = unit.definition.production?.let { producer ->
            checkNotNull(paidLedger).register(unit.unitId,
                FactoryProductionSpec(unit.ownerPlayerId, producer.unitTypeIds.associateWith {
                    checkNotNull(input.unitDefinitions.getValue(it).build)
                }, unit.definition.upgrades?.actions.orEmpty()))
        } ?: paidLedger
        val upgrades = state.ordinaryUpgrades?.register(unit.unitId, unit.definition)
        val terrain = if (unit.definition.building == null) state.buildingTerrain else
            BuildingTerrain.fromLive(input.map, state.units.values + unit, state.buildingTerrain)
        Transport.refreshTerrain(unit, input.map)
        unit.overLiquid = input.map.isLiquid(
            MapBounds.cell(unit.position.x, input.map.tileWidth, input.map.widthTiles),
            MapBounds.cell(unit.position.y, input.map.tileHeight, input.map.heightTiles),
        )
        state.units[unit.unitId] = unit
        state.spatialIndex.insert(unit)
        state.productionLedger = ledger
        state.ordinaryUpgrades = upgrades
        state.buildingTerrain = terrain
        identity?.let {
            state.nextObjectId = it.nextObjectId
            state.lastBirthSerial = it.serial
        }
    }
}
