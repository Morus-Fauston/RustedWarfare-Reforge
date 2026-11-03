package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class MechKnowledgeTest {
    private fun multiplayer(retention: TerrainRetention?, shared: Boolean): Scenario = MechTestScene.scenario().copy(
        players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.LOCAL, "blue"),
            Player("p3", PlayerControl.LOCAL, "red")),
        units = listOf(UnitSpawn("u1", "p1", "mech", Position(100.0, 100.0), 90.0),
            UnitSpawn("u2", "p2", "mech", Position(450.0, 300.0), 270.0),
            UnitSpawn("u3", "p3", "mech", Position(450.0, 100.0), 180.0)),
        automaticVision = retention?.let { KnowledgeRules(it, PathInformationSource.PLAYER_KNOWLEDGE, shared) },
    )

    @Test fun all_modes_filter_mech_pose_with_the_unit_and_shared_vision_never_grants_control() {
        for (mode in listOf(null, TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) for (shared in listOf(false, true)) {
            val context = MechTestScene.open(multiplayer(mode, shared))
            try {
                val view = context.observePlayer(context.world, "p1")
                val expected = if (mode == null) setOf("u1", "u2", "u3") else if (shared) setOf("u1", "u2") else setOf("u1")
                assertEquals(expected, view.units.map { it.unitId }.toSet())
                assertTrue(view.units.all { it.mechPose != null })
                val before = view.units
                assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                    MoveAction("foreign", "p1", "u2", Position(450.0, 250.0), context.world))).error.code)
                assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                    StopAction("foreign-stop", "p1", "u2", context.world))).error.code)
                assertEquals(before, context.observePlayer(context.world, "p1").units)
            } finally { context.close() }
        }
    }

    @Test fun hidden_mech_orders_and_body_animation_do_not_change_another_players_observation() {
        val first = MechTestScene.open(multiplayer(TerrainRetention.MEMORY, false))
        val control = MechTestScene.open(multiplayer(TerrainRetention.MEMORY, false))
        try {
            val before = first.observePlayer(first.world, "p1")
            control.observePlayer(control.world, "p1") // Both worlds have the same public read history.
            assertIs<ActionResult.Accepted>(first.submit(MoveAction("hidden", "p3", "u3", Position(500.0, 100.0), first.world)))
            assertSame(before, first.observePlayer(first.world, "p1"))
            for (step in 0L..129L) {
                first.advance(Step(step, 1.0, "sim-tick")); control.advance(Step(step, 1.0, "sim-tick"))
                val a = first.observePlayer(first.world, "p1")
                val b = control.observePlayer(control.world, "p1")
                assertEquals(b.units, a.units)
                assertEquals(b.events, a.events)
                assertEquals(b.actionResults, a.actionResults)
                assertEquals(b.sampleId, a.sampleId)
                assertEquals(listOf("u1"), a.units.map { it.unitId })
            }
        } finally { first.close(); control.close() }
    }

    @Test fun unknown_wall_planning_remains_identical_until_revealed_then_the_reverse_start_repairs_and_arrives() {
        val rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)
        val wall = (0 until 20).filter { it != 10 }.map { 15 to it }.toSet()
        val open = MechTestScene.open(MechTestScene.scenario(heading = 270.0).copy(automaticVision = rules))
        val blocked = MechTestScene.open(MechTestScene.scenario(heading = 270.0, blocked = wall).copy(automaticVision = rules))
        try {
            open.submit(MoveAction("move", "p1", "u1", Position(500.0, 100.0), open.world))
            blocked.submit(MoveAction("move", "p1", "u1", Position(500.0, 100.0), blocked.world))
            assertEquals(open.observePlayer(open.world, "p1").units.single().pathPoints,
                blocked.observePlayer(blocked.world, "p1").units.single().pathPoints)
            var arrivals = 0
            for (step in 0L..1600L) {
                val result = blocked.advance(Step(step, 1.0, "sim-tick"))
                val unit = result.units.single()
                assertFalse((unit.position.x / 20).toInt() to (unit.position.y / 20).toInt() in wall)
                arrivals += result.events.count { it.kind == "movement-arrived" }
                if (arrivals == 1 && unit.speed == 0.0) break
            }
            println("MECH_KNOWLEDGE final=${blocked.observePlayer(blocked.world, "p1").units.single()}")
            assertEquals(1, arrivals)
            assertEquals(0.0, blocked.observe().units.single().speed)
        } finally { open.close(); blocked.close() }
    }
}
