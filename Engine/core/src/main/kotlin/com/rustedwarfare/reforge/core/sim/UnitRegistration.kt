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
        val objectId = state.nextObjectId
        state.nextObjectId = Math.addExact(state.nextObjectId, 1L)
        state.lastBirthSerial = Math.incrementExact(state.lastBirthSerial)
        val updateKey = "~produced/" + state.lastBirthSerial.toString().padStart(20, '0')
        check(state.units.values.lastOrNull()?.updateKey?.let { it < updateKey } != false)
        val spawn = UnitSpawn(publicId, factory.ownerPlayerId, completed.typeId, birth.position, birth.heading,
            definition.movement.flight?.startingHeightOffset ?: 0.0)
        val born = register(state, input, spawn, objectId, updateKey, factory.unitId, birth.protection) { unit ->
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

    private fun register(state: StepState, input: Scenario, spawn: UnitSpawn, objectId: Long?,
                         updateKey: String = spawn.unitId, producerId: String? = null, birthRemaining: Float = 0f,
                         beforeInsert: (MutableUnit) -> Unit = {}): MutableUnit {
        check(spawn.unitId !in state.units) { "Unit identity must be new in this world" }
        val unit = MutableUnit(spawn.unitId, spawn.ownerPlayerId, spawn.typeId,
            input.unitDefinitions.getValue(spawn.typeId), spawn.position, spawn.heading,
            height = spawn.height, objectId = objectId, updateKey = updateKey,
            producerId = producerId, birthRemaining = birthRemaining)
        beforeInsert(unit)
        state.units[spawn.unitId] = unit
        return unit
    }
}
