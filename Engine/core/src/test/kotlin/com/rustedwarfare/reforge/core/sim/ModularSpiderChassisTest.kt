package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Path
import kotlin.test.*

class ModularSpiderChassisTest {
    @Test fun the_opening_spider_approaches_and_constructs_a_repair_bay() = withBattle { battle ->
        val origin = battle.unit("spider").position
        val balance = battle.balance()
        assertIs<ActionResult.Accepted>(battle.context.submit(BuildAction("bay", "p1", "spider",
            "repairbay", Position(350.0, 170.0), "0123456789abcdef0123456789abcdef", battle.context.world)))
        battle.until(900) { battle.context.observe().units.any { it.typeId == "repairbay" } }
        val bay = battle.context.observe().units.single { it.typeId == "repairbay" }
        assertTrue(battle.unit("spider").position.x > origin.x)
        assertTrue(bay.constructionProgress < 1f)
        assertTrue(battle.balance() < balance)
        battle.until(400) { battle.unit(bay.unitId).constructionProgress == 1f }
    }

    @Test fun ordinary_maintenance_rejects_the_same_real_slot_during_construction_and_after_completion() =
        withBattle { battle ->
            battle.action("selectSlot2")
            battle.action("buildGun")
            battle.tick()
            val slot = checkNotNull(battle.unit("spider").attachmentOverview).slots.single { it.number == 2 }
            assertTrue(slot.progress < 1f)
            fun rejected(id: String) = assertEquals("attached-unit",
                assertIs<ActionResult.Rejected>(battle.context.submit(RepairUnitAction(id, "p1", "spider",
                    slot.unitId, battle.context.world))).error.code)
            rejected("unfinished-slot")
            battle.until(1100) { battle.unit(slot.unitId).constructionProgress == 1f }
            rejected("finished-slot")
            assertTrue(battle.unit("spider").tasks.isEmpty())
        }

    @Test fun automatic_repair_waits_for_idle_and_restores_a_damaged_mobile_unit_at_the_nano_rate() =
        withBattle { battle ->
            val builderId = "constructed:p1:abcdef0123456789abcdef0123456789"
            assertIs<ActionResult.Accepted>(battle.context.submit(BuildAction("worker", "p1", "spider", "builder",
                Position(150.0, 170.0), "abcdef0123456789abcdef0123456789", battle.context.world)))
            battle.until(250) {
                battle.context.observe().units.any { it.unitId == builderId && it.constructionProgress == 1f }
            }
            assertIs<ActionResult.Accepted>(battle.context.submit(
                ReclaimUnitAction("damage", "p1", "spider", builderId, battle.context.world)))
            battle.until(100) { checkNotNull(battle.unit(builderId).health) < 150.0 }
            assertFalse(battle.unit("spider").tasks.any { it.automaticRepair })
            assertIs<ActionResult.Accepted>(battle.context.submit(StopAction("release", "p1", "spider", battle.context.world)))
            battle.until(45) { battle.unit("spider").tasks.any { it.automaticRepair && it.targetUnitId == builderId } }
            val before = checkNotNull(battle.unit(builderId).health)
            battle.until(40) { checkNotNull(battle.unit(builderId).health) > before }
            assertEquals((before.toFloat() + .4f).toDouble(), battle.unit(builderId).health)
            battle.until(150) { battle.unit("spider").tasks.isEmpty() }
            assertEquals(170.0, battle.unit(builderId).health)
        }

    @Test fun health_death_clears_slots_and_income_and_only_nukes_enabled_causes_a_local_nuclear_area() {
        for (nukes in listOf(true, false)) withBattle(nukes, hostile = true) { battle ->
            battle.action("selectSlot2")
            battle.action("buildGun")
            val slotIds = checkNotNull(battle.unit("spider").attachmentOverview).slots.map { it.unitId }.toSet()
            battle.until(200) { !battle.unit("spider").alive }
            assertTrue(battle.context.observe().units.none { it.unitId in slotIds })
            val stoppedIncome = battle.balance()
            repeat(120) { battle.tick() }
            assertEquals(stoppedIncome, battle.balance())
            val nuclear = battle.projectileEvents.filter {
                it.projectile.appearance == ProjectileAppearance.NUCLEAR && it.projectile.sourceUnitId == "spider"
            }
            if (nukes) {
                val impact = nuclear.single { it.kind == "impact" }.projectile
                assertEquals(battle.unit("spider").position, impact.position)
                assertNull(impact.ballistic)
                assertTrue(nuclear.any { it.kind == "area-ended" })
                assertTrue(checkNotNull(battle.unit("near").health) < 10000.0)
            } else {
                assertTrue(nuclear.isEmpty())
                assertEquals(10000.0, battle.unit("near").health)
            }
            assertEquals(10000.0, battle.unit("far").health)
        }
    }

