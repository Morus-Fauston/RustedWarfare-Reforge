package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionGeometryTest {
    @Test fun all_five_buildings_preview_and_authoritative_birth_match_independent_placement_cells() {
        val base = scene()
        val factoryCells = listOf(8 to 7, 9 to 7, 10 to 7, 8 to 8, 9 to 8, 10 to 8,
            8 to 9, 9 to 9, 10 to 9, 8 to 10, 9 to 10, 10 to 10, 8 to 11, 9 to 11, 10 to 11)
        val fabricatorCells = listOf(8 to 7, 9 to 7, 10 to 7, 8 to 8, 9 to 8, 10 to 8, 8 to 9, 9 to 9, 10 to 9)
        val defenseCells = listOf(8 to 7, 9 to 7, 8 to 8, 9 to 8)
        data class Case(val type: String, val position: Position, val cells: List<Pair<Int, Int>>, val price: Double)
        for ((type, position, cells, price) in listOf(
            Case("landFactory", Position(190.0, 170.0), factoryCells, 700.0),
            Case("extractorT1", Position(190.0, 170.0), listOf(9 to 8), 700.0),
            Case("fabricatorT1", Position(190.0, 170.0), fabricatorCells, 2200.0),
            Case("c_turret_t1", Position(180.0, 160.0), defenseCells, 500.0),
            Case("laserDefence", Position(180.0, 160.0), defenseCells, 1200.0))) {
            val groundSet = base.map.tileSets.first()
            val ground = (0 until groundSet.tileCount).first {
                it !in groundSet.blockedTileIds && it !in groundSet.resourcePoolTileIds } + groundSet.firstGid
            val gids = IntArray(600) { ground }
            if (type == "extractorT1") {
                val poolSet = base.map.tileSets.first { it.resourcePoolTileIds.isNotEmpty() }
                gids[8 * 30 + 9] = poolSet.firstGid + poolSet.resourcePoolTileIds.first()
            }
            val input = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, gids,
                BlockingGrid(30, 20, BooleanArray(600))), economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)))
            SandboxSession(input).use { session ->
                session.select(Position(120.0, 170.0))
                val preview = assertNotNull(session.previewConstruction(type, Position(190.0, 170.0)))
                assertEquals(PlacementState.CLEAR, preview.state, type)
                assertEquals(position, preview.position, type)
                assertEquals(cells, preview.cells, type)
                assertIs<ActionResult.Accepted>(session.build(type, Position(190.0, 170.0)))
                session.tick()
                assertEquals(position, session.observation.units.single { it.typeId == type }.position, type)
                assertEquals(5000.0 - price, session.observation.credits, type)
            }
        }
    }

    @Test fun extreme_finite_orders_do_not_wrap_preview_cells_into_negative_integer_coordinates() {
        val base = scene()
        SandboxSession(base).use { session ->
            val worker = session.observation.units.first { it.typeId == "builder" }
            session.select(worker.position)
            for (type in listOf("landFactory", "c_turret_t1", "laserDefence")) {
                val preview = assertNotNull(session.previewConstruction(type, Position(Double.MAX_VALUE, 170.0)))
                assertEquals(PlacementState.BLOCKED, preview.state)
                assertTrue(preview.cells.isEmpty(), "Unsafe integer footprint must not be enumerated: $type")
            }
        }
    }

    private fun scene(): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let { Path.of(it) }
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("SimulationTest/public/inputs") }.first { Files.isDirectory(it) }
        val base = ControlledScenario.load(fixtures.resolve("maintenance-defense/construction"))
        return base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
    }
}
