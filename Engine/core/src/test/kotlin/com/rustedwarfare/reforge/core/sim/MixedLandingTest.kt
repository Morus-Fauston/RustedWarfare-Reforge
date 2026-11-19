package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class MixedLandingTest {
    private val click = Position(420.0, 260.0)

    private fun scenario(blocked: (Int, Int) -> Boolean, smallStart: Position): Scenario {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val cells = BooleanArray(38 * 38) { blocked(it % 38, it / 38) }
        return base.copy(map = MapDefinition(38, 38, 20, 20, emptyList(), IntArray(cells.size), BlockingGrid(38, 38, cells)),
            unitDefinitions = mapOf("small" to TestContentFactory.unitWith(tank, 6.0), "big" to TestContentFactory.unitWith(tank, 18.0)),
            units = listOf(UnitSpawn("a", "p1", "big", Position(180.0, 260.0), 90.0), UnitSpawn("b", "p1", "small", smallStart, 90.0)))
    }

    private fun drive(scene: Scenario, target: Position = click, checkStep: (Observation, List<MotionStepSample>) -> Boolean) {
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = scene.units.filter { it.unitId == "a" || it.unitId == "b" }.map { it.unitId }
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, target))
            for (id in ids) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("go-$id", "p1", id, target, ctx.world), group))
            ctx.enableMotionDiagnostics(ctx.world)
            repeat(3000) { tick ->
                val observation = ctx.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                if (checkStep(observation, ctx.drainMotionDiagnostics(ctx.world))) return
            }
            fail("Group did not settle: ${ctx.observe().units.map { listOf(it.unitId, it.position, it.moveTarget, it.moveArrival) }}")
        } finally { ctx.close() }
    }

    @Test fun mixed_radii_move_towards_the_click_before_claiming_separate_sites() {
        val approaching = mutableSetOf<String>()
        drive(scenario({ _, _ -> false }, Position(160.0, 325.0))) { frame, motion ->
            for (unit in frame.units) if (unit.position.x < 240.0 && unit.pathPoints.isNotEmpty()) {
                assertEquals(click, unit.moveTarget, "Personal pad already bound at departure for ${unit.unitId}")
                assertNull(motion.single { it.unitId == unit.unitId }.landingClaim)
                approaching += unit.unitId
            }
            if (motion.count { it.landingParked } != 2) false else {
                assertEquals(setOf("a", "b"), approaching)
                assertEquals(2, motion.mapNotNull { it.landingClaim }.distinct().size)
                assertTrue(frame.units.all { it.moveArrival != null })
                true
            }
        }
    }

    @Test fun first_arriving_small_car_leaves_the_only_wide_bay() = checkBays(false)
    @Test fun another_reachable_wide_bay_lets_the_small_car_park_nearby() = checkBays(true)

    @Test fun a_wide_bay_is_still_scarce_when_the_large_car_must_detour_to_reach_it() = checkBays(false, true)

    @Test fun click_near_the_end_wall_still_leaves_the_only_wide_bay_for_the_big_car() =
        checkBays(false, target = Position(430.0, 260.0))

    @Test fun click_near_the_end_wall_can_address_the_second_wide_bay() =
        checkBays(true, target = Position(430.0, 260.0))

    @Test fun two_known_parked_cars_in_the_second_leg_keep_the_narrow_bay_unclaimed() {
        val base = scenario({ x, y -> !(x in 2..19 && y in 5..21 || x in 20..21 && (y in 12..13 || y == 16)) },
            Position(380.0, 260.0))
        val scene = base.copy(units = base.units + listOf(UnitSpawn("c", "p1", "small", Position(410.0, 330.0), 90.0),
            UnitSpawn("d", "p1", "small", Position(430.0, 330.0), 90.0)))
        var ticks = 0
        drive(scene) { _, motion ->
            assertNotEquals(Position(420.0, 330.0), motion.single { it.unitId == "b" }.landingClaim)
            ++ticks == 60
        }
    }

    @Test fun one_unit_bay_slices_resume_each_leg_and_recheck_changed_traffic_and_terrain() {
        // The diagonal touches the corner, but both legs of the apron elbow are free.
        val scene = scenario({ x, y -> x == 19 && y == 15 }, Position(380.0, 260.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val site = Position(420.0, 330.0)
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "b"), click))
            val def = scene.unitDefinitions.getValue("small")
            val car = MutableUnit("b", "p1", def.id, def, Position(370.0, 260.0), 90.0).apply {
                landingMember = LandingMember(group)
                moveTarget = click
                pathPoints = mutableListOf(click)
            }
            val other = MutableUnit("parked", "p1", def.id, def, site, 90.0)
            var blockers = emptyList<MutableUnit>()
            var ground = com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(scene.map)
            val paths = ExperimentalPolicies().path
            fun serve(survey: MixedLandingBays, sites: List<Position> = listOf(site)): MixedLandingBays.Decision {
                car.heading += 0.1 // A circular body can keep steering between slices of the same swept route.
                val slice = PathWorkBudget(1)
                return survey.choose(listOf(car), sites, { false }, { true }, { ground }, paths, slice,
                    { _, _ -> fail("Clear elbow should not need an exact search") }, { blockers })
            }
            val bay = MixedLandingBays(click, true, 110.0)
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
            val undo = PathSearchRollback()
            val fork = bay.fork(undo)
            assertIs<MixedLandingBays.Decision.Pending>(serve(fork))
            assertEquals(listOf(Position(370.0, 330.0), site), assertIs<MixedLandingBays.Decision.Claim>(serve(fork)).path)
            undo.restore()
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
            assertIs<MixedLandingBays.Decision.Claim>(serve(bay))
            blockers = listOf(other)
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
            other.position = Position(100.0, 100.0)
            car.position = Position(400.0, 330.0)
            assertEquals(listOf(site), assertIs<MixedLandingBays.Decision.Claim>(serve(bay)).path)
            val blocked = scenario({ x, y -> x == 21 && y == 16 }, car.position)
            ground = com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(blocked.map)
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
        } finally { context.close() }
    }

    private fun checkBays(secondWide: Boolean, detour: Boolean = false, target: Position = click) {
        val scene = scenario({ x, y ->
            val apron = x in 2..19 && y in 5..21
            val bay = x in 20..21 && (y in 12..13 || y == 16 || secondWide && y in 8..9)
            !apron && !bay || detour && x == 12 && y in 9..14
        }, Position(380.0, 260.0))
        var stable = 0
        var previous = emptyList<Pair<Position, Double>>()
        drive(scene, target) { frame, _ ->
            val big = frame.units.single { it.unitId == "a" }
            val small = frame.units.single { it.unitId == "b" }
            fun inside(p: Position, radius: Double, top: Int, bottom: Int) =
                p.x in (400 + radius)..(440 - radius) && p.y in (top + radius)..(bottom - radius)
            val parked = big.moveArrival != null && small.moveArrival != null &&
                inside(big.position, 18.0, if (secondWide) 160 else 240, if (secondWide) 200 else 280) &&
                inside(small.position, 6.0, if (secondWide) 240 else 320, if (secondWide) 280 else 340)
            val poses = frame.units.map { it.position to it.heading }
            stable = if (parked && poses == previous) stable + 1 else 0
            previous = poses
            stable >= 60
        }
    }

    @Test fun a_long_hull_uses_the_wide_branch_and_the_small_car_takes_the_tight_bend() {
        val base = scenario({ x, y ->
            val room = x in 26..36 && y in 18..25
            val departure = x in 2..14 && y in 5..24
            val short = x in 15..31 && y == 8 || x == 31 && y in 8..18
            val wide = x in 8..13 && y in 24..35 || x in 8..33 && y in 31..35 || x in 29..33 && y in 24..35
            !(departure || short || wide || room)
        }, Position(125.0, 170.0))
        val tank = base.unitDefinitions.getValue("small")
        val long = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), movementCollision = MovementCollisionShape.Rectangle(76.0, 16.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("long" to long),
            units = listOf(UnitSpawn("a", "p1", "long", Position(200.0, 170.0), 90.0), base.units.last()))
        var longCrossed = false
        var smallCrossed = false
        val target = Position(630.0, 450.0)
        drive(scene, target) { frame, motion ->
            val truck = frame.units.single { it.unitId == "a" }.position
            val car = frame.units.single { it.unitId == "b" }.position
            if (truck.y > 500.0 && truck.x < 280.0) longCrossed = true
            if (car.x > 300.0 && car.y in 166.0..174.0) smallCrossed = true
            assertFalse(truck.x > 300.0 && truck.y < 185.0, "Long hull entered a turn it cannot clear")
            if (frame.units.any { it.moveArrival == null }) false else {
                assertTrue(longCrossed && smallCrossed)
                assertTrue(frame.units.all { it.position.x in 520.0..740.0 && it.position.y in 360.0..520.0 })
                assertTrue(motion.all { it.landingParked })
                true
            }
        }
    }
}
