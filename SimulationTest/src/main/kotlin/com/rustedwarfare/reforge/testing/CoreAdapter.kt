package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.*
import kotlinx.serialization.json.*

internal class CoreAdapter(private val input: LoadedScenario) : AutoCloseable {
    private val context = when (val result = SimulationContext.initialize(input.core)) {
        is InitResult.Accepted -> result.context
        is InitResult.Rejected -> throw CoreFailure("Initialization rejected: ${result.error}")
    }
    fun initial(): Observation = context.observe().also { observation ->
        val expected = input.core.units.associateBy { it.unitId }
        if (observation.scenarioId != input.core.scenarioId || observation.simulationTime != 0.0 ||
            observation.units.size != expected.size || observation.events.isNotEmpty() || observation.actionResults.isNotEmpty() ||
            observation.units.any { actual -> expected[actual.unitId]?.let { spawn ->
                actual.ownerPlayerId != spawn.ownerPlayerId || actual.typeId != spawn.typeId ||
                    actual.position != spawn.position || actual.heading != spawn.heading || actual.speed != 0.0 ||
                    actual.angularVelocity != 0.0 || actual.moveTarget != null || actual.pathPoints.isNotEmpty() ||
                    actual.movementState != MovementState.IDLE
            } ?: true }) throw CoreFailure("Initial observation differs from declared scenario")
    }
    fun submit(value: JsonObject): ActionResult {
        val id = value.id("actionId")
        val actor = value.id("actor")
        val unit = value.id("unitId")
        // Submit at the current explicit boundary; preserve file order and the core's permission checks.
        val action = when (value.text("kind")) {
            "load" -> LoadAction(id, actor, unit, value.id("targetUnitId"), LoadDirection.valueOf(value.text("direction")), context.world)
            "unload" -> UnloadAction(id, actor, unit, value.required("unloading").jsonPrimitive.boolean, context.world)
            "submarine-depth" -> SubmarineDepthAction(id, actor, unit, value.required("surface").jsonPrimitive.boolean, context.world)
            "amphibious-flight" -> AmphibiousFlightAction(id, actor, unit, value.required("flying").jsonPrimitive.boolean, context.world)
            "move" -> MoveAction(id, actor, unit, position(value.objectAt("target")), context.world)
            "build" -> BuildAction(id, actor, unit, value.id("buildingTypeId"), position(value.objectAt("target")),
                value.id("buildingNonce"), context.world, append = value.required("append").jsonPrimitive.boolean)
            "stop" -> StopAction(id, actor, unit, context.world)
            "select" -> SelectAction(id, actor, unit, context.world)
            "produce" -> ProduceAction(id, actor, unit, value.id("productionTypeId"), value.id("unitNonce"), context.world)
            "cancel-production" -> CancelProductionAction(id, actor, unit, value.id("productionTypeId"), context.world)
            "attack" -> AttackAction(id, actor, unit, value.id("targetUnitId"), context.world)
            "patrol" -> PatrolAction(id, actor, unit, position(value.objectAt("target")), value.required("append").jsonPrimitive.boolean, context.world)
            "upgrade" -> UpgradeAction(id, actor, unit, value.id("upgradeId"), context.world)
            "cancel-upgrade" -> CancelUpgradeAction(id, actor, unit, value.id("upgradeId"), context.world)
            "repair" -> RepairBuildingAction(id, actor, unit, value.id("targetUnitId"), context.world,
                append = value.required("append").jsonPrimitive.boolean)
            "reclaim" -> ReclaimBuildingAction(id, actor, unit, value.id("targetUnitId"), context.world,
                append = value.required("append").jsonPrimitive.boolean)
            "resume-construction" -> ResumeConstructionAction(id, actor, unit, value.id("targetUnitId"), context.world,
                append = value.required("append").jsonPrimitive.boolean)
            else -> throw InputError("Unsupported action")
        }
        return context.submit(action)
    }
    fun advance(step: Step) = context.advance(step)
    fun observe() = context.observe()
    // The host archive labels these as player views; all fields come from the authorized core DTO.
    fun playerViews(): JsonObject? = input.scenarioDocument["automaticVision"]?.jsonObject?.let { rules ->
        JsonObject(rules.arrayAt("observers").associate { observer ->
            val id = observer.jsonPrimitive.content
            val view = context.observePlayer(context.world, id)
            val capabilities = if (input.core.combatContract == null && input.core.economyContract == null) emptyMap() else mapOf(
                "units" to array(view.units.map { it.json(true) }), "balance" to view.balance?.let(::n),
                "credits" to view.credits?.let(::n),
                "factoryQueues" to JsonObject(view.factoryQueues.associate { it.factoryId to array(it.items.map { item -> obj("typeId" to s(item.typeId), "progress" to n(item.progress)) }) }),
                "upgradeQueues" to JsonObject(view.upgradeQueues.associate { it.unitId to array(it.items.map { item -> obj("actionKey" to s(item.actionKey), "targetTypeId" to s(item.targetTypeId), "progress" to n(item.progress)) }) }))
            // Every sample describes the current authorized projection, including an empty result.
            // A factory can introduce the first named weapon after the initial world was archived.
            val namedWeapons = mapOf(
                "weapons" to array(view.weapons.map { it.json() }),
                "projectiles" to array(view.projectiles.map { obj("token" to s(it.token), "multiplicity" to n(it.multiplicity),
                    "appearance" to s(it.appearance.name),
                    "position" to it.position.json(), "heading" to n(it.heading), "height" to n(it.height),
                    "parentToken" to it.parentToken?.let(::s), "areaRadius" to it.areaRadius?.let(::n),
                    "lightning" to it.lightning?.json()) }),
                "projectileEvents" to array(view.projectileEvents.map { it.json() }))
            id to JsonObject(obj("playerId" to s(view.playerId), "worldId" to s(view.worldId),
                "stepIndex" to n(view.stepIndex), "simulationTime" to n(view.simulationTime),
                "visibleUnitIds" to array(view.units.map { s(it.unitId) }),
                "actionResults" to array(view.actionResults.map { it.json() }),
                "events" to array(view.events.map { it.json() }),
                "knowledge" to view.knowledge?.let { knowledge -> obj(
                    "width" to n(knowledge.width), "height" to n(knowledge.height), "revision" to n(knowledge.revision),
                    "cells" to JsonObject(knowledge.cells.mapIndexed { index, cell ->
                        "c${index % knowledge.width}r${index / knowledge.width}" to obj("visible" to b(cell.visible),
                            "lastKnownBlocked" to cell.lastKnownBlocked?.let(::b),
                            "lastKnownGroundGid" to cell.lastKnownGroundGid?.let(::n))
                    }.toMap())) }).toMutableMap().apply {
                        capabilities.forEach { (key, value) -> put(key, value ?: JsonNull) }
                        putAll(namedWeapons)
                    })
        })
    }
    fun reopen(): Observation {
        when (val result = context.reopen(input.core)) {
            is InitResult.Accepted -> Unit
            is InitResult.Rejected -> throw CoreFailure("Reopen rejected: ${result.error}")
        }
        return initial()
    }
    override fun close() { context.close() }
}

