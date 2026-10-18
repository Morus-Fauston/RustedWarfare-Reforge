package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.sim.InitResult
import com.rustedwarfare.reforge.core.sim.SimulationContext
import java.nio.file.Files
import kotlin.math.hypot
import kotlin.test.*

class DemoScenarioTest {
    @Test fun `packaged authored content parses into fifty valid independent spawns`() {
        val scenario = DemoScenario.load()
        assertEquals(64, scenario.map.widthTiles)
        assertEquals(40, scenario.map.heightTiles)
        assertEquals(20, scenario.map.tileWidth)
        assertEquals(20, scenario.map.tileHeight)
        assertEquals(2560, scenario.map.groundGids.size)
        assertEquals(50, scenario.units.size)
        assertEquals(50, scenario.units.map { it.unitId }.toSet().size)
        assertEquals(setOf("p1"), scenario.units.map { it.ownerPlayerId }.toSet())
        assertEquals(setOf(90.0, 110.0, 130.0, 150.0, 170.0), scenario.units.map { it.position.x }.toSet())
        assertEquals((0 until 10).map { 210.0 + it * 20 }.toSet(), scenario.units.map { it.position.y }.toSet())
        val definition = scenario.unitDefinitions.getValue(scenario.units.first().typeId)
        assertEquals(5.0, definition.radius)
        assertEquals(1.0, definition.movement.moveSpeed)
        assertEquals(1, definition.totalFrames)
        assertTrue(Files.isRegularFile(definition.imagePath))
        assertIs<ContentResult.Success<*>>(ContentLoader.loadUnitVariant(definition.imagePath.parent.parent.resolve("units/scout_tank.ini")))
        assertIs<ContentResult.Success<*>>(ContentLoader.loadMap(definition.imagePath.parent.parent.resolve("maps/valley.tmx")))
        val initialized = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario))
        initialized.context.close()
        for ((index, unit) in scenario.units.withIndex()) {
            assertFalse(scenario.map.blocking.isBlocked((unit.position.x / 20).toInt(), (unit.position.y / 20).toInt()))
            for (other in scenario.units.drop(index + 1)) {
                assertTrue(hypot(unit.position.x - other.position.x, unit.position.y - other.position.y) >= 20.0)
            }
        }
    }

    @Test fun `six tile initial circles contain no obstacle and both canyon channels remain open`() {
        val scenario = DemoScenario.load()
        val grid = scenario.map.blocking
        for (row in 0 until 40) for (col in 0 until 64) {
            if (!grid.isBlocked(col, row)) continue
            for (unit in scenario.units) {
                assertTrue(hypot(col * 20.0 + 10 - unit.position.x, row * 20.0 + 10 - unit.position.y) > 120.0)
            }
        }
        for (row in listOf(12, 27)) for (col in 21..29) assertFalse(grid.isBlocked(col, row))
        for (col in 24..26) assertTrue(grid.isBlocked(col, 20))
        for (col in 40..41) for (row in 14..25) assertTrue(grid.isBlocked(col, row))
        assertFalse(grid.isBlocked(57, 20))
        val visited = mutableSetOf(5 to 15)
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        queue.add(5 to 15)
        while (queue.isNotEmpty()) {
            val (col, row) = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val next = col + dx to row + dy
                if (next.first in 0 until 64 && next.second in 0 until 40 &&
                    !grid.isBlocked(next.first, next.second) && visited.add(next)) queue.add(next)
            }
        }
        assertTrue(57 to 20 in visited)
    }

    @Test fun `reopening shares immutable parsed resources and creates independent scenario containers`() {
        val first = DemoScenario.load()
        val second = DemoScenario.load()
        assertNotSame(first, second)
        assertSame(first.map, second.map)
        assertSame(first.unitDefinitions.values.first(), second.unitDefinitions.values.first())
        assertEquals(first.unitDefinitions.values.first().imagePath, second.unitDefinitions.values.first().imagePath)
        assertNotSame(first.units, second.units)
        assertEquals(first.units, second.units)
        val original = first.map.groundGids[0]
        first.map.groundGids[0] = 99
        assertEquals(original, second.map.groundGids[0])
    }
}
