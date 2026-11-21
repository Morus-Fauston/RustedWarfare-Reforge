package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UiHudDebugLayerAndConsoleTest {

    private val fixturesDir: Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()

    @Test
    fun debugLayerStateDefaultsSubLayersAndResetFollowSpec12A2() {
        val initial = HudDebugLayerState()
        assertFalse(initial.enabled)
        assertTrue(initial.showBoxes)
        assertTrue(initial.showRoutes)
        assertFalse(initial.showGrid)

        // Sub-layer toggles are ignored while F3 debug layer is closed
        assertEquals(initial, initial.toggleBoxes())
        assertEquals(initial, initial.toggleRoutes())
        assertEquals(initial, initial.toggleGrid())

        // Opening F3 enables sub-layer toggles
        val open = initial.toggleEnabled()
        assertTrue(open.enabled)
        assertFalse(open.toggleBoxes().showBoxes)
        assertFalse(open.toggleRoutes().showRoutes)
        assertTrue(open.toggleGrid().showGrid)

        // New match reset restores default closed state + boxes/routes ON, grid OFF
        val mutated = open.toggleBoxes().toggleRoutes().toggleGrid()
        val reset = mutated.reset()
        assertEquals(HudDebugLayerState(), reset)
        assertEquals("layers [B] boxes ON · [R] routes ON · [G] grid off", HudDebugProjection.buildLayersLine(open))
        assertEquals("layers [B] boxes off · [R] routes off · [G] grid ON", HudDebugProjection.buildLayersLine(mutated))
        assertTrue(HudDebugFrameMetrics.resolveVersionLabel().isNotBlank())
    }

    @Test
    fun narrowWindowThresholdStacksRightColumnUnderLeft() {
        // 960x640 -> stageWidth=960 -> battlefield = 960 - 232 - 8 = 720 < 780 -> stacked!
        assertTrue(HudDebugProjection.isNarrowStack(960f))
        assertTrue(HudDebugProjection.isNarrowStack(1019f))
        assertFalse(HudDebugProjection.isNarrowStack(1020f))
        assertFalse(HudDebugProjection.isNarrowStack(1137.78f))
    }

    @Test
    fun debugProjectionBuildsCompleteColumnsAndNeverLeaksFogHiddenEnemies() {
        val tank = com.rustedwarfare.reforge.core.content.UnitDefinition(
            "tank", "CustomUnitMetadata", 8.0, 100.0, 1, Path.of("unused.png"), 1.0,
            com.rustedwarfare.reforge.core.content.MovementProfile(
                com.rustedwarfare.reforge.core.content.MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false,
            ),
            emptySet(), 1,
        )
        val fogScenario = Scenario(
            scenarioId = "fog-debug-privacy",
            map = com.rustedwarfare.reforge.core.content.MapDefinition(
                10, 10, 20, 20, emptyList(), IntArray(100) { it + 1 },
                com.rustedwarfare.reforge.core.content.BlockingGrid(10, 10, BooleanArray(100)),
            ),
            unitDefinitions = mapOf("tank" to tank),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            units = listOf(
                UnitSpawn("own-1", "p1", "tank", Position(30.0, 30.0), 90.0),
                UnitSpawn("visible-foe", "p2", "tank", Position(50.0, 30.0), 0.0),
                UnitSpawn("hidden-foe-99", "p2", "tank", Position(190.0, 190.0), 0.0),
            ),
            coordinateModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            movementContract = MovementContract(16.0, 4.0),
        )
        val mapW = fogScenario.map.widthTiles * fogScenario.map.tileWidth.toDouble()
        val mapH = fogScenario.map.heightTiles * fogScenario.map.tileHeight.toDouble()
        val fogOptions = SandboxOptions(mode = SandboxMode.MEMORY, shareAlliedVision = false)
        SandboxSession(fogScenario, options = fogOptions).use { session ->
            // Verify the authoritative scenario has hidden-foe-99, while player-authorized observation hides it
            assertTrue(fogScenario.units.any { it.unitId == "hidden-foe-99" })
            assertTrue(
                session.observation.units.none { it.unitId == "hidden-foe-99" },
                "Fog-hidden enemy must not appear in p1 observation",
            )
            assertTrue(session.observation.units.size < fogScenario.units.size)

            val p1Unit = session.observation.units.first { it.ownerPlayerId == "p1" && it.alive }
            session.select(p1Unit.position)

            val proj = MapProjection(mapW, mapH)
            val state = HudDebugLayerState(enabled = true, showBoxes = true, showRoutes = true, showGrid = false)
            val metrics = HudDebugFrameMetrics(
                fps = 60,
                frameMs = 4.2f,
                maxFrameMs = 9.8f,
                rateMultiplier = 1,
                actualMultiplier = 1.0,
                backlogSteps = 0,
                mapId = "river_crossing",
                cursorWorld = Position(120.0, 140.0),
            )

            // Wide window (1920x1080 -> stageW ~ 1138)
            val wideSnap = HudDebugProjection.project(session, proj, state, metrics, stageWidth = 1138f)
            assertFalse(wideSnap.stacked)
            assertEquals(9, wideSnap.leftLines.size)
            assertTrue(wideSnap.leftLines[0].contains("RustedWarfare-Reforge"))
            assertTrue(wideSnap.leftLines[0].contains("development"))
            assertTrue(wideSnap.leftLines[1].startsWith("fps 60 · frame 4.2 ms (max 9.8)"))
            assertTrue(wideSnap.leftLines[2].startsWith("sim step "))
            assertTrue(wideSnap.leftLines[2].contains("rate 1× · actual 1.00× · backlog 0"))
            assertTrue(wideSnap.leftLines[3].contains("map river_crossing") && wideSnap.leftLines[3].contains("fog on"))
            assertTrue(wideSnap.leftLines[4].startsWith("camera "))
            assertTrue(wideSnap.leftLines[5].startsWith("cursor world 120, 140 · tile "))
            assertTrue(wideSnap.leftLines[7].contains("[B] boxes ON · [R] routes ON · [G] grid off"))
            assertEquals("LEGEND", wideSnap.leftLines[8])

            // Strict fog privacy: visible count equals observation count, NEVER authoritative count, and hidden enemy ID never appears
            val obsAlive = session.observation.units.count { it.alive }
            val obsTotal = session.observation.units.size
            assertTrue(wideSnap.leftLines[6].contains("units visible $obsAlive / $obsTotal"))
            assertTrue((wideSnap.leftLines + wideSnap.rightLines).none { it.contains("hidden-foe-99") })

            // Right column has 6 lines with normalized heading in [-180, 180]
            assertEquals(6, wideSnap.rightLines.size)
            assertTrue(wideSnap.rightLines[0].startsWith("selected ${p1Unit.unitId} · ${p1Unit.typeId} · p1"))
            assertTrue(wideSnap.rightLines[1].startsWith("pos ") && wideSnap.rightLines[1].contains("heading ") && wideSnap.rightLines[1].contains("speed "))
            assertTrue(wideSnap.rightLines[2].startsWith("state "))
            assertTrue(wideSnap.rightLines[3].startsWith("shape ") && wideSnap.rightLines[3].contains("coarse r ") && wideSnap.rightLines[3].contains("mass "))
            assertTrue(wideSnap.rightLines[4].startsWith("contacts ") && wideSnap.rightLines[4].contains("buffer "))
            assertTrue(wideSnap.rightLines[5].startsWith("route ") && wideSnap.rightLines[5].contains("remaining ") && wideSnap.rightLines[5].contains("target "))

            // Narrow window (960x640 -> stageW = 960) stacks right column under left
            val narrowSnap = HudDebugProjection.project(session, proj, state, metrics, stageWidth = 960f)
            assertTrue(narrowSnap.stacked)
            assertEquals(wideSnap.rightLines, narrowSnap.rightLines)

            // Clearing selection hides right column completely
            session.select(Position(150.0, 150.0))
            val noSelSnap = HudDebugProjection.project(session, proj, state, metrics, stageWidth = 1138f)
            assertTrue(noSelSnap.rightLines.isEmpty())
        }
    }

    @Test
    fun togglingDebugProjectionDoesNotMutateAuthoritativeSimulationDeterminism() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        val stateOff = HudDebugLayerState(enabled = false)
        val stateOn = HudDebugLayerState(enabled = true, showBoxes = true, showRoutes = true, showGrid = true)
        val metrics = HudDebugFrameMetrics(60, 16.6f, 16.6f, 1, 1.0, 0, "maint", Position(100.0, 100.0))

        val resultWithoutDebug = SandboxSession(base).use { s ->
            val builder = s.observation.units.first { it.typeId == "builder" && it.ownerPlayerId == "p1" }
            s.select(builder.position)
            s.move(Position(builder.position.x + 120.0, builder.position.y + 40.0))
            repeat(40) { s.tick() }
            s.observation.units.map { Triple(it.unitId, it.position, it.health) }
        }

        val resultWithDebug = SandboxSession(base).use { s ->
            val builder = s.observation.units.first { it.typeId == "builder" && it.ownerPlayerId == "p1" }
            s.select(builder.position)
            s.move(Position(builder.position.x + 120.0, builder.position.y + 40.0))
            val proj = MapProjection(600.0, 400.0)
            repeat(40) { step ->
                val curState = if (step % 2 == 0) stateOn else stateOff
                if (curState.enabled) {
                    HudDebugProjection.project(s, proj, curState, metrics, 1138f)
                    CollisionBoxView.project(s, proj)
                }
                s.tick()
            }
            s.observation.units.map { Triple(it.unitId, it.position, it.health) }
        }

        assertEquals(resultWithoutDebug, resultWithDebug, "Toggling debug layer must produce bit-identical authoritative simulation")
    }

    @Test
    fun sandboxConsoleLayoutCenteredBottom460AndActionIdsAndUiStringsMatchSpec12() {
        // 1280x800 (stageW = 1024): battlefield = 1024 - 232 = 792; centered x = (792 - 460) / 2 = 166
        val rect1280 = HudSandboxConsole.computePanelRect(stageWidth = 1024f, hasFeedback = false)
        assertEquals(460f, rect1280.width, 0.01f)
        assertEquals(166f, rect1280.x, 0.01f)
        assertEquals(UiTokens.SCREEN_MARGIN, rect1280.y, 0.01f)

        // Feedback expands height cleanly without changing width or horizontal centering
        val rectWithFeedback = HudSandboxConsole.computePanelRect(stageWidth = 1024f, hasFeedback = true)
        assertEquals(rect1280.x, rectWithFeedback.x, 0.01f)
        assertEquals(rect1280.width, rectWithFeedback.width, 0.01f)
        assertTrue(rectWithFeedback.height > rect1280.height)

        // Action IDs preserved per Spec §13.1
        assertEquals("sandbox-command-input", HudSandboxConsole.COMMAND_INPUT_ACTION_ID)
        assertEquals(
            listOf("sandbox-pick-spawn", "sandbox-pick-destroy", "sandbox-pick-health", "sandbox-pick-repeat"),
            HudSandboxConsole.PICK_ACTION_IDS,
        )

        // All console & debug button strings live in UiStrings for both ZH and EN
        for (key in listOf("debugBtn", "escClose", "pick.spawn", "pick.destroy", "pick.health", "pick.repeat", "arrivalDeviated", "arrivalDeviatedMulti")) {
            assertTrue(UiStrings.get(key, UiLanguage.ZH) != key, "Missing ZH translation for $key")
            assertTrue(UiStrings.get(key, UiLanguage.EN) != key, "Missing EN translation for $key")
        }
    }

    @Test
    fun arrivalDeviationSingleAndMultiEnterHudEventStreamWithStandardFourSecondFade() {
        val stream = HudEventStream()
        val singleMsg = UiStrings.get("arrivalDeviated", UiLanguage.ZH)
        val multiMsg = UiStrings.format("arrivalDeviatedMulti", "N" to 3, language = UiLanguage.ZH)
        assertTrue(singleMsg.contains("最近可达位置"))
        assertEquals("3 个单位停在目标附近", multiMsg)

        stream.pushInfo(singleMsg)
        stream.pushInfo(multiMsg)
        val visible = stream.visibleNotices()
        assertEquals(2, visible.size)
        assertTrue(visible.all { it.kind == HudNoticeKind.INFO })
        stream.update(3.5f)
        assertEquals(2, stream.visibleNotices().size)
        stream.update(1.0f)
        assertTrue(stream.visibleNotices().isEmpty(), "Info notices must fade out after 4s")
    }
}