    @Test fun chassis_recovers_its_own_health_after_taking_damage() = withBattle(hostile = true) { battle ->
        battle.until(40) { checkNotNull(battle.unit("spider").health) < 10000.0 }
        val damaged = checkNotNull(battle.unit("spider").health)
        assertTrue(battle.unit("spider").tasks.isEmpty())
        battle.tick()
        assertEquals((damaged.toFloat() + .1f).toDouble(), battle.unit("spider").health)
    }

    @Test fun ordinary_reclaim_uses_the_chassis_value_without_death_explosion() =
        withBattle(friendlyWorker = true) { battle ->
            val slots = checkNotNull(battle.unit("spider").attachmentOverview).slots.map { it.unitId }
            val reclaim = ReclaimUnitAction("recover-chassis", "p1", "worker", "spider", battle.context.world)
            assertIs<ActionResult.Accepted>(battle.context.submit(reclaim))
            var beforeReclaim = battle.balance()
            battle.until(5500) {
                if ("spider" in battle.context.observe().reclaimedUnitIds.orEmpty()) true
                else {
                    beforeReclaim = battle.balance()
                    false
                }
            }
            val finish = battle.context.observe()
            assertTrue(finish.units.none { it.unitId == "spider" || it.unitId in slots })
            assertTrue(finish.events.any { it.kind == "building-reclaimed" && it.unitId == "spider" })
            val finalTickIncome = if (finish.simulationTime.toLong() % 40 == 0L) 60L else 0L
            assertEquals(72000L, battle.balance() - beforeReclaim - finalTickIncome)
            battle.until(5) { battle.unit("worker").tasks.isEmpty() }
            assertTrue(battle.projectileEvents.none { it.projectile.appearance == ProjectileAppearance.NUCLEAR })
        }

    @Test fun a_reclaimed_loaded_landing_craft_releases_its_manifest_and_passenger_lifecycle() =
        withBattle(cargo = true) { battle ->
            battle.loadWorker()
            val reclaim = ReclaimUnitAction("reclaim-carrier", "p1", "spider", "carrier", battle.context.world)
            assertIs<ActionResult.Accepted>(battle.context.submit(reclaim))
            battle.until(800) { "carrier" in battle.context.observe().reclaimedUnitIds.orEmpty() }
            assertNull(battle.unit("passenger").carriedBy)
            battle.until(5) { !battle.unit("passenger").alive }
            assertTrue(battle.projectileEvents.none { it.projectile.appearance == ProjectileAppearance.NUCLEAR })
        }

    @Test fun death_nuke_reveals_only_the_source_players_local_fog_until_the_shared_timer_expires() {
        for (nukes in listOf(true, false)) withBattle(nukes, hostile = true, fog = true) { battle ->
            val cell = VisionCell(15, 8)
            battle.until(200) { !battle.unit("spider").alive }
            fun visible(player: String) = cell in battle.context.visionSnapshot(battle.context.world, player).effectiveCells
            if (nukes) battle.until(2) { battle.projectileEvents.any { it.kind == "impact" &&
                it.projectile.appearance == ProjectileAppearance.NUCLEAR } }
            assertEquals(nukes, visible("p1"))
            assertFalse(visible("p2"))
            repeat(360) { battle.tick() }
            assertEquals(nukes, visible("p1"))
            battle.tick()
            assertFalse(visible("p1"))
        }
    }

    @Test fun a_spider_built_turret_can_upgrade_and_reclaim_for_the_current_value() = withBattle { battle ->
        val tower = battle.construct("c_turret_t1", Position(150.0, 170.0))
        assertIs<ActionResult.Accepted>(battle.context.submit(UpgradeAction("upgrade-tower", "p1", tower,
            "upgrade_gun", battle.context.world)))
        battle.until(1200) { battle.unit(tower).typeId == "c_turret_t2_gun" }
        val direct = battle.site("c_turret_t2_gun", Position(210.0, 170.0))
        assertEquals("unsupported-construction-type",
            assertIs<ActionResult.Rejected>(battle.context.submit(direct)).error.code)
        val reclaim = ReclaimUnitAction("reclaim-tower", "p1", "spider", tower, battle.context.world)
        assertIs<ActionResult.Accepted>(battle.context.submit(reclaim))
        val before = battle.balance()
        val time = battle.context.observe().simulationTime.toLong()
        battle.until(700) { tower in battle.context.observe().reclaimedUnitIds.orEmpty() }
        battle.until(5) { battle.unit("spider").tasks.isEmpty() }
        val income = (battle.context.observe().simulationTime.toLong() / 40 - time / 40) * 30
        assertEquals(before + income + 1200, battle.balance())
    }

