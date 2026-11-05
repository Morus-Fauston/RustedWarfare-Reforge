package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class ThreeFormatEconomySandboxTest {
    @Test
    fun actual_background_preparation_rejects_changed_builder_and_economic_parent_then_reopens_and_cancels() = withOwnInput("economy", "json") { root ->
        SandboxLoader().use { loader ->
            loader.request(root)
            val initial = await(loader).getOrThrow()
            val originals = initial.contentSources.associate { it.path to it.sha256 }
            val appearance = initial.renderResources
            val visuals = PreparedVisualAssets(VisualAssetSelection(), "self-owned", "owned-economic-fixture",
                appearance.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                    unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
                appearance.tileSets.associate { it.supportedLayout() to it.imagePath })
            for (name in listOf("builder.json", "extractor_common.json")) {
                val author = root.resolve("units/$name")
                assertTrue(author in originals)
                val bytes = Files.readAllBytes(author)
                loader.request(root) { Files.writeString(author, Files.readString(author) + "\n "); visuals }
                val failure = assertNotNull(await(loader).exceptionOrNull())
                assertTrue(failure.message.orEmpty().contains("source bytes changed"), failure.toString())
                assertNull(loader.pollPrepared())
                assertEquals(originals, initial.contentSources.associate { it.path to it.sha256 })
                assertEquals(170.0, initial.scenario.unitDefinitions.getValue("builder").maximumHealth)
                assertEquals(800.0, initial.scenario.unitDefinitions.getValue("extractorT1").maximumHealth)
                Files.write(author, bytes)
                loader.request(root)
                assertEquals(originals, await(loader).getOrThrow().contentSources.associate { it.path to it.sha256 })
            }
            loader.request(root)
            loader.cancel()
            assertNull(loader.pollPrepared())
        }
    }

    @Test
    fun all_formats_construct_stop_resume_cooperate_and_reject_an_occupied_site_without_double_charging() {
        for (format in formats) {
            val scene = fixture(load("factory", format), listOf(
                UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0)), 1750L)
            SandboxSession(scene).use { session ->
                session.select(Position(120.0, 170.0))
                assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0)))
                repeat(100) { session.tick() }
                val building = session.observation.units.single { it.typeId == "landFactory" }
                assertTrue(building.constructionProgress in 0f..<1f)
                assertEquals(1050L, session.observation.balance)
                session.select(session.observation.units.single { it.unitId == "a" }.position)
                assertIs<ActionResult.Accepted>(session.stop())
                session.select(Position(120.0, 210.0))
                assertIs<ActionResult.Accepted>(session.command(building.position))
                repeat(100) { session.tick() }
                assertTrue(session.observation.units.single { it.unitId == building.unitId }.constructionProgress > building.constructionProgress)
                session.select(session.observation.units.single { it.unitId == "a" }.position)
                assertIs<ActionResult.Accepted>(session.command(building.position))
                assertTrue(session.observation.units.filter { it.typeId == "builder" }.all { it.tasks.isNotEmpty() })
                advanceUntil(session, 2000) { session.observation.units.single { it.unitId == building.unitId }.constructionProgress == 1f }
                assertEquals(1200.0, session.observation.units.single { it.unitId == building.unitId }.health)
                assertEquals(1050L, session.observation.balance)
                assertNull(session.build("landFactory", building.position))
                assertEquals(1050L, session.observation.balance)
                assertEquals(1, session.observation.units.count { it.typeId == "landFactory" })
            }
        }
    }

    @Test
    fun all_formats_reclaim_repair_and_remove_a_real_building_with_the_existing_ledger() {
        for (format in formats) {
            val scene = fixture(load("maintenance", format), listOf(
                UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)), 1750L)
            SandboxSession(scene).use { session ->
                session.select(Position(120.0, 170.0))
                assertTrue(session.canMaintain)
                assertIs<ActionResult.Accepted>(session.reclaim("factory"))
                repeat(100) { session.tick() }
                assertIs<ActionResult.Accepted>(session.stop())
                assertTrue(session.observation.units.single { it.unitId == "factory" }.health!! < 1200.0)
                assertIs<ActionResult.Accepted>(session.command(Position(190.0, 170.0)))
                repeat(1200) { session.tick() }
                assertEquals(1200.0, session.observation.units.single { it.unitId == "factory" }.health)
                assertIs<ActionResult.Accepted>(session.reclaim("factory"))
                repeat(1500) { session.tick() }
                assertTrue(session.observation.units.none { it.unitId == "factory" })
                assertEquals("worker", session.selectedId)
                assertEquals(2310.0000083446503, session.observation.credits)
                assertNull(session.reclaim("factory"))
                assertEquals(2310.0000083446503, session.observation.credits)
            }
        }
    }

    @Test
    fun all_formats_run_each_existing_economic_upgrade_refund_once_and_accumulate_the_new_income() {
        val types = listOf("extractorT1", "extractorT2", "fabricatorT1", "fabricatorT2")
        for (format in formats) for ((index, type) in types.withIndex()) {
            val spawn = UnitSpawn("economic", "p1", type,
                if (type.startsWith("extractor")) Position(190.0, 290.0) else Position(110.0, 110.0), 0.0)
            val scene = fixture(load("economy", format), listOf(spawn), 10000L)
            val action = if (type.endsWith('1')) "upgrade_t2" else "upgrade_t3"
            val target = type.dropLast(1) + if (type.endsWith('1')) "2" else "3"
            val price = listOf(1400L, 4000L, 4400L, 7500L)[index]
            SandboxSession(scene).use { session ->
                session.select(spawn.position)
                assertIs<ActionResult.Accepted>(session.upgrade(action))
                assertEquals(10000L - price, session.observation.balance)
                assertIs<ActionResult.Accepted>(session.cancelUpgrade(action))
                assertEquals(10000L, session.observation.balance)
                assertIs<ActionResult.Rejected>(session.cancelUpgrade(action))
                assertEquals(10000L, session.observation.balance)
                assertIs<ActionResult.Accepted>(session.upgrade(action))
                advanceUntil(session, 7000) { session.observation.units.single().typeId == target }
                assertTrue(session.selectedUpgradeQueue?.items.orEmpty().isEmpty())
                val before = assertNotNull(session.observation.balance)
                repeat(40) { session.tick() }
                assertEquals(before + listOf(12L, 20L, 7L, 14L)[index], session.observation.balance)
            }
            SandboxSession(scene.copy(economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))).use { session ->
                session.select(spawn.position)
                assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(session.upgrade(action)).error.code)
                assertEquals(0L, session.observation.balance)
                assertTrue(assertNotNull(session.selectedUpgradeQueue).items.isEmpty())
            }
        }
    }

    @Test
    fun all_formats_pay_and_produce_a_real_builder_that_can_be_selected_and_moved() {
        for (format in formats) SandboxSession(load("start", format)).use { session ->
            assertEquals(1750L, session.observation.balance)
            session.select(Position(110.0, 210.0))
            assertEquals("h", session.selectedId)
            assertIs<ActionResult.Accepted>(session.produce())
            assertEquals(1250L, session.observation.balance)
            repeat(500) { session.tick() }
            assertTrue(session.observation.units.none { it.typeId == "builder" })
            session.tick()
            val builder = session.observation.units.single { it.typeId == "builder" }
            assertEquals(170.0, builder.health)
            session.select(builder.position)
            assertEquals(builder.unitId, session.selectedId)
            assertIs<ActionResult.Accepted>(session.move(Position(170.0, 310.0)))
            repeat(100) { session.tick() }
            assertNotEquals(builder.position, session.observation.units.single { it.unitId == builder.unitId }.position)
        }
    }

    private val formats = listOf("ini", "json", "hjson")
    private fun root(family: String, format: String): Path =
        (System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs"))
            .resolve("three-format-economy/$family/$format").toAbsolutePath().normalize()
    private fun load(family: String, format: String) = ControlledScenario.load(root(family, format))

    // Public Scenario configures an explicit test-owned starting layout; all definitions come from the actual format load.
    // No context/world setter or synthetic observation is used after initialization.
    private fun fixture(base: Scenario, units: List<UnitSpawn>, balance: Long) = base.copy(units = units,
        combatContract = CombatContract(1, -1, units.mapIndexed { index, unit -> unit.unitId to index + 1L }.toMap(), units.size + 1L),
        economyContract = EconomyContract(mapOf("p1" to balance, "p2" to 0L)))

    private fun advanceUntil(session: SandboxSession, limit: Int, condition: () -> Boolean) {
        repeat(limit) { if (condition()) return; session.tick() }
        assertTrue(condition(), "Actual session condition was not reached within $limit ticks")
    }

    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        repeat(5000) { loader.pollPrepared()?.let { return it }; Thread.sleep(1) }
        error("Actual background load did not complete")
    }

    private fun withOwnInput(family: String, format: String, block: (Path) -> Unit) {
        val source = root(family, format)
        val owned = Files.createTempDirectory("p008-economic-background-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { files -> files.forEach { original ->
                val target = owned.resolve(source.relativize(original)).toAbsolutePath().normalize()
                require(target.startsWith(owned))
                if (Files.isDirectory(original)) Files.createDirectories(target) else Files.copy(original, target)
            } }
            block(owned)
        } finally { Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
            require(it.toAbsolutePath().normalize().startsWith(owned)); Files.deleteIfExists(it)
        } } }
    }
}
