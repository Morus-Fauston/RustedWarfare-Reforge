package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.math.hypot
import kotlin.test.*

internal object NavalSandboxScene {
    fun load(format: String = "ini", choice: String = "builder"): LoadedSandbox {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val folder = root.resolve("naval-production/$format")
        val result = ContentLoader.loadContentPack(folder.resolve("production-package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
        return SandboxLoader().use { loader ->
            loader.requestPack(folder, ContentPackChoice(pack, choice))
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The actual naval production scene did not load")
        }
    }
}

class NavalProductionSandboxTest {
    @Test fun declared_native_tiers_build_upgrade_and_produce_without_a_battleship_dependency() =
        withDeclaredSeaPack { root, packagePath ->
            val attempt = ContentLoader.loadContentPack(packagePath)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, attempt.toString()).value
            assertTrue("heavyBattleship" !in pack.units)
            val loaded = loadDeclaredSeaChoice(root, pack, "spfrigate")
            val opening = loaded.scenario
            assertEquals("p008-naval-production", opening.scenarioId)
            assertEquals(listOf("builder"), opening.units.map { it.typeId })
            SandboxSession(opening).use { session ->
                fun until(limit: Int, name: String, ready: () -> Boolean) {
                    var count = 0
                    while (count < limit && !ready()) { session.tick(); count++ }
                    assertTrue(ready(), "$name after $count simulation steps")
                }
                session.select(opening.units.single().position)
                assertIs<ActionResult.Accepted>(session.build("seaFactory", Position(450.0, 150.0)))
                until(2100, "native T1 site starts") { session.observation.units.any { it.typeId == "seaFactory" } }
                val site = session.observation.units.single { it.typeId == "seaFactory" }
                assertTrue(site.constructionProgress < 1f)
                session.select(site.position)
                println("Incomplete native berth progress=${site.constructionProgress}, cards=${session.productionActions.map { it.typeId }}")
                assertTrue(session.productionActions.isEmpty())
                assertNull(session.produce("spcorvette"))
                until(3200, "native T1 completion") { session.observation.units.any {
                    it.typeId == "seaFactory" && it.constructionProgress >= 1f
                } }
                val berth = session.observation.units.single { it.typeId == "seaFactory" }
                assertEquals(11000L, session.observation.balance)
                session.select(berth.position)
                assertEquals(listOf("spcorvette"), session.productionActions.map { it.typeId })
                assertNull(session.produce("spfrigate"))
                assertEquals(11000L, session.observation.balance)
                assertIs<ActionResult.Accepted>(session.produce("spcorvette"))
                assertEquals(10650L, session.observation.balance)
                assertEquals(1, session.observation.factoryQueues.single().items.size)
                fun leftBerth(id: String): Boolean = session.observation.units.singleOrNull { it.typeId == id }?.let {
                    hypot(it.position.x - berth.position.x, it.position.y - berth.position.y) > 55.0
                } == true
                until(1850, "T1 product leaves the berth") { leftBerth("spcorvette") }
                val first = session.observation.units.single { it.typeId == "spcorvette" }
                assertTrue(session.observation.factoryQueues.single().items.isEmpty())
                session.select(first.position)
                assertIs<ActionResult.Accepted>(session.move(Position(730.0, 270.0)))
                until(1500, "first product clears the outlet") {
                    session.observation.units.single { it.unitId == first.unitId }.position.x > 630.0
                }
                session.select(berth.position)
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                assertEquals(8650L, session.observation.balance)
                until(3600, "native T2 conversion") { session.observation.units.any {
                    it.unitId == berth.unitId && it.typeId == "seaFactoryT2"
                } }
                session.select(berth.position)
                assertEquals(listOf("spfrigate", "spcorvette"), session.productionActions.map { it.typeId })
                assertIs<ActionResult.Accepted>(session.produce("spfrigate"))
                assertEquals(8200L, session.observation.balance)
                assertEquals(1, session.observation.factoryQueues.single().items.size)
                until(1850, "T2 product leaves the berth") { leftBerth("spfrigate") }
                val second = session.observation.units.single { it.typeId == "spfrigate" }
                assertEquals("p1", second.ownerPlayerId)
                assertTrue(session.observation.factoryQueues.single().items.isEmpty())
                assertEquals(8200L, session.observation.balance)
                println("Native declared products: T1 ${first.position}, T2 ${second.position}, balance=${session.observation.balance}")
            }
        }

    @Test fun native_sea_rules_reject_missing_products_land_products_and_undeclared_queue_entries() {
        for (invalid in listOf("missingBoat", "builder", "spfrigate")) withDeclaredSeaPack(tierOne = invalid) { root, path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(path)), invalid).error
            assertEquals("productionUnits", error.field, invalid)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(path)).value
            val start = assertIs<InitResult.Rejected>(SimulationContext.initialize(nativeSeaScenario(root, pack)), invalid)
            assertEquals("unsupported-production", start.error.code, invalid)
        }
        withDeclaredSeaPack { root, path ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(path)).value
            val started = SimulationContext.initialize(nativeSeaScenario(root, pack))
            val context = assertIs<InitResult.Accepted>(started, started.toString()).context
            try {
                val denied = assertIs<ActionResult.Rejected>(context.submit(ProduceAction("denied", "p1", "f", "spfrigate",
                    "a".repeat(32), context.world)))
                assertEquals(10000L, context.observe().economy?.balances?.get("p1"))
                assertTrue(context.observe().economy?.queuedProduction?.get("f").orEmpty().isEmpty())
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("granted", "p1", "f", "spcorvette",
                    "b".repeat(32), context.world)))
                assertEquals(9650L, context.observe().economy?.balances?.get("p1"))
                val item = context.observe().economy?.queuedProduction?.get("f").orEmpty().single()
                assertEquals("spcorvette", item.typeId)
                assertEquals(350L, item.cost)
                println("T1 rejected the undeclared T2 product with ${denied.error.code}; declared product charged once")
            } finally {
                context.close()
            }
        }
    }

    @Test fun nautilus_conversion_forms_do_not_become_native_sea_production_options() {
        val root = publicInputs().resolve("nautilus-production/json")
        val original = root.resolve("production-package")
        val temp = Files.createTempDirectory("reforge-native-sea-form-")
        try {
            Files.walk(original).use { paths -> paths.forEach { path ->
                val target = temp.resolve(original.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val factory = temp.resolve("units/seaFactoryT2.json")
            val source = Files.readString(factory)
            for (form in listOf("nautilusSubmarineLand", "nautilusSubmarineSurface")) {
                Files.writeString(factory, source.replace("nautilusSubmarine\"", "nautilusSubmarine,$form\""))
                val raw = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(temp)).value
                assertTrue(form in raw.units.getValue("seaFactoryT2").production!!.unitTypeIds)
                val composed = ContentLoader.composeContentPacks(listOf(raw))
                println("Native T2 declaration of $form yielded ${composed.javaClass.simpleName}")
                assertEquals("productionUnits", assertIs<ContentResult.Failure>(composed).error.field)
                val opened = SimulationContext.initialize(nativeSeaScenario(root, raw, factoryType = "seaFactoryT2"))
                assertEquals("unsupported-production", assertIs<InitResult.Rejected>(opened).error.code)
            }
        } finally {
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun the_combined_pack_tank_choice_keeps_the_existing_land_opening() {
        val root = publicInputs()
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("phase-five-sandbox/json/package"))).value
        val builder = pack.units.getValue("builder")
        assertTrue(builder.builder!!.buildingTypeIds.contains("seaFactory"))
        assertTrue(builder.builder!!.buildingTypeIds.size > 1)
        val controlled = root.resolve("content-movement-knowledge/P001-移动沙盒输入")
        assertTrue(Files.isRegularFile(controlled.resolve("maps/p001-controlled.tmx")))
        assertFalse(Files.exists(controlled.resolve("maps/naval-shore.tmx")))
        val loaded = loadDeclaredSeaChoice(controlled, pack, "c_tank")
        assertEquals("p008-content-pack", loaded.scenario.scenarioId)
        assertEquals(listOf("c_tank"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
        SandboxSession(loaded.scenario).use { assertTrue(it.observation.units.any { u -> u.typeId == "c_tank" }) }
    }

    private fun nativeSeaScenario(root: Path, pack: LoadedContentPack, factoryType: String = "seaFactory"): Scenario {
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root.resolve("maps/naval-shore.tmx"))).value
        return Scenario("declared-native-sea", map, pack.units,
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("f", "p1", factoryType, Position(450.0, 150.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(11, -1, mapOf("f" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
    }

    private fun loadDeclaredSeaChoice(root: Path, pack: LoadedContentPack, unitId: String): LoadedSandbox =
        SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, unitId))
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Declared native sea pack did not load")
        }

    private fun withDeclaredSeaPack(tierOne: String = "spcorvette", action: (Path, Path) -> Unit) {
        val inputs = publicInputs()
        val root = inputs.resolve("naval-production/json")
        val native = root.resolve("production-package/units")
        val ships = inputs.resolve("author-naval-chain/json/package/units")
        val dir = Files.createTempDirectory("reforge-native-declared-production-")
        val copied = mutableListOf<Path>()
        fun copy(source: Path, name: String) {
            val target = dir.resolve(name)
            Files.copy(source.resolve(name), target)
            copied.add(target)
        }
        fun write(name: String, value: String) {
            val file = dir.resolve(name)
            if (file !in copied) copied.add(file)
            Files.writeString(file, value)
        }
        try {
            for (name in listOf("sea_factory.png", "sea_factory_t2.png", "sea_factory_dead.png",
                    "builder.json", "builder.png", "builder_dead.png")) copy(native, name)
            for (name in listOf("spcorvette.json", "spfrigate.json", "hull-base.ini", "base.ini",
                    "ship.png", "ship_dead.png", "cannon.png")) copy(ships, name)
            write("seaFactory.json", Files.readString(native.resolve("seaFactory.json")).replace("attackSubmarine", tierOne))
            write("seaFactoryT2.json", Files.readString(native.resolve("seaFactoryT2.json"))
                .replace("heavyBattleship,attackSubmarine", "spfrigate,spcorvette"))
            write("spfrigate.json", Files.readString(dir.resolve("spfrigate.json"))
                .replace("\"name\": \"spfrigate\",", "\"name\": \"spfrigate\", \"techLevel\": 2,"))
            write("pack.json", """{"format":"rw-reforge.content-pack.v1","id":"declared-native-sea",
                "name":"Owned declared native sea test","units":[{"type":"sea-factory","file":"seaFactory.json"},
                {"type":"sea-factory","file":"seaFactoryT2.json"},{"type":"builder","file":"builder.json"},
                {"type":"water-combat","file":"spcorvette.json"},{"type":"water-combat","file":"spfrigate.json"}]}""")
            action(root, dir)
        } finally {
            for (file in copied.asReversed()) Files.deleteIfExists(file)
            Files.delete(dir)
        }
    }

    private fun publicInputs(): Path =
        System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun the_fixed_underwater_evidence_target_stays_in_content_but_cannot_be_a_player_start_choice() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val playable = setOf("builder", "seaFactory", "seaFactoryT2", "heavyBattleship", "c_tank", "helicopter", "attackSubmarine")
        for (format in listOf("ini", "json", "hjson")) ContentPackSelectionSession().use { state ->
            state.edit(root.resolve("naval-production/$format/production-package")); state.checkPackage()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (state.busy && System.nanoTime() < deadline) { state.poll(); Thread.sleep(5) }
            assertFalse(state.busy)
            assertNull(state.error)
            val pack = assertNotNull(state.checked)
            assertTrue("navalUnderwaterTarget" in pack.units)
            assertEquals(playable, state.availableUnits)
            state.enable(true)
            assertEquals(playable, state.availableUnits)
            assertFailsWith<IllegalArgumentException> { state.select("navalUnderwaterTarget") }
            assertFailsWith<IllegalArgumentException> { ContentPackChoice(pack, "navalUnderwaterTarget") }
            for (id in playable) {
                state.select(id)
                assertEquals(id, assertNotNull(state.choice()).unitId)
            }
        }
        val onlyTarget = Files.createTempDirectory("rwr-naval-target-only-")
        val source = root.resolve("naval-production/ini/production-package/units/naval")
        Files.copy(source.resolve("underwaterTarget.ini"), onlyTarget.resolve("underwaterTarget.ini"))
        Files.copy(source.resolve("underwater_target.png"), onlyTarget.resolve("underwater_target.png"))
        Files.writeString(onlyTarget.resolve("pack.json"), """{"format":"rw-reforge.content-pack.v1","id":"owned.target-only","name":"Owned target dependency only","units":[{"type":"underwater-target","file":"underwaterTarget.ini"}]}""")
        ContentPackSelectionSession().use { state ->
            state.edit(onlyTarget); state.checkPackage()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (state.busy && System.nanoTime() < deadline) { state.poll(); Thread.sleep(5) }
            assertFalse(state.busy)
            assertNull(state.error)
            state.enable(true)
            assertTrue(state.availableUnits.isEmpty())
            assertFalse(state.canSelect)
            assertFalse(state.canStart)
            assertNull(state.selectedUnitId)
            assertFailsWith<IllegalStateException> { state.choice() }
        }
    }

    @Test fun all_six_playable_choices_in_the_complete_naval_pack_open_the_same_builder_first_scene() {
        for (format in listOf("ini", "json", "hjson"))
            for (choice in listOf("builder", "seaFactory", "seaFactoryT2", "heavyBattleship", "c_tank", "helicopter")) {
                val loaded = NavalSandboxScene.load(format, choice)
                assertEquals("p008-naval-production", loaded.scenario.scenarioId)
                assertEquals(12000L, loaded.scenario.economyContract!!.initialBalances.getValue("p1"))
                assertEquals(listOf("builder"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
                assertEquals(setOf("c_tank", "helicopter", "navalUnderwaterTarget"),
                    loaded.scenario.units.filter { it.ownerPlayerId == "p2" }.map { it.typeId }.toSet())
                assertEquals(-8.0, loaded.scenario.units.single { it.typeId == "navalUnderwaterTarget" }.height)
                SandboxSession(loaded.scenario).use { session ->
                    assertEquals(4, session.observation.units.size)
                    assertEquals(12000L, session.observation.balance)
                    assertTrue(session.observation.factoryQueues.isEmpty())
                }
            }
    }
}
