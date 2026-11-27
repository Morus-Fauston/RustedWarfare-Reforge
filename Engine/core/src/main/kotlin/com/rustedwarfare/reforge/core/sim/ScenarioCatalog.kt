package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.*
import java.security.MessageDigest
import kotlin.math.hypot

/**
 * Unified scenario & map catalog deep module (ADR-012 / ENG-P016-010).
 *
 * Single source of truth shared by:
 * - Client startup map/scenario selector (`SkirmishMapPanel` / `BootstrapScreen`)
 * - Headless benchmark, assertion & 2-second contact-sheet CLI (`SimulationTest --scene <id>`)
 * - Independent frozen-candidate holdout validation (explicit C-F loader; dev default is strictly A/B)
 */
enum class ScenarioCategory(val id: String, val label: String) {
    SKIRMISH_TMX("skirmish", "Skirmish (.tmx)"),
    BENCHMARK_P016("p016-benchmark", "P016 Benchmarks"),
    SANDBOX_PRESET("sandbox", "Sandbox Presets"),
    EXTERNAL_HOLDOUT("holdout", "Holdout (C-F)"),
}

/**
 * Deterministic fingerprint of active movement / flow-field / landing constants so every CLI
 * receipt and frozen-candidate handoff carries an immutable parameter identity hash.
 */
object MovementParameterFingerprint {
    fun canonicalParameters(): LinkedHashMap<String, String> = linkedMapOf(
        "schema" to "p016-movement-params-v1",
        "flow.chunkSize" to ChunkedFlowFieldAdapter.CHUNK_SIZE.toString(),
        "flow.minGroupSize" to ChunkedFlowFieldAdapter.MIN_GROUP_SIZE.toString(),
        "flow.maxCachedFields" to ChunkedFlowFieldAdapter.MAX_CACHED_FIELDS.toString(),
        "flow.baseStepCost" to ChunkedFlowFieldAdapter.BASE_STEP_COST.toString(),
        "flow.wideCorridorYieldSurcharge" to ChunkedFlowFieldAdapter.WIDE_CORRIDOR_YIELD_SURCHARGE.toString(),
        "flow.maxCongestionPenalty" to ChunkedFlowFieldAdapter.MAX_CONGESTION_PENALTY.toString(),
        "flow.hysteresisUpStep" to ChunkedFlowFieldAdapter.HYSTERESIS_UP_STEP.toString(),
        "flow.hysteresisDownStep" to ChunkedFlowFieldAdapter.HYSTERESIS_DOWN_STEP.toString(),
        "flow.hysteresisDeadband" to ChunkedFlowFieldAdapter.HYSTERESIS_DEADBAND.toString(),
        "flow.freeFlowSpeedFraction" to ChunkedFlowFieldAdapter.FREE_FLOW_SPEED_FRACTION.toString(),
        "flow.congestionRefreshInterval" to ChunkedFlowFieldAdapter.CONGESTION_REFRESH_INTERVAL.toString(),
        "flow.trunkReuseRadiusTiles" to ChunkedFlowFieldAdapter.TRUNK_REUSE_RADIUS_TILES.toString(),
        "flow.trunkReuseLocalHaloTiles" to ChunkedFlowFieldAdapter.TRUNK_REUSE_LOCAL_HALO_TILES.toString(),
    )

    fun sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val canonical = canonicalParameters().entries.joinToString("\n") { (k, v) -> "$k=$v" }
        return digest.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class ScenarioCatalogEntry(
    val id: String,
    val displayName: String,
    val category: ScenarioCategory,
    val family: String,
    val group: String,
    val seed: Long,
    val description: String,
    val defaultSteps: Int,
    val playerCount: Int,
    val startingUnitCount: Int,
    val knownLimitation: String? = null,
    val gates: List<DiagnosticGateLine> = emptyList(),
    val buildMap: () -> MapDefinition,
    val buildScenario: (Map<String, UnitDefinition>) -> Scenario,
    val issueOrders: (SimulationContext) -> Unit = {},
    val onStep: (SimulationContext, Long) -> Unit = { _, _ -> },
    val assertOutcome: (DiagnosticSequenceProjection) -> List<String> = { emptyList() },
)

object ScenarioCatalog {
    const val DEFAULT_WORLD_SEED: Long = 12345L