    @Test fun manual_repair_restores_hp_and_drops_the_task_if_another_worker_removes_its_target() =
        withBattle(friendlyWorker = true) { battle ->
            val unit = battle.construct("builder", Position(150.0, 230.0))
            assertIs<ActionResult.Accepted>(battle.context.submit(
                ReclaimUnitAction("partial", "p1", "spider", unit, battle.context.world)))
            battle.until(120) { checkNotNull(battle.unit(unit).health) < 120.0 }
            assertIs<ActionResult.Accepted>(battle.context.submit(
                RepairUnitAction("manual", "p1", "spider", unit, battle.context.world)))
            battle.until(140) { battle.unit(unit).health == 170.0 }
            assertIs<ActionResult.Accepted>(battle.context.submit(
                ReclaimUnitAction("remove", "p1", "worker", unit, battle.context.world)))
            battle.until(260) { checkNotNull(battle.unit(unit).health) < 45.0 }
            assertIs<ActionResult.Accepted>(battle.context.submit(
                RepairUnitAction("until-removed", "p1", "spider", unit, battle.context.world)))
            val point = Position(80.0, 280.0)
            assertIs<ActionResult.Accepted>(battle.context.submit(
                PatrolAction("after-removed", "p1", "spider", point, true, battle.context.world)))
            battle.until(100) { unit in battle.context.observe().reclaimedUnitIds.orEmpty() }
            battle.until(5) { battle.unit("spider").tasks.firstOrNull()?.kind == UnitTaskKind.PATROL }
        }

    @Test fun force_nano_constructs_hovercraft_and_bomber_over_water_but_rejects_occupied_or_external_sites() =
        withBattle(start = Position(333.0, 170.0)) { battle ->
            val boatSite = Position(450.0, 190.0)
            val bomberSite = Position(450.0, 110.0)
            val carrier = battle.construct("hovercraft", boatSite)
            val bomber = battle.construct("bomber", bomberSite)
            assertEquals(boatSite, battle.unit(carrier).position)
            assertEquals(bomberSite, battle.unit(bomber).position)
            for ((type, point) in listOf("hovercraft" to boatSite, "bomber" to bomberSite)) {
                val before = battle.balance()
                val time = battle.context.observe().simulationTime.toLong()
                val occupied = battle.site(type, point)
                assertIs<ActionResult.Accepted>(battle.context.submit(occupied))
                battle.until(130) { battle.unit("spider").tasks.isEmpty() }
                assertTrue(battle.context.observe().units.none { it.unitId == "constructed:p1:${occupied.buildingNonce}" })
                val elapsedIncome = (battle.context.observe().simulationTime.toLong() / 40 - time / 40) * 30
                assertEquals(before + elapsedIncome, battle.balance())
                val outside = battle.site(type, Position(805.0, point.y))
                assertEquals("invalid-target", assertIs<ActionResult.Rejected>(battle.context.submit(outside)).error.code)
                assertEquals(before + elapsedIncome, battle.balance())
            }
        }

    @Test fun a_passenger_still_in_its_carrier_skips_the_death_nuclear_area() =
        withBattle(hostile = true, cargo = true) { battle ->
            battle.loadWorker()
            battle.until(200) { !battle.unit("spider").alive }
            repeat(80) { battle.tick() }
            assertTrue(battle.unit("carrier").alive)
            assertTrue(checkNotNull(battle.unit("carrier").health) in 1.0..10.0)
            assertEquals("carrier", battle.unit("passenger").carriedBy)
            assertEquals(170.0, battle.unit("passenger").health)
        }

    @Test fun an_incomplete_chassis_does_not_start_a_death_nuclear_area() =
        withBattle(hostile = true, incomplete = true) { battle ->
            battle.until(200) { !battle.unit("spider").alive }
            repeat(80) { battle.tick() }
            assertTrue(battle.projectileEvents.none { it.projectile.appearance == ProjectileAppearance.NUCLEAR })
        }

