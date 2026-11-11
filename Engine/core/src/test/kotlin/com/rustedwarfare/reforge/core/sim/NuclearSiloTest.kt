package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object NuclearScene {
    fun scenario(map: MapDefinition, format: String = "ini"): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("nuclear-silo/$format/package"))).value
        return WaterTestScene.scenario(map, MovementType.LAND, Position(180.0, 180.0)).copy(
            unitDefinitions = pack.units,
            units = listOf(UnitSpawn("silo", "p1", "nukeLauncherC", Position(180.0, 180.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L), 2L),
            economyContract = EconomyContract(mapOf("p1" to 100000L, "p2" to 0L)))
    }
    fun open(scenario: Scenario): SimulationContext {
        val result = SimulationContext.initialize(scenario)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}

class NuclearSiloTest {
    @Test fun launch_opens_the_real_doors_and_cancel_blends_them_back_without_spending_the_round() = WaterTestScene.withMap { map ->
        val c = NuclearScene.open(NuclearScene.scenario(map))
        try {
            var step = 0L
            fun silo() = c.observePlayer(c.world, "p1").units.single()
            assertEquals(0f, silo().nuclearDoors)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("ammo", "p1", "silo", "buildNuke", c.world)))
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "silo", "launchNuke", c.world, target = Position(440.0, 180.0))))
            repeat(60) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(0.4999995231628418f, silo().nuclearDoors)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("cancel", "p1", "silo", "launchNuke", c.world, true)))
            repeat(75) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(.25f, silo().nuclearDoors)
            repeat(76) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(0f, silo().nuclearDoors)
            assertEquals(1, silo().unitActions!!.stockpile)
            assertTrue(c.observe().projectiles.isEmpty())
        } finally { c.close() }
    }

    @Test fun reclaiming_the_launcher_does_not_recall_or_break_its_already_flying_missile() = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("nuclear-silo/ini/production-package"))).value
        val base = NuclearScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, pack.units.getValue("builder").imagePath, 1.0,
            MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(10000.0, null))
        val scene = base.copy(unitDefinitions = pack.units + (target.id to target),
            units = base.units + listOf(UnitSpawn("builder", "p1", "builder", Position(100.0, 180.0), 90.0),
                UnitSpawn("victim", "p2", "target", Position(760.0, 180.0), 90.0)),
            maintenanceContract = MaintenanceContract(mapOf("nukeLauncherC" to 45000L)),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "builder" to 2L, "victim" to 3L), 4L))
        val c = NuclearScene.open(scene)
        try {
            var step = 0L
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("ammo", "p1", "silo", "buildNuke", c.world)))
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("reclaim", "p1", "builder", "silo", c.world)))
            repeat(1000) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "silo", "launchNuke", c.world,
                target = Position(760.0, 180.0))))
            while (c.observe().projectiles.isEmpty() && step < 4600) c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(1, c.observe().projectiles.size)
            while (c.observe().units.any { it.unitId == "silo" } && step < 5100) c.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(c.observe().units.none { it.unitId == "silo" })
            assertEquals(1, c.observe().projectiles.size)
            var impact = false
            repeat(700) {
                c.advance(Step(step++, 1.0, "sim-tick"))
                impact = impact || c.observe().projectileEvents.any { it.kind == "impact" }
            }
            assertTrue(impact)
            assertEquals(4600.0, c.observe().units.single { it.unitId == "victim" }.health)
        } finally { c.close() }
    }

    @Test fun the_nuclear_projectile_is_visible_in_fog_but_its_local_reveal_expires_without_exposing_hidden_identity() = WaterTestScene.withMap { map ->
        val base = NuclearScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, base.unitDefinitions.values.single().imagePath, 1.0,
            MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), fogOfWarSightRange = 0,
            durability = DurabilityProfile(10000.0, null))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            units = base.units + UnitSpawn("hidden", "p2", target.id, Position(700.0, 180.0), 90.0),
            automaticVision = com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "hidden" to 2L), 3L))
        val c = NuclearScene.open(scene)
        try {
            var step = 0L
            fun revealed() = c.observePlayer(c.world, "p1").units.any { it.unitId == "hidden" }
            assertFalse(revealed())
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("build", "p1", "silo", "buildNuke", c.world)))
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "silo", "launchNuke", c.world, target = Position(690.0, 180.0))))
            var born = false
            while (!born && step < 3600) {
                c.advance(Step(step++, 1.0, "sim-tick"))
                born = c.observe().projectileEvents.any { it.kind == "born" }
            }
            assertTrue(born)
            val visibleBorn = c.observePlayer(c.world, "p2").projectileEvents.single { it.kind == "born" }
            assertNull(visibleBorn.sourceUnitId)
            assertNull(visibleBorn.sourceWeaponName)
            assertFalse(revealed())
            while (!revealed() && step < 4500) c.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(revealed())
            repeat(360) { c.advance(Step(step++, 1.0, "sim-tick")); assertTrue(revealed()) }
            val previousWorld = c.world
            assertEquals(CloseResult.Closed, c.close())
            assertIs<InitResult.Accepted>(c.initialize(scene))
            assertNotSame(previousWorld, c.world)
            assertFalse(revealed(), "A new world must not inherit the previous world's nuclear reveal")
            assertTrue(c.observePlayer(c.world, "p2").projectileEvents.isEmpty())
            assertEquals(0, c.observePlayer(c.world, "p1").units.single { it.unitId == "silo" }.unitActions!!.stockpile)
        } finally { c.close() }
    }

    @Test fun nuclear_local_reveal_expires_after_360_simulation_units() = WaterTestScene.withMap { map ->
        val base = NuclearScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, base.unitDefinitions.values.single().imagePath, 1.0,
            MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), fogOfWarSightRange = 0,
            durability = DurabilityProfile(10000.0, null))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            units = base.units + UnitSpawn("hidden", "p2", target.id, Position(700.0, 180.0), 90.0),
            automaticVision = com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "hidden" to 2L), 3L))
        val c = NuclearScene.open(scene)
        try {
            var step = 0L
            fun revealed() = c.observePlayer(c.world, "p1").units.any { it.unitId == "hidden" }
            assertFalse(revealed())
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("build", "p1", "silo", "buildNuke", c.world)))
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "silo", "launchNuke", c.world, target = Position(690.0, 180.0))))
            while (!revealed() && step < 4500) c.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(revealed())
            repeat(360) { c.advance(Step(step++, 1.0, "sim-tick")); assertTrue(revealed()) }
            c.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(revealed())
        } finally { c.close() }
    }

    @Test fun reclaim_clears_unfinished_ordnance_and_refunds_it_in_the_same_terminal_transaction() = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("nuclear-silo/ini/production-package"))).value
        val scene = NuclearScene.scenario(map).copy(unitDefinitions = pack.units,
            units = listOf(UnitSpawn("builder", "p1", "builder", Position(100.0, 180.0), 90.0),
                UnitSpawn("silo", "p1", "nukeLauncherC", Position(180.0, 180.0), 90.0)),
            maintenanceContract = MaintenanceContract(mapOf("nukeLauncherC" to 45000L)),
            combatContract = CombatContract(12345, -1, mapOf("builder" to 1L, "silo" to 2L), 3L))
        val c = NuclearScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("pending", "p1", "silo", "buildNuke", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("reclaim", "p1", "builder", "silo", c.world)))
            var step = 0L
            while (c.observe().units.any { it.unitId == "silo" } && step < 2000)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(c.observe().units.none { it.unitId == "silo" })
            assertEquals(136000L, c.observePlayer(c.world, "p1").balance)
            repeat(100) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(136000L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun destroyed_silo_refunds_pending_paid_ammunition_once_and_clears_the_queue() = WaterTestScene.withMap { map ->
        val base = NuclearScene.scenario(map)
        val scene = base.copy(units = listOf(
            UnitSpawn("silo", "p1", "nukeLauncherC", Position(80.0, 180.0), 90.0),
            UnitSpawn("victim", "p1", "nukeLauncherC", Position(340.0, 180.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "victim" to 2L), 3L))
        val c = NuclearScene.open(scene)
        try {
            var step = 0L
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("round", "p1", "silo", "buildNuke", c.world)))
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("pending", "p1", "victim", "buildNuke", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("fire", "p1", "silo", "launchNuke", c.world, target = Position(340.0, 180.0))))
            repeat(1000) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val victim = c.observe().units.single { it.unitId == "victim" }
            assertFalse(victim.alive)
            assertTrue(victim.unitActions!!.queue.isEmpty())
            assertEquals(0, victim.unitActions.stockpile)
            assertEquals(89000L, c.observePlayer(c.world, "p1").balance)
            repeat(100) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(89000L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun no_nukes_locks_new_construction_without_erasing_existing_silo_actions_or_stock() = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("nuclear-silo/ini/production-package"))).value
        val scene = NuclearScene.scenario(map).copy(unitDefinitions = pack.units, nukesEnabled = false,
            units = listOf(UnitSpawn("builder", "p1", "builder", Position(80.0, 80.0), 90.0),
                UnitSpawn("silo", "p1", "nukeLauncherC", Position(180.0, 180.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("builder" to 1L, "silo" to 2L), 3L))
        val c = NuclearScene.open(scene)
        try {
            val build = c.submit(BuildAction("forbidden", "p1", "builder", "nukeLauncherC", Position(300.0, 180.0), "a".repeat(32), c.world))
            assertEquals("nukes-disabled", assertIs<ActionResult.Rejected>(build).error.code)
            assertEquals(100000L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().units.single { it.unitId == "builder" }.tasks.isEmpty())
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("existing-build", "p1", "silo", "buildNuke", c.world)))
            for (step in 0L..3333L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(1, c.observePlayer(c.world, "p1").units.single { it.unitId == "silo" }.unitActions!!.stockpile)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("existing-launch", "p1", "silo", "launchNuke", c.world, target = Position(440.0, 180.0))))
            var born = false
            for (step in 3334L..3550L) {
                c.advance(Step(step, 1.0, "sim-tick"))
                if (c.observe().projectileEvents.any { it.kind == "born" }) born = true
            }
            assertTrue(born)
        } finally { c.close() }
    }

    @Test fun nuclear_building_is_available_in_the_ordinary_paid_builder_menu() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            val result = ContentLoader.loadContentPack(root.resolve("nuclear-silo/$format/production-package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
            val scene = NuclearScene.scenario(map, format).copy(unitDefinitions = pack.units,
                units = listOf(UnitSpawn("builder", "p1", "builder", Position(100.0, 180.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("builder" to 1L), 2L))
            val c = NuclearScene.open(scene)
            try {
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "builder", "nukeLauncherC", Position(180.0, 180.0), "a".repeat(32), c.world)))
                var step = 0L
                while (c.observe().units.size == 1 && step < 100) c.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(2, c.observe().units.size)
                assertEquals(55000L, c.observePlayer(c.world, "p1").balance)
                val siloId = c.observe().units.single { it.typeId == "nukeLauncherC" }.unitId
                assertEquals("unit-incomplete", assertIs<ActionResult.Rejected>(c.submit(UnitAction("unfinished", "p1", siloId, "buildNuke", c.world))).error.code)
                while (c.observe().units.single { it.unitId == siloId }.constructionProgress < 1f && step < 1200)
                    c.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(1f, c.observe().units.single { it.unitId == siloId }.constructionProgress)
                assertIs<ActionResult.Accepted>(c.submit(UnitAction("ammo", "p1", siloId, "buildNuke", c.world)))
                assertEquals(44000L, c.observePlayer(c.world, "p1").balance)
            } finally { c.close() }
        }
    }

    @Test fun a_completed_launch_creates_a_real_point_ballistic_and_expands_once_with_friendly_area_falloff() = WaterTestScene.withMap { map ->
        val base = NuclearScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, base.unitDefinitions.values.single().imagePath, 1.0,
            MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(10000.0, null))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            players = listOf(Player("p1", PlayerControl.LOCAL, "same"), Player("p2", PlayerControl.NONE, "same")),
            units = base.units + listOf(UnitSpawn("center", "p2", target.id, Position(440.0, 180.0), 90.0),
                UnitSpawn("half", "p1", target.id, Position(565.0, 180.0), 90.0),
                UnitSpawn("edge", "p2", target.id, Position(690.0, 180.0), 90.0),
                UnitSpawn("outside", "p2", target.id, Position(691.0, 180.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "center" to 2L, "half" to 3L, "edge" to 4L, "outside" to 5L), 6L))
        val c = NuclearScene.open(scene)
        try {
            var step = 0L
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("build", "p1", "silo", "buildNuke", c.world)))
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch", "p1", "silo", "launchNuke", c.world, target = Position(440.0, 180.0))))
            val born = mutableListOf<ProjectileObservation>()
            var impact = false
            repeat(1000) {
                c.advance(Step(step++, 1.0, "sim-tick"))
                born += c.observe().projectileEvents.filter { it.kind == "born" }.map { it.projectile }
                if (c.observe().projectileEvents.any { it.kind == "impact" }) impact = true
            }
            assertEquals(1, born.size)
            assertNull(born.single().targetUnitId)
            assertEquals("siloTop", born.single().sourceWeaponName)
            assertEquals(Position(440.0, 180.0), born.single().ground!!.targetPosition)
            assertTrue(impact)
            val health = c.observe().units.associate { it.unitId to it.health }
            assertEquals(4600.0, health.getValue("center"))
            // Original binary32: 0.6 -> 0x3f19999a; x5400 -> 0x454a8001 (3240.000244140625).
            assertEquals(6759.999755859375, health.getValue("half"))
            assertEquals(9460.0, health.getValue("edge"))
            assertEquals(10000.0, health.getValue("outside"))
            assertEquals(0, c.observePlayer(c.world, "p1").units.single { it.unitId == "silo" }.unitActions!!.stockpile)
            assertEquals(89000L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun launch_prepays_stock_prioritizes_without_losing_production_and_cancel_can_exceed_admission_threshold() = WaterTestScene.withMap { map ->
        val c = NuclearScene.open(NuclearScene.scenario(map))
        try {
            var id = 0
            var step = 0L
            fun request(name: String, cancel: Boolean = false, target: Position? = null) = c.submit(
                UnitAction("action-${id++}", "p1", "silo", name, c.world, cancel, target = target))
            fun state() = c.observePlayer(c.world, "p1").units.single().unitActions!!
            repeat(4) { assertIs<ActionResult.Accepted>(request("buildNuke")) }
            assertEquals("ammo-queue-limit", assertIs<ActionResult.Rejected>(request("buildNuke")).error.code)
            repeat(13336) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(4, state().stockpile)
            assertEquals(56000L, c.observePlayer(c.world, "p1").balance)
            for (point in listOf(Position(439.0, 180.0), Position(10180.0, 180.0), Position(Double.NaN, 180.0))) {
                assertIs<ActionResult.Rejected>(request("launchNuke", target = point))
                assertEquals(4, state().stockpile)
                assertTrue(state().queue.isEmpty())
            }
            // Both exact inclusive distance boundaries, and repeated same-name work on one silo.
            assertIs<ActionResult.Accepted>(request("launchNuke", target = Position(440.0, 180.0)))
            assertIs<ActionResult.Accepted>(request("launchNuke", target = Position(10179.0, 180.0)))
            assertEquals(2, state().stockpile)
            assertEquals(2, state().queue.size)
            repeat(2) { assertIs<ActionResult.Accepted>(request("launchNuke", cancel = true)) }
            assertEquals(4, state().stockpile)
            assertIs<ActionResult.Accepted>(request("launchNuke", target = Position(440.0, 180.0)))
            assertIs<ActionResult.Accepted>(request("buildNuke"))
            assertEquals(listOf("launchNuke", "buildNuke"), state().queue.map { it.name })
            c.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(state().queue[0].progress > 0f)
            assertEquals(0f, state().queue[1].progress)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "silo", c.world)))
            assertEquals(2, state().queue.size)
            assertIs<ActionResult.Accepted>(request("launchNuke", cancel = true))
            assertEquals(4, state().stockpile)
            repeat(3334) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(5, state().stockpile)
            assertTrue(state().queue.isEmpty())
            assertEquals(45000L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun paid_ammunition_can_be_cancelled_without_creating_stock_and_completed_round_unlocks_launch() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val c = NuclearScene.open(NuclearScene.scenario(map, format))
            try {
                fun action(id: String, cancel: Boolean = false) = c.submit(UnitAction(id, "p1", "silo", "buildNuke", c.world, cancel))
                fun launchAvailable() = c.observePlayer(c.world, "p1").units.single().unitActions!!.actions.single { it.name == "launchNuke" }.available
                assertFalse(launchAvailable())
                assertIs<ActionResult.Accepted>(action("pay"))
                assertEquals(89000L, c.observePlayer(c.world, "p1").balance)
                c.advance(Step(0, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(action("cancel", true))
                assertEquals(100000L, c.observePlayer(c.world, "p1").balance)
                assertFalse(launchAvailable())
                assertIs<ActionResult.Accepted>(action("pay-again"))
                for (step in 1L..3334L) c.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(launchAvailable())
                assertEquals(89000L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Rejected>(action("cancel-completed", true))
                assertEquals(89000L, c.observePlayer(c.world, "p1").balance)
            } finally { c.close() }
        }
    }
}
