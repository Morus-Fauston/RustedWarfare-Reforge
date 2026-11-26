package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.*

/**
 * Unified ScenarioCatalog headless runner, micro-benchmark & 2-second stall-highlighted contact sheet
 * probe (ENG-P016-010B / ENG-P016-010).
 *
 * Shares [ScenarioCatalog] directly with the client startup map/scenario selector and supports:
 * - Default A/B development scenes (`--scene <id|all|dev-ab>`)
 * - Explicit frozen-candidate holdout JSON packs (`--holdout-file <path>`, strictly excluded from dev default)
 * - Optional strict pass assertions (`--assert true|false`)
 * - Complete receipts with `category`, `family`, `group`, `seed`, `mapHash`, `parameterHash`, gate throughput,
 *   step latency distribution, simulation-only heap allocation, and 2s stall-highlighted PNG sheets.
 */
object MovementMicroProbe {
    val ALL_SCENE_IDS: List<String> get() = ScenarioCatalog.allIds()

    fun loadStandardDefinitions(repoRoot: Path): Map<String, UnitDefinition> {
        val packPath = repoRoot.resolve("SimulationTest/public/inputs/phase-six-a-sandbox/json/package")
        val loaded = ContentLoader.loadContentPack(packPath)
        check(loaded is ContentResult.Success) { "Failed to load phase-six-a-sandbox: $loaded" }
        return ScenarioCatalog.deriveBenchmarkDefinitions(loaded.value.units)
    }

    private val SAFE_SCENE_ID_REGEX = Regex("^[A-Za-z0-9._-]+$")
    private val VALID_HOLDOUT_GROUPS = setOf("C", "D", "E", "F")

    /**
     * Loads external frozen-candidate holdout scenario definitions from JSON (C-F matrix).
     * Never loaded unless explicitly requested via `--holdout-file`.
     * Enforces path-safe IDs, forbids shadowing built-in dev A/B scenes, forbids duplicate IDs,
     * and restricts holdout group labels strictly to C-F.
     */
    fun loadExternalHoldoutEntries(file: Path): List<ScenarioCatalogEntry> {
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val scenes = root["scenes"]?.jsonArray ?: throw IllegalArgumentException("Holdout file missing 'scenes' array: $file")
        val seenIds = HashSet<String>()
        return scenes.map { el ->
            val entry = parseExternalHoldoutScene(el.jsonObject)
            require(seenIds.add(entry.id)) { "Duplicate holdout scene id '${entry.id}' in $file" }
            entry
        }
    }

