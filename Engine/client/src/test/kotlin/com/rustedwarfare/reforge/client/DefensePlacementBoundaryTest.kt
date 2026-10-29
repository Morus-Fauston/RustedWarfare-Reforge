package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefensePlacementBoundaryTest {
    @Test fun binary32_boundary_and_double_rounding_are_the_same_in_preview_and_real_defense_placement() {
        for (type in listOf("c_turret_t1", "laserDefence")) {
            // Independent worked threshold: 199 is the first order that snaps to 200;
            // the immediately preceding Float still snaps to 180. A closer Double rounds to 199f.
            for ((x, expectedX) in listOf(198.99998474121094 to 180.0, 198.99999999999997 to 200.0, 199.0 to 200.0)) {
                val scene = scene(120.0)
                SandboxSession(scene).use { session ->
                    session.select(Position(120.0, 170.0))
                    val preview = assertNotNull(session.previewConstruction(type, Position(x, 170.0)))
                    assertEquals(Position(expectedX, 160.0), preview.position)
                    assertEquals(PlacementState.CLEAR, preview.state)
                    assertIs<ActionResult.Accepted>(session.build(type, Position(x, 170.0)))
                    session.tick()
                    assertEquals(listOf(preview.position), session.observation.units.filter { it.typeId == type }.map { it.position })
                }
            }
        }
    }

    @Test fun a_snapped_defense_beyond_the_right_edge_is_blocked_and_never_creates_or_pays_for_a_building() {
        for (type in listOf("c_turret_t1", "laserDefence")) {
            SandboxSession(scene(550.0)).use { session ->
                session.select(Position(550.0, 170.0))
                val preview = assertNotNull(session.previewConstruction(type, Position(599.0, 170.0)))
                assertEquals(Position(600.0, 160.0), preview.position)
                assertEquals(PlacementState.BLOCKED, preview.state)
            }
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(550.0))).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("edge", "p1", "worker", type,
                    Position(599.0, 170.0), "1".repeat(32), c.world)))
                val frame = c.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(frame.events.any { it.kind == "construction-unavailable" })
                assertEquals(listOf("builder"), frame.units.map { it.typeId })
                assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
                assertTrue(frame.construction!!.buildings.isEmpty())
            } finally { c.close() }
        }
    }

    @Test fun both_defenses_reject_a_blocked_footprint_or_an_enemy_or_allied_owners_occupied_site_without_payment_or_reuse() {
        for (type in listOf("c_turret_t1", "laserDefence")) {
            for (obstacle in listOf("terrain", "enemy", "ally")) {
                val base = scene(120.0)
                val terrain = obstacle == "terrain"
                val ground = base.map.groundGids.copyOf()
                val blocked = BooleanArray(600)
                if (terrain) {
                    ground[7 * 30 + 8] = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid + 2
                    blocked[7 * 30 + 8] = true
                }
                val spawns = base.units + if (terrain) emptyList() else listOf(UnitSpawn("occupied",
                    if (obstacle == "enemy") "p2" else "p3", "laserDefence", Position(180.0, 160.0), 0.0))
                val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, ground,
                    BlockingGrid(30, 20, blocked)), units = spawns,
                    players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.NONE, "red"),
                        Player("p3", PlayerControl.NONE, "blue")),
                    economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)),
                    combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 3L))
                SandboxSession(scene).use { session ->
                    session.select(Position(120.0, 170.0))
                    assertEquals(PlacementState.BLOCKED, session.previewConstruction(type, Position(190.0, 170.0))!!.state)
                    assertNull(session.build(type, Position(190.0, 170.0)))
                    assertEquals(1750.0, session.observation.credits)
                }
                val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    assertIs<ActionResult.Accepted>(c.submit(BuildAction("blocked", "p1", "worker", type,
                        Position(190.0, 170.0), "2".repeat(32), c.world)))
                    val frame = c.advance(Step(0, 1.0, "sim-tick"))
                    assertEquals(1, frame.events.count { it.kind == "construction-unavailable" })
                    assertEquals(spawns.map { it.unitId }.toSet(), frame.units.map { it.unitId }.toSet())
                    assertTrue(frame.units.single { it.unitId == "worker" }.tasks.isEmpty())
                    assertTrue(frame.construction!!.buildings.isEmpty())
                    assertTrue(frame.construction!!.nonces.values.all { it.isEmpty() })
                    assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
                } finally { c.close() }
            }
        }
    }

    private fun scene(workerX: Double): Scenario {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        return base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { ground },
            BlockingGrid(30, 20, BooleanArray(600))),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(workerX, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
    }
}