    private val rockGateLines: List<DiagnosticGateLine> = listOf(
        DiagnosticGateLine("wall1-narrow-y12", axisX = true, entryThreshold = 24.0 * 20.0, exitThreshold = 26.0 * 20.0, minOrtho = 11.5 * 20.0, maxOrtho = 13.5 * 20.0),
        DiagnosticGateLine("wall1-wide-y23..28", axisX = true, entryThreshold = 24.0 * 20.0, exitThreshold = 26.0 * 20.0, minOrtho = 22.5 * 20.0, maxOrtho = 29.5 * 20.0),
        DiagnosticGateLine("wall2-narrow-y15", axisX = true, entryThreshold = 34.0 * 20.0, exitThreshold = 36.0 * 20.0, minOrtho = 14.5 * 20.0, maxOrtho = 16.5 * 20.0),
        DiagnosticGateLine("wall2-wide-y20..25", axisX = true, entryThreshold = 34.0 * 20.0, exitThreshold = 36.0 * 20.0, minOrtho = 19.5 * 20.0, maxOrtho = 26.5 * 20.0),
        DiagnosticGateLine("wall3-narrow-y11", axisX = true, entryThreshold = 44.0 * 20.0, exitThreshold = 46.0 * 20.0, minOrtho = 10.5 * 20.0, maxOrtho = 12.5 * 20.0),
        DiagnosticGateLine("wall3-wide-y25..30", axisX = true, entryThreshold = 44.0 * 20.0, exitThreshold = 46.0 * 20.0, minOrtho = 24.5 * 20.0, maxOrtho = 31.5 * 20.0),
    )

