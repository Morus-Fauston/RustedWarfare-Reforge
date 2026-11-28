package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Windows Alt explicit cohort escort input & session dispatch tests for ENG-P017-010:
 * - Holding `ALT_LEFT` or `ALT_RIGHT` (either via `pressed` callback or `keyDown`/`keyUp` modifier tracking
 *   used by `BackgroundClientInput`) + right-click ground dispatches `MoveAction(cohortEscort = true)`;
 * - Ordinary right-click without Alt keeps `cohortEscort = false` (`INDEPENDENT` mode);
 * - `Alt + Right-Click` on an enemy still dispatches `AttackAction` (not escort move);
 * - `Alt + Right-Click` on a friendly transport still dispatches `LoadAction`.
 */
class CohortAltInputTest {

    private fun fixturesDir(): Path =
        System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()

    private fun loadPack(): Map<String, UnitDefinition> {
        val packDir = fixturesDir().resolve("phase-five-sandbox/ini/package")
        return assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packDir)).value.units
    }

    private fun buildInput(
        session: SandboxSession,
        worldW: Double,
        worldH: Double,
        pressed: (Int) -> Boolean = { false },
        evidence: ClientEvidence = ClientEvidence.NONE,
    ): SandboxInput {
        val projection = MapProjection(worldW, worldH, MapArea(0.0, 0.0, worldW, worldH))
        return SandboxInput(
            session = { session },
            projection = { projection },
            uiPoint = { sx, sy -> Position(sx.toDouble(), worldH - sy.toDouble()) },
            active = { true },
            editing = { false },
            pressed = pressed,
            evidence = evidence,
        )
    }

    @Test
    fun alt_left_and_alt_right_ground_right_click_dispatch_cohort_escort_while_normal_click_stays_independent() {
        val defs = ScenarioCatalog.deriveBenchmarkDefinitions(loadPack())
        val map = ScenarioCatalog.buildCohortVanguardMap()
        val worldW = map.widthTiles.toDouble() * map.tileWidth
        val worldH = map.heightTiles.toDouble() * map.tileHeight
        val spawns = listOf(
            UnitSpawn("light-0", "p1", "fast_ranged_light", Position(300.0, 220.0), 90.0),
            UnitSpawn("heavy-0", "p1", "heavy_vanguard", Position(160.0, 220.0), 90.0),
        )
        val scene = ScenarioCatalog.baseScenario("alt-input-ground", map, defs, spawns)

        // 1. ALT_LEFT via keyDown/keyUp (exact BackgroundClientInput path) -> cohortEscort = true
        run {
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { k, f -> records += k to f }).use { session ->
                session.selectBox(Position(100.0, 150.0), Position(360.0, 290.0))
                assertEquals(setOf("light-0", "heavy-0"), session.selectedIds)
                val input = buildInput(session, worldW, worldH)
                input.keyDown(Input.Keys.ALT_LEFT)
                assertTrue(input.alt())
                assertTrue(input.touchDown(900, 220, 0, Input.Buttons.RIGHT))
                assertTrue(input.touchUp(900, 220, 0, Input.Buttons.RIGHT))
                input.keyUp(Input.Keys.ALT_LEFT)
                assertFalse(input.alt())

                val moveRecords = records.filter { it.first == "action" && it.second["type"] == "MoveAction" }
                assertEquals(2, moveRecords.size)
                assertTrue(moveRecords.all { it.second["cohortEscort"] == true }, "ALT_LEFT right-click must dispatch cohortEscort=true")

                var lightYielded = false
                repeat(120) {
                    session.tick()
                    if (session.observation.units.single { it.unitId == "light-0" }.cohortEscortYielding) {
                        lightYielded = true
                    }
                }
                assertTrue(lightYielded, "ALT_LEFT escort move must engage CohortCoordinator pacing")
            }
        }

        // 2. ALT_RIGHT via pressed callback -> cohortEscort = true
        run {
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { k, f -> records += k to f }).use { session ->
                session.selectBox(Position(100.0, 150.0), Position(360.0, 290.0))
                val input = buildInput(session, worldW, worldH, pressed = { it == Input.Keys.ALT_RIGHT })
                assertTrue(input.alt())
                assertTrue(input.touchDown(900, 220, 0, Input.Buttons.RIGHT))
                assertTrue(input.touchUp(900, 220, 0, Input.Buttons.RIGHT))

                val moveRecords = records.filter { it.first == "action" && it.second["type"] == "MoveAction" }
                assertEquals(2, moveRecords.size)
                assertTrue(moveRecords.all { it.second["cohortEscort"] == true }, "ALT_RIGHT right-click must dispatch cohortEscort=true")
            }
        }

        // 3. Normal right-click (no Alt) -> cohortEscort omitted/false & fast unit does NOT yield
        run {
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { k, f -> records += k to f }).use { session ->
                session.selectBox(Position(100.0, 150.0), Position(360.0, 290.0))
                val input = buildInput(session, worldW, worldH)
                assertFalse(input.alt())
                assertTrue(input.touchDown(900, 220, 0, Input.Buttons.RIGHT))
                assertTrue(input.touchUp(900, 220, 0, Input.Buttons.RIGHT))

                val moveRecords = records.filter { it.first == "action" && it.second["type"] == "MoveAction" }
                assertEquals(2, moveRecords.size)
                assertTrue(moveRecords.none { it.second["cohortEscort"] == true }, "Normal right-click must NOT set cohortEscort=true")

                repeat(80) {
                    session.tick()
                    assertFalse(
                        session.observation.units.single { it.unitId == "light-0" }.cohortEscortYielding,
                        "Normal right-click must remain INDEPENDENT without cohort escort yielding",
                    )
                }
            }
        }
    }

    @Test
    fun alt_right_click_on_enemy_or_friendly_transport_preserves_attack_and_load_commands() {
        val pack = loadPack()
        val defs = ScenarioCatalog.deriveBenchmarkDefinitions(pack)
        val map = ScenarioCatalog.buildCohortVanguardMap()
        val worldW = map.widthTiles.toDouble() * map.tileWidth
        val worldH = map.heightTiles.toDouble() * map.tileHeight

        // Enemy attack preserved when holding Alt
        run {
            val spawns = listOf(
                UnitSpawn("u1", "p1", "c_tank", Position(200.0, 220.0), 90.0),
                UnitSpawn("e1", "p2", "c_tank", Position(360.0, 220.0), 270.0),
            )
            val scene = ScenarioCatalog.baseScenario(
                id = "alt-enemy-attack",
                map = map,
                definitions = defs,
                units = spawns,
                players = listOf(Player("p1", PlayerControl.LOCAL, "team1"), Player("p2", PlayerControl.LOCAL, "team2")),
            )
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { k, f -> records += k to f }).use { session ->
                session.select(Position(200.0, 220.0))
                val input = buildInput(session, worldW, worldH, pressed = { it == Input.Keys.ALT_LEFT })
                assertTrue(input.touchDown(360, 220, 0, Input.Buttons.RIGHT))
                assertTrue(input.touchUp(360, 220, 0, Input.Buttons.RIGHT))

                val cmd = records.filter { it.first == "action" }
                assertTrue(cmd.any { it.second["type"] == "AttackAction" && it.second["targetUnitId"] == "e1" },
                    "Alt + Right-Click on enemy must preserve AttackAction, got $cmd")
                assertTrue(cmd.none { it.second["type"] == "MoveAction" }, "Must not fall back to MoveAction on enemy")
            }
        }

        // Friendly transport load preserved when holding Alt
        val transportType = pack.entries.firstOrNull { it.value.transport != null && it.value.building == null }?.key
        if (transportType != null) {
            val spawns = listOf(
                UnitSpawn("u1", "p1", "c_tank", Position(200.0, 220.0), 90.0),
                UnitSpawn("tr1", "p1", transportType, Position(300.0, 220.0), 90.0),
            )
            val scene = ScenarioCatalog.baseScenario("alt-transport-load", map, defs, spawns)
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { k, f -> records += k to f }).use { session ->
                session.select(Position(200.0, 220.0))
                val input = buildInput(session, worldW, worldH, pressed = { it == Input.Keys.ALT_LEFT })
                assertTrue(input.touchDown(300, 220, 0, Input.Buttons.RIGHT))
                assertTrue(input.touchUp(300, 220, 0, Input.Buttons.RIGHT))

                val cmd = records.filter { it.first == "action" }
                assertTrue(cmd.any { it.second["type"] == "LoadAction" && it.second["targetUnitId"] == "tr1" },
                    "Alt + Right-Click on friendly transport must preserve LoadAction, got $cmd")
                assertTrue(cmd.none { it.second["type"] == "MoveAction" }, "Must not fall back to MoveAction on transport")
            }
        }
    }
}
