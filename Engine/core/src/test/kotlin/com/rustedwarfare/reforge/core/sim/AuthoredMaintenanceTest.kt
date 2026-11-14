package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredMaintenanceTest {
    @TempDir lateinit var temporary: Path

    @Test fun a_built_income_source_credits_its_declared_period_only_after_completion() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val source = fixtures.resolve("author-construction/json/package")
        Files.walk(source).use { input -> input.forEach { file ->
            val output = temporary.resolve(source.relativize(file))
            if (Files.isDirectory(file)) Files.createDirectories(output) else Files.copy(file, output)
        } }
        val workshop = temporary.resolve("units/workshop.json")
        val document = org.hjson.JsonValue.readJSON(Files.readString(workshop)).asObject()
            .set("incomePeriod", 7).set("incomeCredits", 3)
        Files.writeString(workshop, document.toString())
        val loaded = ContentLoader.loadContentPack(temporary)
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
        val builder = "spbuilder"
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("author-production/ini/maps/p001-controlled.tmx"))).value
        val base = TestContentFactory.openScenario().copy(map = map, unitDefinitions = definitions,
            units = listOf(UnitSpawn("worker", "p1", builder, Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base)).context
        try {
            var frame = 0L
            fun advance() = context.advance(Step(frame++, 1.0, "sim-tick"))
            fun balance() = context.observePlayer(context.world, "p1").balance
            val nonce = "e".repeat(32)
            val id = "constructed:p1:$nonce"
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("start", "p1", "worker", "workshop",
                Position(190.0, 210.0), nonce, context.world)))
            advance()
            while (context.observe().units.none { it.unitId == id && it.constructionProgress == 1f } && frame < 1200) {
                if (context.observe().units.any { it.unitId == id }) assertEquals(2300L, balance())
                advance()
            }
            assertEquals(1f, context.observe().units.single { it.unitId == id }.constructionProgress)
            assertEquals(1f, context.observe().incomeStates.single { it.unitId == id }.elapsed)
            repeat(5) { advance(); assertEquals(2300L, balance()) }
            advance()
            assertEquals(2303L, balance())
            assertEquals(0L, context.observePlayer(context.world, "p2").balance)
        } finally { context.close() }
        document.remove("incomeCredits")
        Files.writeString(workshop, document.toString())
        val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary)).error
        assertEquals("incomeCredits", error.field)
        document.set("incomePeriod", 20000000).set("incomeCredits", 3)
        Files.writeString(workshop, document.toString())
        val outOfRange = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary)).error
        assertEquals("incomePeriod", outOfRange.field)
    }

    @Test fun configured_constructor_repairs_actual_damage_and_reclaims_once_with_owner_permission() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (name in listOf("base-constructor.ini", "builder.png", "builder_dead.png"))
            Files.copy(fixtures.resolve("author-construction/json/package/units/$name"), temporary.resolve(name))
        Files.writeString(temporary.resolve("caretaker.json"), """{
            "format":"rw-reforge.constructor.v1", "copyFrom":["base-constructor.ini"], "name":"caretaker",
            "constructionTypes":"workshop","canRepairUnits":true,"canRepairBuildings":false,
            "canReclaim":true,"autoRepair":false,"nanoRange":95,"nanoRepairSpeed":1
        }""")
        val workshop = fixtures.resolve("author-construction/json/package/units")
        for (name in listOf("workshop.json", "workshop.png", "workshop_back.png", "workshop_dead.png")) {
            val source = workshop.resolve(name)
            Files.copy(source, temporary.resolve(name))
        }
        Files.writeString(temporary.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1","id":"maintenance-demo","name":"Maintenance demo",
            "units":[{"type":"constructor","file":"caretaker.json"},{"type":"factory","file":"workshop.json"}]
        }""")
        val result = ContentLoader.loadContentPack(temporary)
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value.units
        val worker = definitions.getValue("caretaker")
        val huge = UnitDefinition("huge", "CustomUnitMetadata", 6.0, 6.0, 1, worker.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(),
            durability = DurabilityProfile(1e40, null), reclaimPrice = 100)
        val target = UnitDefinition("crate", "CustomUnitMetadata", 6.0, 6.0, 1, worker.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(),
            durability = DurabilityProfile(120.0, null), reclaimPrice = 80)
        val gun = UnitDefinition("cannon", "CustomUnitMetadata", 5.0, 5.0, 1, worker.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), fogOfWarSightRange = 6,
            combat = CombatProfile(800.0, 85.0, 800.0, 0.0, 15.0, 15.0, ProjectileProfile(30.0, 50.0, 10.0,
                instant = true), null, null, 1.0))
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("author-production/ini/maps/p001-controlled.tmx"))).value
        val spawns = listOf(UnitSpawn("worker", "p1", worker.id, Position(110.0, 180.0), 90.0),
            UnitSpawn("own", "p1", target.id, Position(171.0, 180.0), 90.0),
            UnitSpawn("enemy", "p2", gun.id, Position(230.0, 180.0), 270.0),
            UnitSpawn("factory", "p1", "workshop", Position(255.0, 255.0), 90.0))
        val scenario = TestContentFactory.openScenario().copy(map = map,
            unitDefinitions = definitions + mapOf(target.id to target, gun.id to gun, huge.id to huge), units = spawns + UnitSpawn("huge", "p1", huge.id, Position(150.0, 270.0), 90.0),
            players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.LOCAL, "red")),
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, unit -> unit.unitId to (8L + i) }.toMap() + ("huge" to 12L), 13L),
            economyContract = EconomyContract(mapOf("p1" to 2200L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            fun hp() = context.observe().units.single { it.unitId == "own" }.health
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("damage", "p2", "enemy", "own", context.world)))
            repeat(10) { if (hp() == 120.0) advance() }
            assertEquals(90.0, hp())
            val blocked = assertIs<ActionResult.Rejected>(context.submit(ReclaimUnitAction(
                "oversized", "p1", "worker", "huge", context.world)))
            assertEquals("maintenance-rate-too-small", blocked.error.code)
            assertEquals(1e40, advance().units.single { it.unitId == "huge" }.health)
            assertIs<ActionResult.Rejected>(context.submit(RepairUnitAction("enemy", "p1", "worker", "enemy", context.world)))
            assertIs<ActionResult.Rejected>(context.submit(RepairUnitAction("building", "p1", "worker", "factory", context.world)))
            repeat(45) { advance() }
            assertEquals(90.0, hp())
            assertIs<ActionResult.Accepted>(context.submit(RepairUnitAction("repair", "p1", "worker", "own", context.world)))
            repeat(105) { if (hp() != 120.0) advance() }
            assertEquals(120.0, hp())
            assertEquals(2200L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Rejected>(context.submit(ReclaimUnitAction("foreign-reclaim", "p2", "worker", "own", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("reclaim", "p1", "worker", "own", context.world)))
            val events = (1..120).flatMap { advance().events }
            assertTrue(context.observe().units.none { it.unitId == "own" })
            assertEquals(1, events.count { it.kind == "building-reclaimed" && it.unitId == "own" })
            assertEquals(2280L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Rejected>(context.submit(ReclaimUnitAction("repeat", "p1", "worker", "own", context.world)))
        } finally { context.close() }

        val caretakerFile = temporary.resolve("caretaker.json")
        val caretaker = org.hjson.JsonValue.readJSON(Files.readString(caretakerFile)).asObject()
            .set("nanoRepairSpeed", 0.000001)
        Files.writeString(caretakerFile, caretaker.toString())
        val tinyRate = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(temporary)).value.units.getValue(worker.id)
        val tinyContext = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            scenario.copy(unitDefinitions = scenario.unitDefinitions + (worker.id to tinyRate)))).context
        try {
            var step = 0L
            fun advance() = tinyContext.advance(Step(step++, 1.0, "sim-tick"))
            fun hp() = checkNotNull(tinyContext.observe().units.single { it.unitId == "own" }.health)
            assertIs<ActionResult.Accepted>(tinyContext.submit(
                AttackAction("damage", "p2", "enemy", "own", tinyContext.world)))
            repeat(10) { if (hp() == 120.0) advance() }
            assertEquals(90.0, hp())
            assertIs<ActionResult.Accepted>(tinyContext.submit(
                RepairUnitAction("tiny-repair", "p1", "worker", "own", tinyContext.world)))
            repeat(45) { advance() }
            assertTrue(hp() > 90.0 && hp() < 91.0)
        } finally { tinyContext.close() }
    }
}