    private fun parseExternalHoldoutScene(obj: JsonObject): ScenarioCatalogEntry {
        val id = requireNotNull(obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) {
            "Holdout scene missing required string field 'id'"
        }
        require(SAFE_SCENE_ID_REGEX.matches(id) && id != "." && id != "..") {
            "Unsafe holdout scene id '$id': must match ^[A-Za-z0-9._-]+$ and cannot be '.' or '..'"
        }
        require(ScenarioCatalog.byId(id) == null) {
            "Holdout scene id '$id' conflicts with built-in dev-ab catalog scene"
        }
        val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: id
        val family = obj["family"]?.jsonPrimitive?.contentOrNull ?: "external-holdout"
        val group = obj["group"]?.jsonPrimitive?.contentOrNull ?: "C"
        require(group in VALID_HOLDOUT_GROUPS) {
            "Holdout scene '$id' has invalid group '$group': external holdouts must use C-F (cannot spoof A/B)"
        }
        val seed = obj["seed"]?.jsonPrimitive?.longOrNull ?: ScenarioCatalog.DEFAULT_WORLD_SEED
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "External holdout scenario $id"
        val defaultSteps = obj["defaultSteps"]?.jsonPrimitive?.intOrNull ?: 1800
        require(defaultSteps > 0) { "Holdout scene '$id' defaultSteps must be > 0, got $defaultSteps" }
        val knownLimitation = obj["knownLimitation"]?.jsonPrimitive?.contentOrNull

        val mapObj = requireNotNull(obj["map"]?.jsonObject) { "Holdout scene '$id' missing required 'map' object" }
        val width = requireNotNull(mapObj["width"]?.jsonPrimitive?.intOrNull) { "Holdout scene '$id' missing integer 'map.width'" }
        val height = requireNotNull(mapObj["height"]?.jsonPrimitive?.intOrNull) { "Holdout scene '$id' missing integer 'map.height'" }
        val tileW = mapObj["tileWidth"]?.jsonPrimitive?.intOrNull ?: 20
        val tileH = mapObj["tileHeight"]?.jsonPrimitive?.intOrNull ?: 20
        require(width > 0 && height > 0 && tileW > 0 && tileH > 0) {
            "Holdout scene '$id' map dimensions must be positive: ${width}x${height}@${tileW}x${tileH}"
        }
        val blockedRows = mapObj["rows"]?.jsonArray
        val blockedRects = mapObj["blockedRects"]?.jsonArray
        val borderBlocked = mapObj["borderBlocked"]?.jsonPrimitive?.booleanOrNull ?: true

        fun buildMap(): MapDefinition {
            val blocked = BooleanArray(width * height)
            if (borderBlocked) {
                for (r in 0 until height) for (c in 0 until width) {
                    if (c == 0 || c == width - 1 || r == 0 || r == height - 1) blocked[r * width + c] = true
                }
            }
            if (blockedRows != null) {
                for (r in 0 until min(height, blockedRows.size)) {
                    val line = blockedRows[r].jsonPrimitive.content
                    for (c in 0 until min(width, line.length)) {
                        if (line[c] == '#' || line[c] == 'X') blocked[r * width + c] = true
                    }
                }
            }
            if (blockedRects != null) {
                for (rectEl in blockedRects) {
                    val rect = rectEl.jsonObject
                    val x0 = requireNotNull(rect["x0"]?.jsonPrimitive?.intOrNull) { "Holdout '$id' blockedRect missing x0" }.coerceIn(0, width - 1)
                    val y0 = requireNotNull(rect["y0"]?.jsonPrimitive?.intOrNull) { "Holdout '$id' blockedRect missing y0" }.coerceIn(0, height - 1)
                    val x1 = requireNotNull(rect["x1"]?.jsonPrimitive?.intOrNull) { "Holdout '$id' blockedRect missing x1" }.coerceIn(0, width - 1)
                    val y1 = requireNotNull(rect["y1"]?.jsonPrimitive?.intOrNull) { "Holdout '$id' blockedRect missing y1" }.coerceIn(0, height - 1)
                    val passable = rect["passable"]?.jsonPrimitive?.booleanOrNull ?: false
                    for (r in y0..y1) for (c in x0..x1) blocked[r * width + c] = !passable
                }
            }
            return ScenarioCatalog.proceduralMap(width, height, blocked, tileW, tileH)
        }

        val unitsArr = requireNotNull(obj["units"]?.jsonArray) { "Holdout scene '$id' missing required 'units' array" }
        val unitSpawns = unitsArr.mapIndexed { ui, uEl ->
            val u = uEl.jsonObject
            val uid = requireNotNull(u["unitId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) {
                "Holdout '$id' unit[$ui] missing 'unitId'"
            }
            val ux = requireNotNull(u["x"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' unit '$uid' missing 'x'" }
            val uy = requireNotNull(u["y"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' unit '$uid' missing 'y'" }
            UnitSpawn(
                unitId = uid,
                ownerPlayerId = u["ownerPlayerId"]?.jsonPrimitive?.contentOrNull ?: "p1",
                typeId = u["typeId"]?.jsonPrimitive?.contentOrNull ?: "c_tank",
                position = Position(ux, uy),
                heading = u["heading"]?.jsonPrimitive?.doubleOrNull ?: 90.0,
            )
        }
        val playersList = obj["players"]?.jsonArray?.map { pEl ->
            val p = pEl.jsonObject
            Player(
                playerId = requireNotNull(p["playerId"]?.jsonPrimitive?.contentOrNull) { "Holdout '$id' player missing 'playerId'" },
                control = PlayerControl.LOCAL,
                teamId = p["teamId"]?.jsonPrimitive?.contentOrNull ?: "allies",
            )
        } ?: listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies"))

        val gates = obj["gates"]?.jsonArray?.mapIndexed { gi, gEl ->
            val g = gEl.jsonObject
            DiagnosticGateLine(
                id = requireNotNull(g["id"]?.jsonPrimitive?.contentOrNull) { "Holdout '$id' gate[$gi] missing 'id'" },
                axisX = g["axisX"]?.jsonPrimitive?.booleanOrNull ?: true,
                entryThreshold = requireNotNull(g["entryThreshold"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' gate[$gi] missing 'entryThreshold'" },
                exitThreshold = requireNotNull(g["exitThreshold"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' gate[$gi] missing 'exitThreshold'" },
                minOrtho = requireNotNull(g["minOrtho"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' gate[$gi] missing 'minOrtho'" },
                maxOrtho = requireNotNull(g["maxOrtho"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' gate[$gi] missing 'maxOrtho'" },
                positiveDirection = g["positiveDirection"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }.orEmpty()

        data class ScheduledOrder(
            val atStep: Long,
            val actionId: String,
            val owner: String,
            val unitIds: List<String>,
            val target: Position,
            val groupMove: Boolean,
        )
        val orders = obj["orders"]?.jsonArray?.mapIndexed { oi, oEl ->
            val o = oEl.jsonObject
            val uIds = requireNotNull(o["unitIds"]?.jsonArray) { "Holdout '$id' order[$oi] missing 'unitIds'" }
                .map { it.jsonPrimitive.content }
            val tx = requireNotNull(o["targetX"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' order[$oi] missing 'targetX'" }
            val ty = requireNotNull(o["targetY"]?.jsonPrimitive?.doubleOrNull) { "Holdout '$id' order[$oi] missing 'targetY'" }
            ScheduledOrder(
                atStep = o["atStep"]?.jsonPrimitive?.longOrNull ?: -1L,
                actionId = o["actionId"]?.jsonPrimitive?.contentOrNull ?: "ho-$id-$oi",
                owner = o["ownerPlayerId"]?.jsonPrimitive?.contentOrNull ?: "p1",
                unitIds = uIds,
                target = Position(tx, ty),
                groupMove = o["groupMove"]?.jsonPrimitive?.booleanOrNull ?: (uIds.size > 1),
            )
        }.orEmpty()

        fun dispatch(ctx: SimulationContext, ord: ScheduledOrder) {
            if (ord.groupMove && ord.unitIds.size > 1) {
                val grp = checkNotNull(ctx.requestGroupMove(ord.owner, ord.unitIds, ord.target)) {
                    "Holdout '$id' order '${ord.actionId}' requestGroupMove returned null for ${ord.unitIds}"
                }
                for (uid in ord.unitIds) {
                    val res = ctx.submitGroupMove(MoveAction("${ord.actionId}-$uid", ord.owner, uid, ord.target, ctx.world), grp)
                    check(res is ActionResult.Accepted) {
                        "Holdout '$id' group order '${ord.actionId}' rejected for unit '$uid': $res"
                    }
                }
                return
            }
            for (uid in ord.unitIds) {
                val res = ctx.submit(MoveAction("${ord.actionId}-$uid", ord.owner, uid, ord.target, ctx.world))
                check(res is ActionResult.Accepted) {
                    "Holdout '$id' order '${ord.actionId}' rejected for unit '$uid': $res"
                }
            }
        }

        val minArrived = obj["minArrived"]?.jsonPrimitive?.intOrNull
        val maxContacts = obj["maxHardContactPairs"]?.jsonPrimitive?.intOrNull
        val customDefs = obj["customUnitDefinitions"]?.jsonArray

        return ScenarioCatalogEntry(
            id = id,
            displayName = displayName,
            category = ScenarioCategory.EXTERNAL_HOLDOUT,
            family = family,
            group = group,
            seed = seed,
            description = description,
            defaultSteps = defaultSteps,
            playerCount = playersList.size,
            startingUnitCount = unitSpawns.size,
            knownLimitation = knownLimitation,
            gates = gates,
            buildMap = ::buildMap,
            buildScenario = { defs ->
                val mergedDefs = LinkedHashMap(defs)
                if (customDefs != null) {
                    val baseTank = defs.getValue("c_tank")
                    for (cdEl in customDefs) {
                        val cd = cdEl.jsonObject
                        val cid = cd["id"]!!.jsonPrimitive.content
                        val radius = cd["radius"]?.jsonPrimitive?.double ?: 6.0
                        val moveSpeed = cd["moveSpeed"]?.jsonPrimitive?.double ?: baseTank.movement.moveSpeed
                        val mass = cd["mass"]?.jsonPrimitive?.double ?: 3000.0
                        val rectL = cd["rectLength"]?.jsonPrimitive?.double
                        val rectW = cd["rectWidth"]?.jsonPrimitive?.double
                        val shape = if (rectL != null && rectW != null) MovementCollisionShape.Rectangle(rectL, rectW)
                        else MovementCollisionShape.Circle(radius)
                        mergedDefs[cid] = UnitDefinition(
                            id = cid, className = baseTank.className, radius = radius, displayRadius = radius,
                            totalFrames = baseTank.totalFrames, imagePath = baseTank.imagePath, imageScale = baseTank.imageScale,
                            movement = baseTank.movement.copy(moveSpeed = moveSpeed),
                            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
                            combat = baseTank.combat, durability = baseTank.durability, build = baseTank.build,
                            movementCollision = shape, explicitMovementCollision = true, mass = mass,
                        )
                    }
                }
                ScenarioCatalog.baseScenario(id, buildMap(), mergedDefs, unitSpawns, worldSeed = seed, players = playersList)
            },
            issueOrders = { ctx ->
                for (ord in orders) if (ord.atStep < 0L) dispatch(ctx, ord)
            },
            onStep = { ctx, step ->
                for (ord in orders) if (ord.atStep == step) dispatch(ctx, ord)
            },
            assertOutcome = { seq ->
                val errs = mutableListOf<String>()
                val last = seq.frames.last()
                if (minArrived != null && last.arrivedCount < minArrived) {
                    errs += "Expected >= $minArrived arrived, got ${last.arrivedCount}/${last.units.size}"
                }
                if (maxContacts != null) {
                    val peakContacts = seq.frames.maxOfOrNull { it.hardContactCount } ?: 0
                    if (peakContacts > maxContacts) {
                        errs += "Expected <= $maxContacts hard contacts, got $peakContacts"
                    }
                }
                errs
            },
        )
    }

    fun runSceneProbe(
        entry: ScenarioCatalogEntry,
        definitions: Map<String, UnitDefinition>,
        outputDir: Path,
        stepsOverride: Int? = null,
        renderSheets: Boolean = true,
    ): JsonObject {
        val steps = stepsOverride ?: entry.defaultSteps
        check(steps > 0) { "steps must be positive: $steps" }
        Files.createDirectories(outputDir)

        val scenario = entry.buildScenario(definitions)
        val mapSha = ScenarioCatalog.mapHash(scenario.map)
        val paramSha = MovementParameterFingerprint.sha256Hex()
        val init = SimulationContext.initialize(scenario)
        check(init is InitResult.Accepted) { "Init rejected for ${entry.id}: $init" }
        val ctx = init.context

        val observations = ArrayList<Observation>(steps + 1)
        val stepNanos = LongArray(steps)
        val stepAllocBytes = LongArray(steps)

        val sunThreadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        sunThreadBean?.let {
            if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) {
                it.isThreadAllocatedMemoryEnabled = true
            }
        }
        val threadId = Thread.currentThread().threadId()
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcCountBefore = gcBeans.sumOf { max(0L, it.collectionCount) }
        val gcTimeMsBefore = gcBeans.sumOf { max(0L, it.collectionTime) }
        val wallSimStart = System.nanoTime()

        try {
            entry.issueOrders(ctx)
            observations += ctx.observe()
            for (i in 0 until steps) {
                val stepIdx = i.toLong()
                entry.onStep(ctx, stepIdx)
                val tickStep = Step(stepIdx, 1.0, "sim-tick")
                val allocBefore = sunThreadBean?.getThreadAllocatedBytes(threadId) ?: 0L
                val t0 = System.nanoTime()
                val obs = ctx.advance(tickStep)
                val dt = (System.nanoTime() - t0).coerceAtLeast(0L)
                val allocAfter = sunThreadBean?.getThreadAllocatedBytes(threadId) ?: 0L
                stepNanos[i] = dt
                stepAllocBytes[i] = (allocAfter - allocBefore).coerceAtLeast(0L)
                observations += obs
            }
        } finally {
            ctx.close()
        }

        val simWallSeconds = (System.nanoTime() - wallSimStart).coerceAtLeast(0L) / 1_000_000_000.0
        val gcCountDelta = gcBeans.sumOf { max(0L, it.collectionCount) } - gcCountBefore
        val gcTimeMsDelta = gcBeans.sumOf { max(0L, it.collectionTime) } - gcTimeMsBefore

        val sequence = DiagnosticSceneProjector.projectSequence(observations, scenario.unitDefinitions, gates = entry.gates)
        val assertionFailures = entry.assertOutcome(sequence)
        val enrichedGateStats = sequence.gateSummaries.map { gs ->
            buildJsonObject {
                put("gateId", gs.gateId)
                put("enteredUnits", gs.enteredCount)
                put("sternExitedUnits", gs.sternExitedCount)
                put("crossedUnits", gs.sternExitedCount)
                put("targetSideSettledUnits", gs.targetSideSettledCount)
                put("firstEntryStep", gs.firstEntryStep?.let(::JsonPrimitive) ?: JsonNull)
                put("firstCrossStep", gs.firstExitStep?.let(::JsonPrimitive) ?: JsonNull)
                put("lastCrossStep", gs.lastExitStep?.let(::JsonPrimitive) ?: JsonNull)
                put("maxExitGapSteps", gs.maxExitGapSteps)
                put("throughputUnitsPerSec", round3(gs.throughputUnitsPerSec))
            }
        }

        var contactSheetPathStr: String? = null
        var peakZoomPathStr: String? = null
        if (renderSheets) {
            val contactSheet = outputDir.resolve("${entry.id}-contact-sheet.png")
            val peakZoom = outputDir.resolve("${entry.id}-peak-zoom.png")
            val rendered = DiagnosticContactSheetRenderer.writeArtifacts(
                sequence = sequence,
                map = scenario.map,
                title = "Probe: ${entry.id} (${entry.displayName})",
                contactSheetPath = contactSheet,
                peakZoomPath = peakZoom,
            )
            contactSheetPathStr = rendered.contactSheetPath.toString()
            peakZoomPathStr = rendered.peakZoomPath?.toString()
        }

        val sortedNanos = stepNanos.copyOf().apply { sort() }
        val sortedAlloc = stepAllocBytes.copyOf().apply { sort() }
        val steadyStart = min(30, steps - 1)
        val steadySlice = stepAllocBytes.copyOfRange(steadyStart, steps)
        val steadyMeanAlloc = if (steadySlice.isNotEmpty()) steadySlice.average() else 0.0

        val finalFrame = sequence.frames.last()
        val maxStalled = sequence.frames.maxOfOrNull { it.stalledCount } ?: 0
        val maxContacts = sequence.frames.maxOfOrNull { it.hardContactCount } ?: 0

        val report = buildJsonObject {
            put("sceneId", entry.id)
            put("title", entry.displayName)
            put("category", entry.category.id)
            put("family", entry.family)
            put("group", entry.group)
            put("seed", entry.seed)
            put("mapHash", mapSha)
            put("parameterHash", paramSha)
            put("knownLimitation", entry.knownLimitation?.let(::JsonPrimitive) ?: JsonNull)
            put("steps", steps)
            put("simWallSeconds", round3(simWallSeconds))
            put("totalMobileUnits", finalFrame.units.size)
            put("finalArrivedCount", finalFrame.arrivedCount)
            put("finalActiveMoveCount", finalFrame.activeMoveCount)
            put("firstAutonomousMoveStep", sequence.firstAutonomousMoveStep?.let(::JsonPrimitive) ?: JsonNull)
            put("firstHardContactStep", sequence.firstHardContactStep?.let(::JsonPrimitive) ?: JsonNull)
            put("peakCongestionStep", sequence.peakCongestionStep?.let(::JsonPrimitive) ?: JsonNull)
            put("lastArrivalStep", sequence.lastArrivalStep?.let(::JsonPrimitive) ?: JsonNull)
            put("maxStalled2sUnits", maxStalled)
            put("maxHardContactPairs", maxContacts)
            put("assertionsPassed", assertionFailures.isEmpty())
            put("assertionFailures", JsonArray(assertionFailures.map(::JsonPrimitive)))
            put("stepLatencyMs", buildJsonObject {
                put("mean", round4(stepNanos.average() / 1_000_000.0))
                put("p50", round4(percentileLong(sortedNanos, 0.50) / 1_000_000.0))
                put("p95", round4(percentileLong(sortedNanos, 0.95) / 1_000_000.0))
                put("p99", round4(percentileLong(sortedNanos, 0.99) / 1_000_000.0))
                put("max", round4(sortedNanos.last() / 1_000_000.0))
            })
            put("heapAllocation", buildJsonObject {
                put("supported", sunThreadBean?.isThreadAllocatedMemorySupported == true)
                put("totalSimBytes", stepAllocBytes.sum())
                put("steadyStateMeanBytesPerStep", kotlin.math.round(steadyMeanAlloc).toLong())
                put("p50BytesPerStep", percentileLong(sortedAlloc, 0.50))
                put("p99BytesPerStep", percentileLong(sortedAlloc, 0.99))
                put("gcCountDelta", gcCountDelta)
                put("gcTimeMsDelta", gcTimeMsDelta)
            })
            put("gateThroughput", JsonArray(enrichedGateStats))
            put("contactSheet", contactSheetPathStr?.let(::JsonPrimitive) ?: JsonNull)
            put("peakZoom", peakZoomPathStr?.let(::JsonPrimitive) ?: JsonNull)
        }
        Files.writeString(
            outputDir.resolve("${entry.id}-probe.json"),
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n",
        )
        return report
    }

    /** Executes unified `--scene` CLI arguments and returns exit code (0 = pass, 2 = assertion failure). */
    fun executeCli(args: Array<String>): Int {
        var repoRoot: Path? = null
        var outputDir: Path? = null
        var sceneFilter = "rocks-small-100"
        var stepsOverride: Int? = null
        var renderSheets = true
        var assertMode = false
        var holdoutFile: Path? = null
        var listMode = false

        var idx = 0
        while (idx < args.size) {
            when (val arg = args[idx++]) {
                "--repo-root" -> repoRoot = Path.of(args.getOrElse(idx++) { error("Missing value for --repo-root") })
                "--output" -> outputDir = Path.of(args.getOrElse(idx++) { error("Missing value for --output") })
                "--scene" -> sceneFilter = args.getOrElse(idx++) { error("Missing value for --scene") }
                "--steps" -> stepsOverride = args.getOrElse(idx++) { error("Missing value for --steps") }.toInt()
                "--sheets" -> renderSheets = args.getOrElse(idx++) { error("Missing value for --sheets") }.toBooleanStrict()
                "--assert" -> assertMode = args.getOrElse(idx++) { error("Missing value for --assert") }.toBooleanStrict()
                "--holdout-file" -> holdoutFile = Path.of(args.getOrElse(idx++) { error("Missing value for --holdout-file") })
                "--list" -> listMode = true
                else -> error("Unknown argument: $arg")
            }
        }

        val externalEntries = holdoutFile?.let(::loadExternalHoldoutEntries).orEmpty()
        val allEntries = linkedMapOf<String, ScenarioCatalogEntry>().apply {
            for (e in ScenarioCatalog.devBenchmarkEntries()) put(e.id, e)
            for (e in externalEntries) put(e.id, e)
        }

        if (listMode) {
            val payload = buildJsonObject {
                put("schemaVersion", 2)
                put("parameterHash", MovementParameterFingerprint.sha256Hex())
                put("parameters", buildJsonObject {
                    for ((k, v) in MovementParameterFingerprint.canonicalParameters()) put(k, v)
                })
                put("scenes", JsonArray(allEntries.values.map { e ->
                    buildJsonObject {
                        put("id", e.id)
                        put("displayName", e.displayName)
                        put("category", e.category.id)
                        put("family", e.family)
                        put("group", e.group)
                        put("seed", e.seed)
                        put("defaultSteps", e.defaultSteps)
                        put("knownLimitation", e.knownLimitation?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }))
            }
            println(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload))
            return 0
        }

        val resolvedRepo = (repoRoot ?: generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("AGENTS.md")) || Files.isDirectory(it.resolve("SimulationTest")) }
            ?: error("Cannot locate repository root; pass --repo-root")).toAbsolutePath().normalize()
        val resolvedOut = (outputDir ?: Files.createTempDirectory("reforge-movement-probe-")).toAbsolutePath().normalize()
        Files.createDirectories(resolvedOut)

        val selectedIds = when (sceneFilter) {
            "all" -> if (holdoutFile != null) allEntries.keys.toList() else ScenarioCatalog.allIds()
            "dev-ab" -> ScenarioCatalog.allIds()
            "holdout" -> {
                require(externalEntries.isNotEmpty()) { "--scene holdout requires --holdout-file <json>" }
                externalEntries.map { it.id }
            }
            else -> sceneFilter.split(',').map(String::trim).filter(String::isNotEmpty)
        }
        require(selectedIds.isNotEmpty()) { "--scene must select at least one scenario" }
        for (id in selectedIds) {
            require(id in allEntries) { "Unknown catalog scene '$id'; available=${allEntries.keys}" }
        }

        val definitions = loadStandardDefinitions(resolvedRepo)
        val reports = selectedIds.map { id ->
            val entry = allEntries.getValue(id)
            val sceneOut = resolvedOut.resolve(id).normalize()
            require(sceneOut.startsWith(resolvedOut)) { "Resolved scene output path escapes --output: $sceneOut" }
            runSceneProbe(
                entry = entry,
                definitions = definitions,
                outputDir = sceneOut,
                stepsOverride = stepsOverride,
                renderSheets = renderSheets,
            )
        }
        val allPassed = reports.all { it["assertionsPassed"]?.jsonPrimitive?.booleanOrNull == true }
        val summary = buildJsonObject {
            put("schemaVersion", 2)
            put("parameterHash", MovementParameterFingerprint.sha256Hex())
            put("outputDir", resolvedOut.toString())
            put("assertMode", assertMode)
            put("allAssertionsPassed", allPassed)
            put("scenes", JsonArray(reports))
        }
        val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)
        Files.writeString(resolvedOut.resolve("probe-summary.json"), text + "\n")
        println(text)
        return if (assertMode && !allPassed) 2 else 0
    }

    private fun percentileLong(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
    private fun round4(v: Double): Double = kotlin.math.round(v * 10000.0) / 10000.0
}

fun main(args: Array<String>) {
    val code = MovementMicroProbe.executeCli(args)
    if (code != 0) kotlin.system.exitProcess(code)
}
