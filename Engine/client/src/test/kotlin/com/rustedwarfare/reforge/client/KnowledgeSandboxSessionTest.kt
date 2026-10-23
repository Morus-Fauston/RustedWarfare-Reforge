package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class KnowledgeSandboxSessionTest {
    @Test fun visible_unit_circle_colors_distinguish_owner_allies_and_enemies_by_team() {
        val players=mutableListOf(Player("p1",PlayerControl.LOCAL,"blue"),
            Player("friend",PlayerControl.NONE,"blue"),Player("opponent",PlayerControl.NONE,"orange"),
            Player("neutral-a",PlayerControl.NONE),Player("neutral-b",PlayerControl.NONE))
        val input=scenario().copy(players=players,
            units=scenario().units.map { it.copy(ownerPlayerId=if(it.unitId=="own")"p1"else if(it.unitId=="visible")"opponent"else"friend") })
        val resources=SandboxRenderResources.from(input)
        assertEquals(com.badlogic.gdx.graphics.Color(0.45f,0.91f,0.65f,1f),resources.unitCircleColor("p1","p1"))
        assertEquals(com.badlogic.gdx.graphics.Color(1f,0.72f,0.3f,1f),resources.unitCircleColor("p1","friend"))
        assertEquals(com.badlogic.gdx.graphics.Color.RED,resources.unitCircleColor("p1","opponent"))
        assertEquals(com.badlogic.gdx.graphics.Color.RED,resources.unitCircleColor("p1","neutral-a"))
        assertEquals(com.badlogic.gdx.graphics.Color.RED,resources.unitCircleColor("neutral-a","neutral-b"))
        assertEquals(com.badlogic.gdx.graphics.Color(1f,0.72f,0.3f,1f),resources.unitCircleColor("friend","p1"))
        players[1]=players[1].copy(teamId="orange")
        assertEquals(com.badlogic.gdx.graphics.Color(1f,0.72f,0.3f,1f),resources.unitCircleColor("p1","friend"))
    }
    private fun scenario(): Scenario {
        val tank = UnitDefinition("tank", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet(), 1)
        return Scenario("client-knowledge", MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25) { it + 1 },
            BlockingGrid(5, 5, BooleanArray(25))), mapOf("tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("own", "p1", "tank", Position(30.0, 30.0), 90.0),
                UnitSpawn("visible", "p2", "tank", Position(50.0, 30.0), 0.0),
                UnitSpawn("hidden", "p2", "tank", Position(90.0, 90.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
    }

    @Test fun cl01_one_player_frame_contains_only_authorized_ground_and_units() {
        val input = scenario()
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.NO_FOG)) {
            SandboxSession(input, options = SandboxOptions(mode, false)).use { session ->
                val frame = session.frame
                assertSame(session.observation, frame.observation)
                val expected = setOf(1 to 1, 0 to 1, 2 to 1, 1 to 0, 1 to 2)
                assertEquals(25, frame.terrain.size)
                assertEquals(if (mode == SandboxMode.NO_FOG) setOf("own", "visible", "hidden") else setOf("own", "visible"),
                    frame.observation.units.map { it.unitId }.toSet())
                for (cell in frame.terrain) {
                    val known = mode == SandboxMode.NO_FOG || (cell.column to cell.row) in expected
                    assertEquals(known, cell.known)
                    assertEquals(known, cell.visible)
                    assertEquals(if (known) cell.row * 5 + cell.column + 1 else null, cell.gid)
                }
                if (mode == SandboxMode.NO_FOG) {
                    assertNull(frame.observation.vision)
                    assertNull(frame.observation.knowledge)
                } else {
                    assertNotNull(frame.observation.vision)
                    assertNotNull(frame.observation.knowledge)
                }
                assertFailsWith<UnsupportedOperationException> { (frame.terrain as MutableList).clear() }
                assertEquals(25, input.map.groundGids.size)
                assertNull(input.automaticVision)
            }
        }
    }

    @Test fun cl02_launch_options_are_frozen_until_exit_and_stale_loads_cannot_publish() {
        val first = SandboxConfiguration(Path.of("first-input"), SandboxOptions(SandboxMode.MEMORY, true))
        val changed = SandboxConfiguration(Path.of("other-input"), SandboxOptions(SandboxMode.HARDCORE, false))
        val launch = SandboxLaunchState(first)
        assertTrue(launch.canEdit)
        val loading = launch.start()
        assertEquals(first, loading.configuration)
        assertFalse(launch.canEdit)
        assertFailsWith<IllegalStateException> { launch.configure(changed) }
        assertTrue(launch.complete(loading.generation))
        assertFalse(launch.canEdit)
        val reopened = launch.reopen()
        assertEquals(first, reopened.configuration)
        assertTrue(reopened.generation > loading.generation)
        assertFalse(launch.complete(loading.generation))
        launch.exit()
        assertTrue(launch.canEdit)
        assertFalse(launch.complete(reopened.generation))
        launch.configure(changed)
        val next = launch.start()
        assertEquals(changed, next.configuration)
        assertFalse(launch.fail(reopened.generation))
        assertFalse(launch.canEdit)
        assertTrue(launch.fail(next.generation))
        assertTrue(launch.canEdit)
        val recovered = launch.start()
        assertEquals(changed, recovered.configuration)
        assertTrue(launch.complete(recovered.generation))
    }

    private fun fastScenario(): Scenario {
        val input = scenario()
        val old = input.unitDefinitions.getValue("tank")
        val fast = UnitDefinition("tank", old.className, old.radius, old.displayRadius, old.totalFrames,
            old.imagePath, old.imageScale, MovementProfile(MovementType.LAND, 20.0, 20.0, 20.0, 4.1, 0.25, false, false),
            old.retainedUnconsumed, 1)
        return input.copy(unitDefinitions = mapOf("tank" to fast),
            units = input.units.map { if (it.unitId == "own") it.copy(heading = 180.0) else it })
    }

    @Test fun cl03_memory_keeps_last_ground_and_hardcore_removes_it_after_loss() {
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            SandboxSession(fastScenario(), options = SandboxOptions(mode)).use { session ->
                val initial = session.frame
                assertEquals(TerrainFrameCell(1, 0, 2, true), initial.terrain[1])
                session.select(Position(30.0, 30.0))
                assertIs<ActionResult.Accepted>(session.move(Position(30.0, 90.0)))
                session.tick()
                val lost = session.frame.terrain[1]
                assertFalse(lost.visible)
                assertEquals(if (mode == SandboxMode.MEMORY) 2 else null, lost.gid)
                assertEquals(mode == SandboxMode.MEMORY, lost.known)
                assertEquals(TerrainFrameCell(1, 0, 2, true), initial.terrain[1])
            }
        }
    }

    @Test fun cl05_lost_selection_and_closed_frames_cannot_retain_hidden_unit_state() {
        val session = SandboxSession(fastScenario(), options = SandboxOptions(SandboxMode.MEMORY))
        session.select(Position(30.0, 30.0))
        session.move(Position(30.0, 90.0))
        session.select(Position(50.0, 30.0))
        assertEquals("visible", session.selectedId)
        session.tick()
        assertNull(session.selectedId)
        assertTrue(session.frame.observation.units.none { it.unitId == "visible" })
        session.select(Position(50.0, 30.0))
        assertNull(session.selectedId)
        session.close()
        assertFailsWith<IllegalStateException> { session.frame }
        session.close()
    }

    @Test fun cl03_hidden_truth_pairs_have_the_same_player_frame_until_discovery() {
        val base = scenario().copy(units = scenario().units.take(1))
        val changed = base.copy(map = MapDefinition(5, 5, 20, 20, emptyList(),
            base.map.groundGids.copyOf().also { it[18] = 999 },
            BlockingGrid(5, 5, BooleanArray(25) { it == 18 })))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE, SandboxMode.NO_FOG)) {
            SandboxSession(base, options = SandboxOptions(mode)).use { a ->
                SandboxSession(changed, options = SandboxOptions(mode)).use { b ->
                    repeat(3) {
                        if (mode == SandboxMode.NO_FOG) assertNotEquals(a.frame.terrain, b.frame.terrain)
                        else assertEquals(a.frame.terrain, b.frame.terrain)
                        assertEquals(a.observation.units, b.observation.units)
                        a.tick(); b.tick()
                    }
                }
            }
        }
    }

    @Test fun cl04_shared_vision_reveals_allied_area_without_granting_control() {
        val input = scenario().copy(
            players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.NONE, "blue"),
                Player("p3", PlayerControl.NONE, "red")),
            units = listOf(UnitSpawn("own", "p1", "tank", Position(30.0, 30.0), 0.0),
                UnitSpawn("ally", "p2", "tank", Position(90.0, 90.0), 0.0),
                UnitSpawn("enemy", "p3", "tank", Position(70.0, 90.0), 0.0)))
        for (shared in listOf(false, true)) {
            SandboxSession(input, options = SandboxOptions(SandboxMode.MEMORY, shared)).use { session ->
                assertEquals(if (shared) setOf("own", "ally", "enemy") else setOf("own"),
                    session.observation.units.map { it.unitId }.toSet())
                session.select(Position(90.0, 90.0))
                if (!shared) assertNull(session.selectedId) else {
                    assertEquals("ally", session.selectedId)
                    val before = session.observation.units
                    assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.move(Position(70.0, 70.0))).error.code)
                    assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.stop()).error.code)
                    assertEquals(before, session.observation.units)
                }
            }
        }
    }

    @Test fun cl06_one_client_move_discovers_obstacle_and_arrives_without_passing_through_it() {
        val base = scenario()
        val input = base.copy(map = MapDefinition(7, 5, 20, 20, emptyList(), IntArray(35) { 1 },
            BlockingGrid(7, 5, BooleanArray(35) { it == 17 })),
            units = listOf(UnitSpawn("own", "p1", "tank", Position(10.0, 50.0), 90.0)))
        SandboxSession(input, options = SandboxOptions(SandboxMode.MEMORY)).use { session ->
            assertFalse(session.frame.terrain[17].known)
            session.select(Position(10.0, 50.0))
            assertIs<ActionResult.Accepted>(session.move(Position(130.0, 50.0)))
            var discovered = false
            var changedPath = false
            var arrived = false
            for (step in 0 until 600) {
                session.tick()
                val frame = session.frame
                val unit = frame.observation.units.single()
                discovered = discovered || frame.terrain[17].known
                changedPath = changedPath || unit.pathPoints.any { it.y != 50.0 }
                val dx = maxOf(60.0 - unit.position.x, 0.0, unit.position.x - 80.0)
                val dy = maxOf(40.0 - unit.position.y, 0.0, unit.position.y - 60.0)
                assertTrue(kotlin.math.hypot(dx, dy) > 1.0, "wall contact at $step")
                if (unit.moveTarget == null) {
                    assertTrue(frame.observation.events.any { it.kind == "movement-arrived" })
                    arrived = true
                    break
                }
            }
            assertTrue(discovered)
            assertTrue(changedPath)
            assertTrue(arrived, "one Move must arrive in the original 600-step budget")
        }
    }
}
