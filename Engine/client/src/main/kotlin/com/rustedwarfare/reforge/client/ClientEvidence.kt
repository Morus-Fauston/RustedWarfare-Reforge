package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*

fun interface ClientEvidence {
    fun record(kind: String, values: Map<String, Any?>)

    /** Menu state only, from checked in-memory rows. This never scans sources or claims rendered pixels. */
    fun recordVisualAssetMenu(state: VisualAssetSettingsSession) {
        if (this === NONE) return
        record("visual-assets-menu", mapOf("revision" to state.revision, "busy" to state.busy, "operation" to state.operationLabel,
            "canStart" to state.canStart, "hasUnappliedChanges" to state.hasUnappliedChanges,
            "unsavedCatalog" to state.hasUnsavedCatalog, "temporary" to state.selection.temporary,
            "error" to state.error, "diagnostic" to state.diagnostic, "selectedId" to state.selectedPackId,
            "enabledIds" to state.selection.packs.enabled, "catalogIds" to state.selection.packs.catalog.map { it.id },
            "rows" to state.rows.map { row -> mapOf("id" to row.id, "name" to row.metadata?.name,
                "enabled" to row.enabled, "canEnable" to row.canEnable, "hasIcon" to (row.icon != null), "error" to row.error) }))
    }

    /** One ready shape for both entry points, from the frozen prepared generation only. */
    fun recordVisualAssetsReady(visuals: PreparedVisualAssets, generation: Long? = null) {
        if (this === NONE) return
        val pack = visuals.resourcePack
        val metadata = pack.metadata
        record("visual-assets-ready", mapOf("source" to visuals.selection.source.value,
            "profile" to visuals.profileId, "identity" to visuals.identity,
            "units" to visuals.units.keys.toList(), "terrain" to visuals.terrain.keys.toList(),
            "resourcePack" to mapOf("id" to metadata.id.value, "name" to metadata.name, "description" to metadata.description,
                "icon" to metadata.icon.value, "formatVersion" to metadata.formatVersion,
                "requiresResourceFormat" to metadata.requiresResourceFormat,
                "resources" to pack.resources.mapKeys { it.key.value }.mapValues { it.value.toString() })) +
            (generation?.let { mapOf("generation" to it) } ?: emptyMap()) +
            (visuals.resolvedSounds?.let { stack -> mapOf("soundStack" to mapOf(
                "packOrder" to stack.packOrder,
                "events" to stack.events.mapKeys { it.key.value }.mapValues { (key, mapping) ->
                    val id = ResourceId.parse(key)
                    mapOf("providerId" to stack.eventProviders[id], "totalWeight" to mapping.totalWeight,
                        "candidates" to mapping.candidates.mapIndexed { index, candidate -> mapOf(
                            "sound" to candidate.sound.value, "weight" to candidate.weight,
                            "providerId" to (stack.candidateProviders[id]?.getOrNull(index) ?: stack.eventProviders[id])) }) +
                        (if (mapping.candidates.size == 1) mapOf("sound" to mapping.sound.value) else emptyMap()) },
                "resources" to stack.sounds.mapKeys { it.key.value }.mapValues { (key, sound) -> mapOf(
                    "providerId" to stack.soundProviders[ResourceId.parse(key)], "path" to sound.path.toString(),
                    "sha256" to sound.sha256, "bytes" to sound.bytes, "channels" to sound.channels,
                    "sampleRate" to sound.sampleRate, "frames" to sound.frames) })) } ?: emptyMap()) +
            (visuals.resolvedImages?.let { stack -> mapOf("resourceStack" to mapOf(
                "packOrder" to stack.packOrder,
                "packages" to stack.layers.map { layer -> mapOf("id" to layer.metadata.id.value,
                    "name" to layer.metadata.name, "description" to layer.metadata.description,
                    "icon" to layer.metadata.icon.value, "formatVersion" to layer.metadata.formatVersion,
                    "requiresResourceFormat" to layer.metadata.requiresResourceFormat, "identity" to layer.identity,
                    "sources" to layer.sources.map { mapOf("path" to it.path.toString(), "bytes" to it.bytes, "sha256" to it.sha256) }) },
                "resources" to stack.images.mapKeys { it.key.value }.mapValues { (key, image) -> mapOf(
                    "providerId" to stack.providers.getValue(ResourceId.parse(key)), "path" to image.path.toString(),
                    "declaredFile" to image.declaration.file, "frameWidth" to image.declaration.frameWidth,
                    "frameHeight" to image.declaration.frameHeight, "totalFrames" to image.declaration.totalFrames,
                    "pixelWidth" to image.pixelWidth, "pixelHeight" to image.pixelHeight,
                    // This means a prepared binding exists, not that the scene spawned or drew that object.
                    "bound" to (ResourceId.parse(key) in pack.resources)) } )) } ?: emptyMap()))
    }

    /** The successful load, rather than a second parse of the current input directory. */
    fun recordLoadReady(generation: Long, loaded: LoadedSandbox) {
        if (this !== NONE) record("load-ready", mapOf("generation" to generation,
            "mapWidth" to loaded.scenario.map.widthTiles, "mapHeight" to loaded.scenario.map.heightTiles,
            "blockedCount" to loaded.scenario.map.blocking.blockedCount))
    }

    /** Legacy sinks keep the original field shape; typed sinks can defer immutable knowledge. */
    fun recordObservation(phase: String, observation: PlayerObservation, generation: Long? = null) {
        if (this !== NONE) record("observation", mapOf("phase" to phase, "sample" to observation.fields()) +
            (generation?.let { mapOf("generation" to it) } ?: emptyMap()))
    }

    companion object { val NONE = ClientEvidence { _, _ -> } }
}