internal fun Position.json() = obj("x" to n(x), "y" to n(y))
internal fun LightningSegment.json() = obj("end" to end.json(), "endHeight" to n(endHeight))
internal fun Event.json() = obj("kind" to s(kind), "unitId" to unitId?.let(::s))
internal fun ActionResult.json(): JsonObject = when (this) {
    is ActionResult.Accepted -> obj("actionId" to s(actionId), "status" to s("accepted"),
        "queuedForStep" to queuedForStep?.let(::n), "events" to array(events.map { it.json() }))
    is ActionResult.Rejected -> obj("actionId" to s(actionId), "status" to s("rejected"),
        "error" to obj("code" to s(error.code), "message" to s(error.message)))
}
internal fun Observation.json(): JsonObject = JsonObject(obj(
    "sampleId" to s(sampleId), "worldId" to s(worldId), "scenarioId" to s(scenarioId),
    "stepIndex" to n(stepIndex), "simulationTime" to n(simulationTime),
    "units" to array(units.map { it.json(combat != null || economy != null) }),
    "events" to array(events.map { it.json() }), "actionResults" to array(actionResults.map { it.json() }),
).toMutableMap().apply {
    combat?.let { state ->
        put("combat", obj("frameCounter" to n(state.frameCounter), "nextObjectId" to n(state.nextObjectId),
            "weapons" to array(state.weapons.map { it.json() })))
        put("projectiles", array(projectiles.map { it.json() }))
        put("projectileEvents", array(projectileEvents.map { obj("kind" to s(it.kind),
            "projectile" to it.projectile.json(), "damage" to it.damage?.let(::n),
            "interceptorUnitId" to it.interceptorUnitId?.let(::s)) }))
    }
    reclaimedUnitIds?.let { put("reclaimedUnitIds", array(it.map(::s))) }
    economy?.let { state -> put("economy", obj("balances" to JsonObject(state.balances.mapValues { n(it.value) }),
        "preciseBalances" to state.preciseBalances?.let { JsonObject(it.mapValues { (_, amount) -> n(amount) }) },
        "queues" to JsonObject(state.queuedProduction.mapValues { (_, items) -> array(items.map { item -> obj(
            "typeId" to s(item.typeId), "unitNonce" to s(item.unitNonce), "progress" to n(item.progress), "cost" to n(item.cost), "rate" to n(item.rate)) }) }))) }
    production?.let { state -> put("production", obj("lastBirthSerial" to n(state.lastBirthSerial), "units" to array(state.units.map { item -> obj(
        "unitId" to s(item.unitId), "producerId" to s(item.producerId), "objectId" to n(item.objectId), "birthRemaining" to n(item.birthRemaining)) }))) }
    upgrades?.let { state -> put("upgrades", JsonObject(state.queues.mapValues { (_, items) -> array(items.map { item -> obj(
        "actionKey" to s(item.actionKey), "targetTypeId" to s(item.targetTypeId), "progress" to n(item.progress), "cost" to n(item.cost), "rate" to n(item.rate)) }) })) }
    put("incomeStates", array(incomeStates.map { obj("unitId" to s(it.unitId), "elapsed" to n(it.elapsed)) }))
    construction?.let { state -> put("construction", obj("clock" to n(state.clock),
        "buildings" to array(state.buildings.map { obj("unitId" to s(it.unitId), "objectId" to n(it.objectId),
            "updateKey" to s(it.updateKey), "progress" to n(it.progress)) }),
        "nonces" to JsonObject(state.nonces.mapValues { (_, values) -> array(values.sorted().map(::s)) }),
        "workers" to array(state.workers.map { obj("unitId" to s(it.unitId), "elapsed" to n(it.elapsed),
            "retryTime" to n(it.retryTime), "preparation" to n(it.preparation), "working" to b(it.working),
            "previousTargetActive" to b(it.previousTargetActive), "singleNodePathCount" to n(it.singleNodePathCount),
            "logicalRequestX" to it.logicalRequestX?.let(::n), "logicalRequestY" to it.logicalRequestY?.let(::n),
            "movementRequested" to b(it.movementRequested), "pendingPathNodeCounts" to array(it.pendingPathNodeCounts.map(::n)),
            "consumedPathNodeCounts" to array(it.consumedPathNodeCounts.map(::n)), "qBeforeDispatch" to n(it.qBeforeDispatch)) }))) }
})
internal fun VisibleUnitWeapon.json() = obj("unitId" to s(unitId), "weaponName" to s(weaponName),
    "turretHeading" to n(turretHeading), "pivot" to pivot.json(), "muzzle" to muzzle.json(),
    "cooldown" to cooldown?.let(::n), "warmup" to warmup?.let(::n), "targetUnitId" to targetUnitId?.let(::s))
