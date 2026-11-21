package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/**
 * Offline diagnostic baseline capture CLI (ENG-P016-001).
 * Runs authoritative SimulationContext steps and renders 2s stall-highlighted contact sheets & peak zooms.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: DiagnosticBaselineCaptureMain <repo-root> <output-dir>" }
    val repoRoot = Path.of(args[0]).toAbsolutePath().normalize()
    val outputDir = Path.of(args[1]).toAbsolutePath().normalize()
    Files.createDirectories(outputDir)

    val packPath = repoRoot.resolve("SimulationTest/public/inputs/phase-six-a-sandbox/json/package")
    val pack = (ContentLoader.loadContentPack(packPath) as ContentResult.Success).value
    val loadedTank = pack.units.getValue("c_tank")
    val largeMech = pack.units.getValue("mechGun")

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
    val loadedChopper = pack.units.getValue("helicopter")
    val airChopper = loadedChopper
    val definitions = mapOf(
        "c_tank" to smallTank,
        "mechGun" to largeMech,
        "fast_scout" to fastScout,
        "slow_heavy" to slowHeavy,
        "helicopter" to airChopper,
    )

    val reports = mutableListOf<JsonObject>()

    // 1. 100-unit homogeneous rocks array (rocks-small-100)
    reports += runAndExport(
        id = "v0546-rocks-small-100",
        title = "v0.5.46 Baseline: rocks-small-100 (100x c_tank through staggered gates)",
        scenario = buildRocksScenario("rocks-small-100", definitions, mixed = false),
        steps = 2600,
        outputDir = outputDir.resolve("rocks-small-100"),
    ) { ctx ->
        val ids = (0 until 100).map { String.format("tank-%02d", it) }
        val click = Position(1260.0, 400.0)
        val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
        for (id in ids) {
            check(ctx.submitGroupMove(MoveAction("rocks100-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
        }
    }

    // 2. 25v25 open-field head-on opposed traffic (opposed-open-25v25)
    reports += runAndExport(
        id = "v0546-opposed-open-25v25",
        title = "v0.5.46 Baseline: opposed-open-25v25 (25 Eastbound vs 25 Westbound head-on)",
        scenario = buildOpposedOpenScenario("opposed-open-25v25", definitions),
        steps = 2400,
        outputDir = outputDir.resolve("opposed-open-25v25"),
    ) { ctx ->
        val eastIds = (0 until 25).map { String.format("east-%02d", it) }
        val westIds = (0 until 25).map { String.format("west-%02d", it) }
        val eastGoal = Position(780.0, 300.0)
        val westGoal = Position(220.0, 300.0)
        val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, eastGoal))
        for (id in eastIds) {
            check(ctx.submitGroupMove(MoveAction("east-$id", "p1", id, eastGoal, ctx.world), gEast) is ActionResult.Accepted)
        }
        val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, westGoal))
        for (id in westIds) {
            check(ctx.submitGroupMove(MoveAction("west-$id", "p2", id, westGoal, ctx.world), gWest) is ActionResult.Accepted)
        }
    }

    // 3. Narrow corridor opposed head-on deadlock (opposed-corridor-8v8)
    reports += runAndExport(
        id = "v0546-opposed-corridor-8v8",
        title = "v0.5.46 Baseline: opposed-corridor-8v8 (Two-lane corridor head-on deadlock)",
        scenario = buildOpposedCorridorScenario("opposed-corridor-8v8", definitions),
        steps = 1800,
        outputDir = outputDir.resolve("opposed-corridor-8v8"),
    ) { ctx ->
        val eastIds = (0 until 8).map { String.format("ceast-%02d", it) }
        val westIds = (0 until 8).map { String.format("cwest-%02d", it) }
        val eastGoal = Position(760.0, 200.0)
        val westGoal = Position(140.0, 200.0)
        val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, eastGoal))
        for (id in eastIds) {
            check(ctx.submitGroupMove(MoveAction("ceast-$id", "p1", id, eastGoal, ctx.world), gEast) is ActionResult.Accepted)
        }
        val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, westGoal))
        for (id in westIds) {
            check(ctx.submitGroupMove(MoveAction("cwest-$id", "p2", id, westGoal, ctx.world), gWest) is ActionResult.Accepted)
        }
    }

    // 4. Mixed wide-road overtaking: fast scouts overtake slow heavy giants without stopping (ENG-P016-007)
    reports += runAndExport(
        id = "p016-007-mixed-overtake-wide",
        title = "ENG-P016-007: mixed-overtake-wide (8 fast scouts overtaking 3 slow heavies)",
        scenario = buildMixedOvertakeScenario("mixed-overtake-wide", definitions),
        steps = 1100,
        outputDir = outputDir.resolve("mixed-overtake-wide"),
    ) { ctx ->
        val ids = listOf("heavy-0", "heavy-1", "heavy-2") + (0 until 8).map { "scout-$it" }
        val click = Position(780.0, 220.0)
        val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
        for (id in ids) {
            check(ctx.submitGroupMove(MoveAction("ov-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
        }
    }

    // 5. Single-lane narrow bridge with waiting unit clearing mouth outlet laterally (ENG-P016-007)
    reports += runAndExport(
        id = "p016-007-narrow-bridge-yield",
        title = "ENG-P016-007: narrow-bridge-yield (single-lane bridge outside mouth lateral clearing)",
        scenario = buildNarrowBridgeYieldScenario("narrow-bridge-yield", definitions),
        steps = 900,
        outputDir = outputDir.resolve("narrow-bridge-yield"),
    ) { ctx ->
        check(ctx.submit(MoveAction("nb-east", "p1", "east-0", Position(580.0, 210.0), ctx.world)) is ActionResult.Accepted)
        check(ctx.submit(MoveAction("nb-west", "p2", "west-0", Position(140.0, 210.0), ctx.world)) is ActionResult.Accepted)
    }

    // 6. Air-layer lightweight 2D Boids soft separation on shared rally click (ENG-P016-007)
    reports += runAndExport(
        id = "p016-007-air-flock-rally",
        title = "ENG-P016-007: air-flock-rally (16 airborne choppers soft-separating at shared rally)",
        scenario = buildAirFlockScenario("air-flock-rally", definitions),
        steps = 600,
        outputDir = outputDir.resolve("air-flock-rally"),
    ) { ctx ->
        val click = Position(540.0, 240.0)
        for (i in 0 until 16) {
            check(ctx.submit(MoveAction("air-$i", "p1", "chopper-$i", click, ctx.world)) is ActionResult.Accepted)
        }
    }

    val summary = buildJsonObject {
        put("schemaVersion", 1)
        put("baselineCommit", "2efe9845320ca80f0e8693226228fd3810726986")
        put("baselineVersion", "v0.5.46")
        put("runs", JsonArray(reports))
    }
    val pretty = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)
    Files.writeString(outputDir.resolve("baseline-summary.json"), pretty + "\n")
    println(pretty)
}

private fun runAndExport(
    id: String,
    title: String,
    scenario: Scenario,
    steps: Int,
    outputDir: Path,
    issueOrders: (SimulationContext) -> Unit,
): JsonObject {
    Files.createDirectories(outputDir)
    val init = SimulationContext.initialize(scenario)
    check(init is InitResult.Accepted) { "Init rejected for $id: $init" }
    val ctx = init.context
    val observations = ArrayList<Observation>(steps + 1)
    try {
        issueOrders(ctx)
        observations += ctx.observe()
        for (step in 0L until steps.toLong()) {
            observations += ctx.advance(Step(step, 1.0, "sim-tick"))
        }
    } finally {
        ctx.close()
    }
    val sequence = DiagnosticSceneProjector.projectSequence(observations, scenario.unitDefinitions)
    val contactSheet = outputDir.resolve("$id-contact-sheet.png")
    val peakZoom = outputDir.resolve("$id-peak-zoom.png")
    val rendered = DiagnosticContactSheetRenderer.writeArtifacts(
        sequence = sequence,
        map = scenario.map,
        title = title,
        contactSheetPath = contactSheet,
        peakZoomPath = peakZoom,
    )
    val maxStalled = sequence.frames.maxOfOrNull { it.stalledCount } ?: 0
    val maxContacts = sequence.frames.maxOfOrNull { it.hardContactCount } ?: 0
    val finalFrame = sequence.frames.last()
    return buildJsonObject {
        put("id", id)
        put("title", title)
        put("steps", steps)
        put("totalMobileUnits", finalFrame.units.size)
        put("finalArrivedCount", finalFrame.arrivedCount)
        put("finalActiveMoveCount", finalFrame.activeMoveCount)
        put("firstAutonomousMoveStep", sequence.firstAutonomousMoveStep?.let(::JsonPrimitive) ?: JsonNull)
        put("firstHardContactStep", sequence.firstHardContactStep?.let(::JsonPrimitive) ?: JsonNull)
        put("peakCongestionStep", sequence.peakCongestionStep?.let(::JsonPrimitive) ?: JsonNull)
        put("lastArrivalStep", sequence.lastArrivalStep?.let(::JsonPrimitive) ?: JsonNull)
        put("maxStalled2sUnits", maxStalled)
        put("maxHardContactPairs", maxContacts)
        put("contactSheet", rendered.contactSheetPath.toString())
        put("peakZoom", rendered.peakZoomPath?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("keyframeSteps", JsonArray(rendered.keyframeSteps.map(::JsonPrimitive)))
    }
}

private fun buildRocksScenario(
    id: String,
    definitions: Map<String, UnitDefinition>,
    mixed: Boolean,
): Scenario {
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
    val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    val units = (0 until 100).map { index ->
        val x = 420.0 - 36.0 * (index / 10)
        val y = 180.0 + 36.0 * (index % 10)
        val type = if (mixed && index % 4 == 3) "mechGun" else "c_tank"
        UnitSpawn(String.format("tank-%02d", index), "p1", type, Position(x, y), 90.0)
    }
    return baseScenario(id, map, definitions, units)
}

private fun buildOpposedOpenScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
    val w = 50
    val h = 30
    val blocked = BooleanArray(w * h) { idx ->
        val x = idx % w
        val y = idx / w
        x == 0 || x == w - 1 || y == 0 || y == h - 1
    }
    val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    val units = buildList {
        for (i in 0 until 25) {
            val row = i % 5
            val col = i / 5
            add(UnitSpawn(String.format("east-%02d", i), "p1", "c_tank",
                Position(240.0 - col * 28.0, 244.0 + row * 28.0), 90.0))
        }
        for (i in 0 until 25) {
            val row = i % 5
            val col = i / 5
            add(UnitSpawn(String.format("west-%02d", i), "p2", "c_tank",
                Position(760.0 + col * 28.0, 244.0 + row * 28.0), 270.0))
        }
    }
    return baseScenario(id, map, definitions, units)
}

private fun buildOpposedCorridorScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
    val w = 45
    val h = 20
    // 3-tile wide corridor (60 world units wide -> ample room for two r=6 tanks with 12-unit diameters to pass in two lanes!)
    val blocked = BooleanArray(w * h) { idx ->
        val x = idx % w
        val y = idx / w
        if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
        else (x in 14..30) && (y !in 9..11)
    }
    val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    val units = buildList {
        for (i in 0 until 8) {
            val row = i % 2
            val col = i / 2
            add(UnitSpawn(String.format("ceast-%02d", i), "p1", "c_tank",
                Position(220.0 - col * 26.0, 192.0 + row * 16.0), 90.0))
        }
        for (i in 0 until 8) {
            val row = i % 2
            val col = i / 2
            add(UnitSpawn(String.format("cwest-%02d", i), "p2", "c_tank",
                Position(680.0 + col * 26.0, 192.0 + row * 16.0), 270.0))
        }
    }
    return baseScenario(id, map, definitions, units)
}

private fun baseScenario(
    id: String,
    map: MapDefinition,
    definitions: Map<String, UnitDefinition>,
    units: List<UnitSpawn>,
): Scenario = Scenario(
    scenarioId = id,
    map = map,
    unitDefinitions = definitions,
    players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
    units = units,
    coordinateModel = CoordinateModel("public.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
    timeModel = TimeModel("public.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
    movementContract = MovementContract(16.0, 4.0),
    economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 30000L)),
    combatContract = CombatContract(
        worldSeed = 12345,
        initialFrameCounter = -1,
        unitObjectIds = units.mapIndexed { idx, u -> u.unitId to (8L + idx) }.toMap(),
        nextObjectId = 8L + units.size,
    ),
)

private fun buildMixedOvertakeScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
    val w = 44
    val h = 22
    val blocked = BooleanArray(w * h) { idx ->
        val x = idx % w
        val y = idx / w
        if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
        else (y < 6 || y > 15) // 10-tile wide road (200u)
    }
    val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    val units = buildList {
        add(UnitSpawn("heavy-0", "p1", "slow_heavy", Position(340.0, 220.0), 90.0))
        add(UnitSpawn("heavy-1", "p1", "slow_heavy", Position(280.0, 190.0), 90.0))
        add(UnitSpawn("heavy-2", "p1", "slow_heavy", Position(280.0, 250.0), 90.0))
        for (i in 0 until 8) {
            val col = i / 4
            val row = i % 4
            add(UnitSpawn("scout-$i", "p1", "fast_scout", Position(190.0 - col * 24.0, 185.0 + row * 24.0), 90.0))
        }
    }
    return baseScenario(id, map, definitions, units)
}

private fun buildNarrowBridgeYieldScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
    val w = 36
    val h = 21
    val blocked = BooleanArray(w * h) { idx ->
        val x = idx % w
        val y = idx / w
        if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
        else (x in 15..20) && (y != 10) // single-tile bridge at row 10 (y=200..220)
    }
    val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    val units = listOf(
        UnitSpawn("east-0", "p1", "c_tank", Position(330.0, 210.0), 90.0),
        UnitSpawn("west-0", "p2", "c_tank", Position(440.0, 210.0), 270.0),
    )
    return baseScenario(id, map, definitions, units)
}

private fun buildAirFlockScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
    val w = 36
    val h = 24
    val blocked = BooleanArray(w * h) { idx ->
        val x = idx % w
        val y = idx / w
        x == 0 || x == w - 1 || y == 0 || y == h - 1
    }
    val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    val units = (0 until 16).map { i ->
        val col = i / 4
        val row = i % 4
        UnitSpawn("chopper-$i", "p1", "helicopter", Position(100.0 + col * 34.0, 160.0 + row * 34.0), 90.0)
    }
    return baseScenario(id, map, definitions, units)
}
