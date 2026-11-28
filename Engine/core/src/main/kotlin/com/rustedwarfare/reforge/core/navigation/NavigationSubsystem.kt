package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.MutableUnit

/**
 * Narrow Interface #3 (SPEC §2.1 / ADR-016 / ENG-P017-006):
 * Unified navigation subsystem facade exposed by `core.navigation` to `SimulationContext`,
 * bundling spatial indexing, flow fields, global guidance, landing occupancy, and per-unit
 * movement orchestration behind narrow data contracts.
 */
internal interface NavigationSubsystem {
    val spatialIndex: WorldSpatialIndex
    val flowFields: ChunkedFlowFieldAdapter
    val navigationGuidance: NavigationGuidance
    val landingOccupancy: LandingOccupancy

    fun describeLocomotion(unit: MutableUnit, tileWidth: Int = 20, tileHeight: Int = 20): LocomotionDescriptor =
        UnitLocomotion.describe(unit, tileWidth, tileHeight)

    fun authorizedTerrain(unit: MutableUnit, map: MapDefinition): AuthorizedNavigationTerrain

    fun relocate(unit: MutableUnit) {
        spatialIndex.relocate(unit)
    }

    fun advanceUnit(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
        executionStep: Long,
        movePlanning: MovePlanning,
        pendingFlowStart: Set<String>,
        translations: UnitContact.Translations,
        motionSamples: MutableList<MotionStepSample>?,
        routeContinuationSamples: MutableList<RouteContinuationSample>?,
        passageWaiting: Boolean,
        departureWaiting: Boolean,
    ) {
        UnitMovementOrchestrator.advanceUnit(
            host = host,
            unit = unit,
            map = map,
            delta = delta,
            executionStep = executionStep,
            movePlanning = movePlanning,
            pendingFlowStart = pendingFlowStart,
            translations = translations,
            motionSamples = motionSamples,
            routeContinuationSamples = routeContinuationSamples,
            passageWaiting = passageWaiting,
            departureWaiting = departureWaiting,
        )
    }
}