internal fun UnitWeaponObservation.json() = obj("unitId" to s(unitId), "weaponName" to weaponName?.let(::s),
    "objectId" to n(objectId), "randomCounter" to n(randomCounter), "offset" to offset.json(),
    "targetUnitId" to targetUnitId?.let(::s), "angularVelocity" to n(angularVelocity),
    "pivot" to pivot?.json(), "muzzle" to muzzle?.json(), "cooldown" to n(cooldown),
    "oddShot" to b(oddShot), "warmup" to n(warmup), "turretHeading" to turretHeading?.let(::n),
    "idle" to idle?.let { obj("baseHeading" to n(it.baseHeading), "timer" to n(it.timer),
        "offset" to n(it.offset), "direction" to n(it.direction), "reached" to b(it.reached), "hold" to n(it.hold)) })
internal fun ProjectileObservation.json() = obj("objectId" to n(objectId), "sourceUnitId" to s(sourceUnitId),
    "appearance" to s(appearance.name),
    "sourceWeaponName" to sourceWeaponName?.let(::s), "targetUnitId" to targetUnitId?.let(::s),
    "position" to position.json(), "heading" to n(heading), "remainingLife" to n(remainingLife),
    "offset" to offset.json(), "scatter" to n(scatter), "height" to n(height), "age" to n(age),
    "deflectionHealth" to n(deflectionHealth), "intercepted" to b(intercepted),
    "ground" to ground?.let { obj("targetPosition" to it.targetPosition.json(), "targetHeight" to n(it.targetHeight),
        "verticalSpeed" to n(it.verticalSpeed), "distanceSquared" to it.distanceSquared?.let(::n)) },
    "missile" to missile?.let { obj("speed" to n(it.speed), "reachedApex" to b(it.reachedApex), "rawAz" to n(it.rawAz)) },
    "areaExpansion" to areaExpansion?.let { obj("remaining" to n(it.remaining), "radius" to n(it.radius),
        "hitUnitIds" to array(it.hitUnitIds.map(::s))) }).let { fields ->
        JsonObject((ballistic?.let { fields + ("ballistic" to obj("speed" to n(it.speed),
            "reachedApex" to b(it.reachedApex), "rawAz" to n(it.rawAz))) } ?: fields) +
            (lightning?.let { mapOf("lightning" to it.json()) } ?: emptyMap()))
    }
