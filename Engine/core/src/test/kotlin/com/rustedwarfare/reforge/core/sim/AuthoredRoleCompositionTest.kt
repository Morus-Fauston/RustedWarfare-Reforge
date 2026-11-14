package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Path
import java.nio.file.Files
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredRoleCompositionTest {
    @TempDir lateinit var temporary: Path

    @Test fun incompatible_forms_and_unlisted_nano_actions_are_rejected_by_enabled_packs() {
        val source = fixture()
        Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { file ->
            val target = temporary.resolve(source.relativize(file))
            Files.createDirectories(target.parent)
            Files.copy(file, target)
        } }
        val landed = temporary.resolve("units/support_landed.json")
        val data = Files.readString(landed)
        val manifest = temporary.resolve("pack.json")
        Files.writeString(manifest, Files.readString(manifest).replace("support_landed.json", "support_landed.hjson"))
        val hjson = temporary.resolve("units/support_landed.hjson")
        Files.writeString(hjson, "// Landed form\n" + data.replace("\"roles\":", "roles:"))
        val mixed = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(temporary))).value
        val form = mixed.units.getValue("support_landed")
        assertEquals(loadPack().units.getValue(form.id).production, form.production)
        assertTrue(form.blink!!.locked)
        val changed = JsonValue.readJSON(data).asObject()
        changed["abilities"].asArray()[0].asObject().set("capacity", 4)
        Files.writeString(hjson, changed.toString())
        assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(temporary)))
        val invalid = JsonValue.readJSON(data).asObject()
        invalid["roles"].asObject().set("nanoConstructionTypes", "missing_hull")
        Files.writeString(hjson, invalid.toString())
        val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(temporary))).error
        assertEquals("nanoConstructionTypes", result.field)
    }
    @Test fun conversion_keeps_paid_production_and_passengers_and_locks_landed_blink() =
        withContext(listOf(spawn("support", "support_hover", 230.0), spawn("cargo", "scout_hull", 247.0),
            spawn("enemy", "scout_hull", 310.0, "p2"))) { c ->
            fun owner() = c.observePlayer(c.world, "p1")
            fun support() = owner().units.single { it.unitId == "support" }
            fun order(key: String, player: String = "p1", target: Position? = null) =
                c.submit(UnitAction("$key-$player", player, "support", key, c.world, target = target))
            fun item() = owner().factoryQueues.single { it.factoryId == "support" }.items.single()
            assertIs<ActionResult.Rejected>(order("deploy", "p2"))
            assertIs<ActionResult.Accepted>(c.submit(LoadAction("load", "p1", "support", "cargo", LoadDirection.UP, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(
                ProduceAction("produce", "p1", "support", "scout_hull", "a".repeat(32), c.world)))
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            repeat(4) { tick() }
            val paid = item().progress
            assertTrue(paid > 0f)
            assertIs<ActionResult.Accepted>(order("deploy"))
            assertEquals("duplicate-unit-action", assertIs<ActionResult.Rejected>(
                c.submit(UnitAction("repeat", "p1", "support", "deploy", c.world))).error.code)
            repeat(59) { tick() }
            assertEquals("support_hover", support().typeId)
            assertEquals(paid, item().progress)
            assertEquals("deploy", support().unitActions?.queue?.single()?.name)
            repeat(2) { tick() }
            assertEquals("support_landed", support().typeId)
            assertEquals(0.0, support().height)
            assertEquals(5000f, support().shield?.maximum)
            assertEquals(listOf("cargo"), support().transport?.passengerIds)
            assertEquals(paid, item().progress, .04f)
            assertEquals(3550L, owner().balance)
            assertFalse(support().unitActions!!.actions.single { it.name == "blink" }.available)
            assertEquals("unit-action-locked", assertIs<ActionResult.Rejected>(
                order("blink", target = Position(270.0, 150.0))).error.code)
            val foreign = c.observePlayer(c.world, "p2").units.single { it.unitId == "support" }
            assertNull(foreign.transport)
            assertNull(foreign.unitActions)
            assertIs<ActionResult.Accepted>(order("undeploy"))
            repeat(181) { tick() }
            assertEquals("support_hover", support().typeId)
            assertEquals(listOf("cargo"), support().transport?.passengerIds)
            assertTrue(support().unitActions!!.actions.single { it.name == "blink" }.available)
            repeat(50) { tick() }
            assertEquals("scout_hull", owner().units.single { it.unitId.startsWith("produced:") }.typeId)
            assertEquals(3550L, owner().balance)
        }

    @Test fun air_transport_produces_and_unloads_through_ordinary_player_orders() =
        withContext(listOf(spawn("air", "cargo_air", 180.0), spawn("cargo", "scout_hull", 196.0))) { c ->
            fun owner() = c.observePlayer(c.world, "p1")
            fun cargo() = owner().units.single { it.unitId == "air" }.transport!!
            assertEquals(12, cargo().capacity)
            assertIs<ActionResult.Accepted>(c.submit(LoadAction("load", "p1", "air", "cargo", LoadDirection.UP, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(
                ProduceAction("produce", "p1", "air", "scout_hull", "b".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UnloadAction("unload", "p1", "air", true, c.world)))
            repeat(120) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(cargo().passengerIds.orEmpty().isEmpty())
            assertNull(owner().units.single { it.unitId == "cargo" }.carriedBy)
            assertEquals("scout_hull", owner().units.single { it.unitId.startsWith("produced:") }.typeId)
            assertEquals(3550L, owner().balance)
        }

    @Test fun a_landed_constructor_aims_behind_it_without_rotating_or_moving_its_body() =
        withContext(listOf(spawn("support", "support_landed", 211.0), spawn("ally", "scout_hull", 120.0),
            spawn("attacker", "field_tank", 88.0, "p2"))) { c ->
            fun owner() = c.observePlayer(c.world, "p1")
            val initial = owner().units.single { it.unitId == "support" }
            val nonce = "f".repeat(32)
            val site = Position(171.0, 190.0)
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("rear-site", "p1", "support", "scout_hull", site, nonce, c.world)))
            repeat(135) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val id = "constructed:p1:$nonce"
            assertEquals(1f, owner().units.singleOrNull { it.unitId == id }?.constructionProgress,
                "Rear site $site: ${owner().units.map { it.unitId to it.position }}")
            val landed = owner().units.single { it.unitId == "support" }
            assertEquals(initial.heading, landed.heading)
            assertEquals(initial.position, landed.position)
            assertRepairRaisesHealth(c, "support", "ally", "attacker", 135)
            val repaired = owner().units.single { it.unitId == "support" }
            assertEquals(initial.heading, repaired.heading)
            assertEquals(initial.position, repaired.position)
        }

    @Test fun explicitly_declared_mobile_nano_target_completes_and_accepts_a_move() =
        withContext(listOf(spawn("support", "support_hover", 140.0))) { c ->
            fun owner() = c.observePlayer(c.world, "p1")
            val nonce = "c".repeat(32)
            val id = "constructed:p1:$nonce"
            val site = Position(200.0, 150.0)
            assertIs<ActionResult.Rejected>(c.submit(BuildAction("foreign", "p2", "support", "scout_hull", site, nonce, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("nano", "p1", "support", "scout_hull", site, nonce, c.world)))
            repeat(105) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val completed = owner().units.single { it.unitId == id }
            assertEquals("scout_hull", completed.typeId)
            assertEquals(1f, completed.constructionProgress)
            assertEquals(3550L, owner().balance)
            val waypoints = listOf(Position(264.0, 150.0), Position(208.0, 212.0), Position(208.0, 91.0))
            val orders = waypoints.asSequence().mapIndexed { index, point ->
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("move-new-$index", "p1", id, point, c.world)))
            }
            assertTrue(orders.any { order -> order.events.any { it.kind == "movement-started" } })
            repeat(135) { c.advance(Step(105L + it, 1.0, "sim-tick")) }
            assertNotEquals(completed.position, owner().units.single { it.unitId == id }.position)
        }

    @Test fun four_builders_use_only_their_declared_nano_actions() {
        val declarations = listOf("experimental_spider_work" to "scout_hull", "modular_spider_work" to "field_tank",
            "mech_engineer_work" to "field_tank", "combat_engineer_work" to "scout_hull")
        for ((builder, target) in declarations) {
            withContext(listOf(spawn("worker", builder, 140.0))) { c ->
                val other = if (target == "scout_hull") "field_tank" else "scout_hull"
                val point = Position(200.0, 150.0)
                val nonce = "a".repeat(32)
                val rejected = c.submit(BuildAction("undeclared", "p1", "worker", other, point, nonce, c.world))
                assertEquals("unsupported-construction-type", assertIs<ActionResult.Rejected>(rejected).error.code, builder)
                assertEquals(4000L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("declared", "p1", "worker", target, point, nonce, c.world)))
                repeat(390) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val owner = c.observePlayer(c.world, "p1")
                val built = owner.units.single { it.unitId == "constructed:p1:$nonce" }
                assertEquals(target, built.typeId, builder)
                assertEquals(1f, built.constructionProgress, builder)
                assertEquals(4000L - if (target == "scout_hull") 450L else 350L, owner.balance, builder)
            }
        }
    }

    @Test fun mobile_placement_rejects_visible_occupancy_without_birth_or_debit() =
        withContext(listOf(spawn("worker", "support_landed", 211.0), spawn("occupant", "scout_hull", 130.0))) { c ->
            val order = BuildAction("blocked", "p1", "worker", "scout_hull", Position(131.0, 150.0), "b".repeat(32), c.world)
            assertIs<ActionResult.Accepted>(c.submit(order))
            val events = (0L..129L).flatMap { c.advance(Step(it, 1.0, "sim-tick")).events }
            assertTrue(events.any { it.kind == "construction-unavailable" && it.unitId == "worker" })
            val owner = c.observePlayer(c.world, "p1")
            assertEquals(4000L, owner.balance)
            assertTrue(owner.units.none { it.unitId.startsWith("constructed:") })
        }

    @Test fun possible_building_centers_include_custom_anchor_offsets_before_visibility_checks() {
        val spot = Position(110.0, 170.0)
        val hiddenCenter = Position(168.0, 170.0)
        val shape = BuildingFootprint(-1, 0, 0, 0)
        val anchor = FootprintAnchor(40f, 0f)
        val cells = ConstructionPlacement.footprint(40, 20, 20, 20, hiddenCenter, shape, anchor).cells
        assertContains(cells, 5 to 8)
        val difference = hiddenCenter.x - spot.x
        assertTrue(difference > ConstructionPlacement.centerReach(20, shape.left, shape.right, null))
        assertTrue(difference <= ConstructionPlacement.centerReach(20, shape.left, shape.right, anchor.offsetX))
    }

    @Test fun a_hidden_adjacent_occupant_does_not_change_visible_placement_results() = WaterTestScene.withMap { map ->
        val pack = loadPack()
        val input = AuthorConstructionScene.load("json").copy(map = map,
            unitDefinitions = pack.units,
            units = listOf(UnitSpawn("builder", "p1", "support_landed", Position(394.0, 170.0), 90.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("builder" to 8L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 0L)))
        val hidden = input.copy(units = input.units + UnitSpawn("hidden", "p2", "scout_hull", Position(92.0, 170.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("builder" to 8L, "hidden" to 9L), 10L))
        val runs = listOf(input, hidden).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
        try {
            val target = Position(110.0, 170.0)
            for (c in runs) {
                val owner = c.observePlayer(c.world, "p1")
                assertTrue(owner.vision!!.contains(5, 8))
                assertFalse(owner.vision.contains(4, 8))
                assertTrue(owner.units.none { it.unitId == "hidden" })
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("probe", "p1", "builder", "scout_hull", target,
                    "d".repeat(32), c.world)))
            }
            repeat(124) { step ->
                val samples = runs.map { c ->
                    c.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    val owner = c.observePlayer(c.world, "p1")
                    listOf(owner.balance, owner.units, owner.events, owner.actionResults)
                }
                assertEquals(samples[0], samples[1])
            }
            for (c in runs) {
                val owner = c.observePlayer(c.world, "p1")
                assertEquals(4000L, owner.balance)
                assertTrue(owner.units.none { it.unitId.startsWith("constructed:") })
            }
        } finally { runs.forEach(SimulationContext::close) }
    }

    @Test fun surface_carrier_builds_and_repairs_while_restocking_and_intercepting() = WaterTestScene.withMap { map ->
        val naval = NautilusScene.scenario(map)
        val enemy = NuclearScene.scenario(map)
        val input = naval.copy(
            players = naval.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = loadPack().units + enemy.unitDefinitions,
            units = listOf(naval.units.single().copy(typeId = "support_carrier"),
                UnitSpawn("repairable", "p1", "scout_hull", Position(350.0, 220.0), 90.0),
                UnitSpawn("attacker", "p2", "field_tank", Position(320.0, 220.0), 90.0),
                enemy.units.single().copy(ownerPlayerId = "p2")),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "mother" to 8L, "repairable" to 9L,
                "attacker" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 11000L)))
        val c = NuclearScene.open(input)
        try {
            fun owner() = c.observePlayer(c.world, "p1")
            fun carrier() = owner().units.single { it.unitId == "mother" }
            val nonce = "d".repeat(32)
            val site = Position(349.0, 149.0)
            var step = 0L
            fun advance() = c.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Rejected>(c.submit(UnitAction("foreign-ammo", "p2", "mother", "buildAntiNuke", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("carrier-ammo", "p1", "mother", "buildAntiNuke", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("enemy-ammo", "p2", "silo", "buildNuke", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("carrier-nano", "p1", "mother", "scout_hull", site, nonce, c.world)))
            val constructionId = "constructed:p1:$nonce"
            repeat(180) { advance() }
            val constructed = owner().units.single { it.unitId == constructionId }
            assertEquals(1f, constructed.constructionProgress)
            assertEquals(0, carrier().unitActions?.stockpile)
            assertEquals(23550L, owner().balance)
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("carrier-air", "p1", "mother", "cargo_air", "e".repeat(32), c.world)))
            step = assertRepairRaisesHealth(c, "mother", "repairable", "attacker", step)
            while (step < 3334L) advance()
            assertEquals(1, carrier().unitActions?.stockpile)
            repeat(1350) { advance() }
            val air = owner().units.singleOrNull { it.unitId == "produced:p1:${"e".repeat(32)}" }
            assertEquals("cargo_air", air?.typeId, "Carrier queue: ${owner().factoryQueues}")
            val fired = c.submit(UnitAction("incoming", "p2", "silo", "launchNuke", c.world, target = Position(650.0, 180.0)))
            assertIs<ActionResult.Accepted>(fired, fired.toString())
            val intercepted = (1..1350).flatMap { advance().projectileEvents }.singleOrNull {
                it.kind == "intercepted" && it.projectile.appearance == ProjectileAppearance.NUCLEAR
            }
            assertEquals("mother", intercepted?.interceptorUnitId)
            assertEquals(0, carrier().unitActions?.stockpile)
        } finally { c.close() }
    }

    private fun spawn(id: String, type: String, x: Double, owner: String = "p1") =
        UnitSpawn(id, owner, type, Position(x, 150.0), 90.0)

    private fun assertRepairRaisesHealth(context: SimulationContext, worker: String, ally: String,
                                        attacker: String, start: Long): Long {
        fun hp() = checkNotNull(context.observePlayer(context.world, "p1").units.single { it.unitId == ally }.health)
        var step = start
        fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
        val initial = hp()
        assertIs<ActionResult.Accepted>(context.submit(AttackAction("damage", "p2", attacker, ally, context.world)))
        while (hp() >= initial && step < start + 350) tick()
        val wounded = hp()
        assertTrue(wounded in 1.0..<initial)
        assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-damage", "p2", attacker, context.world)))
        assertIs<ActionResult.Accepted>(context.submit(RepairUnitAction("repair-wound", "p1", worker, ally, context.world)))
        repeat(120) { tick() }
        assertTrue(hp() > wounded)
        return step
    }

    private fun withContext(units: List<UnitSpawn>, action: (SimulationContext) -> Unit) {
        val pack = loadPack()
        val definition = AuthorConstructionScene.load("json").copy(
            unitDefinitions = pack.units, units = units,
            combatContract = CombatContract(12345, -1,
                units.mapIndexed { index, unit -> unit.unitId to (index + 8L) }.toMap(), units.size + 8L),
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 0L)))
        val input = definition.copy(players = definition.players.map { it.copy(control = PlayerControl.LOCAL) })
        val opened = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try { action(context) } finally { context.close() }
    }

    private fun fixture(): Path = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        .resolve("authored-roles/package")

    private fun loadPack(): LoadedContentPack {
        val result = ContentLoader.loadContentPack(fixture())
        return assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
    }
}
