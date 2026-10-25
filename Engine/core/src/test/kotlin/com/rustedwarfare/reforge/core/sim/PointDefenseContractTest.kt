package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class PointDefenseContractTest {
    private fun deflections(trace: List<Observation>) = trace.flatMap { it.projectileEvents }.filter { it.kind == "deflected" }

    @Test fun actual_shot_age_speed_height_and_explicit_nondeflectable_metadata_obey_each_strict_gate() {
        for ((speed, expectedAge) in listOf(5.0 to 8f, 8.0 to 8f, 8.000001 to 3f)) {
            val trace = PointDefenseTestContent.trace(PointDefenseTestContent.scene(speed = speed), 12)
            assertTrue(trace.first().projectileEvents.any { it.kind == "born" })
            val first = deflections(trace).first()
            assertEquals(expectedAge, first.projectile.age)
            assertTrue(first.projectile.intercepted)
            assertEquals(1f - 1.01f, first.projectile.deflectionHealth)
            assertEquals("d", first.interceptorUnitId)
            assertTrue(trace.take(expectedAge.toInt()).none { row -> row.projectileEvents.any { it.kind == "deflected" } })
        }
        val edge = deflections(PointDefenseTestContent.trace(PointDefenseTestContent.scene(height = -1.0), 12))
        assertEquals(-1.0, edge.single().projectile.height)
        val below = PointDefenseTestContent.trace(PointDefenseTestContent.scene(height = -1.000001), 12)
        assertTrue(below.first().projectileEvents.any { it.kind == "born" })
        assertTrue(deflections(below).isEmpty())
        for ((instant, blocked) in listOf(true to false, false to true)) {
            val trace = PointDefenseTestContent.trace(PointDefenseTestContent.scene(instant = instant, nonDeflectable = blocked), 12)
            assertTrue(trace.first().projectileEvents.any { it.kind == "born" })
            assertTrue(deflections(trace).isEmpty())
            assertEquals(1f, trace.last().units.single { it.unitId == "d" }.pointDefense!!.energy)
        }
    }

    @Test fun the_muzzle_circle_is_strict_and_measured_on_the_actual_previous_projectile_position() {
        val quiet = PointDefenseProfile(initialEnergy = 0f, recharge = 0f)
        val base = PointDefenseTestContent.scene(profile = quiet)
        val position = PointDefenseTestContent.trace(base, 8).last().projectiles.single().position
        for ((gap, expected) in listOf(160.0 to false, 160.000001 to false, 159.999999 to true)) {
            val profile = quiet.copy(initialEnergy = 1f, muzzleOffsetX = position.x - 50.0,
                muzzleOffsetY = position.y + gap - 50.0)
            val trace = PointDefenseTestContent.trace(PointDefenseTestContent.scene(profile = profile), 9)
            val events = trace.last().projectileEvents.filter { it.kind == "deflected" }
            assertEquals(expected, events.isNotEmpty(), "gap=$gap")
            if (expected) assertEquals(position, events.single().projectile.position)
        }
    }

    @Test fun first_insertion_wins_over_nearest_and_multiple_towers_share_health_without_reintercepting_a_finished_shot() {
        val profile = PointDefenseProfile(muzzleOffsetX = 250.0, muzzleOffsetY = 160.0, cost = 0f)
        val base = PointDefenseTestContent.scene(profile = profile)
        val two = PointDefenseTestContent.identities(base.copy(units = base.units.filter { it.unitId != "s" } +
            listOf(UnitSpawn("s1", "p2", "shooter", Position(110.0, 210.0), 90.0),
                UnitSpawn("s2", "p2", "shooter", Position(140.0, 210.0), 90.0))))
        val rows = PointDefenseTestContent.trace(two, 10)
        assertEquals(listOf("s1", "s2"), rows.first().projectileEvents.filter { it.kind == "born" }.map { it.projectile.sourceUnitId })
        val before = rows[7].projectiles
        assertTrue(300.0 - before.single { it.sourceUnitId == "s1" }.position.x >
            300.0 - before.single { it.sourceUnitId == "s2" }.position.x)
        assertEquals("s1", deflections(rows).first().projectile.sourceUnitId)
        assertTrue(rows.all { it.projectileEvents.count { event -> event.kind == "deflected" } <= 1 })

        val high = PointDefenseTestContent.scene(health = 2.02f, profile = profile.copy(cost = .11f))
        val d = high.units.single { it.unitId == "d" }
        val towers = PointDefenseTestContent.identities(high.copy(units = high.units.filter { it.unitId != "d" } +
            listOf(d.copy(unitId = "d1"), d.copy(unitId = "d2", position = Position(90.0, 50.0)),
                d.copy(unitId = "d3", position = Position(130.0, 50.0))),
            unitDefinitions = high.unitDefinitions + listOf(90.0, 130.0).mapIndexed { index, x ->
                val type = "defense${index + 2}"
                type to PointDefenseTestContent.variant(high.unitDefinitions.getValue(d.typeId), id = type,
                    defense = profile.copy(muzzleOffsetX = 300.0 - x, cost = .11f))
            }).let { input -> input.copy(units = input.units.map { when (it.unitId) {
                "d2" -> it.copy(typeId = "defense2")
                "d3" -> it.copy(typeId = "defense3")
                else -> it
            } }) })
        val shared = PointDefenseTestContent.trace(towers, 9).last()
        val events = shared.projectileEvents.filter { it.kind == "deflected" }
        assertEquals(listOf("d1", "d2"), events.map { it.interceptorUnitId })
        assertEquals(1, events.map { it.projectile.objectId }.distinct().size)
        assertFalse(events.first().projectile.intercepted)
        assertTrue(events.last().projectile.intercepted)
        assertEquals(1f, shared.units.single { it.unitId == "d3" }.pointDefense!!.energy)
        assertTrue(shared.projectiles.isEmpty())
    }

    @Test fun exact_zero_does_not_lock_but_the_tenth_overdraft_locks_until_float_recharge_reaches_full() {
        val exact = PointDefenseProfile(muzzleOffsetX = 100.0, muzzleOffsetY = 160.0, initialEnergy = .11f, recharge = 0f)
        val edge = PointDefenseTestContent.trace(PointDefenseTestContent.scene(profile = exact), 9).last()
        assertEquals(1, edge.projectileEvents.count { it.kind == "deflected" })
        assertEquals(PointDefenseObservation(0f, false), edge.units.single { it.unitId == "d" }.pointDefense)

        val profile = exact.copy(initialEnergy = 1f, recharge = .0004f)
        val scene = PointDefenseTestContent.scene(speed = .01, health = 1000f, profile = profile)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            PointDefenseTestContent.aim(context)
            var successes = 0; var lockedAt: Int? = null; var unlockedAt: Int? = null
            var expectedEnergy = 1f; var expectedLock = false
            for (step in 0..2700) {
                expectedEnergy = minOf(1f, expectedEnergy + .0004f)
                if (expectedEnergy >= 1f) expectedLock = false
                val row = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                val events = row.projectileEvents.count { it.kind == "deflected" }
                assertTrue(events <= 1)
                if (events == 1) {
                    successes++
                    assertFalse(expectedLock)
                    assertTrue(expectedEnergy > 0f)
                    expectedEnergy -= .11f
                    if (expectedEnergy < 0f) { expectedEnergy = 0f; expectedLock = true }
                }
                val state = row.units.single { it.unitId == "d" }.pointDefense!!
                assertEquals(PointDefenseObservation(expectedEnergy, expectedLock), state)
                if (lockedAt == null && state.exhausted) {
                    lockedAt = step; assertEquals(10, successes)
                    val paused = context.observe()
                    repeat(4) { assertSame(paused, context.observe()) }
                }
                if (lockedAt != null && !state.exhausted && step > lockedAt) {
                    unlockedAt = step; assertEquals(1, events); assertEquals(11, successes); break
                }
            }
            assertNotNull(lockedAt); assertNotNull(unlockedAt)
            assertTrue(unlockedAt - lockedAt > 2400)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertEquals(PointDefenseObservation(1f, false), context.observe().units.single { it.unitId == "d" }.pointDefense)
            assertTrue(context.observe().projectiles.isEmpty())
        } finally { context.close() }
    }

    @Test fun a_deflected_shot_still_runs_one_ordinary_hit_or_life_path_with_hit_precedence() {
        for ((hit, life) in listOf(false to 10000.0, false to 9.0, true to 10000.0, true to 9.0)) {
            val base = PointDefenseTestContent.scene(life = life)
            val weapon = base.unitDefinitions.getValue("shooter").combat!!
            val target = base.units.single { it.unitId == "t" }
            // Trusted radius1 makes the existing radius*.6 random-offset bound zero.
            // This isolates the actual age8 same-step terminal path without pre-seeding a projectile.
            val tiny = PointDefenseTestContent.variant(base.unitDefinitions.getValue("target"), radius = 1.0)
            val contact = maxOf(6f, tiny.radius.toFloat() * .8f)
            val distance = weapon.turretSize + weapon.projectile.speed * 8 + contact - 1.0
            val scene = if (hit) base.copy(unitDefinitions = base.unitDefinitions + (tiny.id to tiny), units = base.units.map {
                if (it.unitId == "t") target.copy(position = Position(110.0 + distance, 210.0)) else it
            }) else base
            val rows = PointDefenseTestContent.trace(scene, 10)
            val shot = deflections(rows).single().projectile.objectId
            val kinds = rows.flatMap { it.projectileEvents }.filter { it.projectile.objectId == shot }.map { it.kind }
            assertEquals(listOf("born", "deflected", if (hit) "impact" else if (life == 9.0) "expired" else "intercepted"), kinds)
            assertEquals(if (hit) 475.0 else 500.0, rows.last().units.single { it.unitId == "t" }.health)
            assertTrue(rows.last().projectiles.none { it.objectId == shot })
        }
    }

    @Test fun owner_only_energy_is_redacted_for_allies_and_enemies_and_stop_is_not_hold_fire() {
        val scene = PointDefenseTestContent.scene()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            PointDefenseTestContent.aim(context)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "d", context.world)))
            assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("move", "p1", "d", Position(80.0, 80.0), context.world))).error.code)
            assertIs<ActionResult.Rejected>(context.submit(StopAction("enemy", "p2", "d", context.world)))
            repeat(9) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val owner = context.observePlayer(context.world, "p1")
            assertNotNull(owner.units.single { it.unitId == "d" }.pointDefense)
            assertEquals("d", owner.projectileEvents.single { it.kind == "deflected" }.interceptorUnitId)
            for (viewer in listOf("p2", "p3")) assertNull(context.observePlayer(context.world, viewer).units.single { it.unitId == "d" }.pointDefense)
        } finally { context.close() }
        val ally = scene.copy(players = scene.players.map { if (it.playerId == "p3") it.copy(teamId = "one") else it })
        val allied = assertIs<InitResult.Accepted>(SimulationContext.initialize(ally)).context
        try { assertNull(allied.observePlayer(allied.world, "p3").units.single { it.unitId == "d" }.pointDefense) }
        finally { allied.close() }
    }

    @Test fun finite_energy_and_projectile_profiles_are_validated_before_world_publication() {
        val base = PointDefenseTestContent.scene()
        val d = base.unitDefinitions.getValue("laserDefence"); val p = d.pointDefense!!
        for (bad in listOf(p.copy(range = Double.NaN), p.copy(range = 0.0), p.copy(range = Double.MAX_VALUE),
            p.copy(muzzleOffsetX = Double.POSITIVE_INFINITY), p.copy(recharge = Float.NaN), p.copy(recharge = -1f),
            p.copy(cost = Float.POSITIVE_INFINITY), p.copy(cost = -1f), p.copy(initialEnergy = -.01f), p.copy(initialEnergy = 1.01f))) {
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (d.id to PointDefenseTestContent.variant(d, defense = bad))))
            try { assertEquals("unsupported-point-defense", assertIs<InitResult.Rejected>(result).error.code) }
            finally { if (result is InitResult.Accepted) result.context.close() }
        }
        val shooter = base.unitDefinitions.getValue("shooter")
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            val combat = shooter.combat!!.let { it.copy(projectile = it.projectile.copy(deflectionHealth = bad)) }
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions +
                (shooter.id to PointDefenseTestContent.variant(shooter, combat = combat))))
            try { assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result).error.code) }
            finally { if (result is InitResult.Accepted) result.context.close() }
        }
    }
}
