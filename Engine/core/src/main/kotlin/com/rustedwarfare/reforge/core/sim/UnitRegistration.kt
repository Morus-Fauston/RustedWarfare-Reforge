package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.FactoryExitProfile
import com.rustedwarfare.reforge.core.content.AttachmentSlotDefinition
import com.rustedwarfare.reforge.core.content.ScoutBotRules
import java.util.UUID

internal data class ConstructionRegistration(val unit: MutableUnit, val ledger: ProductionLedger,
    val upgrades: OrdinaryUpgrades?)

/** Creates and inserts units in the current candidate; world publication remains at the caller's commit. */
internal object UnitRegistration {
    fun initial(state: StepState, input: Scenario, spawn: UnitSpawn, coordinates: CoordinatePolicy): MutableUnit {
        val definition = input.unitDefinitions.getValue(spawn.typeId)
        val chassis = definition.attachmentLayout != null
        return register(state, input, spawn.copy(heading = if (chassis) 90.0 else coordinates.normalize(spawn.heading),
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

    fun produced(state: StepState, input: Scenario, factory: MutableUnit, completed: ProductionCompletion): MutableUnit {
        check(completed.ownerPlayerId == factory.ownerPlayerId)
        val definition = input.unitDefinitions.getValue(completed.typeId)
        val publicId = "produced:${factory.ownerPlayerId}:${completed.unitNonce}"
        check(publicId !in state.units) { "A paid production nonce must identify a new unit" }
        val birth = FactoryExit.birth(factory, definition, input.map)
        val customProducer = factory.definition.production?.exitProfile == FactoryExitProfile.CUSTOM_NAUTILUS
        require(customProducer || MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
            input.map.heightTiles.toDouble() * input.map.tileHeight, birth.position, definition.radius)) {
            "Produced unit collision circle must remain within the map"
        }
        val identity = nextIdentity(state)
        val spawn = UnitSpawn(publicId, factory.ownerPlayerId, completed.typeId, birth.position, birth.heading,
            if (customProducer) factory.height else definition.movement.flight?.startingHeightOffset ?: 0.0)
        val reachable = MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
            input.map.heightTiles.toDouble() * input.map.tileHeight, birth.target, definition.radius)
        val born = register(state, input, spawn, identity.objectId, identity.updateKey,
            ProducedOrigin(factory.unitId, factory.typeId, completed.typeId), birth.protection, identity) { unit ->
            if (customProducer) unit.nativeTransportPlacement = true
            if (reachable) {
                unit.moveTarget = birth.target
                unit.originMoveId = "production-exit:$publicId"
                if (definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP || definition.energy != null)
                    unit.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, checkNotNull(unit.originMoveId), birth.target))
                unit.adoptRoute(listOf(birth.target), birth.target, -1L)
            }
        }
        if (factory.definition.production?.exitProfile == FactoryExitProfile.CUSTOM_MECH) {
            factory.productionSide = !factory.productionSide
            if (birth.fallback) state.pendingEvents += Event("production-exit-fallback", publicId)
        }
        if (customProducer) {
            factory.productionSide = !factory.productionSide
            if (factory.typeId != "nautilusSubmarineSurface" || birth.fallback) {
                Transport.retainProduced(factory, born)
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

    fun sandboxId(ownerPlayerId: String, generation: Long, sequence: Long): String =
        "sandbox:$ownerPlayerId:$generation:$sequence"

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
                generation: Long, sequence: Long, coordinates: CoordinatePolicy): MutableUnit {
        val definition = input.unitDefinitions.getValue(command.typeId)
        val id = sandboxId(command.ownerPlayerId, generation, sequence)
        check(id !in state.units) { "Sandbox identity must be new" }
        val identity = nextIdentity(state)
        val unit = create(input, UnitSpawn(id, command.ownerPlayerId, command.typeId, command.position,
            coordinates.normalize(command.heading), definition.movement.flight?.startingHeightOffset
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
        state.units[unit.unitId] = unit
        state.productionLedger = ledger
        state.ordinaryUpgrades = upgrades
        state.buildingTerrain = terrain
        identity?.let {
            state.nextObjectId = it.nextObjectId
            state.lastBirthSerial = it.serial
        }
    }
}