    private val entriesList: List<ScenarioCatalogEntry> = listOf(
        ScenarioCatalogEntry(
            id = "rocks-small-100",
            displayName = "Rocks Small 100 (Dual-Gate Split)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "flowfield-split",
            group = "A",
            seed = DEFAULT_WORLD_SEED,
            description = "100 homogeneous small tanks crossing 3 staggered walls (1-tile narrow + 6-tile wide gates) with dynamic queue splitting.",
            defaultSteps = 2900,
            playerCount = 2,
            startingUnitCount = 100,
            gates = rockGateLines,
            buildMap = ::buildRocksMap,
            buildScenario = { defs -> buildRocksScenario("rocks-small-100", defs, mixed = false) },
            issueOrders = { ctx ->
                val ids = (0 until 100).map { String.format("tank-%02d", it) }
                val click = Position(1260.0, 400.0)
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                for (id in ids) {
                    check(ctx.submitGroupMove(MoveAction("rocks100-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                }
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 100) errs += "Expected 100/100 arrived, got ${last.arrivedCount}/100"
                val w3n = seq.gateSummaries.firstOrNull { it.gateId == "wall3-narrow-y11" }?.sternExitedCount ?: 0
                val w3w = seq.gateSummaries.firstOrNull { it.gateId == "wall3-wide-y25..30" }?.sternExitedCount ?: 0
                if (w3n <= 0) errs += "Expected true multi-gate split through wall3-narrow-y11 (>0), got $w3n"
                if (w3n + w3w != 100) errs += "Expected wall3 total stern exits == 100, got $w3n + $w3w"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "rocks-mixed-100",
            displayName = "Rocks Mixed 100 (75 Small + 25 Heavy)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "flowfield-split",
            group = "B",
            seed = DEFAULT_WORLD_SEED,
            description = "75 small tanks + 25 slow heavy units crossing 3 staggered walls; small units yield wide gates and split through narrow pinholes.",
            defaultSteps = 4200,
            playerCount = 2,
            startingUnitCount = 100,
            gates = rockGateLines,
            buildMap = ::buildRocksMap,
            buildScenario = { defs -> buildRocksScenario("rocks-mixed-100", defs, mixed = true) },
            issueOrders = { ctx ->
                val ids = (0 until 100).map { String.format("tank-%02d", it) }
                val click = Position(1260.0, 400.0)
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                for (id in ids) {
                    check(ctx.submitGroupMove(MoveAction("rocksmix-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                }
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 100) errs += "Expected 100/100 arrived, got ${last.arrivedCount}/100"
                val w3n = seq.gateSummaries.firstOrNull { it.gateId == "wall3-narrow-y11" }?.sternExitedCount ?: 0
                val w3w = seq.gateSummaries.firstOrNull { it.gateId == "wall3-wide-y25..30" }?.sternExitedCount ?: 0
                if (w3n <= 0) errs += "Expected small units to split through wall3-narrow-y11 (>0), got $w3n"
                if (w3n + w3w != 100) errs += "Expected wall3 total stern exits == 100, got $w3n + $w3w"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "opposed-open-25v25",
            displayName = "Opposed Open 25v25 (Right-Hand Lanes)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "avoidance-opposed",
            group = "A",
            seed = DEFAULT_WORLD_SEED,
            description = "25 Eastbound vs 25 Westbound tanks colliding head-on in open ground, forming clean right-hand passing lanes.",
            defaultSteps = 2400,
            playerCount = 2,
            startingUnitCount = 50,
            gates = listOf(
                DiagnosticGateLine("midline-eastbound", axisX = true, entryThreshold = 460.0, exitThreshold = 500.0, minOrtho = 0.0, maxOrtho = 600.0, positiveDirection = true),
                DiagnosticGateLine("midline-westbound", axisX = true, entryThreshold = 540.0, exitThreshold = 500.0, minOrtho = 0.0, maxOrtho = 600.0, positiveDirection = false),
            ),
            buildMap = ::buildOpposedOpenMap,
            buildScenario = { defs -> buildOpposedOpenScenario("opposed-open-25v25", defs) },
            issueOrders = { ctx ->
                val eastIds = (0 until 25).map { String.format("east-%02d", it) }
                val westIds = (0 until 25).map { String.format("west-%02d", it) }
                val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(780.0, 300.0)))
                for (id in eastIds) check(ctx.submitGroupMove(MoveAction("east-$id", "p1", id, Position(780.0, 300.0), ctx.world), gEast) is ActionResult.Accepted)
                val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, Position(220.0, 300.0)))
                for (id in westIds) check(ctx.submitGroupMove(MoveAction("west-$id", "p2", id, Position(220.0, 300.0), ctx.world), gWest) is ActionResult.Accepted)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 50) errs += "Expected 50/50 arrived, got ${last.arrivedCount}/50"
                val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
                if (maxStalled > 0) errs += "Expected 0 stalled units, got maxStalled=$maxStalled"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "opposed-corridor-8v8",
            displayName = "Opposed Corridor 8v8 (3-Tile Dual Lane)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "bottleneck-handoff",
            group = "B",
            seed = DEFAULT_WORLD_SEED,
            description = "8 Eastbound vs 8 Westbound tanks passing simultaneously through a 3-tile wide corridor without head-on lockups.",
            defaultSteps = 1800,
            playerCount = 2,
            startingUnitCount = 16,
            gates = listOf(
                DiagnosticGateLine("corridor-exit-east", axisX = true, entryThreshold = 580.0, exitThreshold = 620.0, minOrtho = 175.0, maxOrtho = 245.0, positiveDirection = true),
                DiagnosticGateLine("corridor-exit-west", axisX = true, entryThreshold = 320.0, exitThreshold = 280.0, minOrtho = 175.0, maxOrtho = 245.0, positiveDirection = false),
            ),
            buildMap = ::buildOpposedCorridorMap,
            buildScenario = { defs -> buildOpposedCorridorScenario("opposed-corridor-8v8", defs) },
            issueOrders = { ctx ->
                val eastIds = (0 until 8).map { String.format("ceast-%02d", it) }
                val westIds = (0 until 8).map { String.format("cwest-%02d", it) }
                val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(760.0, 200.0)))
                for (id in eastIds) check(ctx.submitGroupMove(MoveAction("ceast-$id", "p1", id, Position(760.0, 200.0), ctx.world), gEast) is ActionResult.Accepted)
                val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, Position(140.0, 200.0)))
                for (id in westIds) check(ctx.submitGroupMove(MoveAction("cwest-$id", "p2", id, Position(140.0, 200.0), ctx.world), gWest) is ActionResult.Accepted)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 16) errs += "Expected 16/16 arrived, got ${last.arrivedCount}/16"
                val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
                if (maxStalled > 0) errs += "Expected 0 stalled units, got maxStalled=$maxStalled"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "narrow-bridge-yield",
            displayName = "Narrow Bridge Yield (1-Tile Single File)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "bottleneck-handoff",
            group = "A",
            seed = DEFAULT_WORLD_SEED,
            description = "2 opposing tanks meeting at a true 1-tile bridge; outside-mouth yield waits until stern clears before handoff.",
            defaultSteps = 900,
            playerCount = 2,
            startingUnitCount = 2,
            gates = listOf(
                DiagnosticGateLine("bridge-east-exit", axisX = true, entryThreshold = 380.0, exitThreshold = 420.0, minOrtho = 195.0, maxOrtho = 225.0, positiveDirection = true),
                DiagnosticGateLine("bridge-west-exit", axisX = true, entryThreshold = 340.0, exitThreshold = 300.0, minOrtho = 195.0, maxOrtho = 225.0, positiveDirection = false),
            ),
            buildMap = ::buildNarrowBridgeMap,
            buildScenario = { defs -> buildNarrowBridgeYieldScenario("narrow-bridge-yield", defs) },
            issueOrders = { ctx ->
                check(ctx.submit(MoveAction("nb-east", "p1", "east-0", Position(580.0, 210.0), ctx.world)) is ActionResult.Accepted)
                check(ctx.submit(MoveAction("nb-west", "p2", "west-0", Position(140.0, 210.0), ctx.world)) is ActionResult.Accepted)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 2) errs += "Expected 2/2 arrived, got ${last.arrivedCount}/2"
                val maxContacts = seq.frames.maxOfOrNull { it.hardContactCount } ?: 0
                if (maxContacts > 0) errs += "Expected 0 hard contacts on single-file bridge, got $maxContacts"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "mixed-overtake-wide",
            displayName = "Mixed Overtake Wide (8 Scouts + 3 Heavies)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "avoidance-overtake",
            group = "A",
            seed = DEFAULT_WORLD_SEED,
            description = "8 fast scouts overtaking 3 slow heavy tanks inside a 10-tile wide corridor via real-time 2D ORCA passing.",
            defaultSteps = 1300,
            playerCount = 2,
            startingUnitCount = 11,
            gates = listOf(
                DiagnosticGateLine("overtake-line-x540", axisX = true, entryThreshold = 500.0, exitThreshold = 540.0, minOrtho = 110.0, maxOrtho = 330.0, positiveDirection = true),
            ),
            buildMap = ::buildMixedOvertakeMap,
            buildScenario = { defs -> buildMixedOvertakeScenario("mixed-overtake-wide", defs) },
            issueOrders = { ctx ->
                val ids = listOf("heavy-0", "heavy-1", "heavy-2") + (0 until 8).map { "scout-$it" }
                val click = Position(780.0, 220.0)
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                for (id in ids) check(ctx.submitGroupMove(MoveAction("ov-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 11) errs += "Expected 11/11 arrived, got ${last.arrivedCount}/11"
                val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
                if (maxStalled > 0) errs += "Expected 0 stalled units, got maxStalled=$maxStalled"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "mixed-overtake-single-pull",
            displayName = "Mixed Overtake + Mid-Route Single Pull",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "avoidance-overtake",
            group = "B",
            seed = DEFAULT_WORLD_SEED,
            description = "8 fast scouts overtaking 3 slow heavies; at step 90 scout-0 is pulled mid-route to a flank waypoint without stalling the column.",
            defaultSteps = 1300,
            playerCount = 2,
            startingUnitCount = 11,
            gates = listOf(
                DiagnosticGateLine("overtake-line-x540", axisX = true, entryThreshold = 500.0, exitThreshold = 540.0, minOrtho = 110.0, maxOrtho = 330.0, positiveDirection = true),
            ),
            buildMap = ::buildMixedOvertakeMap,
            buildScenario = { defs -> buildMixedOvertakeScenario("mixed-overtake-single-pull", defs) },
            issueOrders = { ctx ->
                val ids = listOf("heavy-0", "heavy-1", "heavy-2") + (0 until 8).map { "scout-$it" }
                val click = Position(780.0, 220.0)
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                for (id in ids) check(ctx.submitGroupMove(MoveAction("ovp-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
            },
            onStep = { ctx, step ->
                if (step == 90L) {
                    check(ctx.submit(MoveAction("pull-scout-0", "p1", "scout-0", Position(620.0, 145.0), ctx.world)) is ActionResult.Accepted)
                }
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 11) errs += "Expected 11/11 arrived after mid-route single pull, got ${last.arrivedCount}/11"
                val scout0 = last.units.firstOrNull { it.unitId == "scout-0" }
                if (scout0 == null || hypot(scout0.center.x - 620.0, scout0.center.y - 145.0) > 24.0) {
                    errs += "Expected scout-0 to settle at pulled flank target (620, 145), got ${scout0?.center}"
                }
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "air-flock-rally",
            displayName = "Air Flock Rally (16 Choppers Soft Separation)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "air-separation",
            group = "A",
            seed = DEFAULT_WORLD_SEED,
            description = "16 airborne helicopters converging on a single shared rally point with smooth air-layer soft separation.",
            defaultSteps = 600,
            playerCount = 2,
            startingUnitCount = 16,
            gates = emptyList(),
            buildMap = ::buildAirFlockMap,
            buildScenario = { defs -> buildAirFlockScenario("air-flock-rally", defs) },
            issueOrders = { ctx ->
                val click = Position(540.0, 240.0)
                for (i in 0 until 16) check(ctx.submit(MoveAction("air-$i", "p1", "chopper-$i", click, ctx.world)) is ActionResult.Accepted)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 16) errs += "Expected 16/16 choppers arrived, got ${last.arrivedCount}/16"
                if (last.hardContactCount != 0) errs += "Expected 0 hard contacts in air layer, got ${last.hardContactCount}"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "open-50-217-166",
            displayName = "Open Ground 50 Tanks (217, 166)",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "terminal-landing",
            group = "A",
            seed = DEFAULT_WORLD_SEED,
            description = "50 tanks converging tightly onto (217, 166) in open ground with continuous centripetal + tangential landing.",
            defaultSteps = 1400,
            playerCount = 2,
            startingUnitCount = 50,
            gates = emptyList(),
            buildMap = ::buildOpen50Map,
            buildScenario = { defs -> buildOpen50Scenario("open-50-217-166", defs) },
            issueOrders = { ctx ->
                val ids = (0 until 50).map { String.format("u-%02d", it) }
                val click = Position(217.0, 166.0)
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                for (id in ids) check(ctx.submitGroupMove(MoveAction("open50-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (last.arrivedCount != 50) errs += "Expected 50/50 arrived, got ${last.arrivedCount}/50"
                val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
                if (maxStalled > 0) errs += "Expected 0 stalled units in open-50, got maxStalled=$maxStalled"
                errs
            },
        ),
        ScenarioCatalogEntry(
            id = "culdesac-overflow-100",
            displayName = "Cul-de-sac Overflow 100 [Known Limitation]",
            category = ScenarioCategory.BENCHMARK_P016,
            family = "terminal-landing",
            group = "B",
            seed = DEFAULT_WORLD_SEED,
            description = "100 tanks ordered deep into an undersized single-mouth cul-de-sac pocket. Retained for selection and replay under known-limitation tracking.",
            defaultSteps = 2200,
            playerCount = 2,
            startingUnitCount = 100,
            knownLimitation = "Known limitation (2026-11-23 maintainer decision): extreme 100-unit single-mouth cul-de-sac overflow is tracked separately without blocking normal gameplay.",
            gates = listOf(
                DiagnosticGateLine("pocket-mouth-x560", axisX = true, entryThreshold = 520.0, exitThreshold = 560.0, minOrtho = 215.0, maxOrtho = 305.0, positiveDirection = true),
            ),
            buildMap = ::buildCulDeSacMap,
            buildScenario = { defs -> buildCulDeSacScenario("culdesac-overflow-100", defs) },
            issueOrders = { ctx ->
                val ids = (0 until 100).map { String.format("cds-%02d", it) }
                val click = Position(710.0, 260.0)
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                for (id in ids) check(ctx.submitGroupMove(MoveAction("cds100-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
            },
            assertOutcome = { emptyList() },
        ),
    )

    private val byIdMap: Map<String, ScenarioCatalogEntry> = entriesList.associateBy { it.id }

    /** All built-in A/B development benchmark entries (strictly excludes C-F holdouts). */
    fun devBenchmarkEntries(): List<ScenarioCatalogEntry> = entriesList

    fun byId(id: String): ScenarioCatalogEntry? = byIdMap[id]

    fun allIds(): List<String> = entriesList.map { it.id }

    /** Computes a deterministic SHA-256 identity hash over map dimensions and passability grids. */
    fun mapHash(map: MapDefinition): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${map.widthTiles}x${map.heightTiles}@${map.tileWidth}x${map.tileHeight}\n".toByteArray(Charsets.UTF_8))
        val buf = ByteArray(map.widthTiles * map.heightTiles)
        for (r in 0 until map.heightTiles) {
            val base = r * map.widthTiles
            for (c in 0 until map.widthTiles) {
                var bits = 0
                if (map.blocking.isBlocked(c, r)) bits = bits or 1
                if (map.waterBlocking.isBlocked(c, r)) bits = bits or 2
                if (map.hoverBlocking.isBlocked(c, r)) bits = bits or 4
                buf[base + c] = bits.toByte()
            }
        }
        digest.update(buf)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Derives the standardized benchmark unit definitions (`c_tank`, `fast_scout`, `slow_heavy`, `helicopter`)
     * from any loaded base content pack while preserving all existing definitions in [baseDefs].
     */
    fun deriveBenchmarkDefinitions(baseDefs: Map<String, UnitDefinition>): Map<String, UnitDefinition> {
        val loadedTank = baseDefs["c_tank"]
            ?: baseDefs["tank"]
            ?: baseDefs.values.firstOrNull { it.building == null && it.movement.type == MovementType.LAND }
            ?: error("No land mobile unit definition found in content pack (${baseDefs.keys})")
        val smallTank = UnitDefinition(
            id = "c_tank", className = loadedTank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
            movement = loadedTank.movement.copy(maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val fastScout = UnitDefinition(
            id = "fast_scout", className = loadedTank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
            movement = loadedTank.movement.copy(moveSpeed = 1.65, maxTurnSpeed = 8.0, turnAcceleration = 1.2),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 2200.0,
        )
        val slowHeavy = UnitDefinition(
            id = "slow_heavy", className = loadedTank.className, radius = 14.0, displayRadius = 14.0,
            totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
            movement = loadedTank.movement.copy(moveSpeed = 0.55, maxTurnSpeed = 2.0, turnAcceleration = 0.2),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
            movementCollision = MovementCollisionShape.Circle(14.0), mass = 36000.0,
        )
        val airChopper = baseDefs["helicopter"]
            ?: baseDefs.values.firstOrNull { it.building == null && it.movement.type == MovementType.AIR }
            ?: UnitDefinition(
                id = "helicopter", className = loadedTank.className, radius = 9.0, displayRadius = 9.0,
                totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
                movement = loadedTank.movement.copy(
                    type = MovementType.AIR,
                    moveSpeed = 1.8,
                    slidingMode = true,
                    ignoringBody = true,
                    reverseSpeedPercentage = 0.1,
                    flight = FlightProfile(targetHeight = 20.0, heightDrift = 0.0, heightChangeRate = 1.0, startingHeightOffset = 20.0),
                ),
                retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
                combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
                rotor = RotorDefinition(loadedTank.imagePath, 18.0),
                movementCollision = MovementCollisionShape.Circle(9.0), mass = 1200.0,
            )
        return LinkedHashMap(baseDefs).apply {
            put("c_tank", smallTank)
            put("fast_scout", fastScout)
            put("slow_heavy", slowHeavy)
            put("helicopter", airChopper)
        }
    }

    fun proceduralMap(w: Int, h: Int, blocked: BooleanArray, tileWidth: Int = 20, tileHeight: Int = 20): MapDefinition {
        require(w > 0 && h > 0 && tileWidth > 0 && tileHeight > 0) { "Map dimensions must be positive: ${w}x${h}@${tileWidth}x${tileHeight}" }
        // GID 1 = passable ground, GID 2 = blocked rock/wall (rendered procedurally when tileSets is empty)
        val ground = IntArray(w * h) { idx -> if (blocked[idx]) 2 else 1 }
        return MapDefinition(w, h, tileWidth, tileHeight, emptyList(), ground, BlockingGrid(w, h, blocked))
    }

    fun buildRocksMap(): MapDefinition {
        val w = 74
        val h = 40
        val gates = listOf(Triple(24, 12, 23), Triple(34, 15, 20), Triple(44, 11, 25))
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else gates.any { (start, narrow, wide) ->
                x in start until (start + 2) && y != narrow && y !in wide until (wide + 6)
            }
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildRocksScenario(id: String, definitions: Map<String, UnitDefinition>, mixed: Boolean): Scenario {
        val map = buildRocksMap()
        val units = (0 until 100).map { index ->
            val x = 420.0 - 36.0 * (index / 10)
            val y = 180.0 + 36.0 * (index % 10)
            val type = if (mixed && index % 4 == 3) "slow_heavy" else "c_tank"
            UnitSpawn(String.format("tank-%02d", index), "p1", type, Position(x, y), 90.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    fun buildOpposedOpenMap(): MapDefinition {
        val w = 50; val h = 30
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildOpposedOpenScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildOpposedOpenMap()
        val units = buildList {
            for (i in 0 until 25) {
                add(UnitSpawn(String.format("east-%02d", i), "p1", "c_tank",
                    Position(240.0 - (i / 5) * 28.0, 244.0 + (i % 5) * 28.0), 90.0))
            }
            for (i in 0 until 25) {
                add(UnitSpawn(String.format("west-%02d", i), "p2", "c_tank",
                    Position(760.0 + (i / 5) * 28.0, 244.0 + (i % 5) * 28.0), 270.0))
            }
        }
        return baseScenario(id, map, definitions, units)
    }

    fun buildOpposedCorridorMap(): MapDefinition {
        val w = 45; val h = 20
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 14..30) && (y !in 9..11)
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildOpposedCorridorScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildOpposedCorridorMap()
        val units = buildList {
            for (i in 0 until 8) {
                add(UnitSpawn(String.format("ceast-%02d", i), "p1", "c_tank",
                    Position(220.0 - (i / 2) * 26.0, 192.0 + (i % 2) * 16.0), 90.0))
            }
            for (i in 0 until 8) {
                add(UnitSpawn(String.format("cwest-%02d", i), "p2", "c_tank",
                    Position(680.0 + (i / 2) * 26.0, 192.0 + (i % 2) * 16.0), 270.0))
            }
        }
        return baseScenario(id, map, definitions, units)
    }

    fun buildMixedOvertakeMap(): MapDefinition {
        val w = 44; val h = 22
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (y < 6 || y > 15)
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildMixedOvertakeScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildMixedOvertakeMap()
        val units = buildList {
            add(UnitSpawn("heavy-0", "p1", "slow_heavy", Position(340.0, 220.0), 90.0))
            add(UnitSpawn("heavy-1", "p1", "slow_heavy", Position(280.0, 190.0), 90.0))
            add(UnitSpawn("heavy-2", "p1", "slow_heavy", Position(280.0, 250.0), 90.0))
            for (i in 0 until 8) {
                add(UnitSpawn("scout-$i", "p1", "fast_scout", Position(190.0 - (i / 4) * 24.0, 185.0 + (i % 4) * 24.0), 90.0))
            }
        }
        return baseScenario(id, map, definitions, units)
    }

    fun buildNarrowBridgeMap(): MapDefinition {
        val w = 36; val h = 21
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 15..20) && (y != 10)
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildNarrowBridgeYieldScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildNarrowBridgeMap()
        val units = listOf(
            UnitSpawn("east-0", "p1", "c_tank", Position(330.0, 210.0), 90.0),
            UnitSpawn("west-0", "p2", "c_tank", Position(440.0, 210.0), 270.0),
        )
        return baseScenario(id, map, definitions, units)
    }

    fun buildAirFlockMap(): MapDefinition {
        val w = 36; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildAirFlockScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildAirFlockMap()
        val units = (0 until 16).map { i ->
            UnitSpawn("chopper-$i", "p1", "helicopter", Position(100.0 + (i / 4) * 34.0, 160.0 + (i % 4) * 34.0), 90.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    fun buildOpen50Map(): MapDefinition {
        val w = 40; val h = 30
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildOpen50Scenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildOpen50Map()
        val units = (0 until 50).map { i ->
            val col = i % 10; val row = i / 10
            UnitSpawn(String.format("u-%02d", i), "p1", "c_tank", Position(420.0 + col * 24.0, 260.0 + row * 24.0), 270.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    fun buildCulDeSacMap(): MapDefinition {
        val w = 44; val h = 26
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else x >= 28 && (y < 11 || y > 14 || x > 37)
        }
        return proceduralMap(w, h, blocked)
    }

    private fun buildCulDeSacScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val map = buildCulDeSacMap()
        val units = (0 until 100).map { i ->
            UnitSpawn(String.format("cds-%02d", i), "p1", "c_tank", Position(420.0 - (i / 10) * 24.0, 160.0 + (i % 10) * 22.0), 90.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    fun baseScenario(
        id: String,
        map: MapDefinition,
        definitions: Map<String, UnitDefinition>,
        units: List<UnitSpawn>,
        worldSeed: Long = DEFAULT_WORLD_SEED,
        players: List<Player> = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
    ): Scenario = Scenario(
        scenarioId = id,
        map = map,
        unitDefinitions = definitions,
        players = players,
        units = units,
        coordinateModel = CoordinateModel("public.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        timeModel = TimeModel("public.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
        movementContract = MovementContract(16.0, 4.0),
        economyContract = EconomyContract(players.associate { it.playerId to 30000L }),
        combatContract = CombatContract(
            worldSeed = worldSeed.toInt(),
            initialFrameCounter = -1,
            unitObjectIds = units.mapIndexed { idx, u -> u.unitId to (8L + idx) }.toMap(),
            nextObjectId = 8L + units.size,
        ),
    )
}