internal fun VisibleProjectileEvent.json() = obj("token" to s(token), "kind" to s(kind), "occurrences" to n(occurrences),
    "appearance" to s(appearance.name),
    "position" to position.json(), "heading" to n(heading), "height" to n(height),
    "sourceUnitId" to sourceUnitId?.let(::s), "sourceWeaponName" to sourceWeaponName?.let(::s),
    "targetUnitId" to targetUnitId?.let(::s), "damage" to damage?.let(::n),
    "parentToken" to parentToken?.let(::s), "interceptorUnitId" to interceptorUnitId?.let(::s),
    "areaRadius" to areaRadius?.let(::n), "lightning" to lightning?.json())
internal fun UnitObservation.json(capabilities: Boolean): JsonObject = JsonObject(obj(
    "unitId" to s(unitId), "ownerPlayerId" to s(ownerPlayerId), "typeId" to s(typeId),
    "position" to position.json(), "heading" to n(heading), "speed" to n(speed),
    "angularVelocity" to n(angularVelocity), "moveTarget" to moveTarget?.json(),
    "pathPoints" to array(pathPoints.map { it.json() }), "movementState" to s(movementState.name),
    "constructionProgress" to n(constructionProgress), "constructionTargetId" to constructionTargetId?.let(::s),
    "carriedBy" to carriedBy?.let(::s),
    "transportExit" to transportExit?.let { obj("previousCarrierId" to s(it.previousCarrierId), "remaining" to n(it.remaining)) },
    "tasks" to array(tasks.map { obj("kind" to s(it.kind.name), "actionId" to s(it.actionId),
        "target" to it.target?.json(), "targetUnitId" to it.targetUnitId?.let(::s),
        "atStep" to it.atStep?.let(::n), "buildingTypeId" to it.buildingTypeId?.let(::s),
        "buildingNonce" to it.buildingNonce?.let(::s)) }),
).toMutableMap().apply {
    transport?.let { put("height", n(height)); put("transport", obj(
        "passengerIds" to it.passengerIds?.let { ids -> array(ids.map(::s)) }, "passengerCount" to n(it.passengerCount),
        "occupiedSlots" to n(it.occupiedSlots), "capacity" to n(it.capacity), "unloading" to b(it.unloading),
        "unloadRemaining" to it.unloadRemaining?.let(::n), "canUnload" to b(it.canUnload))) }
    shield?.let { put("shield", obj("current" to n(it.current), "maximum" to n(it.maximum),
        "displayDamage" to n(it.displayDamage))) }
    mechPose?.let { put("mechPose", it.json()) }
    flightPose?.let { put("height", n(height)); put("flightPose", it.json()) }
    waterPose?.let { put("height", n(height)); put("waterPose", obj(
        "reversing" to b(it.reversing), "sinkSpeed" to n(it.sinkSpeed), "settled" to b(it.settled))) }
    submarine?.let { put("height", n(height)); put("submarine", obj("surfaceIntent" to it.surfaceIntent?.let(::b),
        "verticalSpeed" to it.verticalSpeed?.let(::n), "underwater" to b(it.underwater),
        "surfacePresentation" to b(it.surfacePresentation), "maximumSpeed" to n(it.maximumSpeed), "attackRange" to n(it.attackRange))) }
    amphibiousJet?.let { put("height", n(height)); put("amphibiousJet", obj(
        "flyingIntent" to it.flyingIntent?.let(::b), "verticalSpeed" to it.verticalSpeed?.let(::n),
        "underwater" to b(it.underwater), "surfacePresentation" to b(it.surfacePresentation),
        "movementType" to s(it.movementType.name), "maximumSpeed" to n(it.maximumSpeed), "attackRange" to n(it.attackRange),
        "wingFold" to n(it.wingFold), "phase" to n(it.phase), "sinkSpeed" to n(it.sinkSpeed), "settled" to b(it.settled),
        "canDive" to it.canDive?.let(::b))) }
    // This finite loader requires matching declared/source identity, including singular declarations.
    if (typeId == "navalUnderwaterTarget") put("height", n(height))
    if (capabilities) putAll(mapOf("health" to (health?.let(::n) ?: JsonNull),
    "maxHealth" to (maxHealth?.let(::n) ?: JsonNull), "alive" to b(alive), "attackTargetId" to (attackTargetId?.let(::s) ?: JsonNull),
    "pointDefense" to (pointDefense?.let { obj("energy" to n(it.energy), "exhausted" to b(it.exhausted)) } ?: JsonNull))) })

internal fun MechPose.json(): JsonObject = obj(
    "forwardHeading" to n(forwardHeading), "angularVelocity" to n(angularVelocity), "reversing" to b(reversing),
    "legs" to array(legs.map { leg -> obj("index" to n(leg.index), "anchor" to leg.anchor.json(),
        "foot" to leg.foot.json(), "height" to n(leg.height), "heading" to n(leg.heading), "phase" to s(leg.phase.name)) }))

internal fun FlightPose.json(): JsonObject = obj("velocity" to velocity.json(), "height" to n(height),
    "heightPhase" to n(heightPhase), "rotorPhase" to n(rotorPhase), "rotorHeading" to n(rotorHeading),
    "rotorHeightOffset" to n(rotorHeightOffset))
