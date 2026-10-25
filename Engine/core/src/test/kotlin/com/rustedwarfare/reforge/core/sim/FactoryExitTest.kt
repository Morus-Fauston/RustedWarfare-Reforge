package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class FactoryExitTest {
    @Test fun being_inside_a_hidden_truth_building_does_not_select_the_owners_escape_planning_mode() {
        val base = BuildingTestContent.scenario()
        val input = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, u) ->
            BuildingTestContent.variant(u, sight = if (u.building == null) 0 else 1) },
            units = listOf(base.units.single { it.unitId == "f" },
                UnitSpawn("g", "p2", "landFactory", Position(310.0, 270.0), 0.0),
                UnitSpawn("h", "p1", "landFactory", Position(310.0, 330.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "g" to 12L, "h" to 13L), 14L))
        for (hidden in listOf(true, false)) {
            val scene = input.copy(automaticVision = if (!hidden) null else
                KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), c.world)))
                repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val productId = c.observe().production!!.units.single().unitId
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("inside-hidden", "p1", productId,
                    Position(310.0, 295.0), c.world)))
                for (step in 501L..900L) c.advance(Step(step, 1.0, "sim-tick"))
                val born = c.observe().units.single { it.unitId.startsWith("produced:") }
                assertEquals(0f, c.observe().production!!.units.single().birthRemaining)
                assertTrue(born.position.y in 280.0..<300.0,
                    "The unit must actually be inside g's truth footprint: ${born.position}, target=${born.moveTarget}")
                assertTrue(c.observe().units.single { it.unitId == "g" }.alive)
                val visible = c.observePlayer(c.world, "p1")
                assertEquals(!hidden, visible.units.any { it.unitId == "g" })
                assertTrue(visible.units.any { it.unitId == "h" })
                val move = assertIs<ActionResult.Accepted>(c.submit(MoveAction("known-target", "p1", born.unitId,
                    Position(310.0, 330.0), c.world)))
                if (hidden) {
                    assertTrue(move.events.any { it.kind == "path-unreachable" },
                        "Hidden truth must not remove the known destination building from owner planning")
                    assertNull(c.observe().units.single { it.unitId == born.unitId }.moveTarget)
                } else {
                    assertEquals(Position(310.0, 330.0), c.observe().units.single { it.unitId == born.unitId }.moveTarget)
                }
            } finally { c.close() }
        }
    }

    @Test fun nonzero_birth_can_really_cross_another_live_building_while_the_hard_layer_stays_separate() {
        val base = BuildingTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val fast = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(moveSpeed = 80.0, moveAccelerationSpeed = 1.0), original.retainedUnconsumed,
            original.fogOfWarSightRange, original.combat, original.durability, original.building,
            original.production, original.build, original.directHitTargetKind)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (fast.id to fast),
            units = listOf(base.units.single { it.unitId == "f" },
                UnitSpawn("g", "p1", "landFactory", Position(310.0, 270.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "g" to 12L), 13L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", fast.id, "0".repeat(32), c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val view = c.advance(Step(501, 1.0, "sim-tick"))
            val born = view.units.single { it.unitId.startsWith("produced:") }
            assertEquals(29f, view.production!!.units.single().birthRemaining)
            assertTrue(born.position.x in 280.0..<340.0 && born.position.y in 260.0..<300.0)
            assertTrue(view.units.single { it.unitId == "g" }.alive)
            assertTrue(view.events.none { it.kind == "movement-blocked" && it.unitId == born.unitId })
        } finally { c.close() }
    }

    @Test fun a_real_edge_birth_keeps_its_payment_when_exit_is_outside_and_clips_temporary_motion_by_the_whole_circle() {
        val base = BuildingTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val fast = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(moveSpeed = 80.0, moveAccelerationSpeed = 1.0), original.retainedUnconsumed,
            original.fogOfWarSightRange, original.combat, original.durability, original.building,
            original.production, original.build, original.directHitTargetKind)
        val m = base.map
        val map = MapDefinition(m.widthTiles, m.heightTiles, 20, 20, m.tileSets, m.groundGids,
            BlockingGrid(m.widthTiles, m.heightTiles, BooleanArray(m.widthTiles * m.heightTiles)))
        val bottom = map.heightTiles * 20.0
        val scene = base.copy(map = map, unitDefinitions = base.unitDefinitions + (fast.id to fast),
            units = listOf(UnitSpawn("f", "p1", "landFactory", Position(30.0, bottom - 30.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val id = "produced:p1:${"0".repeat(32)}"
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", fast.id, "0".repeat(32), c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val born = c.observe().units.single { it.unitId == id }
            assertEquals(Position(30.0, bottom - 25.0), born.position)
            assertNull(born.moveTarget)
            assertTrue(c.observe().events.any { it.kind == "path-unreachable" && it.unitId == id })
            assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
            val tangent = Position(30.0, bottom - 11.0)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("tangent", "p1", id, tangent, c.world)))
            assertEquals(tangent, c.observe().units.single { it.unitId == id }.moveTarget)
            val step = c.advance(Step(501, 1.0, "sim-tick"))
            assertEquals(born.position, step.units.single { it.unitId == id }.position)
            assertTrue(step.events.any { it.kind == "movement-boundary-blocked" && it.unitId == id })
            assertEquals(29f, step.production!!.units.single().birthRemaining)
            assertEquals(0.0, step.units.single { it.unitId == id }.speed)
            val outside = assertIs<ActionResult.Accepted>(c.submit(MoveAction("outside", "p1", id,
                Position(30.0, bottom - 10.999), c.world)))
            assertTrue(outside.events.any { it.kind == "path-unreachable" })
            assertNull(c.observe().units.single { it.unitId == id }.moveTarget)
            assertEquals("invalid-target", assertIs<ActionResult.Rejected>(c.submit(MoveAction("nonfinite", "p1", id,
                Position(Double.NaN, bottom - 11.0), c.world))).error.code)
            assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun an_illegal_birth_circle_in_an_explicit_trusted_small_producer_variant_restores_the_whole_candidate() {
        val base = BuildingTestContent.scenario()
        val original = base.unitDefinitions.getValue("landFactory")
        // This host fixture reaches an otherwise impossible native-factory failure branch:
        // a radius-1 single-cell producer at the edge cannot contain its normal radius-11 product.
        val small = UnitDefinition(original.id, original.className, 1.0, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            original.fogOfWarSightRange, original.combat, original.durability,
            original.building!!.copy(footprint = BuildingFootprint(0, 0, 0, 0)), original.production, original.build,
            original.directHitTargetKind)
        val m = base.map
        val map = MapDefinition(m.widthTiles, m.heightTiles, 20, 20, m.tileSets, m.groundGids,
            BlockingGrid(m.widthTiles, m.heightTiles, BooleanArray(m.widthTiles * m.heightTiles)))
        val scene = base.copy(map = map, unitDefinitions = base.unitDefinitions + (small.id to small),
            units = listOf(UnitSpawn("f", "p1", small.id, Position(30.0, map.heightTiles * 20.0 - 1.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), c.world)))
            repeat(500) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val old = c.observe()
            val player = c.observePlayer(c.world, "p1")
            assertFailsWith<IllegalArgumentException> { c.advance(Step(500, 1.0, "sim-tick")) }
            assertSame(old, c.observe())
            assertSame(player, c.observePlayer(c.world, "p1"))
            assertEquals(0L, old.production!!.lastBirthSerial)
            assertEquals(11L, old.combat!!.nextObjectId)
            assertEquals(0.9999942183494568f, old.economy!!.queuedProduction.getValue("f").single().progress)
        } finally { c.close() }
    }

    @Test fun escape_planning_and_collision_release_all_known_buildings_without_giving_the_outer_car_that_mode() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId != "b" } +
            UnitSpawn("g", "p1", "landFactory", Position(310.0, 270.0), 0.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L, "g" to 12L), 13L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val id = "produced:p1:${"0".repeat(32)}"
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(StopAction("hold", "p1", id, c.world)))
            for (step in 501L..530L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(0f, c.observe().production!!.units.single().birthRemaining)
            val destination = Position(310.0, 270.0)
            // Alternate the same owner's inside/outside units so the shared cache must change mode.
            repeat(2) { attempt ->
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("escape-$attempt", "p1", id, destination, c.world)))
                assertEquals(destination, c.observe().units.single { it.unitId == id }.moveTarget)
                val ordinary = assertIs<ActionResult.Accepted>(c.submit(MoveAction("ordinary-$attempt", "p1", "a", destination, c.world)))
                assertTrue(ordinary.events.any { it.kind == "path-unreachable" })
                assertNull(c.observe().units.single { it.unitId == "a" }.moveTarget)
            }
            for (step in 531L..730L) c.advance(Step(step, 1.0, "sim-tick"))
            val insideOther = c.observe().units.single { it.unitId == id }
            assertTrue(insideOther.position.y in 260.0..<280.0,
                "C6 must also release another building, not just the producer")
            assertTrue(c.observe().units.single { it.unitId == "g" }.alive)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("leave-other", "p1", id, Position(390.0, 270.0), c.world)))
            for (step in 731L..1030L) c.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(c.observe().units.single { it.unitId == id }.position.x > 350.0)
            val back = assertIs<ActionResult.Accepted>(c.submit(MoveAction("back-normal", "p1", id, destination, c.world)))
            assertTrue(back.events.any { it.kind == "path-unreachable" }, "After leaving known e, normal building cost must apply again")
            assertNull(c.observe().units.single { it.unitId == id }.moveTarget)
        } finally { c.close() }
    }

    @Test fun real_production_keeps_hard_walls_during_birth_and_after_zero_including_a_multi_cell_candidate() {
        for (fast in listOf(false, true)) {
            val base = BuildingTestContent.scenario()
            val original = base.unitDefinitions.getValue("c_tank")
            // The trusted speed variant isolates the approved multi-cell hard-wall gate.
            // It is not an original c_tank motion reference.
            val tank = if (!fast) original else UnitDefinition(original.id, original.className, original.radius,
                original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
                original.movement.copy(moveSpeed = 80.0, moveAccelerationSpeed = 1.0), original.retainedUnconsumed,
                original.fogOfWarSightRange, original.combat, original.durability, original.building,
                original.production, original.build, original.directHitTargetKind)
            val m = base.map
            val map = MapDefinition(m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight, m.tileSets, m.groundGids,
                BlockingGrid(m.widthTiles, m.heightTiles, BooleanArray(m.widthTiles * m.heightTiles) { index ->
                    index / m.widthTiles == 12 || m.blocking.isBlocked(index % m.widthTiles, index / m.widthTiles)
                }))
            val scene = base.copy(map = map, unitDefinitions = base.unitDefinitions + (tank.id to tank),
                units = base.units.filter { it.unitId == "f" },
                combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            val id = "produced:p1:${"0".repeat(32)}"
            try {
                c.enableMotionDiagnostics(c.world)
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", tank.id, "0".repeat(32), c.world)))
                repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                c.drainMotionDiagnostics(c.world)
                var blocked = false
                for (step in 501L..589L) {
                    val view = c.advance(Step(step, 1.0, "sim-tick"))
                    assertTrue(view.units.single { it.unitId == id }.position.y < 240.0)
                    blocked = blocked || view.events.any { it.kind == "movement-blocked" && it.unitId == id }
                }
                assertTrue(blocked)
                assertEquals(0f, c.observe().production!!.units.single().birthRemaining)
                assertEquals(1400L, c.observe().economy!!.balances["p1"])
                assertTrue(c.observe().economy!!.queuedProduction.getValue("f").isEmpty())
                if (fast) {
                    val first = c.drainMotionDiagnostics(c.world).first { it.unitId == id }
                    assertEquals(501L, first.stepIndex)
                    assertTrue(first.candidate.y > 260.0, "The candidate must cross an intermediate wall, with a clear endpoint")
                    assertFalse(map.blocking.isBlocked((first.candidate.x / 20).toInt(), (first.candidate.y / 20).toInt()))
                    assertEquals(MotionTerrainDisposition.CORRECTED, first.terrainDisposition)
                    assertEquals(Position(310.0, 215.0), first.finalPosition)
                }
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("reverse", "p1", id, Position(310.0, 150.0), c.world)))
                for (step in 590L..1089L) c.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(c.observe().units.single { it.unitId == id }.position.y < 180.0,
                    "A fresh legal command must remain usable after the blocked exit")
                assertEquals(1400L, c.observe().economy!!.balances["p1"])
            } finally { c.close() }
        }
    }
}
