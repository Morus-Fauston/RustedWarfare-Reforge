package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Rectangle2D
import java.nio.file.Path
import kotlin.test.*

class DemoSessionTest {
    private fun scenario(count: Int = 50, blocked: Set<Pair<Int, Int>> = emptySet()): Scenario {
        val cells = BooleanArray(64 * 40)
        blocked.forEach { (col, row) -> cells[row * 64 + col] = true }
        val definition = UnitDefinition("scout", "CustomUnitMetadata", 1.0, 5.0, 1,
            Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 4.0, 1.0, 1.0, 90.0, 90.0, false, false), emptySet())
        return Scenario("demo-session-test", MapDefinition(64, 40, 20, 20,
            emptyList(), IntArray(64 * 40), BlockingGrid(64, 40, cells)), mapOf("scout" to definition),
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            List(count) { i -> UnitSpawn("u$i", "p1", "scout",
                Position(70.0 + (i % 5) * 20, 210.0 + (i / 5) * 20), 0.0) },
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
    }

    @Test fun `fifty selected units receive real core moves and advance independently`() {
        DemoSession(scenario()).use { session ->
            assertEquals(50, session.observation.units.size)
            assertEquals(session.observation.units.map { it.unitId }.toSet(), session.selectedIds)
            assertTrue(session.visibleCells > 0)
            assertNull(session.knowledge.cell(50, 30).lastKnownBlocked)
            assertEquals(0, session.searches)
            val before = session.observation.units.associate { it.unitId to it.position }
            val target = Position(900.0, 310.0)
            session.moveSelected(target)
            assertEquals(target, session.lastTarget)
            assertEquals(50, session.searches)
            assertTrue(session.observation.units.all { it.moveTarget == target })
            repeat(20) { session.tick() }
            assertTrue(session.observation.units.all { it.position != before.getValue(it.unitId) })
            assertTrue(session.exploredCells >= session.visibleCells)
        }
    }

    @Test fun `authored map discovers unseen barriers replans retains memory and reaches goal`() {
        val input = DemoScenario.load()
        DemoSession(input).use { session ->
            assertNull(session.knowledge.cell(25, 20).lastKnownBlocked)
            val goal = Position(1140.0, 400.0)
            session.moveSelected(goal)
            assertEquals(50, session.searches)
            assertEquals(0, session.replans)
            // The initial plans cross the unknown band instead of consulting its truth grid.
            val unknownBand = Rectangle2D.Double(480.0, 300.0, 60.0, 200.0)
            assertTrue(session.observation.units.any { unit -> (listOf(unit.position) + unit.pathPoints).zipWithNext().any { (a, b) ->
                unknownBand.intersectsLine(a.x, a.y, b.x, b.y)
            } })
            var discovered = false
            var memory = false
            var moved = false
            repeat(2500) {
                session.tick()
                discovered = discovered || session.knowledge.cells.any { it.lastKnownBlocked == true }
                memory = memory || session.knowledge.cells.any { !it.visible && it.lastKnownBlocked != null }
                moved = moved || session.observation.units.any { it.position.x > 1000 }
                for (unit in session.observation.units) {
                    assertFalse(input.map.blocking.isBlocked((unit.position.x / 20).toInt(), (unit.position.y / 20).toInt()),
                        "${unit.unitId} entered true blocking cell at step ${session.observation.stepIndex}")
                }
            }
            assertTrue(discovered, "must discover blocked terrain")
            assertTrue(session.replans > 0, "must execute actual KR-R searches")
            assertTrue(memory, "must retain terrain after it leaves sight")
            assertTrue(moved, "must move beyond barriers")
            val radius = input.movementContract!!.targetRadius
            assertTrue(session.observation.units.all {
                val dx = it.position.x - goal.x; val dy = it.position.y - goal.y
                dx * dx + dy * dy <= radius * radius && it.moveTarget == null && it.speed == 0.0
            },
                "all fifty must arrive: ${session.observation.units.groupingBy { it.movementState }.eachCount()}")
        }
    }

    @Test fun `stop selection close and restart keep sessions independent`() {
        val input = DemoScenario.load()
        val session = DemoSession(input)
        session.selectAt(session.observation.units.first().position)
        assertEquals(1, session.selectedIds.size)
        session.moveSelected(Position(1140.0, 400.0))
        repeat(15) { session.tick() }
        session.stopSelected()
        assertTrue(session.observation.units.all { it.moveTarget == null })
        repeat(15) { session.tick() }
        assertTrue(session.observation.units.all { it.speed == 0.0 })
        val stopped = session.observation.units.associate { it.unitId to it.position }
        repeat(15) { session.tick() }
        assertEquals(stopped, session.observation.units.associate { it.unitId to it.position })
        assertTrue(session.observation.units.all { it.moveTarget == null })
        session.close(); session.close()
        assertFalse(session.isOpen)
        session.moveSelected(Position(500.0, 300.0)); session.tick()
        DemoSession(DemoScenario.load()).use { fresh ->
            assertEquals(50, fresh.selectedIds.size)
            assertEquals(0, fresh.replans)
            assertNull(fresh.knowledge.cell(25, 20).lastKnownBlocked)
            assertEquals(input.units.associate { it.unitId to it.position },
                fresh.observation.units.associate { it.unitId to it.position })
        }
    }

    @Test fun `circle is centered on actual off-grid position and obstacles do not occlude`() {
        val original = scenario(1, setOf(4 to 10))
        val input = original.copy(units = listOf(original.units.single().copy(position = Position(73.0, 213.0))))
        DemoSession(input).use { session ->
            assertTrue(session.knowledge.cell(4, 10).visible)
            assertTrue(session.knowledge.cell(5, 10).visible, "wall does not occlude")
            for (row in 0 until 40) for (column in 0 until 64) {
                val dx = column * 20 + 10 - 73.0
                val dy = row * 20 + 10 - 213.0
                assertEquals(dx * dx + dy * dy <= 120.0 * 120.0, session.knowledge.cell(column, row).visible)
            }
        }
    }
}
