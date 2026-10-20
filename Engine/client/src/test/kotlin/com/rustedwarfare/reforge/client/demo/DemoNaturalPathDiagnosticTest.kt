package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.sim.Position
import java.awt.geom.Line2D
import java.nio.file.Files
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoNaturalPathDiagnosticTest {
    @Test
    fun `natural initial segments cross the old band and preserve the full authored demo journey`() {
        val directory = Files.createTempDirectory("demo-natural-path-diagnostic-")
        println("demo-natural-path-diagnostic-output=$directory")
        val input = DemoScenario.load()
        val goal = Position(1140.0, 400.0)
        val blocked = buildList {
            for (row in 0 until input.map.heightTiles) for (column in 0 until input.map.widthTiles) {
                if (input.map.blocking.isBlocked(column, row)) add(column to row)
            }
        }
        val violations = mutableListOf<String>()
        var violationCount = 0L
        Files.newBufferedWriter(directory.resolve("trajectory.tsv")).use { trace ->
            trace.appendLine("kind\tstep\tunit\tx\ty\theading\tspeed\tstate\ttarget\tpath")
            DemoSession(input).use { session ->
                val starts = session.observation.units.associate { it.unitId to it.position }
                val initiallyUnknown = session.knowledge.cell(25, 20).lastKnownBlocked == null
                session.moveSelected(goal)
                var pointBandUnits = 0
                var segmentBandUnits = 0
                Files.newBufferedWriter(directory.resolve("initial-paths.tsv")).use { initial ->
                    initial.appendLine("unit\tstartX\tstartY\tpathPoints\toldPointPredicate\tcontinuousSegmentCrossesBand")
                    for (unit in session.observation.units) {
                        val points = listOf(starts.getValue(unit.unitId)) + unit.pathPoints
                        val pointBand = unit.pathPoints.any {
                            (it.x / 20).toInt() in 24..26 && (it.y / 20).toInt() in 15..24
                        }
                        val segmentBand = points.zipWithNext().any { (a, b) ->
                            segmentRectangleDistance(a, b, 480.0, 300.0, 540.0, 500.0) == 0.0
                        }
                        if (pointBand) pointBandUnits++
                        if (segmentBand) segmentBandUnits++
                        val start = starts.getValue(unit.unitId)
                        initial.appendLine("${unit.unitId}\t${start.x}\t${start.y}\t${unit.pathPoints.joinToString(";") { "${it.x}:${it.y}" }}\t$pointBand\t$segmentBand")
                    }
                }
                val initialSearches = session.searches
                val initialReplans = session.replans
                var discovered = false
                var memory = false
                var moved = false
                var previous = starts
                var knowledge = session.knowledge.cells.map { it.visible to it.lastKnownBlocked }
                Files.newBufferedWriter(directory.resolve("steps-and-knowledge.tsv")).use { states ->
                    states.appendLine("step\tsearches\treplans\tvisible\texplored\tevents\tknowledgeChanges")
                    repeat(2500) {
                        session.tick()
                        val observation = session.observation
                        val snapshot = session.knowledge
                        discovered = discovered || snapshot.cells.any { it.lastKnownBlocked == true }
                        memory = memory || snapshot.cells.any { !it.visible && it.lastKnownBlocked != null }
                        moved = moved || observation.units.any { it.position.x > 1000 }
                        val current = snapshot.cells.map { it.visible to it.lastKnownBlocked }
                        states.appendLine("${observation.stepIndex}\t${session.searches}\t${session.replans}\t${session.visibleCells}\t${session.exploredCells}\t${observation.events.joinToString(";") { "${it.kind}:${it.unitId}" }}\t${current.indices.filter { current[it] != knowledge[it] }.joinToString(";") { "$it:${current[it].first}:${current[it].second}" }}")
                        knowledge = current
                        for (unit in observation.units) {
                            trace.appendLine("advance\t${observation.stepIndex}\t${unit.unitId}\t${unit.position.x}\t${unit.position.y}\t${unit.heading}\t${unit.speed}\t${unit.movementState}\t${unit.moveTarget}\t${unit.pathPoints.joinToString(";") { "${it.x}:${it.y}" }}")
                            val radius = input.unitDefinitions.getValue(unit.typeId).radius
                            for ((column, row) in blocked) {
                                val distance = segmentRectangleDistance(previous.getValue(unit.unitId), unit.position,
                                    column * 20.0, row * 20.0, (column + 1) * 20.0, (row + 1) * 20.0)
                                if (distance <= radius) {
                                    violationCount++
                                    if (violations.size < 16) violations += "${unit.unitId} step=${observation.stepIndex} wall=($column,$row) distance=$distance radius=$radius"
                                }
                            }
                        }
                        previous = observation.units.associate { it.unitId to it.position }
                    }
                }
                val targetRadius = input.movementContract!!.targetRadius
                val arrived = session.observation.units.count {
                    hypot(it.position.x - goal.x, it.position.y - goal.y) <= targetRadius &&
                        it.moveTarget == null && it.speed == 0.0
                }
                val summary = "initiallyUnknown=$initiallyUnknown\nunits=${starts.size}\ninitialSearches=$initialSearches\ninitialReplans=$initialReplans\npointBandUnits=$pointBandUnits\nsegmentBandUnits=$segmentBandUnits\nsteps=2500\ndiscovered=$discovered\nmemory=$memory\nmovedBeyondBarriers=$moved\nsearches=${session.searches}\nreplans=${session.replans}\narrived=$arrived\ntrueWallSweepViolations=$violationCount\nfirstViolations=${violations.joinToString(" | ")}\nfinalStates=${session.observation.units.groupingBy { it.movementState }.eachCount()}\n"
                Files.writeString(directory.resolve("summary.txt"), summary)
                println(summary)
                assertTrue(initiallyUnknown)
                assertEquals(50, starts.size)
                assertEquals(50, initialSearches)
                assertEquals(0, initialReplans)
                assertEquals(0, pointBandUnits, "Retained old point predicate must stay visibly distinct")
                assertTrue(segmentBandUnits > 0, "The actual initial continuous routes must cross the old unknown band")
                assertTrue(discovered)
                assertTrue(memory)
                assertTrue(moved)
                assertTrue(session.replans > 0)
                assertEquals(0L, violationCount, violations.joinToString("; "))
                assertEquals(50, arrived, summary)
            }
        }
    }

    private fun segmentRectangleDistance(a: Position, b: Position, left: Double, top: Double,
                                         right: Double, bottom: Double): Double {
        fun endpointDistance(p: Position) = hypot(maxOf(left - p.x, 0.0, p.x - right),
            maxOf(top - p.y, 0.0, p.y - bottom))
        if (endpointDistance(a) == 0.0 || endpointDistance(b) == 0.0) return 0.0
        val corners = listOf(Position(left, top), Position(right, top), Position(right, bottom), Position(left, bottom))
        for (index in corners.indices) {
            val c = corners[index]
            val d = corners[(index + 1) % corners.size]
            if (Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)) return 0.0
        }
        return minOf(endpointDistance(a), endpointDistance(b), corners.minOf {
            Line2D.ptSegDist(a.x, a.y, b.x, b.y, it.x, it.y)
        })
    }
}
