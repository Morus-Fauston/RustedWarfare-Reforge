package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.test.*

class AmphibiousJetRecoveryTest {
    @Test fun a_new_group_order_replaces_the_old_paused_route_when_flight_resumes() =
        WaterTestScene.withMap { map ->
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 270.0),
                    UnitSpawn("wing", "p1", "amphibiousJet", Position(530.0, 300.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "wing" to 9L), 10L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("old", "p1", "jet", Position(300.0, 200.0), context.world)))
                context.advance(Step(0, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                for (step in 1L..300L) context.advance(Step(step, 1.0, "sim-tick"))
                fun jet() = context.observe().units.single { it.unitId == "jet" }
                assertNull(jet().moveTarget)
                assertEquals(UnitTaskKind.MOVE, jet().tasks.single().kind)
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("fly", "p1", "jet", true, context.world)))
                var step = 301L
                while (jet().amphibiousJet?.movementType != MovementType.AIR && step < 650L) {
                    context.advance(Step(step++, 1.0, "sim-tick"))
                }
                assertEquals(MovementType.AIR, jet().amphibiousJet?.movementType)
                assertNull(jet().moveTarget)
                context.enablePathDiagnostics(context.world)
                val target = Position(280.0, 220.0)
                val members = listOf("jet", "wing")
                val request = assertNotNull(context.requestGroupMove("p1", members, target))
                for (id in members) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                    MoveAction("group-$id", "p1", id, target, context.world), request))
                val published = mutableSetOf<String>()
                for (tick in step..step + 120) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    for (unit in frame.units) {
                        if (unit.moveTarget == null) continue
                        assertEquals("group-${unit.unitId}", unit.tasks.first().actionId)
                        assertEquals(target, unit.moveTarget)
                        published += unit.unitId
                    }
                    assertTrue(context.drainPathDiagnostics(context.world).none { it.actionId == "old" })
                    if (published.containsAll(members)) break
                }
                assertEquals(members.toSet(), published)
            } finally { context.close() }
        }

    @Test fun flying_again_resumes_a_preserved_move_that_was_paused_by_water_terrain() =
        WaterTestScene.withMap { map ->
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "jet", Position(300.0, 200.0), context.world)))
                context.advance(Step(0, 1.0, "sim-tick"))
                val task = context.observe().units.single().tasks
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                for (q in 1L..300L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(task, context.observe().units.single().tasks)
                assertTrue(context.observe().units.single().position.x >= 400.0)
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("fly", "p1", "jet", true, context.world)))
                for (q in 301L..1000L) context.advance(Step(q, 1.0, "sim-tick"))
                val arrived = context.observe().units.single()
                assertTrue(arrived.position.x < 320.0, "The preserved move should resume after AIR becomes physically valid")
                assertTrue(arrived.tasks.isEmpty())
            } finally { context.close() }
        }
}
