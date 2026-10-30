package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Isolated acceptance state-machine checks, not a real solver's zero-node result. */
class ConstructionNavigationStateTest {
    @Test fun zero_and_multiple_node_acceptances_keep_the_count_and_signed_zero_keeps_the_float_request() {
        val unit = worker()
        val target = Position(0.0, 100.0)
        unit.adoptRoute(listOf(target), target, 0)
        unit.requestMovement(target)
        unit.finishMovementRequest()
        assertEquals(1.toByte(), unit.singleNodePathCount)
        unit.adoptRoute(emptyList(), target, 0)
        assertEquals(1.toByte(), unit.singleNodePathCount)
        unit.requestMovement(target); unit.finishMovementRequest()
        assertEquals(listOf(0), unit.consumedPathNodeCounts)
        assertEquals(1.toByte(), unit.singleNodePathCount)
        unit.adoptRoute(listOf(Position(10.0, 90.0), target), target, 0)
        unit.requestMovement(Position(-0.0, 100.0))
        assertEquals(listOf(2), unit.pendingPathNodeCounts)
        unit.finishMovementRequest()
        assertEquals(listOf(2), unit.consumedPathNodeCounts)
        assertEquals(1.toByte(), unit.singleNodePathCount)
        unit.adoptRoute(listOf(target), target, 0)
        unit.requestMovement(Position(0.0, 101.0))
        assertEquals(0.toByte(), unit.singleNodePathCount)
        assertTrue(unit.pendingPathNodeCounts.isEmpty())
        unit.finishMovementRequest()
        assertEquals(0.toByte(), unit.singleNodePathCount)
    }

    @Test fun no_request_and_cancel_discard_pending_acceptances_without_resurrecting_them_at_the_tail() {
        val unit = worker(); val target = Position(650.0, 170.0)
        unit.adoptRoute(listOf(target), target, 0)
        unit.requestMovement(target); unit.finishMovementRequest()
        assertEquals(1.toByte(), unit.singleNodePathCount)
        unit.adoptRoute(listOf(target), target, 0)
        // Mirrors the real dispatch boundary; this module fixture never sets q.
        unit.movementRequested = false
        unit.finishMovementRequest()
        assertEquals(0.toByte(), unit.singleNodePathCount)
        assertNull(unit.logicalRequestX); assertNull(unit.logicalRequestY)
        assertTrue(unit.pendingPathNodeCounts.isEmpty() && unit.consumedPathNodeCounts.isEmpty())
        unit.adoptRoute(listOf(target), target, 0); unit.requestMovement(target)
        unit.cancelRoute(); unit.finishMovementRequest()
        assertEquals(0.toByte(), unit.singleNodePathCount)
        assertTrue(unit.pendingPathNodeCounts.isEmpty() && unit.consumedPathNodeCounts.isEmpty())
        assertNull(unit.logicalRequestX)
    }

    private fun worker(): MutableUnit {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
            .resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input"))
        val definition = assertIs<ContentResult.Success<com.rustedwarfare.reforge.core.content.UnitDefinition>>(
            ContentLoader.loadBuilder(root.resolve("units/builder.properties"))).value
        return MutableUnit("worker", "p1", definition.id, definition, Position(120.0, 170.0), 90.0)
    }
}
