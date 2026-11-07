package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class AuthorFactoryUpgradeTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun withFactory(action: (Path) -> Unit) {
        val source = fixtures.resolve("author-upgrade/ini/package")
        val root = Files.createTempDirectory("rwr-author-upgrade-").toAbsolutePath().normalize()
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

    private fun scene(packageRoot: Path): Scenario {
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(
            ContentLoader.loadContentPacks(listOf(packageRoot))).value
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("author-upgrade/ini/maps/p001-controlled.tmx"))).value
        return TestContentFactory.openScenario().copy(map = map, unitDefinitions = content.units,
            units = listOf(UnitSpawn("factory", "p1", "spfactory", Position(110.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
    }

    @Test fun a_real_post_birth_failure_rolls_back_the_scheduled_upgrade_charge_and_same_step_retry_matches_control() = withFactory { root ->
        val source = root.resolve("units/spfactory.ini")
        Files.writeString(source, Files.readString(source).replace("productionUnits=builder", "productionUnits=c_tank"))
        val base = scene(root)
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("f", "p1", "spfactory", Position(312.0, 210.0), 0.0),
                UnitSpawn("z", "p2", "c_tank", Position(439.0, 240.0), 281.14)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 8L, "z" to 9L), Long.MAX_VALUE - 1L))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first", "p1", "f", "c_tank", "a".repeat(32), context.world)))
                repeat(500) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertTrue(context.observe().projectiles.isEmpty())
                assertEquals(9650L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("scheduled", "p1", "f", "spfactoryt2", context.world, 500L)))
            }
            val failed = contexts[0]
            val before = failed.observe()
            val players = listOf("p1", "p2").map { failed.observePlayer(failed.world, it) }
            assertFailsWith<ArithmeticException> { failed.advance(Step(500L, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            players.forEach { assertSame(it, failed.observePlayer(failed.world, it.playerId)) }
            assertEquals(9650L, failed.observePlayer(failed.world, "p1").balance)
            assertTrue(failed.observePlayer(failed.world, "p1").upgradeQueues.single().items.isEmpty())
            assertEquals(0L, failed.observe().production!!.lastBirthSerial)
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", context.world)))
                context.advance(Step(500L, 1.0, "sim-tick"))
            }
            val actual = failed.observe()
            val control = contexts[1].observe()
            assertEquals(control.units, actual.units)
            assertEquals(control.events, actual.events)
            assertEquals(control.actionResults, actual.actionResults)
            assertEquals(control.combat, actual.combat)
            assertEquals(control.economy!!.balances, actual.economy!!.balances)
            assertEquals(control.economy.queuedProduction, actual.economy.queuedProduction)
            assertEquals(7900L, actual.economy.balances["p1"])
            assertEquals(.004f, failed.observePlayer(failed.world, "p1").upgradeQueues.single().items.single().progress)
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave", "p2", "z", Position(550.0, 240.0), context.world)))
                for (step in 501L..800L) context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals("spfactoryt2", context.observe().units.single { it.unitId == "f" }.typeId)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("after", "p1", "f", "c_tank", "b".repeat(32), context.world)))
                for (step in 801L..1400L) context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(1L, context.observe().production!!.lastBirthSerial)
                assertEquals(7550L, context.observePlayer(context.world, "p1").balance)
                assertEquals(Long.MAX_VALUE - 1L, context.observe().production!!.units.single().objectId)
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun production_upgrade_and_production_share_one_fifo_without_advancing_the_next_head_on_completion() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            scene(fixtures.resolve("author-upgrade/ini/package")))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first", "p1", "factory", "builder", "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("middle", "p1", "factory", "spfactoryt2", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("last", "p1", "factory", "builder", "b".repeat(32), context.world)))
            assertEquals(6900L, context.observePlayer(context.world, "p1").balance)
            var step = 0L
            while (context.observe().production!!.lastBirthSerial == 0L && step < 600L) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(0f, context.observePlayer(context.world, "p1").upgradeQueues.single().items.single().progress)
            }
            assertEquals(501L, step)
            assertEquals("spfactory", context.observe().units.single { it.unitId == "factory" }.typeId)
            assertEquals(listOf("b".repeat(32)), context.observe().economy!!.queuedProduction.getValue("factory").map { it.unitNonce })
            assertEquals(0f, context.observePlayer(context.world, "p1").factoryQueues.single().items.single().progress)
            context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(.004f, context.observePlayer(context.world, "p1").upgradeQueues.single().items.single().progress)
            while (context.observe().units.single { it.unitId == "factory" }.typeId == "spfactory" && step < 900L) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(0f, context.observePlayer(context.world, "p1").factoryQueues.single().items.single().progress)
            }
            assertEquals("spfactoryt2", context.observe().units.single { it.unitId == "factory" }.typeId)
            assertEquals(1, context.observe().events.count { it.kind == "upgrade-completed" })
            assertEquals(1L, context.observe().production!!.lastBirthSerial)
            context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(.002f, context.observePlayer(context.world, "p1").factoryQueues.single().items.single().progress)
            repeat(600) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(2L, context.observe().production!!.lastBirthSerial)
            assertEquals(6900L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observePlayer(context.world, "p1").factoryQueues.single().items.isEmpty())
        } finally { context.close() }
    }

    @Test fun conversion_refunds_a_product_dropped_by_the_target_once_and_keeps_its_nonce_retired() = withFactory { root ->
        val target = root.resolve("units/spfactoryt2.ini")
        Files.writeString(target, Files.readString(target).replace("productionUnits=builder,c_tank", "productionUnits=c_tank"))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(root))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first", "p1", "factory", "builder", "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("middle", "p1", "factory", "spfactoryt2", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("dropped", "p1", "factory", "builder", "b".repeat(32), context.world)))
            assertEquals(6900L, context.observePlayer(context.world, "p1").balance)
            var step = 0L
            while (context.observe().units.single { it.unitId == "factory" }.typeId == "spfactory" && step < 900L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals("spfactoryt2", context.observe().units.single { it.unitId == "factory" }.typeId)
            assertEquals(7400L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observePlayer(context.world, "p1").factoryQueues.single().items.isEmpty())
            assertEquals(1L, context.observe().production!!.lastBirthSerial)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("reuse", "p1", "factory", "c_tank", "b".repeat(32), context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("new", "p1", "factory", "c_tank", "c".repeat(32), context.world)))
            assertEquals(7050L, context.observePlayer(context.world, "p1").balance)
            repeat(600) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(2L, context.observe().production!!.lastBirthSerial)
            assertEquals(7050L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun a_damaged_author_factory_uses_ordinary_health_conversion_even_with_the_sea_exit() = withFactory { root ->
        val base = root.resolve("units/factory-base.ini")
        Files.writeString(base, Files.readString(base).replace("foundationMovementType=LAND", "foundationMovementType=WATER"))
        for (name in listOf("spfactory.ini", "spfactoryt2.ini")) {
            val file = root.resolve("units/$name")
            Files.writeString(file, Files.readString(file).replace("builder,c_tank", "spcruiser")
                .replace("productionUnits=builder", "productionUnits=spcruiser").replace("NATIVE_LAND", "NATIVE_SEA"))
        }
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(
            listOf(root, fixtures.resolve("author-water/ini/package")))).value
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("author-water/ini/maps/naval-shore.tmx"))).value
        val scene = TestContentFactory.openScenario().copy(map = map, unitDefinitions = content.units,
            units = emptyList(), combatContract = CombatContract(12345, -1, emptyMap(), 8L),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
        OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
            val context = host.context
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(SandboxCommandRequest(1L, context.world, 1L,
                "/spawn spfactory 550 270 p1 state=damaged")).status)
            host.advance(Step(0L, 1.0, "sim-tick"))
            val id = assertNotNull(host.receipt(1L)?.unitId)
            assertEquals(600.0, context.observe().units.single().health)
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("paid", "p1", id, "spfactoryt2", context.world)))
            repeat(300) { host.advance(Step(it + 1L, 1.0, "sim-tick")) }
            val converted = context.observe().units.single()
            assertEquals(id, converted.unitId)
            assertEquals("spfactoryt2", converted.typeId)
            assertEquals(900.0, converted.health)
            assertEquals(7900L, context.observePlayer(context.world, "p1").balance)
        }
    }

    @Test fun an_author_factory_pays_2100_and_converts_the_same_identity_before_producing_two_products() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = fixtures.resolve("author-upgrade/$format")
            val loaded = ContentLoader.loadContentPacks(listOf(root.resolve("package")))
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, "$format: $loaded").value
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
                root.resolve("maps/p001-controlled.tmx"))).value
            val scene = TestContentFactory.openScenario().copy(map = map, unitDefinitions = content.units,
                units = listOf(UnitSpawn("factory", "p1", "spfactory", Position(110.0, 210.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val before = context.observe().units.single()
                assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("too-early", "p1", "factory", "c_tank", "a".repeat(32), context.world))).error.code)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "factory",
                    "spfactoryt2", context.world)))
                assertEquals(7900L, context.observePlayer(context.world, "p1").balance)
                var step = 0L
                var completions = 0
                while (context.observe().units.single().typeId == "spfactory" && step < 300L)
                    completions += context.advance(Step(step++, 1.0, "sim-tick")).events.count { it.kind == "upgrade-completed" }
                val converted = context.observe().units.single()
                assertEquals("spfactoryt2", converted.typeId)
                assertEquals(1800.0, converted.health)
                assertEquals(listOf(before.unitId, before.ownerPlayerId, before.position, before.heading),
                    listOf(converted.unitId, converted.ownerPlayerId, converted.position, converted.heading))
                assertEquals(1, completions)
                for ((index, type) in listOf("builder", "c_tank").withIndex())
                    assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy-$index", "p1", "factory", type,
                        ('a' + index).toString().repeat(32), context.world)))
                assertEquals(7050L, context.observePlayer(context.world, "p1").balance)
                val births = mutableListOf<String>()
                repeat(1100) {
                    births += context.advance(Step(step++, 1.0, "sim-tick")).events
                        .filter { it.kind == "unit-produced" }.map { checkNotNull(it.unitId) }
                }
                assertEquals(listOf("produced:p1:${"a".repeat(32)}", "produced:p1:${"b".repeat(32)}"), births)
                assertEquals(listOf("builder", "c_tank"), births.map { id -> context.observe().units.single { it.unitId == id }.typeId })
                assertTrue(context.observePlayer(context.world, "p1").factoryQueues.single().items.isEmpty())
                assertEquals(7050L, context.observePlayer(context.world, "p1").balance)
                assertEquals(2L, context.observe().production!!.lastBirthSerial)
            } finally { context.close() }
        }
    }
}
