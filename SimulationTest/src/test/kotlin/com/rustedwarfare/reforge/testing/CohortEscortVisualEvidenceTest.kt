package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/**
 * Generates and validates same-origin 2-second red-highlight contact sheets & peak zooms for ENG-P017-010:
 * 1. Island naval normal right-click (gunboats shortcut south narrow strait ahead of battleship);
 * 2. Island naval Alt cohort escort (gunboats & battleship sail together through wide north channel);
 * 3. Reversed vanguard overtake escort (5 fast fragile lights start ahead of 5 slow heavy vanguards ->
 *    heavies overtake to lead the wavefront while lights pace behind with zero 2s red stalls);
 * 4. Cross-layer air-ground Alt escort (helicopters pace alongside slow ground tanks).
 */
class CohortEscortVisualEvidenceTest {

    private fun fixturesDir(): Path {
        System.getProperty("public.fixtures.dir")?.let { return Path.of(it) }
        val repoRoot = Path.of(checkNotNull(System.getProperty("repo.root")))
        return repoRoot.resolve("SimulationTest/public/inputs")
    }

    private fun loadBenchmarkDefs(): Map<String, UnitDefinition> {
        val packDir = fixturesDir().resolve("phase-five-sandbox/ini/package")
        val base = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packDir)).value.units
        return ScenarioCatalog.deriveBenchmarkDefinitions(base)
    }

    private fun runAndRender(
        scene: Scenario,
        title: String,
        filePrefix: String,
        outDir: Path,
        gates: List<DiagnosticGateLine>,
        maxSteps: Long,
        issueOrders: (SimulationContext) -> Unit,
    ): DiagnosticSequenceProjection {
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val obs = ArrayList<Observation>(maxSteps.toInt() + 1)
        try {
            issueOrders(ctx)
            obs += ctx.observe()
            for (step in 0L until maxSteps) {
                val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                obs += frame
                if (step >= 20L && frame.units.all { it.moveTarget == null && abs(it.speed) <= 1e-4 }) {
                    // Add 30 settle frames for visual clarity
                    for (k in 1L..30L) obs += ctx.advance(Step(step + k, 1.0, "sim-tick"))
                    break
                }
            }
        } finally {
            ctx.close()
        }
        val seq = DiagnosticSceneProjector.projectSequence(obs, scene.unitDefinitions, gates = gates)
        val art = DiagnosticContactSheetRenderer.writeArtifacts(
            sequence = seq,
            map = scene.map,
            title = title,
            contactSheetPath = outDir.resolve("$filePrefix-contact-sheet.png"),
            peakZoomPath = outDir.resolve("$filePrefix-peak.png"),
        )
        assertTrue(Files.size(art.contactSheetPath) > 4096L)
        art.peakZoomPath?.let { assertTrue(Files.size(it) > 4096L) }
        return seq
    }

    @Test
    fun render_and_validate_eng_p017_010_cohort_escort_visual_evidence() {
        val explicitDir = System.getProperty("evidence.dir")?.takeIf { it.isNotBlank() }
        val outDir = if (explicitDir != null) Path.of(explicitDir)
        else Files.createTempDirectory("rwr-p017-010-visual-")
        try {
            Files.createDirectories(outDir)
            val defs = loadBenchmarkDefs()
            val islandEntry = checkNotNull(ScenarioCatalog.byId("cohort-island-naval-escort"))

            // 1. Island naval NORMAL right-click (contrast baseline: gunboats take narrow south strait)
            run {
                val scene = islandEntry.buildScenario(defs)
                val seq = runAndRender(
                    scene = scene,
                    title = "ENG-P017-010 Contrast: Normal Right-Click (Gunboats Shortcut South Strait)",
                    filePrefix = "island-normal-shortcut",
                    outDir = outDir,
                    gates = islandEntry.gates,
                    maxSteps = 2800L,
                ) { ctx ->
                    val ids = listOf("bs-0") + (0 until 6).map { "gb-$it" }
                    val goal = Position(960.0, 480.0)
                    val g = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = false))
                    for (id in ids) check(ctx.submitGroupMove(MoveAction("norm-$id", "p1", id, goal, ctx.world, cohortEscort = false), g) is ActionResult.Accepted)
                }
                assertEquals(7, seq.frames.last().arrivedCount)
                assertTrue(seq.gateSummaries.first { it.gateId == "south-narrow-strait" }.sternExitedCount >= 5)
            }

            // 2. Island naval ALT escort (gunboats accompany battleship around wide north channel)
            run {
                val scene = islandEntry.buildScenario(defs)
                val seq = runAndRender(
                    scene = scene,
                    title = "ENG-P017-010 Alt Escort: Shared Wide North Channel Together",
                    filePrefix = "island-alt-escort",
                    outDir = outDir,
                    gates = islandEntry.gates,
                    maxSteps = 2800L,
                    issueOrders = islandEntry.issueOrders,
                )
                val failures = islandEntry.assertOutcome(seq)
                assertTrue(failures.isEmpty(), "Island Alt escort failed: $failures")
            }

            // 3. Reversed vanguard overtake escort (5 fast light ahead + 5 slow heavy behind)
            run {
                val vanEntry = checkNotNull(ScenarioCatalog.byId("cohort-vanguard-escort-march"))
                val scene = vanEntry.buildScenario(defs)
                val seq = runAndRender(
                    scene = scene,
                    title = "ENG-P017-010 Alt Vanguard Overtake: Heavy Leads, Fast Escorts Behind",
                    filePrefix = "vanguard-overtake-escort",
                    outDir = outDir,
                    gates = vanEntry.gates,
                    maxSteps = 2200L,
                    issueOrders = vanEntry.issueOrders,
                )
                val failures = vanEntry.assertOutcome(seq)
                assertTrue(failures.isEmpty(), "Vanguard escort failed: $failures")
            }

            // 4. Cross-layer air-ground escort (helicopters + heavy tanks)
            run {
                val map = ScenarioCatalog.buildCohortVanguardMap()
                val goal = Position(1080.0, 240.0)
                val spawns = listOf(
                    UnitSpawn("tank-0", "p1", "heavy_vanguard", Position(150.0, 200.0), 90.0),
                    UnitSpawn("tank-1", "p1", "heavy_vanguard", Position(150.0, 240.0), 90.0),
                    UnitSpawn("tank-2", "p1", "heavy_vanguard", Position(150.0, 280.0), 90.0),
                    UnitSpawn("air-0", "p1", "helicopter", Position(220.0, 190.0), 90.0),
                    UnitSpawn("air-1", "p1", "helicopter", Position(220.0, 240.0), 90.0),
                    UnitSpawn("air-2", "p1", "helicopter", Position(220.0, 290.0), 90.0),
                )
                val scene = ScenarioCatalog.baseScenario("eng-p017-010-air-ground", map, defs, spawns)
                val seq = runAndRender(
                    scene = scene,
                    title = "ENG-P017-010 Cross-Layer Alt Escort: Helicopters Pace Ground Tanks",
                    filePrefix = "air-ground-escort",
                    outDir = outDir,
                    gates = emptyList(),
                    maxSteps = 1800L,
                ) { ctx ->
                    val ids = spawns.map { it.unitId }
                    val g = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
                    for (id in ids) check(ctx.submitGroupMove(MoveAction("airg-$id", "p1", id, goal, ctx.world, cohortEscort = true), g) is ActionResult.Accepted)
                }
                assertEquals(6, seq.frames.last().arrivedCount)
                assertEquals(0, seq.frames.maxOf { it.stalledCount })
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
