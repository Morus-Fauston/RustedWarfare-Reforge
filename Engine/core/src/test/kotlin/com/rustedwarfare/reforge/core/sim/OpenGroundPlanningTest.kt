package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningWorkSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenGroundPlanningTest {
    private fun withOpenOrder(run: (MovePlanningInput, GroupMoveRequest) -> Unit) {
        val opening = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 6.0)
        val map = MapDefinition(48, 32, 20, 20, emptyList(), IntArray(48 * 32),
            BlockingGrid(48, 32, BooleanArray(48 * 32)))
        val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = (0 until 6).map {
            UnitSpawn("u$it", "p1", "tank", Position(80.0 + it * 16, 160.0 + it * 17), 90.0)
        })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(660.0, 220.0)
            val terrain = PlanningTerrain.fromNoFog(map)
            val members = scene.units.map { s ->
                val action = MoveAction("go-${s.unitId}", "p1", s.unitId, click, context.world)
                val body = MutableUnit(s.unitId, "p1", "tank", tank, s.position, s.heading)
                MovePlanningMember(action, body, MovePlanningPose(s.position, 0.0, ContactLayer.SURFACE,
                    tank.movementCollision), s.position, tank.radius, terrain, null, false, 0)
            }
            run(MovePlanningInput(map, members, emptyList(), false),
                assertNotNull(context.requestGroupMove("p1", members.map { it.action.unitId }, click)))
        } finally { context.close() }
    }

    @Test
    fun a_newly_known_body_on_the_approach_invalidates_a_pending_open_ground_order() = withOpenOrder { input, group ->
        fun run(at: Position): Pair<Boolean, Int> {
            val mover = input.members.first()
            val other = mover.body!!.copy(unitId = "newcomer", position = at)
            val current = input.copy(occupants = listOf(MovePlanningOccupant(other,
                mover.pose.copy(position = at), null, idle = true)))
            val planning = MovePlanning()
            input.members.forEach { planning.enqueue(it.action, group) }
            var snapshots = 0
            var published = 0
            val samples = mutableListOf<PlanningWorkSlice>()
            for (tick in 0..4000) {
                planning.advance(ExperimentalPolicies().path, PathWorkBudget(128),
                    { _, _, _ -> if (snapshots++ == 0) input else current },
                    { _, _, _, _, result -> assertNotNull(result.slot); published++ }, samples)
                if (samples.any { it.restarted } || published != 0) break
            }
            assertTrue(snapshots >= 2, "The order must span simulation steps")
            return samples.any { it.restarted } to published
        }
        assertEquals(false to input.members.size, run(Position(900.0, 500.0)), "A distant bystander is irrelevant")
        assertEquals(true to 0, run(Position(340.0, 200.0)), "A newcomer between start and click invalidates the clear approach")
    }

    @Test
    fun a_known_distant_population_is_not_rescanned_for_every_open_ground_route() = withOpenOrder { input, group ->
        val member = input.members.first()
        val far = Position(900.0, 520.0)
        val holders = (0 until 384).map { i -> MovePlanningOccupant(member.body!!.copy(unitId = "far$i", position = far),
            member.pose.copy(position = far), null, idle = true) }
        var reads = 0
        val counted = object : AbstractList<MovePlanningOccupant>() {
            override val size get() = holders.size
            override fun get(index: Int): MovePlanningOccupant { reads++; return holders[index] }
        }
        val start = input.copy(occupants = counted)
        val planning = MovePlanning()
        input.members.forEach { planning.enqueue(it.action, group) }
        var snapshots = 0
        var published = 0
        for (tick in 0..4000) {
            planning.advance(ExperimentalPolicies().path, PathWorkBudget(128), { _, _, _ ->
                if (++snapshots == 2) assertTrue(reads <= holders.size,
                    "Before live publication, $reads occupant reads for ${holders.size} distant holders")
                start
            }, { _, _, _, _, result -> assertNotNull(result.slot); published++ })
            if (published == input.members.size) break
        }
        assertTrue(snapshots >= 2)
        assertEquals(input.members.size, published)
    }

    @Test
    fun a_ready_driver_moves_under_its_own_order_while_the_tail_is_still_planning() {
        val opening = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 6.0)
        val width = 48; val height = 32
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height)))
        val click = Position(640.0, 240.0)
        val spawns = List(50) { i -> UnitSpawn("u${i.toString().padStart(2, '0')}", "p1", "tank",
            Position(80.0 + i % 10 * 32, 150.0 + i / 10 * 38), 90.0) }
        val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = spawns.map { it.unitId }.toSet()
            val group = assertNotNull(context.requestGroupMove("p1", ids, click))
            context.enableMotionDiagnostics(context.world)
            for (id in ids) assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("go-$id", "p1", id, click, context.world), group))
            val started = mutableMapOf<String, Long>()
            val own = mutableMapOf<String, Long>()
            for (tick in 0L..2000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                for (event in frame.events.filter { it.kind == "movement-started" }) {
                    val id = assertNotNull(event.unitId)
                    assertTrue(id !in started, "Must not reissue $id after publishing it")
                    started[id] = tick
                }
                for (m in context.drainMotionDiagnostics(context.world)) if (m.originMoveId == "go-${m.unitId}" &&
                    m.commandTargetBefore != null && m.pushedDisplacement == Position(0.0, 0.0) &&
                    (m.finalPosition != m.positionBefore || m.finalHeading != m.headingBefore)) own.putIfAbsent(m.unitId, tick)
                if (own.size == ids.size) break
            }
            assertEquals(ids, started.keys, "Queue tail must receive routes")
            assertEquals(ids, own.keys, "A published route or a push alone is not a start")
            assertTrue(own.values.min() <= started.values.max(),
                "Autonomous motion must begin no later than last publication: firstOwn=${own.values.min()}, lastPublished=${started.values.max()}")
        } finally { context.close() }
    }
}
