package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deep coordination module sitting strictly between global [NavigationGuidance] and local
 * 2D kinematic avoidance [LocomotionModule] (ADR-016 / ENG-P017-009 / ENG-P017-010).
 *
 * Responsibilities:
 * - Zero-seed convergence nucleation leadership and inward-convergence angular-momentum damping
 *   in open/terminal basins so multi-directional group moves nucleate cleanly without right-shift
 *   vortexes (ENG-P017-009), without injecting any group/command special-casing into [LocomotionModule].
 * - Stateless, deterministic, rollback-safe, O(1) spatial-bucket neighborhood evaluation.
 */
internal object CohortCoordinator {
    internal data class ConvergenceAdvice(
        val inConvergenceBasin: Boolean = false,
        val hasSettledSeed: Boolean = false,
        val isNucleationLeader: Boolean = false,
        val inwardSqueezed: Boolean = false,
        val dockingWeightFloor: Double = 0.0,
        val speedScale: Double = 1.0,
    ) {
        companion object {
            val NONE = ConvergenceAdvice()
        }
    }

    /**
     * Evaluates centripetal convergence state for a group-move circle unit approaching [goal].
     * Returns [ConvergenceAdvice.NONE] in O(1) outside group convergence basins or for factory
     * doorway fans, claimed scarce bays, and non-circular hulls.
     */
    fun evaluateConvergence(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        goal: Position,
        hardR: Double,
        maxSpeed: Double,
        hasTerrainLosToGoal: Boolean,
        inConfinedPocket: Boolean = false,
    ): ConvergenceAdvice {
        val member = unit.landingMember ?: return ConvergenceAdvice.NONE
        if (member.parked || member.claimed != null) return ConvergenceAdvice.NONE
        if (member.group.noParkingCorridor != null) return ConvergenceAdvice.NONE
        if (inConfinedPocket) return ConvergenceAdvice.NONE
        if (!hasTerrainLosToGoal) return ConvergenceAdvice.NONE
        if (unit.definition.movementCollision !is MovementCollisionShape.Circle) return ConvergenceAdvice.NONE
        val layer = UnitContact.layer(unit) ?: return ConvergenceAdvice.NONE

        val groupSize = max(1, member.group.members.size)
        if (!member.group.dynamicCluster && groupSize <= 1) return ConvergenceAdvice.NONE

        val dx = goal.x - unit.position.x
        val dy = goal.y - unit.position.y
        val distToGoal = hypot(dx, dy)
        val basinR = min(220.0, max(hardR * 8.5, sqrt(groupSize.toDouble()) * hardR * 2.4 + 24.0))
        if (distToGoal > basinR) return ConvergenceAdvice.NONE

        val radX = if (distToGoal > 1e-5) dx / distToGoal else 0.0
        val radY = if (distToGoal > 1e-5) dy / distToGoal else 0.0

        val spatialLayer = SpatialLayer.fromContact(layer)
        val queryR = min(basinR + hardR * 2.0, max(hardR * 7.5, distToGoal + hardR * 3.5))
        val nearby = host.spatialIndex.queryBounds(
            spatialLayer,
            goal.x - queryR, goal.x + queryR,
            goal.y - queryR, goal.y + queryR,
            useBufferRadius = false,
        )

        var settledSeedCount = 0
        var activePeerCount = 0
        var closerActiveCount = 0
        var opposingPeerCount = 0
        var blockingCorePeers = 0
        val group = member.group

        for (i in 0 until nearby.size) {
            val other = nearby[i]
            if (other === unit || !other.alive) continue
            val otherMember = other.landingMember ?: continue
            if (otherMember.group !== group) continue
            if (otherMember.parked) {
                settledSeedCount++
                continue
            }
            if (other.moveTarget == null) continue
            val odx = goal.x - other.position.x
            val ody = goal.y - other.position.y
            val otherDist = hypot(odx, ody)
            if (otherDist > basinR + hardR * 2.0) continue

            activePeerCount++
            if (otherDist < distToGoal - 0.5 ||
                (abs(otherDist - distToGoal) <= 0.5 && other.updateKey < unit.updateKey)
            ) {
                closerActiveCount++
            }

            if (distToGoal > 1e-5 && otherDist > 1e-5) {
                val oradX = odx / otherDist
                val oradY = ody / otherDist
                // Opposing or cross-angle inward convergence (< 0.25 cosine => > 75 deg apart)
                if (radX * oradX + radY * oradY < 0.25) {
                    opposingPeerCount++
                }
            }

            val sepX = other.position.x - unit.position.x
            val sepY = other.position.y - unit.position.y
            val sepDist = hypot(sepX, sepY)
            val otherR = max(1.0, other.definition.movementCollision.boundingRadius)
            val contactReach = hardR + otherR + max(10.0, hardR * 0.95)
            if (sepDist <= contactReach && otherDist <= distToGoal + hardR * 0.85) {
                val fwdDot = if (sepDist > 1e-5) (sepX * radX + sepY * radY) / sepDist else 1.0
                if (fwdDot >= 0.15) {
                    blockingCorePeers++
                }
            }
        }

        val hasSettledSeed = settledSeedCount > 0
        // Once a static core seed has anchored inside the convergence basin, vortex deadlock is broken:
        // hand control seamlessly back to TerminalLanding's contagious settling and LocomotionModule's
        // full mixed-size overtaking without capping en-route dockingFade or speed.
        if (hasSettledSeed || activePeerCount == 0) {
            return ConvergenceAdvice.NONE
        }

        // Measure own tangential velocity component relative to the goal center.
        val headingVec = ScreenPlane.displacement(unit.heading, abs(unit.speed))
        val tangentialRatio = if (maxSpeed > 1e-5 && distToGoal > 1e-5) {
            val tang = abs(headingVec.x * (-radY) + headingVec.y * radX)
            (tang / maxSpeed).coerceIn(0.0, 1.0)
        } else 0.0

        val multiDirectionalOrSwirl = opposingPeerCount >= 1 || (activePeerCount >= 2 && tangentialRatio > 0.48)
        if (!multiDirectionalOrSwirl) {
            return ConvergenceAdvice.NONE
        }
        val isNucleationLeader = closerActiveCount == 0
        val inwardSqueezed = blockingCorePeers >= 1 ||
            (closerActiveCount <= 1 && opposingPeerCount >= 1 && distToGoal <= max(hardR * 3.0, 32.0))

        // Smoothly raise kinematic terminalDockingWeight inside zero-seed multi-directional
        // convergence basins so LocomotionModule naturally damps head-on rightShift vortex energy
        // while keeping full 2D ORCA collision avoidance & static wall half-planes intact.
        val prox = ((basinR - distToGoal) / max(1.0, basinR * 0.65)).coerceIn(0.0, 1.0)
        val dockingWeightFloor = when {
            opposingPeerCount >= 1 || distToGoal <= max(hardR * 5.0, 58.0) ->
                (0.84 + 0.16 * prox).coerceIn(0.84, 1.0)
            else ->
                (0.72 + 0.28 * prox).coerceIn(0.72, 1.0)
        }

        // Centripetal speed damping while zero-seed nucleation is in progress:
        // - Zero-seed leader approaches cleanly without interference so the first seed anchors immediately;
        // - Outer / opposing peers inside the inner core yield gently and bleed tangential momentum so they
        //   never knock the nucleating leader out of the center or spin into an orbital ring.
        val speedScale = when {
            isNucleationLeader -> {
                if (tangentialRatio > 0.50 && distToGoal <= max(hardR * 3.5, 40.0)) {
                    (1.0 - 0.35 * tangentialRatio).coerceIn(0.60, 1.0)
                } else 1.0
            }
            closerActiveCount >= 1 && distToGoal <= max(hardR * 5.5, 66.0) -> {
                val ringFrac = ((distToGoal - hardR * 1.8) / max(1.0, hardR * 3.7)).coerceIn(0.0, 1.0)
                val baseYield = 0.42 + 0.40 * ringFrac
                val tangDamp = 1.0 - 0.35 * tangentialRatio
                (baseYield * tangDamp).coerceIn(0.30, 0.85)
            }
            tangentialRatio > 0.45 ->
                (1.0 - 0.35 * tangentialRatio).coerceIn(0.55, 1.0)
            else -> 1.0
        }

        return ConvergenceAdvice(
            inConvergenceBasin = true,
            hasSettledSeed = false,
            isNucleationLeader = isNucleationLeader,
            inwardSqueezed = inwardSqueezed,
            dockingWeightFloor = dockingWeightFloor,
            speedScale = speedScale,
        )
    }
}