/** Preserve the typed path and the existing generation override through the window adapter. */
internal fun ClientEvidence.withGeneration(generation: Long): ClientEvidence {
    if (this === ClientEvidence.NONE) return this
    val sink = this
    val fixedGeneration = generation
    return object : ClientEvidence {
        override fun record(kind: String, values: Map<String, Any?>) =
            sink.record(kind, values + ("generation" to generation))
        override fun recordObservation(phase: String, observation: PlayerObservation, generation: Long?) =
            sink.recordObservation(phase, observation, fixedGeneration)
        override fun recordLoadReady(generation: Long, loaded: LoadedSandbox) =
            sink.recordLoadReady(fixedGeneration, loaded)
    }
}

/** Keep the public sink contract; the canonical disabled sink needs no payload preparation. */
internal inline fun ClientEvidence.recordWhenEnabled(kind: String, values: () -> Map<String, Any?>) {
    if (this !== ClientEvidence.NONE) record(kind, values())
}

internal inline fun preserveFailure(primary: Throwable, action: () -> Unit) {
    try { action() } catch (secondary: Throwable) {
        if (secondary !== primary) primary.addSuppressed(secondary)
    }
}

internal fun ClientEvidence.recordFailure(kind: String, primary: Throwable) {
    preserveFailure(primary) { recordWhenEnabled(kind) { mapOf("message" to primary.toString()) } }
}

internal fun Position.fields(): Map<String, Any?> = mapOf("x" to x, "y" to y)
internal fun LightningSegment.fields(): Map<String, Any?> = mapOf("end" to end.fields(), "endHeight" to endHeight)
internal fun BeamSegment.fields(): Map<String, Any?> = mapOf("end" to end.fields(), "endHeight" to endHeight,
    "textureOffset" to textureOffset)
private fun projectileVisualFields(appearance: ProjectileAppearance, lightning: LightningSegment?, beam: BeamSegment?): Map<String, Any?> =
    buildMap {
        if (appearance != ProjectileAppearance.STANDARD) put("appearance", appearance.name)
        if (lightning != null) put("lightning", lightning.fields())
        if (beam != null) put("beam", beam.fields())
    }
internal fun FlightPose.fields(): Map<String, Any?> = mapOf(
    "velocity" to velocity.fields(), "height" to height, "heightPhase" to heightPhase,
    "rotorPhase" to rotorPhase, "rotorHeading" to rotorHeading, "rotorHeightOffset" to rotorHeightOffset) +
    (fall?.let { mapOf("fall" to mapOf("speed" to it.speed, "landed" to it.landed,
        "landedInWater" to it.landedInWater, "settled" to it.settled)) } ?: emptyMap())
internal fun MechPose.fields(): Map<String, Any?> = mapOf(
    "forwardHeading" to forwardHeading, "angularVelocity" to angularVelocity, "reversing" to reversing,
    "legs" to legs.map { leg -> mapOf("index" to leg.index, "anchor" to leg.anchor.fields(),
        "foot" to leg.foot.fields(), "height" to leg.height, "heading" to leg.heading, "phase" to leg.phase.name) })
internal fun VisibleUnitWeapon.fields(): Map<String, Any?> = mapOf(
    "unitId" to unitId, "weaponName" to weaponName, "turretHeading" to turretHeading,
    "pivot" to pivot.fields(), "muzzle" to muzzle.fields(), "cooldown" to cooldown,
    "warmup" to warmup, "targetUnitId" to targetUnitId)
