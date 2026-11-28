package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deep coordination module sitting strictly between global [NavigationGuidance] and local
 * 2D kinematic avoidance [LocomotionModule] (ADR-016 / ENG-P017-009 / ENG-P017-010).
 *
 * Responsibilities:
 * 1. Zero-seed convergence nucleation leadership and inward-convergence angular-momentum damping
 *    in open/terminal basins so multi-directional group moves nucleate cleanly without right-shift
 *    vortexes (ENG-P017-009), without injecting any group/command special-casing into [LocomotionModule].
 * 2. Explicit `COHORT_ESCORT` (Windows `Alt` move) group traversal coordination (ENG-P017-010):
 *    - Generic zero-unit-ID wavefront escort bias $\Delta \phi \ge 0$ derived solely from durability
 *      (`maxHealth + maxShield`) and effective max weapon range;
 *    - Smooth 1D wavefront potential pacing so slow durable vanguard naturally overtakes ahead while
 *      faster/fragile/ranged escorts pace behind and trailing peers stay at full speed;
 *    - Narrow-throat single-file suspension and post-exit regrouping;
 *    - Cross-layer air-ground remaining-progress pacing;
 *    - Immediate dynamic decoupling on death, re-order, arrival, or sustained genuine blockage;
 *    - Active yielding flag so deliberate escort deceleration is exempt from stall replanning and
 *      2s red-highlight false positives.
 *
 * Stateless, deterministic, rollback-safe, zero heap allocation on hot 60Hz paths.
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

    internal data class EscortPacingAdvice(
        val active: Boolean = false,
        val speedCap: Double = Double.POSITIVE_INFINITY,
        val activelyYielding: Boolean = false,
    ) {
        companion object {
            val NONE = EscortPacingAdvice()
        }
    }

    private const val STALL_DECOUPLE_TICKS = 90L

    /**
     * Computes generic, zero-unit-ID longitudinal wavefront escort biases $\Delta \phi_i \ge 0$
     * (in world units) purely from physical durability (`maxHealth + maxShield`) and effective
     * combat range. Durable short-range/vanguard units receive `0.0` (front of wavefront), while
     * more fragile / longer-range / unarmed support units receive positive rear bias so vanguard
     * naturally leads in front during `COHORT_ESCORT` marches. Homogeneous squads all receive `0.0`.
     */
    fun computeEscortBiases(members: Collection<MutableUnit>): Map<String, Double> {
        if (members.size < 2) return emptyMap()
        val durabilities = LinkedHashMap<String, Double>(members.size)
        val ranges = LinkedHashMap<String, Double>(members.size)
        var minDur = Double.POSITIVE_INFINITY
        var maxDur = Double.NEGATIVE_INFINITY
        var minRng = Double.POSITIVE_INFINITY
        var maxRng = Double.NEGATIVE_INFINITY
        var maxRadius = 8.0

        for (u in members) {
            val def = u.definition
            val dur = (def.maximumHealth ?: 100.0) + (def.shield?.maximum?.toDouble() ?: 0.0)
            val rng = effectiveMaxWeaponRange(def)
            durabilities[u.unitId] = dur
            ranges[u.unitId] = rng
            if (rng >= 0.0) {
                if (rng < minRng) minRng = rng
                if (rng > maxRng) maxRng = rng
            }
            val r = def.movementCollision.boundingRadius
            if (r > maxRadius) maxRadius = r
        }

        val hasArmed = minRng.isFinite() && maxRng.isFinite()
        // When armed units are present, calibrate the combat-vanguard durability gradient across armed
        // units only so a high-HP unarmed transport/engineer never pushes armed fighters to the rear.
        for (u in members) {
            val rng = ranges.getValue(u.unitId)
            if (hasArmed && rng < 0.0) continue
            val dur = durabilities.getValue(u.unitId)
            if (dur < minDur) minDur = dur
            if (dur > maxDur) maxDur = dur
        }
        val effectiveMinRng = if (hasArmed) minRng else 0.0
        val effectiveMaxRng = if (hasArmed) maxRng else 0.0
        val hasDurGradient = minDur.isFinite() && maxDur.isFinite() && (maxDur > minDur * 1.15 + 1e-3)
        val hasRngGradient = hasArmed && (effectiveMaxRng > effectiveMinRng + 20.0)
        val hasUnarmedMix = hasArmed && ranges.values.any { it < 0.0 }
        if (!hasDurGradient && !hasRngGradient && !hasUnarmedMix) {
            // Homogeneous squad: zero artificial offset, all members share the wavefront cleanly.
            return emptyMap()
        }

        val escortSpan = (max(78.0, maxRadius * 4.5)).coerceIn(78.0, 128.0)
        val result = LinkedHashMap<String, Double>(members.size)
        for (u in members) {
            val dur = durabilities.getValue(u.unitId)
            val rng = ranges.getValue(u.unitId)
            val fragility = if (hasDurGradient && (!hasArmed || rng >= 0.0)) {
                (1.0 - (dur - minDur) / max(1.0, maxDur - minDur)).coerceIn(0.0, 1.0)
            } else 0.0
            val rangedness = when {
                rng < 0.0 -> if (hasArmed) 0.85 else 0.0
                hasRngGradient -> ((rng - effectiveMinRng) / max(1.0, effectiveMaxRng - effectiveMinRng)).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            val hasRangeFactor = hasRngGradient || hasUnarmedMix
            val baseRearWeight = when {
                hasDurGradient && hasRangeFactor -> (0.60 * fragility + 0.40 * rangedness).coerceIn(0.0, 1.0)
                hasDurGradient -> fragility
                else -> rangedness
            }
            // In an armed+unarmed mix, unarmed support/transport/engineer units always trail armed vanguard.
            val rearWeight = if (hasUnarmedMix && rng < 0.0) max(0.75, baseRearWeight) else baseRearWeight
            val bias = if (rearWeight <= 0.20) 0.0 else ((rearWeight - 0.20) / 0.80) * escortSpan
            if (bias > 1e-3) {
                result[u.unitId] = bias
            }
        }
        return result
    }

    private fun effectiveMaxWeaponRange(def: UnitDefinition): Double {
        val combat = def.combat ?: return -1.0
        var maxR = combat.attackRange
        val named = combat.namedWeapons?.ordered
        if (named != null) {
            for (weapon in named) {
                val mr = weapon.maximumRange
                if (mr != null && mr > maxR) maxR = mr
            }
        }
        return maxR
    }

    /**
     * Computes `COHORT_ESCORT` longitudinal wavefront & cross-layer escort pacing for [unit].
     * Returns [EscortPacingAdvice.NONE] in O(1) for all non-escort (`INDEPENDENT`) orders.
     */
    fun evaluateEscortPacing(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        goal: Position,
        maxSpeed: Double,
        remainingDistance: Double,
        isAir: Boolean,
    ): EscortPacingAdvice {
        val contract = unit.motionContract ?: return EscortPacingAdvice.NONE
        if (contract.traversalMode != TraversalMode.COHORT_ESCORT) return EscortPacingAdvice.NONE
        val member = unit.landingMember ?: return EscortPacingAdvice.NONE
        if (member.parked) return EscortPacingAdvice.NONE
        val group = member.group
        if (group.traversalMode != TraversalMode.COHORT_ESCORT || group.members.size < 2) {
            return EscortPacingAdvice.NONE
        }
        if (maxSpeed <= 1e-5) return EscortPacingAdvice.NONE

        val ownHardR = max(1.0, unit.definition.movementCollision.boundingRadius)
        // Release escort pacing inside final terminal landing zone so everyone docks cleanly.
        val terminalReleaseR = max(ownHardR * 3.8, 42.0)
        if (hypot(goal.x - unit.position.x, goal.y - unit.position.y) <= terminalReleaseR) {
            return EscortPacingAdvice.NONE
        }

        // Narrow-throat single-file suspension: never brake inside a narrow chokepoint or its
        // immediate outlet apron; let units pass through single-file and regroup cleanly beyond it.
        if (!isAir && isInsideOrExitingNarrowThroat(unit, terrain, ownHardR)) {
            return EscortPacingAdvice.NONE
        }

        val ownMoveType = UnitLocomotion.movement(unit).type
        val ownBias = group.cohortEscortBiasByUnit[unit.unitId] ?: 0.0

        // Cross-layer air-ground escort: air units pace against active surface/water/hover ground peers.
        if (isAir) {
            var activeSurfaceCount = 0
            var slowestGroundRemRatio = -1.0
            var slowestGroundRemDist = -1.0
            var slowestGroundSpeed = Double.POSITIVE_INFINITY

            for (peerId in group.trafficOrder) {
                if (peerId == unit.unitId) continue
                val peer = host.units[peerId] ?: continue
                if (!isActiveCohortPeer(peer, group)) continue
                val peerMove = UnitLocomotion.movement(peer)
                if (peerMove.type == MovementType.AIR || peerMove.type == MovementType.NONE) continue
                activeSurfaceCount++
                val peerDist = hypot(goal.x - peer.position.x, goal.y - peer.position.y)
                val peerStart = max(60.0, group.cohortStartDistances[peerId] ?: peerDist)
                val remRatio = (peerDist / peerStart).coerceIn(0.0, 1.5)
                if (remRatio > slowestGroundRemRatio) slowestGroundRemRatio = remRatio
                if (peerDist > slowestGroundRemDist) slowestGroundRemDist = peerDist
                if (peerMove.moveSpeed > 0.05 && peerMove.moveSpeed < slowestGroundSpeed) {
                    slowestGroundSpeed = peerMove.moveSpeed
                }
            }

            if (activeSurfaceCount == 0 || !slowestGroundSpeed.isFinite()) {
                // Ground force died, arrived, or decoupled: air escort immediately resumes full speed!
                return EscortPacingAdvice.NONE
            }
            val ownDist = hypot(goal.x - unit.position.x, goal.y - unit.position.y)
            val ownStart = max(60.0, group.cohortStartDistances[unit.unitId] ?: ownDist)
            val ownRatio = (ownDist / ownStart).coerceIn(0.0, 1.5)
            val ratioLead = slowestGroundRemRatio - ownRatio
            val distLead = (slowestGroundRemDist + ownBias * 0.5) - ownDist
            val effectiveLead = max(distLead, ratioLead * ownStart)
            if (effectiveLead <= 4.0) {
                return EscortPacingAdvice.NONE
            }
            val anchorSpeed = min(maxSpeed, slowestGroundSpeed)
            val cap = computePacingSpeedCap(effectiveLead, maxSpeed, anchorSpeed, isEscortRear = ownBias > 1e-3)
            val yielding = cap < maxSpeed * 0.90
            return EscortPacingAdvice(active = true, speedCap = cap, activelyYielding = yielding)
        }

        // Same-layer 1D wavefront potential pacing (land / water / hover / over-cliff).
        val hasSharedCorridor = group.sharedCorridorRadiusByMovement[ownMoveType] != null
        val cf = if (!hasSharedCorridor) terrain.clearanceField() else null
        val tw = terrain.tileWidth
        val th = terrain.tileHeight
        val wTiles = terrain.widthTiles
        val hTiles = terrain.heightTiles
        val gCol = floor(goal.x / tw).toInt().coerceIn(0, wTiles - 1)
        val gRow = floor(goal.y / th).toInt().coerceIn(0, hTiles - 1)

        var activePeerCount = 0
        var anchorEffectivePhi = Double.NEGATIVE_INFINITY
        var anchorCruiseSpeed = Double.POSITIVE_INFINITY
        val ownGeo = if (remainingDistance.isFinite() && remainingDistance > 0.0) {
            remainingDistance
        } else {
            host.navigationGuidance.sampleGeodesicDistance(host, unit, terrain, unit.position, goal)
        }

        for (peerId in group.trafficOrder) {
            if (peerId == unit.unitId) continue
            val peer = host.units[peerId] ?: continue
            if (!isActiveCohortPeer(peer, group)) continue
            val peerMove = UnitLocomotion.movement(peer)
            if (peerMove.type != ownMoveType) continue
            if (cf != null) {
                // Disconnected fallback split: decouple from peers that cannot reach the goal at their
                // own clearance band (e.g. a heavy unit blocked by a 1-tile wall) so reachable units never stall.
                val peerR = max(1.0, ChunkedFlowFieldAdapter.flowPassabilityRadius(peer, tw, th))
                val peerBand = ChunkedFlowFieldAdapter.clearanceBandFor(terrain, peerR)
                val pCol = floor(peer.position.x / tw).toInt().coerceIn(0, wTiles - 1)
                val pRow = floor(peer.position.y / th).toInt().coerceIn(0, hTiles - 1)
                val pComp = if (cf.clearanceRings(pCol, pRow) >= peerBand) cf.componentId(pCol, pRow, peerBand) else 0
                val gComp = if (cf.clearanceRings(gCol, gRow) >= peerBand) cf.componentId(gCol, gRow, peerBand) else 0
                if (pComp == 0 || gComp == 0 || pComp != gComp) continue
            }
            activePeerCount++
            val peerPhi = host.navigationGuidance.sampleGeodesicDistance(host, peer, terrain, peer.position, goal)
            val peerBias = group.cohortEscortBiasByUnit[peerId] ?: 0.0
            val peerEffPhi = peerPhi - peerBias
            if (peerEffPhi > anchorEffectivePhi) {
                anchorEffectivePhi = peerEffPhi
            }
            if (peerMove.moveSpeed > 0.05 && peerMove.moveSpeed < anchorCruiseSpeed) {
                anchorCruiseSpeed = peerMove.moveSpeed
            }
        }

        if (activePeerCount == 0 || !anchorEffectivePhi.isFinite() || !anchorCruiseSpeed.isFinite()) {
            return EscortPacingAdvice.NONE
        }

        val desiredPhi = anchorEffectivePhi + ownBias
        val leadError = desiredPhi - ownGeo
        if (leadError <= 4.0) {
            // Trailing or at desired escort slot: run at full speed to catch up / hold vanguard!
            return EscortPacingAdvice.NONE
        }

        val anchorSpeed = min(maxSpeed, anchorCruiseSpeed)
        val cap = computePacingSpeedCap(leadError, maxSpeed, anchorSpeed, isEscortRear = ownBias > 1e-3)
        val yielding = cap < maxSpeed * 0.90
        return EscortPacingAdvice(active = true, speedCap = cap, activelyYielding = yielding)
    }

    private fun computePacingSpeedCap(
        leadError: Double,
        maxSpeed: Double,
        anchorSpeed: Double,
        isEscortRear: Boolean,
    ): Double {
        // Smooth monotonic speed cap convergence (zero step-function chatter):
        // - Small lead (4..24px): smoothly settle from maxSpeed down to ~0.88 * anchorSpeed,
        //   establishing steady-state equilibrium right around ~12-16px of desired slot;
        // - Medium lead (24..75px): smoothly taper down to ~0.08 * anchorSpeed so vanguard closes gap;
        // - Large lead (> 75px, e.g. fragile escort started ahead of slow heavy vanguard): pause
        //   cleanly at 0 so the durable vanguard smoothly overtakes to the front, then resume at anchorSpeed.
        return when {
            leadError <= 24.0 -> {
                val t = ((leadError - 4.0) / 20.0).coerceIn(0.0, 1.0)
                val target = anchorSpeed * 0.88
                (maxSpeed * (1.0 - t) + target * t).coerceIn(target, maxSpeed)
            }
            leadError <= 75.0 -> {
                val t = ((leadError - 24.0) / 51.0).coerceIn(0.0, 1.0)
                val hi = anchorSpeed * 0.88
                val lo = if (isEscortRear) anchorSpeed * 0.08 else anchorSpeed * 0.30
                (hi * (1.0 - t) + lo * t).coerceIn(0.0, maxSpeed)
            }
            leadError <= 85.0 -> {
                val t = ((leadError - 75.0) / 10.0).coerceIn(0.0, 1.0)
                val hi = if (isEscortRear) anchorSpeed * 0.08 else anchorSpeed * 0.30
                val lo = if (isEscortRear) 0.0 else anchorSpeed * 0.25
                (hi * (1.0 - t) + lo * t).coerceIn(0.0, maxSpeed)
            }
            else -> {
                if (isEscortRear) 0.0
                else (anchorSpeed * 0.25).coerceIn(0.0, maxSpeed)
            }
        }
    }

    private fun isActiveCohortPeer(peer: MutableUnit, group: GroupMoveRequest): Boolean {
        if (!peer.alive || peer.carriedBy != null || peer.attachment != null) return false
        if (peer.moveTarget == null) return false
        if (peer.motionContract?.traversalMode != TraversalMode.COHORT_ESCORT) return false
        val lm = peer.landingMember ?: return false
        if (lm.group !== group || lm.parked) return false
        // Dynamic decoupling: if a peer is genuinely stuck for >= 90 ticks, stop letting it drag the cohort.
        if (peer.stalledRouteTicks >= STALL_DECOUPLE_TICKS && !peer.cohortEscortYielding) return false
        return true
    }

    private fun isInsideOrExitingNarrowThroat(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        hardR: Double,
    ): Boolean {
        if (unit.passageEntry?.entered == true) return true
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val col = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, w - 1)
        val row = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, h - 1)
        // Only treat bilateral 1-tile pinches (blocked on BOTH opposite sides) as narrow throats;
        // never suspend cohort pacing along open shorelines, map borders, or lone rocks.
        val horizPinch = (col > 0 && terrain.isBlocked(col - 1, row)) && (col + 1 < w && terrain.isBlocked(col + 1, row))
        val vertPinch = (row > 0 && terrain.isBlocked(col, row - 1)) && (row + 1 < h && terrain.isBlocked(col, row + 1))
        if (horizPinch || vertPinch) return true
        val gateMask = terrain.gateThroatMask()
        if (gateMask[row * w + col]) return true
        // Clear a 2-tile apron around any gate throat so leading units move completely clear of the
        // doorway outlet before resuming escort pacing and never plug the throat for trailing peers.
        val apronTiles = if (hardR * 2.5 >= min(terrain.tileWidth, terrain.tileHeight).toDouble()) 3 else 2
        for (dy in -apronTiles..apronTiles) {
            val nr = row + dy
            if (nr !in 0 until h) continue
            for (dx in -apronTiles..apronTiles) {
                val nc = col + dx
                if (nc !in 0 until w) continue
                if (gateMask[nr * w + nc]) return true
            }
        }
        return false
    }

    /**
     * Evaluates centripetal convergence state for a group-move circle unit approaching [goal].
     * Uses zero-allocation [WorldSpatialIndex.forEachInBoundsUnordered] on hot 60Hz paths.
     */
    fun evaluateConvergence(
        host: UnitMovementHost,
        unit: MutableUnit,
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

        var settledSeedCount = 0
        var activePeerCount = 0
        var closerActiveCount = 0
        var opposingPeerCount = 0
        var blockingCorePeers = 0
        val group = member.group

        host.spatialIndex.forEachInBoundsUnordered(
            spatialLayer,
            goal.x - queryR, goal.x + queryR,
            goal.y - queryR, goal.y + queryR,
            useBufferRadius = false,
        ) { other ->
            if (other === unit || !other.alive) return@forEachInBoundsUnordered false
            val otherMember = other.landingMember ?: return@forEachInBoundsUnordered false
            if (otherMember.group !== group) return@forEachInBoundsUnordered false
            if (otherMember.parked) {
                settledSeedCount++
                return@forEachInBoundsUnordered true // Early exit: static seed already exists!
            }
            if (other.moveTarget == null) return@forEachInBoundsUnordered false
            val odx = goal.x - other.position.x
            val ody = goal.y - other.position.y
            val otherDist = hypot(odx, ody)
            if (otherDist > basinR + hardR * 2.0) return@forEachInBoundsUnordered false

            activePeerCount++
            if (otherDist < distToGoal - 0.5 ||
                (abs(otherDist - distToGoal) <= 0.5 && other.updateKey < unit.updateKey)
            ) {
                closerActiveCount++
            }

            if (distToGoal > 1e-5 && otherDist > 1e-5) {
                val oradX = odx / otherDist
                val oradY = ody / otherDist
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
            false
        }

        if (settledSeedCount > 0 || activePeerCount == 0) {
            return ConvergenceAdvice.NONE
        }

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

        val prox = ((basinR - distToGoal) / max(1.0, basinR * 0.65)).coerceIn(0.0, 1.0)
        val dockingWeightFloor = when {
            opposingPeerCount >= 1 || distToGoal <= max(hardR * 5.0, 58.0) ->
                (0.84 + 0.16 * prox).coerceIn(0.84, 1.0)
            else ->
                (0.72 + 0.28 * prox).coerceIn(0.72, 1.0)
        }

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
