package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.foundationMovementType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot JVM JIT pre-warmer for core simulation, flow-field, ORCA locomotion, cohort
 * coordination, contact, automatic vision, building terrain, and combat kernels, invoked once
 * during match loading so the player's first group move on a large map does not stall on cold
 * interpreter or Tier-1 deoptimization storms (ENG-P017-011).
 *
 * Uses a tiny isolated throwaway scenario with zero shared state and zero effect on match
 * determinism or live profiling.
 */
object SimulationKernelWarmup {
    private val warmed = AtomicBoolean(false)

    fun warmUpOnce(template: Scenario): Boolean {
        if (!warmed.compareAndSet(false, true)) return true
        return warmUpScenario(template)
    }

    internal fun warmUpScenario(template: Scenario): Boolean = warmUpScenarioDiagnostic(template) == null

    internal fun warmUpScenarioDiagnostic(template: Scenario): String? = runCatching {
        val landEntry = template.unitDefinitions.entries.firstOrNull { (_, def) ->
            def.building == null &&
                def.movement.type == MovementType.LAND &&
                def.movement.moveSpeed > 0.2 &&
                def.movementCollision is MovementCollisionShape.Circle
        } ?: return "no-land-def"
        val hoverEntry = template.unitDefinitions.entries.firstOrNull { (_, def) ->
            def.building == null &&
                def.movement.type == MovementType.HOVER &&
                def.movement.moveSpeed > 0.2 &&
                def.movementCollision is MovementCollisionShape.Circle
        } ?: landEntry
        val airEntry = template.unitDefinitions.entries.firstOrNull { (_, def) ->
            def.building == null &&
                def.movement.type == MovementType.AIR &&
                def.movement.moveSpeed > 0.2
        }
        val factoryEntry = template.unitDefinitions.entries.firstOrNull { (_, def) ->
            def.building != null &&
                def.building.requiresResourcePool != true &&
                def.production != null &&
                def.foundationMovementType == MovementType.LAND
        }
        val landKey = landEntry.key
        val hoverKey = hoverEntry.key
        val airKey = airEntry?.key
        val factoryKey = factoryEntry?.key

        // Pick a guaranteed LAND-passable ground GID from the template map (tile (0,0) on island maps
        // is often deep water, which would cause LAND spawns to be rejected if chosen blindly!).
        val landBlocking = template.map.blockingFor(MovementType.LAND)
        var sampleGid = 0
        outer@ for (r in 0 until template.map.heightTiles) {
            val base = r * template.map.widthTiles
            for (c in 0 until template.map.widthTiles) {
                if (!landBlocking.isBlocked(c, r)) {
                    val g = template.map.groundGids[base + c]
                    if (g != 0) {
                        sampleGid = g
                        break@outer
                    }
                }
            }
        }
        if (sampleGid == 0) sampleGid = template.map.groundGids.firstOrNull { it != 0 } ?: 1

        val w = 24
        val h = 20
        val tw = template.map.tileWidth
        val th = template.map.tileHeight
        val gids = IntArray(w * h) { sampleGid }
        val blocked = BooleanArray(w * h)
        // Build a narrow 2-tile gate wall down the middle to exercise flow-field gate throats,
        // congestion hysteresis, shadow monotonic rebuilds, and ORCA overtaking.
        for (r in 0 until h) {
            if (r != 9 && r != 10) blocked[r * w + 12] = true
        }
        val map = MapDefinition(
            widthTiles = w,
            heightTiles = h,
            tileWidth = tw,
            tileHeight = th,
            tileSets = template.map.tileSets,
            groundGids = gids,
            blocking = BlockingGrid(w, h, blocked),
        )

        val stepX = maxOf(34.0, tw * 1.7)
        val stepY = maxOf(34.0, th * 1.7)
        val spawns = ArrayList<UnitSpawn>(32)
        val aIds = ArrayList<String>(16)
        val bIds = ArrayList<String>(16)
        for (i in 0 until 12) {
            val typeA = if (i % 3 == 0) hoverKey else landKey
            val idA = "wa$i"
            spawns += UnitSpawn(
                idA, "p1", typeA,
                Position(2.5 * tw + (i % 4) * stepX, 2.5 * th + (i / 4) * stepY),
                90.0,
            )
            aIds += idA
            val typeB = if (i % 3 == 0) hoverKey else landKey
            val idB = "wb$i"
            spawns += UnitSpawn(
                idB, "p2", typeB,
                Position((w - 2.5) * tw - (i % 4) * stepX, (h - 2.5) * th - (i / 4) * stepY),
                270.0,
            )
            bIds += idB
        }
        if (airKey != null) {
            for (i in 0 until 4) {
                val idAir = "wair$i"
                spawns += UnitSpawn(idAir, "p1", airKey, Position(3.0 * tw + i * stepX, (h - 6.0) * th), 90.0)
                aIds += idAir
            }
        }
        if (factoryKey != null) {
            spawns += UnitSpawn("wfac1", "p1", factoryKey, Position(5.5 * tw, (h - 3.5) * th), 0.0)
        }
        val economy = template.economyContract?.let {
            EconomyContract(mapOf("p1" to 50_000L, "p2" to 50_000L))
        }
        val scenario = Scenario(
            scenarioId = "kernel-warmup",
            coordinateModel = template.coordinateModel,
            timeModel = template.timeModel,
            movementContract = template.movementContract,
            combatContract = template.combatContract,
            economyContract = economy,
            automaticVision = template.automaticVision,
            map = map,
            players = listOf(
                Player("p1", PlayerControl.LOCAL, "teamA"),
                Player("p2", PlayerControl.LOCAL, "teamB"),
            ),
            unitDefinitions = template.unitDefinitions,
            units = spawns,
        )
        val init = SimulationContext().initialize(scenario)
        if (init !is InitResult.Accepted) return "init-rejected:$init"
        val ctx = init.context
        try {
            val goalA = Position((w - 3.5) * tw, 9.5 * th)
            val goalB = Position(3.5 * tw, 9.5 * th)
            val gA = ctx.requestGroupMove("p1", aIds, goalA, cohortEscort = true)
            val gB = ctx.requestGroupMove("p2", bIds, goalB, cohortEscort = false)
            if (gA != null) {
                for (id in aIds) ctx.submitGroupMove(MoveAction("wa-$id", "p1", id, goalA, ctx.world), gA)
            }
            if (gB != null) {
                for (id in bIds) ctx.submitGroupMove(MoveAction("wb-$id", "p2", id, goalB, ctx.world), gB)
            }
            for (step in 0L until 320L) {
                if (step == 160L) {
                    val gA2 = ctx.requestGroupMove("p1", aIds, goalB, cohortEscort = false)
                    if (gA2 != null) {
                        for (id in aIds) ctx.submitGroupMove(MoveAction("wa2-$id", "p1", id, goalB, ctx.world), gA2)
                    }
                }
                ctx.advance(Step(step, 1.0, template.timeModel.deltaUnit))
            }
            null
        } finally {
            ctx.close()
        }
    }.getOrElse { "exception:${it.javaClass.simpleName}:${it.message}" }
}
