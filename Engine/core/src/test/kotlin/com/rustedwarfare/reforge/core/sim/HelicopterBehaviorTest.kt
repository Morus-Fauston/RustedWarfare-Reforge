package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class HelicopterBehaviorTest {
    @Test fun explicit_height_rate_and_sliding_direction_identity_follow_the_published_content_contract() {
        val source = HelicopterTestScene.scenario()
        val m = source.unitDefinitions.getValue("helicopter").movement
        val c = HelicopterTestScene.open(changed(source, m.copy(flight = checkNotNull(m.flight).copy(
            heightChangeRate = 0.01, startingHeightOffset = 5.0))))
        try {
            assertEquals(5.0, c.observe().units.single().flightPose?.height)
            assertEquals(5.01, assertNotNull(c.advance(Step(0, 1.0, "sim-tick")).units.single().flightPose).height, 1e-10)
        } finally { c.close() }
        fun run(direction: Int): List<UnitObservation> {
            val ctx = HelicopterTestScene.open(changed(source, m.copy(slidingDirection = direction)))
            return try {
                move(ctx)
                (0L..30L).map { ctx.advance(Step(it, 1.0, "sim-tick")).units.single() }
            } finally { ctx.close() }
        }
        assertEquals(run(181), run(0))
    }

    @Test fun visible_enemy_paths_are_redacted_and_uncontrolled_or_unknown_players_cannot_issue_orders() {
        val s = HelicopterTestScene.scenario()
        val c = HelicopterTestScene.open(s.copy(units = s.units + UnitSpawn("other", "p2", "helicopter", Position(400.0, 200.0), 0.0)))
        try {
            move(c)
            val visible = c.observePlayer(c.world, "p2").units.single { it.unitId == "h1" }
            assertNotNull(visible.flightPose)
            assertNull(visible.moveTarget)
            assertTrue(visible.pathPoints.isEmpty())
            assertEquals("actor-not-controlled", assertIs<ActionResult.Rejected>(c.submit(StopAction("uncontrolled", "p2", "other", c.world))).error.code)
            assertEquals("unknown-actor", assertIs<ActionResult.Rejected>(c.submit(StopAction("unknown", "missing", "h1", c.world))).error.code)
        } finally { c.close() }
    }

    private fun changed(source: Scenario, movement: MovementProfile? = null, rotor: RotorDefinition? = null): Scenario {
        val d = source.unitDefinitions.getValue("helicopter")
        val definition = UnitDefinition(d.id, d.className, d.radius, d.displayRadius, d.totalFrames, d.imagePath,
            d.imageScale, movement ?: d.movement, d.retainedUnconsumed, d.fogOfWarSightRange, rotor = rotor ?: d.rotor)
        return source.copy(unitDefinitions = mapOf("helicopter" to definition))
    }
    private fun move(c: SimulationContext, id: String = "move", target: Position = Position(600.0, 200.0)) =
        assertIs<ActionResult.Accepted>(c.submit(MoveAction(id, "p1", "h1", target, c.world)))

    @Test fun all_cardinal_and_diagonal_commands_work_from_opposite_and_wrapped_body_headings() {
        val targets = listOf(Position(600.0, 200.0), Position(100.0, 200.0), Position(200.0, 100.0),
            Position(200.0, 500.0), Position(500.0, 500.0), Position(100.0, 100.0), Position(100.0, 300.0), Position(300.0, 100.0))
        for (heading in listOf(0.0, 90.0, 180.0, 359.9)) for (target in targets) {
            val c = HelicopterTestScene.open(HelicopterTestScene.scenario(heading = heading))
            try {
                move(c, target = target)
                val u = c.advance(Step(0, 1.0, "sim-tick")).units.single()
                assertEquals(0.0423, u.speed, 1e-10)
                val vx = u.position.x - 200.0; val vy = u.position.y - 200.0
                assertTrue(vx * (target.x - 200.0) >= 0 && vy * (target.y - 200.0) >= 0)
                if (target.x == 200.0) assertEquals(0.0, vx, 1e-10)
                if (target.y == 200.0) assertEquals(0.0, vy, 1e-10)
                if (target.x != 200.0 && target.y != 200.0) assertEquals(kotlin.math.abs(vx), kotlin.math.abs(vy), 1e-10)
            } finally { c.close() }
        }
    }

    @Test fun retarget_preserves_vector_inertia_and_uses_the_large_difference_damping_branch() {
        val c = HelicopterTestScene.open(HelicopterTestScene.scenario(heading = 90.0))
        try {
            move(c)
            repeat(45) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = c.observe().units.single()
            assertEquals(1.8, before.speed, 1e-10)
            move(c, "reverse", Position(100.0, 200.0))
            val after = c.advance(Step(45, 1.0, "sim-tick")).units.single()
            // Independent literal witness: 1.8*.95 - .03*1.41 = 1.6677.
            assertEquals(1.6677, assertNotNull(after.flightPose).velocity.x, 1e-10)
            assertTrue(after.position.x > before.position.x)
            move(c, "north", Position(after.position.x, 50.0))
            val north = c.advance(Step(46, 1.0, "sim-tick")).units.single()
            assertTrue(assertNotNull(north.flightPose).velocity.x > 0.0)
            assertTrue(assertNotNull(north.flightPose).velocity.y < 0.0)
        } finally { c.close() }
    }

    @Test fun stop_decays_a_vector_without_rotating_body_and_keeps_hover_animation_alive() {
        val c = HelicopterTestScene.open()
        try {
            move(c)
            repeat(10) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = c.observe().units.single()
            assertEquals(202.3265, before.position.x, 1e-10)
            assertEquals(0.423, before.speed, 1e-10)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "h1", c.world)))
            val first = c.advance(Step(10, 1.0, "sim-tick")).units.single()
            assertEquals(0.3384, first.speed, 1e-10)
            assertEquals(MovementState.DECELERATING, first.movementState)
            assertEquals(null, first.moveTarget)
            repeat(10) { c.advance(Step(11L + it, 1.0, "sim-tick")) }
            val stopped = c.observe().units.single()
            assertEquals(0.0, stopped.speed)
            assertEquals(before.heading, stopped.heading)
            assertEquals(before.angularVelocity, stopped.angularVelocity)
            assertEquals(MovementState.IDLE, stopped.movementState)
            val next = c.advance(Step(21, 1.0, "sim-tick")).units.single()
            assertEquals(stopped.position, next.position)
            assertNotEquals(stopped.flightPose?.height, next.flightPose?.height)
            assertNotEquals(stopped.flightPose?.rotorPhase, next.flightPose?.rotorPhase)
        } finally { c.close() }
    }

    @Test fun short_and_long_moves_report_normal_arrival_and_settle_without_an_external_stop() {
        for (target in listOf(Position(206.0, 200.0), Position(600.0, 200.0))) {
            val c = HelicopterTestScene.open()
            try {
                move(c, target = target)
                val events = mutableListOf<String>()
                repeat(700) { q ->
                    val before = c.observe().units.single()
                    val result = c.advance(Step(q.toLong(), 1.0, "sim-tick"))
                    events += result.events.map { it.kind }
                    if (result.events.any { it.kind == "movement-arrived" }) {
                        // The seven-unit gate clears the command BEFORE this step's inertial integration.
                        assertTrue(kotlin.math.hypot(before.position.x - target.x, before.position.y - target.y) < 7.0)
                        println("normal-arrival target=$target q=$q before=$before after=${result.units.single()}")
                    }
                }
                val last = c.observe().units.single()
                assertEquals(1, events.count { it == "movement-arrived" })
                assertFalse("movement-cancelled" in events)
                assertNull(last.moveTarget)
                assertEquals(0.0, last.speed)
                assertEquals(last.position, c.advance(Step(700, 1.0, "sim-tick")).units.single().position)
            } finally { c.close() }
        }
    }

    @Test fun air_can_spawn_in_and_cross_completely_blocked_ground_in_every_knowledge_mode() {
        for (rules in listOf(null, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
                KnowledgeRules(TerrainRetention.HARDCORE_FORGET, PathInformationSource.PLAYER_KNOWLEDGE, false))) {
            val source = HelicopterTestScene.scenario(heading = 90.0)
            val m = source.map
            val blocked = MapDefinition(m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight, m.tileSets,
                m.groundGids, BlockingGrid(m.widthTiles, m.heightTiles, BooleanArray(m.widthTiles * m.heightTiles) { true }))
            val c = HelicopterTestScene.open(source.copy(map = blocked, automaticVision = rules))
            try {
                move(c)
                assertEquals(listOf(Position(600.0, 200.0)), c.observePlayer(c.world, "p1").units.single().pathPoints)
                val events = mutableListOf<String>()
                repeat(500) { q -> events += c.advance(Step(q.toLong(), 1.0, "sim-tick")).events.map { it.kind } }
                assertEquals(1, events.count { it == "movement-arrived" })
                assertFalse("movement-blocked" in events)
                assertFalse("path-unreachable" in events)
            } finally { c.close() }
        }
    }

    @Test fun radius_tangent_spawns_are_legal_but_outside_spawns_and_targets_keep_existing_boundary_rules() {
        val s = HelicopterTestScene.scenario()
        for (position in listOf(Position(15.0, 15.0), Position(785.0, 585.0))) {
            val c = HelicopterTestScene.open(s.copy(units = listOf(s.units.single().copy(position = position))))
            c.close()
        }
        val invalid = SimulationContext.initialize(s.copy(units = listOf(s.units.single().copy(position = Position(14.99, 200.0)))))
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(invalid).error.code)
        val c = HelicopterTestScene.open(s)
        try {
            move(c)
            repeat(10) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val response = move(c, "invalid-target", Position(14.99, 200.0))
            assertTrue(response.events.any { it.kind == "path-unreachable" })
            assertNull(c.observe().units.single().moveTarget)
            val before = c.observe().units.single()
            val result = assertIs<ActionResult.Rejected>(c.submit(MoveAction("nan", "p1", "h1", Position(Double.NaN, 1.0), c.world)))
            assertEquals("invalid-target", result.error.code)
            assertEquals(before, c.observe().units.single())
        } finally { c.close() }
    }

    @Test fun residual_velocity_cannot_cross_the_outer_circle_boundary_and_new_orders_can_recover() {
        val source = HelicopterTestScene.scenario(heading = 90.0)
        val m = source.unitDefinitions.getValue("helicopter").movement
        val c = HelicopterTestScene.open(changed(source, m.copy(moveDecelerationSpeed = 0.0)).copy(
            units = listOf(source.units.single().copy(position = Position(700.0, 200.0)))))
        try {
            move(c, target = Position(785.0, 200.0))
            var boundaryStep = -1L
            for (q in 0L..299L) {
                val before = c.observe().units.single()
                val result = c.advance(Step(q, 1.0, "sim-tick"))
                if (result.events.any { it.kind == "movement-boundary-blocked" }) {
                    val after = result.units.single()
                    assertEquals(before.position, after.position)
                    assertEquals(0.0, after.speed)
                    assertEquals(Position(0.0, 0.0), after.flightPose?.velocity)
                    assertEquals(MovementState.BLOCKED, after.movementState)
                    boundaryStep = q
                    break
                }
            }
            assertTrue(boundaryStep >= 0)
            val before = c.observe().units.single().position
            move(c, "recover", Position(500.0, 200.0))
            val result = c.advance(Step(boundaryStep + 1, 1.0, "sim-tick")).units.single()
            assertTrue(result.position.x < before.x)
        } finally { c.close() }
    }

    @Test fun hidden_enemy_pose_and_commands_do_not_escape_player_observation_or_control_rules() {
        for (retention in TerrainRetention.entries) {
            val source = HelicopterTestScene.scenario()
            val c = HelicopterTestScene.open(source.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                units = source.units + UnitSpawn("hidden", "p2", "helicopter", Position(700.0, 500.0), 90.0),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false)))
            try {
                val before = c.observePlayer(c.world, "p1")
                assertEquals(listOf("h1"), before.units.map { it.unitId })
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("enemy", "p2", "hidden", Position(600.0, 500.0), c.world)))
                val after = c.observePlayer(c.world, "p1")
                assertSame(before, after)
                assertTrue(after.actionResults.isEmpty())
                assertEquals("not-owner", assertIs<ActionResult.Rejected>(c.submit(StopAction("steal", "p1", "hidden", c.world))).error.code)
                val world = c.world
                c.close()
                assertIs<InitResult.Accepted>(c.reopen(source))
                assertEquals("stale-world", assertIs<ActionResult.Rejected>(c.submit(StopAction("stale", "p1", "h1", world))).error.code)
            } finally { c.close() }
        }
    }

    @Test fun phase_boundaries_signed_spin_and_repeated_runs_are_reproducible() {
        val source = HelicopterTestScene.scenario(heading = 359.0)
        val d = source.unitDefinitions.getValue("helicopter")
        val input = changed(source, rotor = checkNotNull(d.rotor).copy(spinRate = -30.0))
        fun run(): List<UnitObservation> {
            val c = HelicopterTestScene.open(input)
            return try { (0..180).map { c.advance(Step(it.toLong(), 1.0, "sim-tick")).units.single() } }
                   finally { c.close() }
        }
        val first = run()
        assertEquals(first, run())
        assertEquals(-30.0, first[0].flightPose?.rotorPhase)
        assertEquals(329.0, first[0].flightPose?.rotorHeading)
        assertEquals(-0.0, first[11].flightPose?.rotorPhase)
        assertEquals(360.0, first[179].flightPose?.heightPhase)
        assertEquals(2.0, first[180].flightPose?.heightPhase)
        assertTrue(first.all { val pose = assertNotNull(it.flightPose); pose.height.isFinite() && pose.height in 0.0..22.0 })
    }

    @Test fun nonfinite_flight_configuration_is_rejected_and_arithmetic_failure_rolls_back_the_whole_step() {
        val source = HelicopterTestScene.scenario(heading = 90.0)
        val d = source.unitDefinitions.getValue("helicopter")
        val invalid = changed(source, d.movement.copy(flight = checkNotNull(d.movement.flight).copy(heightDrift = Double.NaN)))
        assertEquals("unsupported-flight", assertIs<InitResult.Rejected>(SimulationContext.initialize(invalid)).error.code)
        val bad = UnitDefinition("bad", d.className, d.radius, d.displayRadius, d.totalFrames, d.imagePath, d.imageScale,
            d.movement.copy(moveAccelerationSpeed = Double.MAX_VALUE), d.retainedUnconsumed, d.fogOfWarSightRange, rotor = d.rotor)
        val c = HelicopterTestScene.open(source.copy(unitDefinitions = source.unitDefinitions + ("bad" to bad),
            units = source.units + UnitSpawn("z-bad", "p1", "bad", Position(200.0, 300.0), 90.0)))
        try {
            move(c)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("bad", "p1", "z-bad", Position(600.0, 300.0), c.world)))
            val before = c.observe()
            assertFailsWith<IllegalStateException> { c.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, c.observe())
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-bad", "p1", "z-bad", c.world)))
            val next = c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(200.0423, next.units.first().position.x, 1e-10)
        } finally { c.close() }
    }
}
