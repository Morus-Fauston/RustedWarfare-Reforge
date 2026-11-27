package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.*

/**
 * Generates and validates same-origin 2-second red-highlight contact sheets & peak zooms for ENG-P017-009:
 * 1. Exact live-playtest step 36307 19x c_tank open-ground convergence;
 * 2. Symmetric 4-way inward surround (40x c_tank) convergence;
 * 3. Tight tangential 16x c_tank vortex-seed ring breaking orbit & nucleating cleanly.
 *
 * Writes into a cleaned temp directory during ordinary test runs, or into
 * `-Devidence.dir=<path>` when capturing review artifacts.
 */
class CohortAntiVortexVisualEvidenceTest {

    private fun fixturesDir(): Path {
        System.getProperty("public.fixtures.dir")?.let { return Path.of(it) }
        val repoRoot = Path.of(checkNotNull(System.getProperty("repo.root")))
        return repoRoot.resolve("SimulationTest/public/inputs")
    }

    private fun loadRealCTank(): UnitDefinition {
        val packDir = fixturesDir().resolve("phase-five-sandbox/ini/package")
        return assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packDir))
            .value.units.getValue("c_tank")
    }

    private fun openMap(w: Int, h: Int): MapDefinition =
        MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, BooleanArray(w * h)))

    private fun runAndRender(
        scenarioId: String,
        title: String,
        filePrefix: String,
        outDir: Path,
        mapW: Int,
        mapH: Int,
        tankDef: UnitDefinition,
        spawns: List<UnitSpawn>,
        goal: Position,
        maxSteps: Long,
        maxAllowedSweepRad: Double,
    ) {
        val coordModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64")
        val timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta")
        val scene = Scenario(
            scenarioId = scenarioId,
            map = openMap(mapW, mapH),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            unitDefinitions = mapOf(tankDef.id to tankDef),
            units = spawns,
            coordinateModel = coordModel,
            timeModel = timeModel,
            movementContract = MovementContract(16.0, 4.0),
            combatContract = CombatContract(
                12345, -1,
                spawns.mapIndexed { idx, s -> s.unitId to (idx + 1L) }.toMap(),
                (spawns.size + 10L),
            ),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 10000L)),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val obs = mutableListOf<Observation>()
        val prevAng = spawns.associate { it.unitId to atan2(it.position.y - goal.y, it.position.x - goal.x) }.toMutableMap()
        val cumSweep = spawns.associate { it.unitId to 0.0 }.toMutableMap()
        val radius = tankDef.movementCollision.boundingRadius
        var firstNucleation = -1L
        var allSettled = -1L
        try {
            val ids = spawns.map { it.unitId }
            val req = checkNotNull(ctx.requestGroupMove("p1", ids, goal))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m-$id", "p1", id, goal, ctx.world), req))
            }
            obs += ctx.observe()
            for (step in 0L until maxSteps) {
                val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                obs += frame
                var settled = 0
                for (u in frame.units) {
                    val d = hypot(u.position.x - goal.x, u.position.y - goal.y)
                    val ang = atan2(u.position.y - goal.y, u.position.x - goal.x)
                    var dAng = ang - prevAng.getValue(u.unitId)
                    while (dAng > PI) dAng -= 2.0 * PI
                    while (dAng < -PI) dAng += 2.0 * PI
                    if (d > 4.0 && d < 220.0 && u.moveTarget != null) {
                        cumSweep[u.unitId] = cumSweep.getValue(u.unitId) + dAng
                    }
                    prevAng[u.unitId] = ang
                    if (u.moveTarget == null && abs(u.speed) <= 1e-4) settled++
                }
                if (firstNucleation < 0L && settled > 0) firstNucleation = step
                if (allSettled < 0L && settled == ids.size) allSettled = step
                if (allSettled >= 0L && step >= allSettled + 60L) break
            }
        } finally {
            ctx.close()
        }

        val finalUnits = obs.last().units
        val settledEnd = finalUnits.count { it.moveTarget == null && abs(it.speed) <= 1e-4 }
        assertEquals(spawns.size, settledEnd, "$scenarioId must settle 100%: $settledEnd/${spawns.size}")
        for (i in finalUnits.indices) for (j in i + 1 until finalUnits.size) {
            val gap = hypot(finalUnits[i].position.x - finalUnits[j].position.x, finalUnits[i].position.y - finalUnits[j].position.y) - 2.0 * radius
            assertTrue(gap >= -0.05, "$scenarioId final hard overlap detected: gap=$gap")
        }
        val maxSweep = cumSweep.values.maxOf { abs(it) }
        assertTrue(maxSweep < maxAllowedSweepRad, "$scenarioId maxCumSweepRad=$maxSweep exceeded $maxAllowedSweepRad")

        val seq = DiagnosticSceneProjector.projectSequence(obs, scene.unitDefinitions)
        val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
        assertEquals(0, maxStalled, "$scenarioId must have zero 2s red-highlighted stalls (maxStalled=$maxStalled)")
        val art = DiagnosticContactSheetRenderer.writeArtifacts(
            sequence = seq,
            map = scene.map,
            title = "$title (seed@$firstNucleation, all@$allSettled, maxSweep=%.2frad)".format(maxSweep),
            contactSheetPath = outDir.resolve("$filePrefix-contact-sheet.png"),
            peakZoomPath = outDir.resolve("$filePrefix-peak.png"),
        )
        assertTrue(Files.size(art.contactSheetPath) > 4096L)
        assertTrue(Files.size(art.peakZoomPath) > 4096L)
    }

    @Test
    fun render_and_validate_eng_p017_009_anti_vortex_visual_evidence() {
        val explicitDir = System.getProperty("evidence.dir")?.takeIf { it.isNotBlank() }
        val outDir = if (explicitDir != null) Path.of(explicitDir)
        else Files.createTempDirectory("rwr-p017-009-visual-")
        try {
            Files.createDirectories(outDir)
            val cTank = loadRealCTank()

            // 1. Live playtest step 36307 exact 19 c_tank open-ground convergence
            run {
                val ox = 5300.0
                val oy = 5800.0
                val goal = Position(5667.400520833336 - ox, 6175.274532624842 - oy)
                val raw = listOf(
                    Triple(5904.50862735067, 6034.6451534005755, 16.233477747844518),
                    Triple(5944.421166408567, 5999.269052969083, 336.1360945662557),
                    Triple(5930.188414331645, 6048.903823547332, 11.932966355529743),
                    Triple(6133.565088453003, 6073.829315715916, 214.66963757212056),
                    Triple(5931.000389370818, 6093.201888991381, 84.09698914782246),
                    Triple(6120.591965442484, 6091.597210339475, 144.06169579896834),
                    Triple(5924.349889735192, 6072.231174778542, 16.85130351864916),
                    Triple(6130.897183968626, 6033.305779396412, 194.86870958633642),
                    Triple(5925.51490311379, 6027.405956137852, 352.3640313958158),
                    Triple(6130.696678273717, 6111.139338642152, 222.92111480337223),
                    Triple(5907.144668056869, 6008.629014439846, 198.85739306713845),
                    Triple(5958.280367505926, 6062.05941149407, 289.1021501306046),
                    Triple(6120.1933944992825, 6130.470169714657, 127.9714483451744),
                    Triple(5941.750366275263, 6116.906471999449, 274.60996227990483),
                    Triple(5902.793864769823, 6090.917341600373, 329.82094548290934),
                    Triple(6123.782942912637, 6054.123742173716, 158.959926020245),
                    Triple(5885.732163501283, 6074.7002398216, 285.35883148964024),
                    Triple(5913.175605477998, 6113.643881268031, 272.7106629146534),
                    Triple(5909.395187044328, 6056.095597087369, 358.2729838210346),
                )
                val spawns = raw.mapIndexed { idx, (x, y, h) ->
                    UnitSpawn("t-${idx.toString().padStart(2, '0')}", "p1", cTank.id, Position(x - ox, y - oy), h)
                }
                runAndRender(
                    scenarioId = "eng-p017-009-step-36307-19",
                    title = "ENG-P017-009: Step 36307 19x c_tank Open-Ground Convergence",
                    filePrefix = "step-36307-19-tanks",
                    outDir = outDir,
                    mapW = 55, mapH = 36,
                    tankDef = cTank,
                    spawns = spawns,
                    goal = goal,
                    maxSteps = 900L,
                    maxAllowedSweepRad = PI * 1.2,
                )
            }

            // 2. Four-way symmetric inward surround (40 tanks)
            run {
                val goal = Position(460.0, 460.0)
                val spawns = mutableListOf<UnitSpawn>()
                val spacing = 26.0
                var id = 0
                for (r in 0 until 2) for (c in 0 until 5) {
                    spawns += UnitSpawn("n-${id++}", "p1", cTank.id, Position(goal.x + (c - 2) * spacing, goal.y - 210.0 - r * spacing), 180.0)
                    spawns += UnitSpawn("s-${id++}", "p1", cTank.id, Position(goal.x + (c - 2) * spacing, goal.y + 210.0 + r * spacing), 0.0)
                }
                for (r in 0 until 5) for (c in 0 until 2) {
                    spawns += UnitSpawn("w-${id++}", "p1", cTank.id, Position(goal.x - 210.0 - c * spacing, goal.y + (r - 2) * spacing), 90.0)
                    spawns += UnitSpawn("e-${id++}", "p1", cTank.id, Position(goal.x + 210.0 + c * spacing, goal.y + (r - 2) * spacing), 270.0)
                }
                runAndRender(
                    scenarioId = "eng-p017-009-4way-40",
                    title = "ENG-P017-009: 4-Way Symmetric 40x c_tank Convergence",
                    filePrefix = "four-way-surround-40",
                    outDir = outDir,
                    mapW = 46, mapH = 46,
                    tankDef = cTank,
                    spawns = spawns,
                    goal = goal,
                    maxSteps = 900L,
                    maxAllowedSweepRad = PI * 1.0,
                )
            }

            // 3. Tight tangential vortex-seed 16-tank ring breaking orbit immediately
            run {
                fun desiredHeadingDeg(from: Position, to: Position): Double {
                    val deg = Math.toDegrees(atan2(to.x - from.x, -(to.y - from.y)))
                    return ((deg % 360.0) + 360.0) % 360.0
                }
                val goal = Position(360.0, 360.0)
                val spawns = mutableListOf<UnitSpawn>()
                for (i in 0 until 8) {
                    val angle = (2.0 * PI * i) / 8.0
                    val px = goal.x + cos(angle) * 48.0
                    val py = goal.y + sin(angle) * 48.0
                    val radHdg = desiredHeadingDeg(Position(px, py), goal)
                    spawns += UnitSpawn("in-$i", "p1", cTank.id, Position(px, py), (radHdg + 75.0) % 360.0)
                }
                for (i in 0 until 8) {
                    val angle = (2.0 * PI * i + PI / 8.0) / 8.0
                    val px = goal.x + cos(angle) * 82.0
                    val py = goal.y + sin(angle) * 82.0
                    val radHdg = desiredHeadingDeg(Position(px, py), goal)
                    spawns += UnitSpawn("out-$i", "p1", cTank.id, Position(px, py), (radHdg + 75.0) % 360.0)
                }
                runAndRender(
                    scenarioId = "eng-p017-009-tight-ring-16",
                    title = "ENG-P017-009: Tight Tangential 16x c_tank Vortex-Break Nucleation",
                    filePrefix = "tight-tangential-ring-16",
                    outDir = outDir,
                    mapW = 36, mapH = 36,
                    tankDef = cTank,
                    spawns = spawns,
                    goal = goal,
                    maxSteps = 500L,
                    maxAllowedSweepRad = PI * 0.5,
                )
            }
        } finally {
            if (explicitDir == null) {
                runCatching {
                    if (Files.exists(outDir)) {
                        Files.walk(outDir).use { stream ->
                            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                }
            }
        }
    }
}