internal fun ActionResult.fields(): Map<String, Any?> = when (this) {
    is ActionResult.Accepted -> mapOf("actionId" to actionId, "accepted" to true,
        "events" to events.map { mapOf("kind" to it.kind, "unitId" to it.unitId) })
    is ActionResult.Rejected -> mapOf("actionId" to actionId, "accepted" to false,
        "code" to error.code, "message" to error.message)
}

internal fun UnitObservation.fields(): Map<String, Any?> {
    val unit = this
    val values = linkedMapOf<String, Any?>(
        "unitId" to unit.unitId, "ownerPlayerId" to unit.ownerPlayerId, "typeId" to unit.typeId,
        "position" to unit.position.fields(), "heading" to unit.heading, "speed" to unit.speed,
        "angularVelocity" to unit.angularVelocity, "moveTarget" to unit.moveTarget?.fields(),
        "pathPoints" to unit.pathPoints.map { it.fields() }, "movementState" to unit.movementState.name.lowercase(),
        "health" to unit.health, "maxHealth" to unit.maxHealth, "alive" to unit.alive,
        "constructionProgress" to unit.constructionProgress, "constructionTargetId" to unit.constructionTargetId,
        "attackTargetId" to unit.attackTargetId, "turretHeading" to unit.turretHeading, "cooldown" to unit.cooldown,
        "height" to unit.height,
        "pointDefense" to unit.pointDefense?.let { mapOf("energy" to it.energy, "exhausted" to it.exhausted) },
        "tasks" to unit.tasks.map { task -> mapOf("kind" to task.kind.name.lowercase(),
            "target" to task.target?.fields(), "targetUnitId" to task.targetUnitId,
            "automaticRepair" to task.automaticRepair, "extraWorkRange" to task.extraWorkRange) +
                (task.buildingTypeId?.let { mapOf("buildingTypeId" to it) } ?: emptyMap()) },
    )
    unit.mechPose?.let { values["mechPose"] = it.fields() }
    unit.shield?.let { values["shield"] = mapOf("current" to it.current, "maximum" to it.maximum, "displayDamage" to it.displayDamage) }
    unit.energy?.let { values["energy"] = mapOf("current" to it.current, "maximum" to it.maximum, "recharging" to it.recharging) }
    unit.nuclearDoors?.let { values["nuclearDoors"] = it }
    unit.attachment?.let {
        values["attachment"] = mapOf("parentUnitId" to it.parentUnitId, "slot" to it.slot, "empty" to it.empty)
    }
    unit.attachmentOverview?.let { overview ->
        values["attachmentOverview"] = mapOf("selectedSlot" to overview.selectedSlot, "slots" to overview.slots.map {
            mapOf("number" to it.number, "unitId" to it.unitId, "typeId" to it.typeId, "progress" to it.progress)
        })
    }
    unit.unitActions?.let { state -> values["unitActions"] = mapOf("automaticCooldown" to state.automaticCooldown,
        "actions" to state.actions.associate { action -> action.name to mapOf("queueIndex" to action.queueIndex,
            "progress" to action.progress, "cooldownRemaining" to action.cooldownRemaining, "available" to action.available) }) +
        (state.stockpile?.let { mapOf("stockpile" to it) } ?: emptyMap()) +
        (state.interceptorOnline?.let { mapOf("interceptorOnline" to it) } ?: emptyMap()) +
        (if (state.stockpile != null || state.queue.isNotEmpty()) mapOf("queue" to state.queue.map {
            mapOf("name" to it.name, "progress" to it.progress, "target" to it.target?.fields()) }) else emptyMap()) }
    unit.submarine?.let { values["submarine"] = mapOf("surfaceIntent" to it.surfaceIntent,
        "verticalSpeed" to it.verticalSpeed, "underwater" to it.underwater, "surfacePresentation" to it.surfacePresentation,
        "maximumSpeed" to it.maximumSpeed, "attackRange" to it.attackRange) }
    unit.flightPose?.let { values["flightPose"] = it.fields() }
    unit.amphibiousJet?.let { values["amphibiousJet"] = mapOf("flyingIntent" to it.flyingIntent,
        "verticalSpeed" to it.verticalSpeed, "underwater" to it.underwater, "surfacePresentation" to it.surfacePresentation,
        "movementType" to it.movementType.name, "maximumSpeed" to it.maximumSpeed, "attackRange" to it.attackRange,
        "wingFold" to it.wingFold, "phase" to it.phase, "sinkSpeed" to it.sinkSpeed, "settled" to it.settled,
        "canDive" to it.canDive) }
    unit.hoverPose?.let { values["hoverPose"] = mapOf("velocity" to it.velocity.fields(), "heightPhase" to it.heightPhase) }
    unit.waterPose?.let { values["waterPose"] = mapOf(
        "reversing" to it.reversing, "sinkSpeed" to it.sinkSpeed, "settled" to it.settled) }
    unit.carriedBy?.let { values["carriedBy"] = it }
    unit.transport?.let { values["transport"] = mapOf("passengerIds" to it.passengerIds,
        "passengerCount" to it.passengerCount, "occupiedSlots" to it.occupiedSlots, "capacity" to it.capacity,
        "unloading" to it.unloading, "unloadRemaining" to it.unloadRemaining, "canUnload" to it.canUnload) }
    unit.transportExit?.let { values["transportExit"] = mapOf("previousCarrierId" to it.previousCarrierId, "remaining" to it.remaining) }
    return values
}

