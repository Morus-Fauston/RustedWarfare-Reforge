package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class AuthorFactoryProductionTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun a_distinct_v2_json_unit_is_bought_from_the_factory_and_hits_with_its_inline_projectile() {
        val source = fixtures.resolve("author-production/json/package")
        val workspace = Files.createTempDirectory("rwr-v2-production-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = workspace.resolve(source.relativize(path)).normalize()
                require(target.startsWith(workspace))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val units = workspace.resolve("units")
            val factory = units.resolve("spfactory.json")
            Files.writeString(factory, Files.readString(factory).replace("builder,c_tank", "builder,amber_scout"))
            val pack = workspace.resolve("pack.json")
            Files.writeString(pack, Files.readString(pack).replace("units/tank.json", "units/amber.json"))
            Files.writeString(units.resolve("amber.json"), """
                {"format":"rw-reforge.unit.v2", "copyFrom":["tank.json"], "core":{"name":"amber_scout"},
                 "weapons":[{"name":"1", "projectile":{"directDamage":41,"life":60,"speed":5}}]}
            """.trimIndent())
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(
                ContentLoader.loadContentPacks(listOf(workspace))).value
            val scene = TestContentFactory.openScenario().copy(unitDefinitions = content.units, units = listOf(
                UnitSpawn("factory", "p1", "spfactory", Position(210.0, 150.0), 0.0),
                UnitSpawn("target", "p2", "builder", Position(280.0, 220.0), 0.0),
            ), combatContract = CombatContract(12345, -1, mapOf("factory" to 8L, "target" to 9L), 10L),
                economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
            val simulation = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(simulation.submit(ProduceAction(
                    "buy", "p1", "factory", "amber_scout", "a".repeat(32), simulation.world)))
                assertEquals(2650L, simulation.observePlayer(simulation.world, "p1").balance)
                val born = "produced:p1:${"a".repeat(32)}"
                var step = 0L
                while (simulation.observe().units.none { it.unitId == born } && step < 1000)
                    simulation.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals("amber_scout", simulation.observe().units.single { it.unitId == born }.typeId)
                val before = assertNotNull(simulation.observe().units.single { it.unitId == "target" }.health)
                assertIs<ActionResult.Accepted>(simulation.submit(AttackAction("aim", "p1", born, "target", simulation.world)))
                var fired = false
                while (step < 1600 && simulation.observe().units.any { it.unitId == "target" && it.health == before }) {
                    val observation = simulation.advance(Step(step++, 1.0, "sim-tick"))
                    fired = fired || observation.events.any { it.kind == "projectile-fired" && it.unitId == born }
                }
                assertTrue(fired)
                assertEquals(before - 41.0, simulation.observe().units.single { it.unitId == "target" }.health)
            } finally { simulation.close() }
        } finally {
            Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(workspace))
                Files.deleteIfExists(path)
            } }
        }
    }

    private fun withFactory(action: (Path) -> Unit) {
        val source = fixtures.resolve("author-production/ini/package")
        val root = Files.createTempDirectory("rwr-author-production-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { paths -> paths.forEach { file ->
                val target = root.resolve(source.relativize(file)).normalize()
                require(target.startsWith(root))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            action(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file ->
                require(file.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(file)
            } }
        }
    }

    @Test fun an_author_factory_under_construction_rejects_production_then_accepts_it_after_completion() = withFactory { root ->
        for (name in listOf("base-constructor.ini", "spbuilder.ini"))
            Files.copy(fixtures.resolve("author-construction/ini/package/units/$name"), root.resolve("units/$name"))
        val worker = root.resolve("units/spbuilder.ini")
        Files.writeString(worker, Files.readString(worker).replace("workshop", "spfactory"))
        val manifest = root.resolve("pack.json")
        Files.writeString(manifest, Files.readString(manifest).replace("\"units\": [",
            "\"units\": [{\"type\":\"constructor\",\"file\":\"units/spbuilder.ini\"},"))
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root))).value
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("author-production/ini/maps/p001-controlled.tmx"))).value
        val scene = TestContentFactory.openScenario().copy(map = map, unitDefinitions = content.units,
            units = listOf(UnitSpawn("worker", "p1", "spbuilder", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "spfactory",
                Position(190.0, 210.0), "a".repeat(32), context.world)))
            var step = 0L
            while (context.observe().units.size == 1 && step < 400) context.advance(Step(step++, 1.0, "sim-tick"))
            val site = context.observe().units.single { it.typeId == "spfactory" }
            assertTrue(site.constructionProgress < 1f)
            assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
            assertEquals("factory-under-construction", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("unfinished", "p1", site.unitId, "c_tank", "b".repeat(32), context.world))).error.code)
            assertTrue(context.observePlayer(context.world, "p1").factoryQueues.single().items.isEmpty())
            while (context.observe().units.single { it.unitId == site.unitId }.constructionProgress < 1f && step < 2000)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(1f, context.observe().units.single { it.unitId == site.unitId }.constructionProgress)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("complete", "p1", site.unitId, "c_tank",
                "b".repeat(32), context.world)))
            assertEquals(1950L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun author_factories_reuse_the_existing_mech_air_and_sea_exit_profiles_with_compatible_products() {
        for ((exit, product, extra) in listOf(
            Triple("CUSTOM_MECH", "c_tank", null),
            Triple("NATIVE_AIR", "helicopter", "helicopter-production/ini/package"),
            Triple("NATIVE_SEA", "spcruiser", "author-water/ini/package"))) withFactory { root ->
            val child = root.resolve("units/spfactory.ini")
            Files.writeString(child, Files.readString(child).replace("builder,c_tank", product).replace("NATIVE_LAND", exit))
            val water = exit == "NATIVE_SEA"
            if (water) {
                val parent = root.resolve("units/factory-base.ini")
                Files.writeString(parent, Files.readString(parent).replace("foundationMovementType=LAND", "foundationMovementType=WATER"))
            }
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(
                listOfNotNull(root, extra?.let(fixtures::resolve)))).value
            val base = TestContentFactory.openScenario()
            val map = if (water) assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
                fixtures.resolve("author-water/ini/maps/naval-shore.tmx"))).value else base.map
            val position = if (water) Position(550.0, 270.0) else Position(210.0, 150.0)
            val scene = base.copy(map = map, unitDefinitions = content.units,
                units = listOf(UnitSpawn("factory", "p1", "spfactory", position, 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene), exit).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy", "p1", "factory", product,
                    "a".repeat(32), context.world)))
                var born: UnitObservation? = null
                var step = 0L
                while (born == null && step < 1000) {
                    val view = context.advance(Step(step++, 1.0, "sim-tick"))
                    if (view.events.any { it.kind == "unit-produced" }) born = view.units.single { it.unitId.startsWith("produced:") }
                }
                assertNotNull(born, exit)
                assertEquals(product, born.typeId)
                assertEquals(when (exit) {
                    "CUSTOM_MECH" -> Position(217.0, 149.0)
                    "NATIVE_AIR" -> Position(210.0, 155.0)
                    else -> Position(550.0, 290.0)
                }, born.position)
                assertEquals(if (exit == "NATIVE_AIR") 2300L else 2650L, context.observePlayer(context.world, "p1").balance)
                assertEquals(1L, context.observe().production!!.lastBirthSerial)
            } finally { context.close() }
        }
    }

    @Test fun a_three_format_author_factory_pays_for_and_produces_two_existing_products_through_the_common_world() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = fixtures.resolve("author-production/$format")
            val result = ContentLoader.loadContentPacks(listOf(root.resolve("package")))
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(result, "$format: $result").value
            val factory = content.units.getValue("spfactory")
            assertEquals(listOf("builder", "c_tank"), assertNotNull(factory.production).unitTypeIds)
            assertEquals(FactoryExitProfile.NATIVE_LAND, factory.production.exitProfile)
            val base = TestContentFactory.openScenario()
            val scene = base.copy(unitDefinitions = content.units,
                units = listOf(UnitSpawn("factory", "p1", "spfactory", Position(210.0, 150.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                for ((index, type) in listOf("builder", "c_tank").withIndex())
                    assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy-$index", "p1", "factory", type,
                        ('a' + index).toString().repeat(32), context.world)))
                assertEquals(2150L, context.observePlayer(context.world, "p1").balance)
                val births = mutableListOf<String>()
                repeat(1100) { step ->
                    births += context.advance(Step(step.toLong(), 1.0, "sim-tick")).events
                        .filter { it.kind == "unit-produced" }.map { checkNotNull(it.unitId) }
                }
                assertEquals(listOf("produced:p1:${"a".repeat(32)}", "produced:p1:${"b".repeat(32)}"), births)
                val produced = context.observe().units.filter { it.unitId in births }
                assertEquals(listOf("builder", "c_tank"), produced.map { it.typeId })
                assertEquals(listOf(170.0, 210.0), produced.map { it.health })
                assertEquals(2L, context.observe().production!!.lastBirthSerial)
                assertTrue(context.observePlayer(context.world, "p1").factoryQueues.single().items.isEmpty())
                assertEquals(2150L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }
}
