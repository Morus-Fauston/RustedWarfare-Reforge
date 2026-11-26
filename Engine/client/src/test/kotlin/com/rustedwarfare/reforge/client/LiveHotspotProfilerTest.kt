package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.SimHotspotCollector
import com.rustedwarfare.reforge.core.sim.SimHotspotStage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LiveHotspotProfilerTest {

    private val fixturesDir: Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()

    @Test
    fun ringBufferPercentilesSlowFrameTop10AndJsonSchemaAreCompleteAndBounded() {
        var fakeAlloc = 1_000_000L
        var fakeGcCount = 2L
        var fakeGcTimeMs = 10L
        val fakeTelemetry = object : RuntimeMemoryTelemetry {
            override val supported: Boolean = true
            override fun currentThreadAllocatedBytes(): Long = fakeAlloc
            override fun totalGcCountAndTimeMs(): Pair<Long, Long> = fakeGcCount to fakeGcTimeMs
        }

        val profiler = LiveHotspotProfiler(
            ringCapacity = 32,
            warmupFramesToSkip = 2,
            slowFrameThresholdMs = 33.0,
            telemetry = fakeTelemetry,
        )
        profiler.resetMatch(
            mapId = "[z;p10]Many Islands Large (10p)",
            widthTiles = 350,
            heightTiles = 350,
            visualSource = "original",
            visualProfile = "rw.pc.steam.9902063.images.v8.mapped",
            visualIdentity = "sha256:verified-pack",
        )

        val simCollector = SimHotspotCollector()
        var nowNs = 1_000_000_000L

        // Record 42 frames (2 warmup + 40 steady-state -> wraps ringCapacity=32; 12 slow frames > 33ms -> Top-10 retained)
        for (f in 0 until 42) {
            val isSlow = f >= 30 // 12 slow frames at the end (34ms .. 45ms)
            val workMs = if (isSlow) (34 + (f - 30)).toDouble() else 4.0 + (f % 5)
            val workNs = (workMs * 1_000_000.0).toLong()

            profiler.beginMatchFrame(nowNs)
            fakeAlloc += 128_000L
            if (isSlow && f % 3 == 0) {
                fakeGcCount += 1L
                fakeGcTimeMs += 4L
            }

            simCollector.addNanos(SimHotspotStage.NAV_FLOW, workNs / 6)
            simCollector.addNanos(SimHotspotStage.NAV_PLAN, workNs / 12)
            simCollector.addNanos(SimHotspotStage.NAV_AVOID, workNs / 6)
            simCollector.addNanos(SimHotspotStage.SIM_CONTACT, workNs / 12)
            simCollector.addNanos(SimHotspotStage.SIM_VISION, workNs / 12)
            simCollector.addNanos(SimHotspotStage.SIM_COMBAT, workNs / 12)
            simCollector.addNanos(SimHotspotStage.SIM_FORK_COMMIT, workNs / 12)
            simCollector.recordStepCompleted()
            profiler.absorbSimCollector(simCollector)

            profiler.addClientStageNanos(HotspotStage.CLIENT_OBSERVE, if (isSlow) workNs / 3 else workNs / 10)
            profiler.addClientStageNanos(HotspotStage.RENDER_TERRAIN, workNs / 10)
            profiler.addClientStageNanos(HotspotStage.RENDER_ENTITIES, workNs / 12)
            profiler.addClientStageNanos(HotspotStage.RENDER_MINIMAP, workNs / 20)
            profiler.addClientStageNanos(HotspotStage.RENDER_HUD, workNs / 20)

            profiler.endMatchFrame(
                stepIndex = (f * 4).toLong(),
                executedSubsteps = 4,
                pendingSteps = if (isSlow) 2.0 else 0.0,
                requestedRate = 4,
                actualMultiplier = 3.95,
                liveUnits = 150 + f,
                activeFlowFields = 3,
                cachedFlowFields = 6,
                endNanos = nowNs + workNs,
            )
            nowNs += maxOf(workNs, 16_666_667L)
        }

        val summary = profiler.buildSummary()
        assertEquals(42L, summary.totalMatchFrames)
        assertEquals(2, summary.warmupFramesSkipped)
        assertEquals(32, summary.steadyStateSampledFrames, "Ring buffer must cap steady-state window at ringCapacity")
        assertEquals(12L, summary.slowFrameCount)
        assertEquals(10, summary.slowFramesTop10.size, "Top-10 must keep at most 10 slowest frames")
        assertEquals(45.0, summary.slowFramesTop10.first().frameWorkMs, 0.01, "Slowest frame must rank #1")
        assertEquals(36.0, summary.slowFramesTop10.last().frameWorkMs, 0.01, "10th slowest of 34..45ms must be 36ms")
        assertEquals("client.observe", summary.slowFramesTop10.first().dominantStage)
        assertTrue(summary.profilerSelfOverheadMsPerFrame < 0.2, "Slow-frame snapshot test overhead must remain small: ${summary.profilerSelfOverheadMsPerFrame}")

        val steadyProfiler = LiveHotspotProfiler(ringCapacity = 512, warmupFramesToSkip = 16, telemetry = fakeTelemetry)
        steadyProfiler.resetMatch("steady", 350, 350)
        for (f in 0 until 512) {
            val t0 = 2_000_000_000L + f * 16_666_667L
            steadyProfiler.beginMatchFrame(t0)
            steadyProfiler.addClientStageNanos(HotspotStage.RENDER_TERRAIN, 4_000_000L)
            steadyProfiler.endMatchFrame((f * 4).toLong(), 4, 0.0, 4, 4.0, 200, 4, 8, t0 + 8_000_000L)
        }
        val steadySummary = steadyProfiler.buildSummary()
        assertTrue(steadySummary.profilerSelfOverheadMsPerFrame < 0.02, "Steady-state self overhead must be < 0.02 ms/frame, got ${steadySummary.profilerSelfOverheadMsPerFrame}")
        assertEquals(12, summary.stagesMs.size, "All 12 stages (7 sim + 5 client) must be present")
        for (stage in HotspotStage.ALL) {
            val st = summary.stagesMs[stage.key]
            assertNotNull(st, "Missing stage ${stage.key}")
            assertTrue(st!!.max > 0.0, "Stage ${stage.key} must record positive timings")
            assertTrue(st.p50 <= st.p95 && st.p95 <= st.p99 && st.p99 <= st.max)
        }

        val overlayLines = profiler.formatOverlayLines()
        assertEquals(5, overlayLines.size)
        assertTrue(overlayLines[0].startsWith("hotspots [F3+H]"))

        val tempDir = Files.createTempDirectory("hotspot-summary-test-")
        try {
            val jsonFile = tempDir.resolve("hotspot-summary.json")
            profiler.writeSummaryJson(jsonFile, summary)
            assertTrue(Files.isRegularFile(jsonFile))
            val raw = Files.readString(jsonFile)
            assertTrue(raw.length in 500..32_768, "Summary JSON must stay compact (<32KB), got ${raw.length} bytes")
            val parsed = JsonReader().parse(raw)
            assertEquals(1, parsed.getInt("schemaVersion"))
            assertEquals("[z;p10]Many Islands Large (10p)", parsed.getString("mapId"))
            assertEquals(350, parsed.get("mapTiles").getInt("width"))
            assertEquals("rw.pc.steam.9902063.images.v8.mapped", parsed.get("visualAssets").getString("profile"))
            assertEquals(10, parsed.get("slowFramesTop10").size)
            assertEquals(12, parsed.get("stagesMs").size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun enablingHotspotProfilerAndF3SublayerPreservesBitIdenticalSimulationDeterminism() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        val stateHotspots = HudDebugLayerState(enabled = true, showHotspots = true)
        assertTrue(HudDebugProjection.buildLayersLine(stateHotspots).endsWith("· [H] hotspots ON"))
        assertFalse(HudDebugLayerState().toggleHotspots().showHotspots, "toggleHotspots must be ignored when F3 is closed")

        val withoutProfiler = SandboxSession(base).use { s ->
            // Detach simulation hotspot collector completely on reference run
            val units = s.observation.units.filter { it.ownerPlayerId == "p1" && it.typeId == "builder" }
            val b = units.first()
            s.select(b.position)
            s.move(Position(b.position.x + 140.0, b.position.y + 60.0))
            repeat(60) { s.tick(publishRenderFrame = (it % 4 == 3)) }
            s.observation.units.map { Triple(it.unitId, it.position.x.toBits() to it.position.y.toBits(), it.heading.toBits()) }
        }

        val profiler = LiveHotspotProfiler(warmupFramesToSkip = 0)
        profiler.resetMatch("maint", 30, 20)
        val withProfiler = SandboxSession(base).use { s ->
            val units = s.observation.units.filter { it.ownerPlayerId == "p1" && it.typeId == "builder" }
            val b = units.first()
            s.select(b.position)
            s.move(Position(b.position.x + 140.0, b.position.y + 60.0))
            val proj = MapProjection(600.0, 400.0)
            repeat(15) { frameIdx ->
                profiler.beginMatchFrame()
                repeat(4) { sub ->
                    s.tick(publishRenderFrame = (sub == 3))
                }
                profiler.absorbSimCollector(s.simHotspotCollector)
                profiler.addClientStageNanos(HotspotStage.CLIENT_OBSERVE, s.drainPublishFrameNanos())
                profiler.measureClientStage(HotspotStage.RENDER_HUD) {
                    val metrics = HudDebugFrameMetrics(
                        60, 4.0f, 8.0f, 4, 4.0, 0, "maint", null,
                        hotspotLines = profiler.formatOverlayLines(),
                    )
                    val snap = HudDebugProjection.project(s, proj, stateHotspots, metrics, 1280f)
                    assertTrue(snap.hotspotLines.isNotEmpty())
                }
                profiler.endMatchFrame(
                    stepIndex = s.observation.stepIndex,
                    executedSubsteps = 4,
                    pendingSteps = 0.0,
                    requestedRate = 4,
                    actualMultiplier = 4.0,
                    liveUnits = s.liveUnitCountForProfiler,
                    activeFlowFields = s.activeFlowFieldCountForProfiler,
                    cachedFlowFields = s.cachedFlowFieldCountForProfiler,
                )
            }
            s.observation.units.map { Triple(it.unitId, it.position.x.toBits() to it.position.y.toBits(), it.heading.toBits()) }
        }

        assertEquals(withoutProfiler, withProfiler, "LiveHotspotProfiler must never alter authoritative simulation bits")
        val summary = profiler.buildSummary()
        assertEquals(15L, summary.totalMatchFrames)
        assertEquals(60L, summary.totalExecutedSubsteps)
        assertTrue(summary.stagesMs.getValue("sim.forkCommit").max > 0.0)
    }

    @Test
    fun sandboxInputTracksSyntheticAltModifierCleanly() {
        val input = SandboxInput(
            session = { null },
            projection = { null },
            uiPoint = { _, _ -> null },
            active = { true },
            editing = { false },
            pressed = { false },
        )
        assertFalse(input.alt())
        input.keyDown(Input.Keys.ALT_LEFT)
        assertTrue(input.alt())
        input.keyUp(Input.Keys.ALT_LEFT)
        assertFalse(input.alt())

        input.keyDown(Input.Keys.ALT_RIGHT)
        assertTrue(input.alt())
        input.cancel()
        assertFalse(input.alt(), "cancel() must clear synthetic held Alt modifier")
    }
}
