package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class OfflineSandboxHostTest {
    @Test fun ordinary_offline_stale_identity_and_invalid_placement_never_change_the_world() {
        val scene = BuildingTestContent.scenario()
        val ordinary = OfflineSandboxHost(scene)
        val host = OfflineSandboxHost(scene, deploymentEnabled = true, generation = 7L)
        try {
            val old = ordinary.context.observe()
            val disabled = ordinary.submit(SandboxCommandRequest(1L, ordinary.context.world, 1L, "/help"))
            assertEquals("deployment-disabled", disabled.code)
            assertSame(old, ordinary.context.observe())
            val before = host.context.observe()
            assertEquals("stale-world", host.submit(SandboxCommandRequest(1L, ordinary.context.world, 7L, "/help")).code)
            assertEquals("stale-world", host.submit(SandboxCommandRequest(1L, host.context.world, 6L, "/help")).code)
            for ((index, pair) in listOf(
                "/spawn missing 190 130 p1" to "unknown-type",
                "/spawn c_tank 190 130 p9" to "unknown-owner",
                "/spawn c_tank 0 0 p1" to "invalid-placement",
                "/spawn c_tank 310 210 p1" to "invalid-placement",
                "/spawn c_tank NaN 130 p1" to "invalid-number").withIndex()) {
                val result = host.submit(SandboxCommandRequest(index + 1L, host.context.world, 7L, pair.first))
                assertEquals(SandboxCommandStatus.REJECTED, result.status, pair.first)
                assertEquals(pair.second, result.code, pair.first)
                assertSame(before, host.context.observe())
            }
            assertEquals(SandboxCommandStatus.HELP,
                host.submit(SandboxCommandRequest(10L, host.context.world, 7L, "/help")).status)
            assertSame(before, host.context.observe())
            val oldWorld = host.context.world
            host.close()
            assertEquals("closed", host.submit(SandboxCommandRequest(11L, oldWorld, 7L, "/help")).code)
        } finally { ordinary.close(); host.close() }
    }

    @Test fun a_ready_spawned_factory_joins_the_normal_paid_production_ledger() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val host = OfflineSandboxHost(scene, deploymentEnabled = true)
        val context = host.context
        try {
            host.submit(SandboxCommandRequest(1L, context.world, 1L, "/spawn landFactory 110 130 p1"))
            host.advance(Step(0L, 1.0, "sim-tick"))
            val id = assertNotNull(host.receipt(1L)!!.unitId)
            assertEquals(1f, context.observe().units.single { it.unitId == id }.constructionProgress)
            assertTrue(context.observePlayer(context.world, "p1").factoryQueues.any { it.factoryId == id })
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("paid", "p1", id, "c_tank",
                "a".repeat(32), context.world)))
            for (step in 1L..501L) host.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(1400L, context.observePlayer(context.world, "p1").balance)
            assertEquals(1, context.observe().units.count { it.unitId == "produced:p1:${"a".repeat(32)}" })
        } finally { host.close() }
    }

    @Test fun a_later_real_factory_birth_overflow_rolls_back_the_spawn_and_its_dedup_result_before_retry() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), Long.MAX_VALUE - 1))
        val host = OfflineSandboxHost(scene, deploymentEnabled = true)
        val context = host.context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("paid", "p1", "f", "c_tank",
                "a".repeat(32), context.world)))
            var step = 0L
            fun queued() = context.observePlayer(context.world, "p1").factoryQueues.single { it.factoryId == "f" }.items.single()
            while (queued().progress + checkNotNull(scene.unitDefinitions.getValue("c_tank").build).rate < 1f) {
                check(step < 1000L)
                host.advance(Step(step++, 1.0, "sim-tick"))
            }
            val request = SandboxCommandRequest(1L, context.world, 1L, "/spawn c_tank 110 130 p1 state=damaged")
            val accepted = host.submit(request)
            val before = context.observe()
            val beforePlayer = context.observePlayer(context.world, "p1")
            assertEquals("request-conflict", host.submit(request.copy(text = "/help")).code)
            assertFailsWith<ArithmeticException> { host.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, context.observe())
            assertSame(beforePlayer, context.observePlayer(context.world, "p1"))
            assertEquals(accepted, host.receipt(1L))
            assertEquals(accepted, host.submit(request))
            assertTrue(context.observe().units.none { it.unitId.startsWith("sandbox:") })
            assertEquals(0L, context.observe().production!!.lastBirthSerial)
            assertEquals(Long.MAX_VALUE - 1, context.observe().combat!!.nextObjectId)
            // Ordinary cancellation removes the later allocation that failed, without changing this request.
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", context.world)))
            val result = host.advance(Step(step, 1.0, "sim-tick"))
            val receipt = assertNotNull(host.receipt(1L))
            assertEquals(SandboxCommandStatus.APPLIED, receipt.status)
            assertEquals(1, result.units.count { it.unitId == receipt.unitId })
            assertEquals(1L, result.production!!.lastBirthSerial)
            assertEquals(Long.MAX_VALUE, result.combat!!.nextObjectId)
            assertEquals(1750L, context.observePlayer(context.world, "p1").balance)
            assertEquals(receipt, host.submit(request))
        } finally { host.close() }
    }

    @Test fun rejected_current_world_requests_keep_their_identity_and_cannot_be_rebound_to_another_command() {
        val host = OfflineSandboxHost(BuildingTestContent.scenario(), deploymentEnabled = true)
        try {
            val before = host.context.observe()
            val request = SandboxCommandRequest(1L, host.context.world, 1L, "/spawn missing 190 130 p1")
            val rejected = host.submit(request)
            assertEquals("unknown-type", rejected.code)
            assertEquals(rejected, host.receipt(1L))
            assertEquals(rejected, host.submit(request))
            assertEquals("request-conflict", host.submit(request.copy(text = "/spawn c_tank 190 130 p1")).code)
            assertSame(before, host.context.observe())
        } finally { host.close() }
    }

    @Test fun three_format_water_commands_respect_shore_and_fixed_underwater_height_and_create_real_opponents() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val host = OfflineSandboxHost(SeaFactoryScene.scenario(map, format), deploymentEnabled = true)
            val context = host.context
            try {
                assertEquals("invalid-placement", host.submit(SandboxCommandRequest(1L, context.world, 1L,
                    "/spawn heavyBattleship 240 160 p2")).code)
                assertEquals("invalid-placement", host.submit(SandboxCommandRequest(2L, context.world, 1L,
                    "/spawn builder 650 300 p1")).code)
                for ((sequence, text) in listOf(
                    3L to "/spawn heavyBattleship 550 300 p2 heading=90 state=damaged",
                    4L to "/spawn heavyBattleship 700 300 p1 heading=270",
                    5L to "/spawn navalUnderwaterTarget 650 200 p2")) {
                    assertEquals(SandboxCommandStatus.QUEUED,
                        host.submit(SandboxCommandRequest(sequence, context.world, 1L, text)).status)
                }
                var view = host.advance(Step(0L, 1.0, "sim-tick"))
                val enemy = assertNotNull(host.receipt(3L)!!.unitId)
                val own = assertNotNull(host.receipt(4L)!!.unitId)
                val underwater = assertNotNull(host.receipt(5L)!!.unitId)
                assertEquals(2000.0, view.units.single { it.unitId == enemy }.health)
                assertEquals(-8.0, view.units.single { it.unitId == underwater }.height)
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("manual", "p1", own, enemy, context.world)))
                val fired = mutableSetOf<Long>()
                val otherTargets = mutableSetOf<String>()
                var impact = false
                for (step in 1L..400L) {
                    view = host.advance(Step(step, 1.0, "sim-tick"))
                    for (event in view.projectileEvents) {
                        if (event.kind == "born" && event.projectile.sourceUnitId == own)
                            otherTargets += event.projectile.targetUnitId.toString()
                        if (event.kind == "born" && event.projectile.sourceUnitId == own &&
                            event.projectile.sourceWeaponName in setOf("1", "2") && event.projectile.ground != null)
                            fired += event.projectile.objectId
                        if (event.kind == "impact" && event.projectile.objectId in fired) impact = true
                    }
                }
                assertTrue(fired.isNotEmpty(), "$format bornTargets=$otherTargets units=${view.units.map { listOf(it.unitId,it.ownerPlayerId,it.alive,it.health,it.height,it.attackTargetId) }}")
                assertTrue(impact, format)
                assertTrue(view.units.single { it.unitId == enemy }.health!! < 2000.0, format)
                assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
            } finally { host.close() }
        }
    }

    @Test fun an_existing_initial_id_cannot_turn_a_spawn_into_an_exception_or_duplicate() {
        val base = BuildingTestContent.scenario()
        val id = "sandbox:p1:1:1"
        val scene = base.copy(units = listOf(base.units.single { it.unitId == "a" }.copy(unitId = id)),
            combatContract = CombatContract(12345, -1, mapOf(id to 8L), 9L))
        val host = OfflineSandboxHost(scene, deploymentEnabled = true)
        try {
            val before = host.context.observe()
            val result = host.submit(SandboxCommandRequest(1L, host.context.world, 1L, "/spawn c_tank 190 130 p1"))
            assertEquals(SandboxCommandStatus.REJECTED, result.status)
            assertEquals("duplicate-unit-id", result.code)
            assertSame(before, host.context.observe())
        } finally { host.close() }
    }

    @Test fun replay_rebinds_only_the_world_capability_and_preserves_every_recorded_step() {
        val scene = BuildingTestContent.scenario()
        val first = OfflineSandboxHost(scene, deploymentEnabled = true, generation = 3L)
        val replay = OfflineSandboxHost(scene, deploymentEnabled = true, generation = 3L)
        try {
            val request = SandboxCommandRequest(1L, first.context.world, 3L,
                "/spawn c_tank 190 130 p2 heading=270 state=damaged")
            assertEquals("stale-world", replay.submit(request).code)
            first.submit(request)
            replay.submit(request.copy(world = replay.context.world))
            for (step in 0L..80L) {
                val left = first.advance(Step(step, 1.0, "sim-tick"))
                val right = replay.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(left.units, right.units)
                assertEquals(left.events, right.events)
                assertEquals(left.projectiles, right.projectiles)
                assertEquals(left.projectileEvents, right.projectileEvents)
                assertEquals(left.combat, right.combat)
                assertEquals(left.production!!.lastBirthSerial, right.production!!.lastBirthSerial)
                assertEquals(left.economy!!.balances, right.economy!!.balances)
                assertEquals(left.stepIndex, right.stepIndex)
                assertEquals(left.simulationTime, right.simulationTime)
            }
            assertEquals(first.receipt(1L), replay.receipt(1L))
        } finally { first.close(); replay.close() }
    }

    @Test fun queued_overlapping_factories_are_rechecked_inside_the_same_candidate_without_a_second_identity() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val host = OfflineSandboxHost(scene, deploymentEnabled = true)
        try {
            for ((sequence, owner) in listOf(1L to "p1", 2L to "p2")) assertEquals(SandboxCommandStatus.QUEUED,
                host.submit(SandboxCommandRequest(sequence, host.context.world, 1L, "/spawn landFactory 110 130 $owner")).status)
            val view = host.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1L)!!.status)
            assertEquals(SandboxCommandStatus.REJECTED, host.receipt(2L)!!.status)
            assertEquals("invalid-placement", host.receipt(2L)!!.code)
            assertEquals(1L, view.production!!.lastBirthSerial)
            assertEquals(12L, view.combat!!.nextObjectId)
            assertEquals(2, view.units.size)
            assertEquals(1750L, host.context.observePlayer(host.context.world, "p1").balance)
        } finally { host.close() }
    }

    @Test fun ready_air_uses_the_existing_nonzero_flight_initial_state_and_damaged_requires_maximum_health() {
        val original = HelicopterTestScene.unit()
        val definition = com.rustedwarfare.reforge.core.content.UnitDefinition(original.id, original.className,
            original.radius, original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(flight = checkNotNull(original.movement.flight).copy(startingHeightOffset = 5.0)),
            original.retainedUnconsumed, original.fogOfWarSightRange, rotor = original.rotor)
        val host = OfflineSandboxHost(HelicopterTestScene.scenario(unit = definition), deploymentEnabled = true)
        try {
            val before = host.context.observe()
            val rejected = host.submit(SandboxCommandRequest(1L, host.context.world, 1L,
                "/spawn helicopter 310 210 p1 state=damaged"))
            assertEquals("unsupported-state", rejected.code)
            assertSame(before, host.context.observe())
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(SandboxCommandRequest(2L, host.context.world, 1L,
                "/spawn helicopter 310 210 p1 heading=90")).status)
            val observation = host.advance(Step(0L, 1.0, "sim-tick"))
            val spawned = observation.units.single { it.unitId == host.receipt(2L)!!.unitId }
            val initial = observation.units.single { it.unitId == "h1" }
            assertNull(spawned.health)
            assertTrue(spawned.height > 5.0)
            assertEquals(initial.height, spawned.height)
            assertEquals(initial.flightPose, spawned.flightPose)
        } finally { host.close() }
    }

    @Test fun a_legal_factory_can_be_deployed_while_an_existing_paid_product_is_still_inside_its_birth_factory() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val host = OfflineSandboxHost(scene, deploymentEnabled = true)
        val context = host.context
        try {
            val product = "produced:p1:${"a".repeat(32)}"
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("paid", "p1", "f", "c_tank",
                "a".repeat(32), context.world)))
            for (step in 0L..500L) host.advance(Step(step, 1.0, "sim-tick"))
            val before = context.observe()
            val birthState = before.production!!.units.single { it.unitId == product }
            assertTrue(birthState.birthRemaining > 0f)
            val original = before.units.single { it.unitId == product }
            val factory = scene.unitDefinitions.getValue("landFactory").building!!
            assertTrue((MapBounds.cell(original.position.x, scene.map.tileWidth, scene.map.widthTiles) to
                MapBounds.cell(original.position.y, scene.map.tileHeight, scene.map.heightTiles)) in
                BuildingTerrain.footprint(scene.map, scene.units.single().position, factory.footprint, factory.anchor))
            val request = SandboxCommandRequest(1L, context.world, 1L, "/spawn landFactory 110 130 p1")
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request).status)
            val view = host.advance(Step(501L, 1.0, "sim-tick"))
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1L)!!.status)
            assertEquals(1f, view.units.single { it.unitId == host.receipt(1L)!!.unitId }.constructionProgress)
            val continued = view.production!!.units.single { it.unitId == product }
            assertEquals(birthState.objectId, continued.objectId)
            assertEquals("f", continued.producerId)
            assertEquals(birthState.birthRemaining - 1f, continued.birthRemaining)
            assertEquals(1400L, context.observePlayer(context.world, "p1").balance)
            assertEquals(2L, view.production.lastBirthSerial)
            // Only a mobile inside the proposed new footprint is a placement conflict.
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(SandboxCommandRequest(2L, context.world, 1L,
                "/spawn c_tank 190 130 p1")).status)
            host.advance(Step(502L, 1.0, "sim-tick"))
            val blocked = host.submit(SandboxCommandRequest(3L, context.world, 1L, "/spawn landFactory 190 130 p1"))
            assertEquals("invalid-placement", blocked.code)
            assertEquals(3L, context.observe().production!!.lastBirthSerial)
        } finally { host.close() }
    }

    @Test fun explicit_local_deployment_creates_once_on_the_next_step_with_core_precision_and_normal_control() {
        val scene = BuildingTestContent.scenario()
        val host = OfflineSandboxHost(scene, deploymentEnabled = true, generation = 7L)
        val context = host.context
        try {
            val before = context.observe()
            val request = SandboxCommandRequest(1L, context.world, 7L, "/spawn c_tank 190 130 p1 heading=90 state=damaged")
            val queued = host.submit(request)
            assertEquals(SandboxCommandStatus.QUEUED, queued.status)
            assertSame(before, context.observe())
            assertEquals(queued, host.submit(request))
            val view = host.advance(Step(0L, 1.0, "sim-tick"))
            val applied = host.receipt(1L)!!
            assertEquals(SandboxCommandStatus.APPLIED, applied.status)
            assertEquals(0L, applied.stepIndex)
            assertEquals(7L, applied.generation)
            val id = assertNotNull(applied.unitId)
            val unit = view.units.single { it.unitId == id }
            assertEquals("c_tank", unit.typeId)
            assertEquals("p1", unit.ownerPlayerId)
            assertEquals(105.0, unit.health)
            assertEquals(1f, unit.constructionProgress)
            assertEquals(1750L, context.observePlayer(context.world, "p1").balance)
            assertEquals(1L, view.production!!.lastBirthSerial)
            assertEquals(applied, host.submit(request))
            host.advance(Step(1L, 1.0, "sim-tick"))
            assertEquals(1, context.observe().units.count { it.unitId == id })
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", id, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", id, Position(210.0, 130.0), context.world)))
            host.advance(Step(2L, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single { it.unitId == id }.position != unit.position)
        } finally { host.close() }
    }
}
