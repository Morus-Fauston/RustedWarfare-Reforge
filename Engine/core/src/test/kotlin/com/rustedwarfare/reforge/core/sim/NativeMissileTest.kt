package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class NativeMissileTest {
    @Test fun negative_raw_angle_keeps_the_native_lookup_cell_and_zero_target_speed_does_not_brake() {
        val base = scene()
        val native = base.unitDefinitions.getValue("commandCenter")
        val center = UnitDefinition(native.id, native.className, native.radius, native.displayRadius,
            native.totalFrames, native.imagePath, native.imageScale, native.movement, emptySet(),
            combat = native.combat!!.copy(shootDelay = 1000.0, projectile = native.combat.projectile.copy(
                missile = NativeMissileProfile(targetSpeed = 0f, climbThreshold = 0f, apex = 0f))),
            durability = native.durability, building = native.building)
        val input = base.copy(unitDefinitions = base.unitDefinitions + (center.id to center),
            units = listOf(UnitSpawn("h", "p1", center.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("z", "p2", "builder", Position(110.0, 206.0), 0.0)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            c.advance(Step(0, 1.0, "sim-tick"))
            val shot = c.advance(Step(1, 1.0, "sim-tick")).projectiles.single()
            // Independent Float-table contract sample: raw index4122, normalized angle would select4121.
            assertEquals(-178.88108825683594f, shot.missile!!.rawAz)
            assertEquals(Position(308.00042724609375, 209.9593505859375), shot.position)
            assertEquals(2f, shot.missile.speed)
        } finally { c.close() }
    }

    @Test fun units_migrating_into_one_cell_preserve_arrival_order_instead_of_sorting_ids_again() = migrationOrder(false)

    @Test fun returning_to_a_cell_appends_again_but_moving_inside_it_keeps_that_order() = migrationOrder(true)

    private fun migrationOrder(roundTrip: Boolean) {
        val base = scene()
        val native = base.unitDefinitions.getValue("commandCenter")
        val center = UnitDefinition(native.id, native.className, native.radius, native.displayRadius,
            native.totalFrames, native.imagePath, native.imageScale, native.movement, emptySet(),
            combat = native.combat!!.copy(shootDelay = 1000.0, projectile = native.combat.projectile.copy(life = 1000.0,
                missile = NativeMissileProfile(verticalSpeed = 0f))), durability = native.durability, building = native.building)
        val builder = base.unitDefinitions.getValue("builder")
        val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius,
            1, builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability!!.copy(maxHp = 25.0))
        val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
        val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, 1.0, tank.movement, emptySet(), combat = tank.combat!!.copy(attackRange = 100.0,
                shootDelay = 1000.0, turretTurnSpeed = 999.0, projectile = tank.combat.projectile.copy(speed = 10000.0)))
        val input = PointDefenseTestContent.identities(base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions + mapOf(center.id to center, fragile.id to fragile, killer.id to killer),
            units = listOf(base.units.first(), UnitSpawn("b", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("c", "p2", builder.id, Position(180.0, 210.0), 90.0),
                UnitSpawn("d", "p2", builder.id, Position(180.0, 210.0), 90.0),
                UnitSpawn("far", "p2", builder.id, Position(310.0, 310.0), 0.0),
                UnitSpawn("z", "p1", killer.id, Position(310.0, 30.0), 180.0))))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("aim", "p1", "h", "b", c.world)))
            var step = 0L
            c.advance(Step(step++, 1.0, "sim-tick"))
            val shot = c.observe().projectiles.single { it.sourceUnitId == "h" }.objectId
            for (id in listOf("d", "c")) {
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("migrate-$id", "p2", id, Position(210.0, 210.0), c.world)))
                do { c.advance(Step(step++, 1.0, "sim-tick")) }
                while (c.observe().units.single { it.unitId == id }.let { it.moveTarget != null || it.speed != 0.0 } && step < 400)
                assertNull(c.observe().units.single { it.unitId == id }.moveTarget)
            }
            assertEquals(c.observe().units.single { it.unitId == "c" }.position,
                c.observe().units.single { it.unitId == "d" }.position)
            fun moveAndStop(id: String, destination: Position, nonce: String) {
                assertIs<ActionResult.Accepted>(c.submit(MoveAction(nonce, "p2", id, destination, c.world)))
                val deadline = step + 300
                do { c.advance(Step(step++, 1.0, "sim-tick")) }
                while (c.observe().units.single { it.unitId == id }.let { it.moveTarget != null || it.speed != 0.0 } && step < deadline)
                assertNull(c.observe().units.single { it.unitId == id }.moveTarget)
                assertEquals(0.0, c.observe().units.single { it.unitId == id }.speed)
            }
            if (roundTrip) {
                for (id in listOf("c", "d")) {
                    moveAndStop(id, Position(180.0, 210.0), "leave-$id")
                    moveAndStop(id, Position(210.0, 210.0), "return-$id")
                }
                val same = c.observe().units.single { it.unitId == "c" }.position
                assertEquals(same, c.observe().units.single { it.unitId == "d" }.position)
                for (id in listOf("d", "c")) {
                    // The x/y cells are 18 by 12; this short motion remains in the current cell.
                    moveAndStop(id, Position(same.x + 8.0, same.y), "within-$id")
                }
                assertEquals(c.observe().units.single { it.unitId == "c" }.position,
                    c.observe().units.single { it.unitId == "d" }.position)
                assertNotEquals(same, c.observe().units.single { it.unitId == "c" }.position)
            }
            // Keep the source's preferred target outside the retarget circle so it cannot mask cell order.
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("prefer-far", "p1", "h", "far", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("kill-later", "p1", "z", Position(310.0, 140.0), c.world)))
            val deathDeadline = step + 200
            while (c.observe().units.single { it.unitId == "b" }.alive && step < deathDeadline)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(c.observe().units.single { it.unitId == "b" }.alive)
            c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(if (roundTrip) "d" else "c", c.observe().projectiles.single { it.objectId == shot }.targetUnitId,
                "Newest arrival wins an equal-distance tie; same-cell motion must not change arrival order")
        } finally { c.close() }
    }

    @Test fun a_preferred_farther_candidate_can_replace_nearer_then_yield_to_a_later_nearer_candidate() {
        val base = scene()
        val builder = base.unitDefinitions.getValue("builder")
        val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius,
            1, builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability!!.copy(maxHp = 25.0))
        val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
        val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, 1.0, tank.movement, emptySet(), combat = tank.combat!!.copy(shootDelay = 1000.0,
                turretTurnSpeed = 999.0, projectile = tank.combat.projectile.copy(speed = 10000.0)))
        for (third in listOf(false, true)) {
            val units = listOf(base.units.first(), UnitSpawn("b", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("z", "p1", killer.id, Position(310.0, 110.0), 180.0),
                UnitSpawn("near", "p2", builder.id, Position(200.0, 210.0), 0.0),
                UnitSpawn("preferred", "p2", builder.id, Position(180.0, 280.0), 0.0)) +
                if (third) listOf(UnitSpawn("later", "p2", builder.id, Position(160.0, 230.0), 0.0)) else emptyList()
            val input = PointDefenseTestContent.identities(base.copy(unitDefinitions = base.unitDefinitions +
                mapOf(fragile.id to fragile, killer.id to killer), units = units))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("aim", "p1", "h", "b", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill", "p1", "z", "b", c.world)))
                val first = c.advance(Step(0, 1.0, "sim-tick"))
                val id = first.projectiles.single { it.sourceUnitId == "h" }.objectId
                assertFalse(first.units.single { it.unitId == "b" }.alive)
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("prefer", "p1", "h", "preferred", c.world)))
                val next = c.advance(Step(1, 1.0, "sim-tick"))
                assertEquals(if (third) "later" else "preferred", next.projectiles.single { it.objectId == id }.targetUnitId)
            } finally { c.close() }
        }
    }

    @Test fun a_builder_born_before_projectile_processing_enters_retarget_candidates_only_on_the_following_step() {
        val base = scene()
        val builder = base.unitDefinitions.getValue("builder")
        val fastBuild = UnitDefinition(builder.id, builder.className, builder.radius, builder.displayRadius,
            1, builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability, build = BuildProfile(500L, 1f))
        val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius,
            1, builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability!!.copy(maxHp = 25.0))
        val old = BuildingTestContent.scenario()
        val f = old.unitDefinitions.getValue("landFactory")
        val factory = UnitDefinition(f.id, f.className, f.radius, f.displayRadius, f.totalFrames, f.imagePath,
            f.imageScale, f.movement, emptySet(), durability = f.durability, building = f.building, production = ProductionProfile("builder"))
        val tank = old.unitDefinitions.getValue("c_tank")
        val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, 1.0, tank.movement, emptySet(), combat = tank.combat!!.copy(shootDelay = 1000.0,
                turretTurnSpeed = 999.0, projectile = tank.combat.projectile.copy(speed = 10000.0)))
        val input = PointDefenseTestContent.identities(base.copy(
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions + mapOf(fastBuild.id to fastBuild, fragile.id to fragile,
                factory.id to factory, killer.id to killer),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 500L)),
            units = listOf(UnitSpawn("a", "p1", killer.id, Position(310.0, 110.0), 180.0),
                UnitSpawn("b", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("f", "p2", factory.id, Position(130.0, 90.0), 0.0), base.units.first())))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("aim", "p1", "h", "b", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill", "p1", "a", "b", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("newborn", "p2", "f", "builder", "0".repeat(32), c.world)))
            val first = c.advance(Step(0, 1.0, "sim-tick"))
            val born = first.units.single { it.unitId.startsWith("produced:") }
            assertEquals(Position(130.0, 95.0), born.position)
            assertFalse(first.units.single { it.unitId == "b" }.alive)
            val shot = first.projectiles.single { it.sourceUnitId == "h" }
            assertEquals("b", shot.targetUnitId, "The newly created unit is not in the old grid yet")
            val next = c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(born.unitId, next.projectiles.single { it.objectId == shot.objectId }.targetUnitId)
        } finally { c.close() }
    }

    @Test fun each_native_shot_uses_the_preceding_aim_offset_without_consuming_random_or_odd_shot_state() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            val first = c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(Position(0.0, 0.0), first.projectileEvents.single { it.kind == "born" }.projectile.offset)
            val cached = first.combat!!.weapons.single().offset
            assertNotEquals(Position(0.0, 0.0), cached)
            var second: ProjectileObservation? = null
            for (step in 1L..70L) {
                val row = c.advance(Step(step, 1.0, "sim-tick"))
                second = second ?: row.projectileEvents.firstOrNull { it.kind == "born" }?.projectile
                assertEquals(0, row.combat!!.weapons.single().randomCounter)
                assertFalse(row.combat.weapons.single().oddShot)
            }
            assertEquals(cached, assertNotNull(second).offset)
        } finally { c.close() }
    }

    @Test fun late_missile_geometry_failure_restores_earlier_death_index_income_and_same_step_retry() {
        val base = smallFlightScene(5.0, 180.0)
        val original = base.unitDefinitions.getValue("commandCenter")
        val center = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, emptySet(),
            combat = original.combat, income = IncomeProfile(1f, 4.5f))
        val t = base.unitDefinitions.getValue("tiny")
        val fragile = UnitDefinition(t.id, t.className, t.radius, t.displayRadius, t.totalFrames, t.imagePath,
            t.imageScale, t.movement, emptySet(), durability = t.durability!!.copy(maxHp = 25.0))
        val input = PointDefenseTestContent.identities(base.copy(
            unitDefinitions = mapOf(center.id to center, fragile.id to fragile),
            units = listOf(UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0),
                UnitSpawn("b", "p2", fragile.id, Position(115.0, 210.0), 0.0),
                UnitSpawn("side", "p2", fragile.id, Position(110.0, 310.0), 0.0),
                UnitSpawn("z", "p1", center.id, Position(510.0, 210.0), 0.0),
                UnitSpawn("bad", "p2", fragile.id, Position(510.0, 110.0), 0.0, Double.MAX_VALUE),
                UnitSpawn("good", "p2", fragile.id, Position(510.0, 330.0), 0.0))))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        fun host(r: Observation) = listOf(r.units, r.events, r.actionResults, r.projectiles, r.projectileEvents,
            r.combat, r.economy!!.preciseBalances, r.economy.queuedProduction)
        try {
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("first", "p1", "h", "b", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("late", "p1", "z", "bad", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(SelectAction("queued", "p1", "h", c.world, 0)))
            }
            val failed = contexts[0]
            val before = failed.observe()
            val beforePlayer = failed.observePlayer(failed.world, "p1")
            assertFailsWith<IllegalArgumentException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(beforePlayer, failed.observePlayer(failed.world, "p1"))
            assertTrue(before.units.single { it.unitId == "b" }.alive)
            assertEquals(1750.0, beforePlayer.credits)
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("correct", "p1", "z", "good", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("leave-original-alive", "p1", "h", "side", c.world)))
            }
            for (step in 0L..25L) {
                val rows = contexts.map { it.advance(Step(step, 1.0, "sim-tick")) }
                assertEquals(host(rows[1]), host(rows[0]))
            }
            assertTrue(failed.observe().units.single { it.unitId == "b" }.alive,
                "The retry keeps the previously killed candidate alive, requiring restoration of its index membership")
            assertEquals(1984.0, failed.observePlayer(failed.world, "p1").credits)
            assertTrue(before.units.single { it.unitId == "b" }.alive)
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun failed_death_then_retry_keeps_equal_distance_retarget_history() {
        val base = smallFlightScene(5.0, 1000.0)
        val center = base.unitDefinitions.getValue("commandCenter")
        val t = base.unitDefinitions.getValue("tiny")
        val fragile = UnitDefinition(t.id, t.className, t.radius, t.displayRadius, t.totalFrames, t.imagePath,
            t.imageScale, t.movement, emptySet(), durability = t.durability!!.copy(maxHp = 25.0))
        val watcher = UnitDefinition("watcher", center.className, center.radius, center.displayRadius,
            center.totalFrames, center.imagePath, center.imageScale, center.movement, emptySet(),
            combat = center.combat!!.copy(projectile = center.combat.projectile.copy(
                missile = NativeMissileProfile(verticalSpeed = 0f))))
        val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
        val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, 1.0, tank.movement, emptySet(), combat = tank.combat!!.copy(attackRange = 100.0,
                shootDelay = 1000.0, turretTurnSpeed = 999.0, projectile = tank.combat.projectile.copy(speed = 10000.0)))
        val input = PointDefenseTestContent.identities(base.copy(
            unitDefinitions = mapOf(center.id to center, fragile.id to fragile, watcher.id to watcher, killer.id to killer),
            units = listOf(UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0),
                UnitSpawn("b", "p2", fragile.id, Position(115.0, 210.0), 0.0),
                UnitSpawn("c", "p2", fragile.id, Position(115.0, 210.0), 0.0),
                UnitSpawn("side", "p2", fragile.id, Position(110.0, 310.0), 0.0),
                UnitSpawn("watch", "p1", watcher.id, Position(115.0, 150.0), 0.0),
                UnitSpawn("victim", "p2", fragile.id, Position(310.0, 150.0), 0.0),
                UnitSpawn("far", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("killer", "p1", killer.id, Position(310.0, 30.0), 180.0),
                UnitSpawn("z", "p1", center.id, Position(510.0, 210.0), 0.0),
                UnitSpawn("bad", "p2", fragile.id, Position(510.0, 110.0), 0.0, Double.MAX_VALUE),
                UnitSpawn("good", "p2", fragile.id, Position(510.0, 330.0), 0.0))))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("first", "p1", "h", "b", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("watch", "p1", "watch", "victim", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("late", "p1", "z", "bad", c.world)))
            }
            val failed = contexts.first()
            val before = failed.observe()
            assertFailsWith<IllegalArgumentException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("correct", "p1", "z", "good", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("spare-b", "p1", "h", "side", c.world)))
                c.advance(Step(0, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("prefer-far", "p1", "watch", "far", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("kill-later", "p1", "killer", Position(310.0, 70.0), c.world)))
            }
            var step = 1L
            while (contexts.last().observe().units.single { it.unitId == "victim" }.alive && step < 45) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }; step++
            }
            assertFalse(contexts.last().observe().units.single { it.unitId == "victim" }.alive)
            for (c in contexts) {
                val row = c.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(row.units.single { it.unitId == "b" }.alive)
                assertTrue(row.units.single { it.unitId == "c" }.alive)
                assertEquals("c", row.projectiles.single { it.sourceUnitId == "watch" }.targetUnitId,
                    "Restoring only membership by appending b on retry would change this equal-distance choice to b")
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun first_retarget_uses_reverse_grid_order_and_reverse_same_cell_insertion_for_equal_distances() {
        for ((low, high) in listOf(Position(200.0, 170.0) to Position(200.0, 250.0),
                Position(200.0, 209.0) to Position(200.0, 211.0))) {
            val base = scene()
            val builder = base.unitDefinitions.getValue("builder")
            val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius,
                1, builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability!!.copy(maxHp = 25.0))
            val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
            val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
                tank.imagePath, 1.0, tank.movement, emptySet(), combat = tank.combat!!.copy(turretTurnSpeed = 999.0,
                    shootDelay = 1000.0, projectile = tank.combat.projectile.copy(speed = 10000.0)))
            val input = PointDefenseTestContent.identities(base.copy(
                unitDefinitions = base.unitDefinitions + mapOf(fragile.id to fragile, killer.id to killer),
                units = listOf(UnitSpawn("a", "p1", killer.id, Position(310.0, 110.0), 180.0),
                    base.units.first(), UnitSpawn("b", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                    UnitSpawn("c", "p2", builder.id, low, 0.0), UnitSpawn("d", "p2", builder.id, high, 0.0))))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("aim", "p1", "h", "b", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill", "p1", "a", "b", c.world)))
                val row = c.advance(Step(0, 1.0, "sim-tick"))
                assertFalse(row.units.single { it.unitId == "b" }.alive)
                assertEquals("d", row.projectiles.single { it.sourceUnitId == "h" }.targetUnitId)
                assertEquals(0, row.combat!!.weapons.single { it.unitId == "h" }.randomCounter)
            } finally { c.close() }
        }
    }

    private fun smallFlightScene(distance: Double, life: Double, defenseX: Double? = null): Scenario {
        val base = scene()
        val native = base.unitDefinitions.getValue("commandCenter")
        val center = UnitDefinition(native.id, native.className, 1.0, 1.0, native.totalFrames, native.imagePath,
            native.imageScale, native.movement, emptySet(), combat = native.combat!!.copy(shootDelay = 1000.0,
                projectile = native.combat.projectile.copy(life = life,
                    missile = NativeMissileProfile(targetSpeed = 2f, acceleration = 0f, climbThreshold = 0f, apex = 0f))))
        val builder = base.unitDefinitions.getValue("builder")
        val target = UnitDefinition("tiny", builder.className, 1.0, 1.0, 1, builder.imagePath, 1.0,
            builder.movement, emptySet(), durability = builder.durability)
        var defs = mapOf(center.id to center, target.id to target)
        var units = listOf(UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0),
            UnitSpawn("z", "p2", target.id, Position(110.0 + distance, 210.0), 0.0))
        if (defenseX != null) {
            val d = PointDefenseTestContent.native()
            val defense = PointDefenseTestContent.variant(d, defense = PointDefenseProfile(
                range = .5, muzzleOffsetX = defenseX - 50, muzzleOffsetY = 160.0))
            defs = defs + (defense.id to defense)
            units = units + UnitSpawn("d", "p2", defense.id, Position(50.0, 50.0), 0.0)
        }
        return PointDefenseTestContent.identities(base.copy(unitDefinitions = defs, units = units))
    }

    @Test fun life_tail_step_can_hit_before_expiring_even_without_reaching_the_native_apex() {
        for ((distance, expected) in listOf(5.0 to "impact", 40.0 to "expired")) {
            val base = smallFlightScene(distance, 1.0)
            val center = base.unitDefinitions.getValue("commandCenter")
            val nativeClimb = UnitDefinition(center.id, center.className, center.radius, center.displayRadius,
                center.totalFrames, center.imagePath, center.imageScale, center.movement, emptySet(),
                combat = center.combat!!.copy(projectile = center.combat.projectile.copy(missile = NativeMissileProfile())))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                unitDefinitions = base.unitDefinitions + (center.id to nativeClimb)))).context
            try {
                val result = c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(listOf("born", expected), result.projectileEvents.map { it.kind })
                assertFalse(result.projectileEvents.last().projectile.missile!!.reachedApex)
                assertEquals(0.0, result.projectileEvents.last().projectile.remainingLife)
                assertEquals(if (expected == "impact") 100.0 else 170.0, result.units.single { it.unitId == "z" }.health)
            } finally { c.close() }
        }
    }

    @Test fun real_point_defense_tail_update_keeps_hit_then_expiry_then_interception_priority() {
        for ((muzzle, life, expected) in listOf(Triple(146.0, 20.0, "impact"),
                Triple(144.0, 19.0, "expired"), Triple(144.0, 100.0, "intercepted"))) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(smallFlightScene(40.0, life, muzzle))).context
            try {
                val rows = (0L..21L).map { c.advance(Step(it, 1.0, "sim-tick")) }
                val events = rows.flatMap { it.projectileEvents }
                assertEquals(listOf("born", "deflected", expected), events.map { it.kind }, "muzzle=$muzzle life=$life")
                assertTrue(events.last().projectile.intercepted)
                assertEquals(if (expected == "impact") 100.0 else 170.0, rows.last().units.single { it.unitId == "z" }.health)
            } finally { c.close() }
        }
    }

    @Test fun flight_speed_approaches_its_target_with_the_native_float_boundary_order() {
        val base = scene()
        val native = base.unitDefinitions.getValue("commandCenter")
        val center = UnitDefinition(native.id, native.className, native.radius, native.displayRadius,
            native.totalFrames, native.imagePath, native.imageScale, native.movement, emptySet(),
            combat = native.combat!!.copy(projectile = native.combat.projectile.copy(speed = .3f.toDouble(),
                missile = NativeMissileProfile(targetSpeed = .2f, acceleration = .1f, climbThreshold = 0f, apex = 0f))),
            durability = native.durability, building = native.building, production = native.production,
            directHitTargetKind = native.directHitTargetKind, income = native.income)
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = base.unitDefinitions + (center.id to center)))).context
        try {
            c.advance(Step(0, 1.0, "sim-tick"))
            val shot = c.advance(Step(1, 1.0, "sim-tick")).projectiles.single()
            assertEquals(.2f, shot.missile!!.speed)
        } finally { c.close() }
    }

    private fun weaponVariant(base: UnitDefinition, missile: NativeMissileProfile? = base.combat!!.projectile.missile,
                              cap: Double? = base.durability!!.maxDamagePerHit): UnitDefinition =
        UnitDefinition(base.id, base.className, base.radius, base.displayRadius, base.totalFrames, base.imagePath,
            base.imageScale, base.movement, base.retainedUnconsumed, base.fogOfWarSightRange,
            base.combat!!.copy(projectile = base.combat.projectile.copy(missile = missile)),
            base.durability!!.copy(maxDamagePerHit = cap), base.building, base.production, base.build,
            base.directHitTargetKind, income = base.income)
    private fun scene(): Scenario {
        val repo = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01")
        val center = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCommandCenter(
            System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/center/units/commandCenter.properties") }
                ?: root.resolve("center-input-01/units/commandCenter.properties"))).value
        val builder = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder(
            System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/center/units/builder.properties") }
                ?: root.resolve("builder-input-01/units/builder.properties"))).value
        return BuildingTestContent.scenario().copy(unitDefinitions = mapOf(center.id to center, builder.id to builder),
            units = listOf(UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0),
                UnitSpawn("z", "p2", builder.id, Position(310.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("h" to 10L, "z" to 11L), 12L))
    }

    @Test fun native_center_missile_climbs_for_twenty_one_updates_before_plane_motion_and_acceleration() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            val first = c.advance(Step(0, 1.0, "sim-tick"))
            val born = first.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals(Position(110.0, 210.0), born.position)
            assertEquals(0.0, born.height)
            assertEquals(0, first.combat!!.weapons.single().randomCounter)
            for (step in 1L..20L) c.advance(Step(step, 1.0, "sim-tick"))
            val climbing = c.observe().projectiles.single()
            assertEquals(Position(110.0, 210.0), climbing.position)
            assertEquals(42.0, climbing.height)
            assertEquals(2f, climbing.missile!!.speed)
            assertFalse(climbing.missile.reachedApex)
            val flying = c.advance(Step(21, 1.0, "sim-tick")).projectiles.single()
            assertTrue(flying.position.x > 111.9)
            assertEquals(44.0, flying.height)
            assertEquals(2.1f, flying.missile!!.speed)
            assertEquals(0f, flying.missile.rawAz)
            assertEquals(0, c.observe().combat!!.weapons.single().randomCounter)
            assertEquals(1759.0, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun invalid_missile_parameters_and_damage_caps_are_rejected_before_opening_a_world() {
        val base = scene()
        val center = base.unitDefinitions.getValue("commandCenter")
        val bad = listOf(
            weaponVariant(center, NativeMissileProfile(targetSpeed = Float.NaN)),
            weaponVariant(center, NativeMissileProfile(acceleration = -1f)),
            weaponVariant(center, NativeMissileProfile(apex = 39f)),
            weaponVariant(center, NativeMissileProfile(retargetRadius = 0f)),
            weaponVariant(center, NativeMissileProfile(retargetRadius = Float.MAX_VALUE)),
            weaponVariant(center, cap = Double.POSITIVE_INFINITY), weaponVariant(center, cap = -1.0))
        for (definition in bad) {
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (definition.id to definition)))
            if (result is InitResult.Accepted) result.context.close()
            assertIs<InitResult.Rejected>(result)
        }
    }

    @Test fun center_caps_each_real_hit_after_finite_validation_and_refunds_pending_production_on_death() {
        val base = scene()
        val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
        val gun = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                shootDelay = 2.0, projectile = tank.combat.projectile.copy(directDamage = 3000.0, speed = 100.0)))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (gun.id to gun),
            units = listOf(base.units.first(), UnitSpawn("z", "p2", gun.id, Position(210.0, 210.0), 270.0)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("pending", "p1", "h", "builder", "0".repeat(32), c.world)))
            var step = 0L
            var firstHit: Double? = null
            while (c.observe().units.single { it.unitId == "h" }.alive && step < 30) {
                val view = c.advance(Step(step++, 1.0, "sim-tick"))
                if (firstHit == null && view.events.any { it.kind == "unit-damaged" && it.unitId == "h" }) {
                    firstHit = view.units.single { it.unitId == "h" }.health
                    assertEquals(1500.0, firstHit)
                    assertEquals(2500.0, view.projectileEvents.single { it.kind == "impact" }.damage)
                }
            }
            assertNotNull(firstHit)
            assertFalse(c.observe().units.single { it.unitId == "h" }.alive)
            assertTrue(c.observePlayer(c.world, "p1").factoryQueues.isEmpty())
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            val after = c.observePlayer(c.world, "p1").credits
            repeat(20) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(after, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun descending_missile_contacts_using_the_previous_height_difference() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            c.advance(Step(0, 1.0, "sim-tick"))
            val id = c.observe().projectiles.single().objectId
            var step = 1L
            while (c.observe().projectiles.firstOrNull { it.objectId == id }?.let {
                    it.height == 4.0 && it.missile?.reachedApex == true } != true && step < 180)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(4.0, c.observe().projectiles.single { it.objectId == id }.height)
            val lowered = c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(2.0, lowered.projectiles.single { it.objectId == id }.height)
            assertTrue(lowered.projectileEvents.none { it.kind == "impact" && it.projectile.objectId == id })
            val hit = c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(70.0, hit.projectileEvents.single { it.kind == "impact" && it.projectile.objectId == id }.damage)
            assertEquals(100.0, hit.units.single { it.unitId == "z" }.health)
        } finally { c.close() }
    }

    @Test fun a_dead_target_is_replaced_by_an_eligible_nearby_unit_on_the_next_projectile_update() {
        val base = scene()
        val builder = base.unitDefinitions.getValue("builder")
        val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius,
            builder.totalFrames, builder.imagePath, builder.imageScale, builder.movement, emptySet(),
            durability = builder.durability!!.copy(maxHp = 25.0))
        val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
        val ally = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                shootDelay = 1000.0, projectile = tank.combat.projectile.copy(speed = 100.0)))
        val input = base.copy(unitDefinitions = base.unitDefinitions + mapOf(ally.id to ally, fragile.id to fragile),
            units = listOf(base.units.first(), UnitSpawn("a", "p1", ally.id, Position(310.0, 110.0), 180.0),
                UnitSpawn("b", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("c", "p2", builder.id, Position(200.0, 270.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "h" to 10L, "b" to 11L, "c" to 12L), 13L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("aim", "p1", "h", "b", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill", "p1", "a", "b", c.world)))
            var step = 0L
            var missile: Long? = null
            while (c.observe().units.single { it.unitId == "b" }.alive && step < 20L) {
                val view = c.advance(Step(step++, 1.0, "sim-tick"))
                missile = missile ?: view.projectileEvents.firstOrNull { it.kind == "born" && it.projectile.sourceUnitId == "h" }?.projectile?.objectId
            }
            assertFalse(c.observe().units.single { it.unitId == "b" }.alive)
            assertNotNull(missile)
            c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals("c", c.observe().projectiles.single { it.objectId == missile }.targetUnitId)
        } finally { c.close() }
    }

    @Test fun a_dead_source_keeps_its_missile_and_a_dead_target_is_retained_until_a_candidate_enters_range() {
        val base = scene()
        val native = base.unitDefinitions.getValue("commandCenter")
        val center = UnitDefinition(native.id, native.className, native.radius, native.displayRadius,
            native.totalFrames, native.imagePath, native.imageScale, native.movement, emptySet(),
            combat = native.combat!!.copy(maxHp = 25.0, shootDelay = 1000.0),
            durability = native.durability!!.copy(maxHp = 25.0), building = native.building)
        val builder = base.unitDefinitions.getValue("builder")
        val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius,
            1, builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability!!.copy(maxHp = 25.0))
        val tank = BuildingTestContent.scenario().unitDefinitions.getValue("c_tank")
        val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(attackRange = 1000.0,
                turretTurnSpeed = 999.0, turretTurnAcceleration = 0.0, shootDelay = 1000.0,
                projectile = tank.combat.projectile.copy(speed = 10000.0)))
        val input = PointDefenseTestContent.identities(base.copy(
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = mapOf(center.id to center, builder.id to builder, fragile.id to fragile, killer.id to killer),
            units = listOf(UnitSpawn("a", "p1", killer.id, Position(310.0, 110.0), 180.0),
                UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0),
                UnitSpawn("b", "p2", fragile.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("c", "p2", builder.id, Position(240.0, 280.0), 0.0),
                UnitSpawn("z", "p2", killer.id, Position(550.0, 370.0), 270.0))))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            for ((id, owner, source, target) in listOf(listOf("aim", "p1", "h", "b"),
                    listOf("kill-target", "p1", "a", "b"), listOf("kill-source", "p2", "z", "h")))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction(id, owner, source, target, c.world)))
            var step = 0L
            val first = c.advance(Step(step++, 1.0, "sim-tick"))
            val missile = first.projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "h" }.projectile.objectId
            assertFalse(first.units.single { it.unitId == "h" }.alive)
            assertFalse(first.units.single { it.unitId == "b" }.alive)
            val waiting = c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals("b", waiting.projectiles.single { it.objectId == missile }.targetUnitId)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("candidate-enters", "p2", "c", Position(200.0, 270.0), c.world)))
            var retargeted = false
            var damaged = false
            while (step < 180 && !damaged) {
                val row = c.advance(Step(step++, 1.0, "sim-tick"))
                retargeted = retargeted || row.projectiles.any { it.objectId == missile && it.targetUnitId == "c" }
                damaged = row.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == missile && it.damage == 70.0 }
            }
            assertTrue(retargeted)
            assertTrue(damaged)
            assertEquals(100.0, c.observe().units.single { it.unitId == "c" }.health)
        } finally { c.close() }
    }
}
