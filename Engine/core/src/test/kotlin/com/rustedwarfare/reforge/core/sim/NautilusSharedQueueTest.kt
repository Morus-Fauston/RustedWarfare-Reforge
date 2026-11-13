package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusSharedQueueTest {
    @Test fun naval_interceptor_rounds_share_the_moving_factory_queue_and_refund_the_last_order() = WaterTestScene.withMap { map ->
        val scenario = NautilusScene.scenario(map).copy(economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 0L)))
        val c = NautilusScene.open(scenario)
        try {
            fun queue(id: String, cancel: Boolean = false) =
                c.submit(UnitAction(id, "p1", "mother", "buildAntiNuke", c.world, cancelQueued = cancel))
            fun unit() = c.observe().units.single { it.unitId == "mother" }
            fun rounds() = unit().unitActions!!.queue.filter { it.name == "buildAntiNuke" }

            assertIs<ActionResult.Accepted>(queue("round-1"))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "a".repeat(32), c.world)))
            for (index in 2..4) assertIs<ActionResult.Accepted>(queue("round-$index"))
            assertEquals(4, rounds().size)
            assertEquals(5550L, c.observePlayer(c.world, "p1").balance)
            assertEquals("ammo-queue-limit", assertIs<ActionResult.Rejected>(queue("over-capacity")).error.code)
            assertIs<ActionResult.Accepted>(queue("cancel-last", cancel = true))
            assertEquals(11550L, c.observePlayer(c.world, "p1").balance)

            assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", "mother", Position(650.0, 180.0), c.world)))
            val origin = unit().position
            for (step in 0L..29L) c.advance(Step(step, 1.0, "sim-tick"))
            assertNotEquals(origin, unit().position)
            assertEquals(3, rounds().size)
            assertEquals(.015f, rounds().first().progress, .000001f)
            assertTrue(rounds().drop(1).all { it.progress == 0f })

            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(30L, 1.0, "sim-tick"))
            assertEquals("nautilusSubmarineSurface", unit().typeId)
            assertEquals(.015f, rounds().first().progress, .000001f)
            assertEquals(0, unit().unitActions!!.stockpile)
            for (step in 31L..2050L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(1, unit().unitActions!!.stockpile)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("mother").single().progress > 0f)
            assertEquals(2, rounds().size)
            assertTrue(rounds().all { it.progress == 0f })
        } finally { c.close() }
    }

    @Test fun no_nukes_locks_naval_ammunition_without_disabling_other_actions() = WaterTestScene.withMap { map ->
        val scene = NautilusScene.scenario(map).copy(nukesEnabled = false,
            economyContract = EconomyContract(mapOf("p1" to 6000L, "p2" to 0L)))
        val c = NautilusScene.open(scene)
        try {
            val result = c.submit(UnitAction("blocked", "p1", "mother", "buildAntiNuke", c.world))
            assertEquals("nukes-disabled", assertIs<ActionResult.Rejected>(result).error.code)
            assertEquals(6000L, c.observePlayer(c.world, "p1").balance)
            val actions = c.observe().units.single().unitActions!!.actions
            assertFalse(actions.single { it.name == "buildAntiNuke" }.available)
            assertTrue(actions.single { it.name == "surface" }.available)
        } finally { c.close() }
    }

    @Test fun completed_scout_launch_expires_the_mother_shot_and_bears_independent_split_shots() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            val target = Position(565.0, 180.0)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "mother", "launchScoutBot", c.world, target = target)))
            var observation = c.observe()
            var step = 1L
            while (step < 111 && observation.projectileEvents.none { it.kind == "born" }) {
                observation = c.advance(Step(step++, 1.0, "sim-tick"))
            }
            val launch = observation.projectileEvents.first { it.kind == "born" }.projectile
            assertEquals("botTurret", launch.sourceWeaponName)
            assertEquals(0.0, launch.remainingLife)
            assertEquals(target, launch.ground?.targetPosition)
            assertTrue(observation.projectileEvents.any { it.kind == "expired" && it.projectile.objectId == launch.objectId })
            val split = observation.projectileEvents.filter { it.kind == "born" && it.projectile.objectId != launch.objectId }
                .map { it.projectile }
            assertEquals(2, split.size)
            assertEquals(listOf(90.0, 90.0), split.map { it.remainingLife })
            assertEquals(listOf(15.0, 180.0), split.map { kotlin.math.abs(ScreenPlane.difference(it.heading, launch.heading)) })
            assertTrue(split.all { it.position == launch.position && it.ground != null })
            assertTrue(observation.units.single().unitActions!!.actions.single { it.name == "launchScoutBot" }.cooldownRemaining > 0)
            assertEquals(4750L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun scout_launch_checks_the_point_before_charging_and_cancels_with_a_full_refund() = WaterTestScene.withMap { map ->
        val scenario = NautilusScene.scenario(map).copy(economyContract = EconomyContract(mapOf("p1" to 250L, "p2" to 0L)))
        val c = NautilusScene.open(scenario)
        try {
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            fun launch(id: String, target: Position?) =
                c.submit(UnitAction(id, "p1", "mother", "launchScoutBot", c.world, target = target))
            assertEquals("missing-action-target", assertIs<ActionResult.Rejected>(launch("missing", null)).error.code)
            assertEquals("invalid-action-target", assertIs<ActionResult.Rejected>(launch("nan", Position(Double.NaN, 180.0))).error.code)
            assertEquals("action-target-out-of-range",
                assertIs<ActionResult.Rejected>(launch("far", Position(1.0, 250.0))).error.code)
            assertEquals(250L, c.observePlayer(c.world, "p1").balance)

            val endpoint = Position(0.0, 180.0)
            assertIs<ActionResult.Accepted>(launch("at-range", endpoint))
            assertEquals(0L, c.observePlayer(c.world, "p1").balance)
            assertEquals(listOf(QueuedUnitActionObservation("launchScoutBot", 0f, endpoint)),
                c.observe().units.single().unitActions!!.queue)
            assertEquals("duplicate-unit-action", assertIs<ActionResult.Rejected>(launch("duplicate", endpoint)).error.code)
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(2f / 210f, c.observe().units.single().unitActions!!.queue.single().progress)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("cancel", "p1", "mother", "launchScoutBot", c.world, cancelQueued = true)))
            assertEquals(250L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().units.single().unitActions!!.queue.isEmpty())
            assertIs<ActionResult.Accepted>(launch("point-blank", c.observe().units.single().position))
        } finally { c.close() }
    }

    @Test fun paid_scout_launch_stays_queued_and_keeps_its_target_when_dive_preempts_it() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            val target = Position(510.0, 180.0)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "mother", "launchScoutBot", c.world, target = target)))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "mother", c.world)))
            assertEquals(listOf(QueuedUnitActionObservation("launchScoutBot", 2f / 210f, target)),
                c.observe().units.single().unitActions!!.queue)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            for (step in 2L..136L) c.advance(Step(step, 1.0, "sim-tick"))
            val unit = c.observe().units.single()
            assertEquals("nautilusSubmarine", unit.typeId)
            assertTrue(unit.unitActions!!.actions.none { it.name == "launchScoutBot" })
            assertEquals(listOf(QueuedUnitActionObservation("launchScoutBot", 2f / 210f, target)), unit.unitActions!!.queue)
            assertEquals(4750L, c.observePlayer(c.world, "p1").balance)
            assertEquals("unsupported-unit-action",
                assertIs<ActionResult.Rejected>(c.submit(UnitAction("hidden-cancel", "p1", "mother", "launchScoutBot", c.world,
                    cancelQueued = true))).error.code)
        } finally { c.close() }
    }

    @Test fun conversion_preempts_paid_production_preserving_progress_and_completion_has_no_overflow() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "a".repeat(32), c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            val saved = c.observe().economy!!.queuedProduction.getValue("mother").single().progress
            assertEquals(.004f, saved)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals("nautilusSubmarineSurface", c.observe().units.single().typeId)
            assertEquals(saved, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            for (step in 2L..136L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals("nautilusSubmarine", c.observe().units.single().typeId)
            assertEquals(saved, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            c.advance(Step(137, 1.0, "sim-tick"))
            assertEquals(.008f, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }
}