    private fun withBattle(nukes: Boolean = true, hostile: Boolean = false, friendlyWorker: Boolean = false,
                           fog: Boolean = false, cargo: Boolean = false,
                           incomplete: Boolean = false, start: Position = Position(80.0, 170.0), run: (Battle) -> Unit) =
        WaterTestScene.withMap { map ->
            val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            fun units(path: String): Map<String, UnitDefinition> {
                val result = ContentLoader.loadContentPack(fixtures.resolve(path))
                return assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value.units
            }
            val spider = units("modular-spider-start/ini/opening-package")
            val origin = start
            val base = WaterTestScene.unit(MovementType.LAND)
            val inert = UnitDefinition("deathWitness", "CustomUnitMetadata", 6.0, 6.0, 1,
                base.imagePath, 1.0, base.movement, emptySet(), durability = DurabilityProfile(10000.0, null))
            val attacker = UnitDefinition("deathAttacker", "CustomUnitMetadata", 6.0, 6.0, 1,
                base.imagePath, 1.0, base.movement, emptySet(), fogOfWarSightRange = if (fog) 0 else 15,
                combat = CombatProfile(10000.0, 100.0, 60.0, 0.0, 8.0, 2.0,
                    ProjectileProfile(8000.0, 80.0, 15.0, instant = true), null, null, 1.0))
            val spawns = buildList {
                add(UnitSpawn("spider", "p1", "modularSpider", origin, 90.0))
                if (friendlyWorker) add(UnitSpawn("worker", "p1", "modularSpider", Position(170.0, 170.0), 270.0))
                if (cargo) {
                    add(UnitSpawn("carrier", "p1", "hovercraft", Position(279.9, 170.0), 90.0))
                    add(UnitSpawn("passenger", "p1", "builder", Position(279.9, 188.0), 270.0))
                }
            } +
                if (hostile) listOf(UnitSpawn("attacker", "p2", attacker.id, Position(142.0, 170.0), 270.0),
                    UnitSpawn("near", "p2", inert.id, Position(168.0, 170.0), 90.0),
                    UnitSpawn("far", "p2", inert.id, Position(315.0, 170.0), 90.0)).filter {
                    !fog || it.unitId == "attacker"
                } else emptyList()
            val scene = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, origin).copy(
                unitDefinitions = spider + listOf(inert, attacker).associateBy { it.id },
                units = spawns, nukesEnabled = nukes,
                automaticVision = if (fog) KnowledgeRules(TerrainRetention.MEMORY,
                    PathInformationSource.PLAYER_KNOWLEDGE, false) else null,
                economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)),
                combatContract = CombatContract(173, -1,
                    spawns.mapIndexed { index, unit -> unit.unitId to (8L + index) }.toMap(), 8L + spawns.size))
            val context = NuclearScene.open(scene)
            try {
                if (incomplete) {
                    // Direct setup for this otherwise unreachable opening state. Combat still causes the death.
                    val field = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
                    val steps = field.get(context) as CandidateSteps
                    steps.current.units.getValue("spider").constructionProgress = .5f
                }
                run(Battle(context))
            } finally { context.close() }
        }

    private class Battle(val context: SimulationContext) {
        private var step = 0L
        private var command = 0
        val projectileEvents = mutableListOf<ProjectileEventObservation>()

        fun unit(id: String) = context.observe().units.single { it.unitId == id }
        fun balance() = checkNotNull(context.observe().economy).balances.getValue("p1")
        fun tick() {
            val observation = context.advance(Step(step++, 1.0, "sim-tick"))
            projectileEvents += observation.projectileEvents
        }
        fun action(name: String) {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("chassis-${command++}", "p1", "spider", name, context.world)))
        }
        fun site(type: String, position: Position): BuildAction {
            val nonce = (command++).toString(16).padStart(32, '0')
            return BuildAction("site-$nonce", "p1", "spider", type, position, nonce, context.world)
        }
        fun construct(type: String, position: Position): String {
            val action = site(type, position)
            val id = "constructed:p1:${action.buildingNonce}"
            assertIs<ActionResult.Accepted>(context.submit(action))
            until(2300) { context.observe().units.any { it.unitId == id && it.constructionProgress == 1f } }
            return id
        }
        fun loadWorker() {
            assertEquals("transport-full", assertIs<ActionResult.Rejected>(context.submit(
                LoadAction("spider-cannot-board", "p1", "spider", "carrier", LoadDirection.INTO, context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(
                LoadAction("load-worker", "p1", "passenger", "carrier", LoadDirection.INTO, context.world)))
            until(100) { unit("passenger").carriedBy == "carrier" }
        }
        fun until(limit: Int, condition: () -> Boolean) {
            repeat(limit) { if (condition()) return else tick() }
            assertTrue(condition(), "Expected state within $limit simulation steps")
        }
    }
}
