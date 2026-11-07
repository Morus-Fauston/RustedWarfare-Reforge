package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.FactoryExitProfile

/** Creates and inserts units in the current candidate; world publication remains at the caller's commit. */
internal object UnitRegistration {
    fun initial(state: StepState, input: Scenario, spawn: UnitSpawn, coordinates: CoordinatePolicy): MutableUnit {
        val definition = input.unitDefinitions.getValue(spawn.typeId)
        return register(state, input, spawn.copy(heading = coordinates.normalize(spawn.heading),
            height = definition.movement.flight?.startingHeightOffset ?: spawn.height),
            input.combatContract?.unitObjectIds?.getValue(spawn.unitId))
    }

    fun produced(state: StepState, input: Scenario, factory: MutableUnit, completed: ProductionCompletion): MutableUnit {
        check(completed.ownerPlayerId == factory.ownerPlayerId)
        val definition = input.unitDefinitions.getValue(completed.typeId)
        val publicId = "produced:${factory.ownerPlayerId}:${completed.unitNonce}"
        check(publicId !in state.units) { "A paid production nonce must identify a new unit" }
        val birth = FactoryExit.birth(factory, definition, input.map)
        require(MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
            input.map.heightTiles.toDouble() * input.map.tileHeight, birth.position, definition.radius)) {
            "Produced unit collision circle must remain within the map"
        }
        val identity = nextIdentity(state)
        val spawn = UnitSpawn(publicId, factory.ownerPlayerId, completed.typeId, birth.position, birth.heading,
            definition.movement.flight?.startingHeightOffset ?: 0.0)
        val born = register(state, input, spawn, identity.objectId, identity.updateKey,
            ProducedOrigin(factory.unitId, factory.typeId), birth.protection) { unit ->
            if (factory.definition.production?.exitProfile == FactoryExitProfile.CUSTOM_MECH) {
                factory.productionSide = !factory.productionSide
                if (birth.fallback) state.pendingEvents += Event("production-exit-fallback", publicId)
            }
            if (MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                    input.map.heightTiles.toDouble() * input.map.tileHeight, birth.target, definition.radius)) {
                unit.moveTarget = birth.target
                unit.originMoveId = "production-exit:$publicId"
                unit.adoptRoute(listOf(birth.target), birth.target, -1L)
            } else {
                state.pendingEvents += Event("movement-cancelled", publicId)
                state.pendingEvents += Event("path-unreachable", publicId)
            }
        }
        state.pendingEvents += Event("unit-produced", publicId)
        return born
    }

    fun sandboxId(ownerPlayerId: String, generation: Long, sequence: Long): String =
        "sandbox:$ownerPlayerId:$generation:$sequence"

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
        registerPrepared(state, unit)
        return unit
    }

    /** Prepares an unfinished site; the caller registers it after its own bookkeeping. */
    fun prepareConstruction(state: StepState, input: Scenario, ownerPlayerId: String, typeId: String,
                            position: Position, nonce: String): MutableUnit {
        val id = "constructed:$ownerPlayerId:$nonce"
        check(id !in state.units) { "Construction identity must be new in this world" }
        val definition = input.unitDefinitions.getValue(typeId)
        val identity = nextIdentity(state)
        return create(input, UnitSpawn(id, ownerPlayerId, typeId, position, 0.0,
            definition.movement.flight?.startingHeightOffset ?: 0.0), identity.objectId, identity.updateKey,
            constructionProgress = 0f)
    }

    fun registerPrepared(state: StepState, unit: MutableUnit) {
        check(unit.unitId !in state.units) { "Unit identity must be new in this world" }
        state.units[unit.unitId] = unit
    }

    private data class Identity(val objectId: Long, val updateKey: String)

    private fun nextIdentity(state: StepState): Identity {
        val objectId = state.nextObjectId
        state.nextObjectId = Math.addExact(state.nextObjectId, 1L)
        state.lastBirthSerial = Math.incrementExact(state.lastBirthSerial)
        val updateKey = "~produced/" + state.lastBirthSerial.toString().padStart(20, '0')
        check(state.units.values.lastOrNull()?.updateKey?.let { it < updateKey } != false)
        return Identity(objectId, updateKey)
    }

    private fun create(input: Scenario, spawn: UnitSpawn, objectId: Long?, updateKey: String = spawn.unitId,
                       origin: ProducedOrigin? = null, birthRemaining: Float = 0f,
                       constructionProgress: Float = 1f): MutableUnit =
        MutableUnit(spawn.unitId, spawn.ownerPlayerId, spawn.typeId,
            input.unitDefinitions.getValue(spawn.typeId), spawn.position, spawn.heading,
            height = spawn.height, objectId = objectId, updateKey = updateKey,
            productionOrigin = origin,
            birthRemaining = birthRemaining, constructionProgress = constructionProgress)

    private fun register(state: StepState, input: Scenario, spawn: UnitSpawn, objectId: Long?,
                         updateKey: String = spawn.unitId, origin: ProducedOrigin? = null, birthRemaining: Float = 0f,
                         beforeInsert: (MutableUnit) -> Unit = {}): MutableUnit {
        check(spawn.unitId !in state.units) { "Unit identity must be new in this world" }
        val unit = create(input, spawn, objectId, updateKey, origin, birthRemaining)
        beforeInsert(unit)
        registerPrepared(state, unit)
        return unit
    }
}