internal fun Observation.fields(): Map<String, Any?> = mapOf(
    "sampleId" to sampleId, "worldId" to worldId, "scenarioId" to scenarioId,
    "stepIndex" to stepIndex, "simulationTime" to simulationTime,
    "units" to units.map { it.fields() },
    "events" to events.map { mapOf("kind" to it.kind, "unitId" to it.unitId) },
    "actionResults" to actionResults.map { it.fields() },
    "construction" to construction?.let { value -> mapOf("clock" to value.clock,
        "buildings" to value.buildings.map { mapOf("unitId" to it.unitId, "objectId" to it.objectId,
            "updateKey" to it.updateKey, "progress" to it.progress) },
        "workers" to value.workers.map { mapOf("unitId" to it.unitId, "elapsed" to it.elapsed,
            "retryTime" to it.retryTime, "preparation" to it.preparation, "working" to it.working,
            "previousTargetActive" to it.previousTargetActive, "singleNodePathCount" to it.singleNodePathCount,
            "logicalRequestX" to it.logicalRequestX, "logicalRequestY" to it.logicalRequestY,
            "movementRequested" to it.movementRequested, "pendingPathNodeCounts" to it.pendingPathNodeCounts,
            "consumedPathNodeCounts" to it.consumedPathNodeCounts, "qBeforeDispatch" to it.qBeforeDispatch) }, "nonces" to value.nonces) },
) + (reclaimedUnitIds?.let { mapOf("reclaimedUnitIds" to it) } ?: emptyMap())

internal fun PlayerObservation.fields(includeKnowledge: Boolean = true): Map<String, Any?> =
    Observation(sampleId, worldId, scenarioId, stepIndex, simulationTime, units, events, actionResults).fields() + mapOf(
        "playerId" to playerId,
        "visionEpoch" to vision?.epoch,
        "projectiles" to projectiles.map { mapOf<String, Any?>("token" to it.token, "multiplicity" to it.multiplicity,
            "position" to it.position.fields(), "heading" to it.heading, "height" to it.height, "parentToken" to it.parentToken) +
                (it.areaRadius?.let { radius -> mapOf("areaRadius" to radius) } ?: emptyMap()) +
                projectileVisualFields(it.appearance, it.lightning, it.beam) },
        "projectileEvents" to projectileEvents.map { mapOf<String, Any?>("token" to it.token, "kind" to it.kind,
            "occurrences" to it.occurrences, "position" to it.position.fields(), "heading" to it.heading,
            "height" to it.height, "sourceUnitId" to it.sourceUnitId, "targetUnitId" to it.targetUnitId,
            "damage" to it.damage, "parentToken" to it.parentToken, "interceptorUnitId" to it.interceptorUnitId) +
                (it.areaRadius?.let { radius -> mapOf("areaRadius" to radius) } ?: emptyMap()) +
                (it.sourceWeaponName?.let { name -> mapOf("sourceWeaponName" to name) } ?: emptyMap()) +
                projectileVisualFields(it.appearance, it.lightning, it.beam) },
        "knowledge" to (if (includeKnowledge) knowledge?.cells?.map { mapOf("visible" to it.visible,
            "blocked" to it.lastKnownBlocked, "gid" to it.lastKnownGroundGid) } else null),
    ) + (balance?.let { mapOf("balance" to it, "factoryQueues" to factoryQueues.map { factory ->
        mapOf("factoryId" to factory.factoryId, "items" to factory.items.map { item ->
            mapOf("typeId" to item.typeId, "progress" to item.progress) }) },
        "upgradeQueues" to upgradeQueues.map { queue -> mapOf("unitId" to queue.unitId, "items" to queue.items.map { item ->
            mapOf("actionKey" to item.actionKey, "targetTypeId" to item.targetTypeId, "progress" to item.progress) }) }) } ?: emptyMap()) +
        (credits?.let { mapOf("credits" to it) } ?: emptyMap()) +
        mapOf("weapons" to weapons.map { it.fields() })
