package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ThreeFormatProductionSandboxTest {
    @Test
    fun each_format_pays_rejects_insufficient_funds_and_controls_a_real_factory_product() {
        for (format in formats) SandboxSession(load("production", format)).use { session ->
            session.select(Position(310.0, 210.0))
            assertEquals("f", session.selectedId)
            assertEquals(350L, session.productionPrice)
            repeat(5) { assertIs<ActionResult.Accepted>(session.produce()) }
            assertEquals(0L, session.observation.balance)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(session.produce()).error.code)
            assertEquals(5, assertNotNull(session.selectedFactoryQueue).items.size)
            repeat(4) { assertIs<ActionResult.Accepted>(session.cancelProduction()) }
            assertEquals(1400L, session.observation.balance)
            repeat(500) { session.tick() }
            assertTrue(session.observation.units.none { it.unitId.startsWith("produced:") })
            session.tick()
            val born = session.observation.units.single { it.unitId.startsWith("produced:p1:") }
            assertEquals("c_tank", born.typeId)
            assertTrue(assertNotNull(session.selectedFactoryQueue).items.isEmpty())
            session.select(born.position)
            assertEquals(born.unitId, session.selectedId)
            assertIs<ActionResult.Accepted>(session.command(Position(430.0, 210.0)))
            var projectile = false
            repeat(250) { session.tick(); projectile = projectile || session.observation.projectileEvents.any { it.sourceUnitId == born.unitId } }
            assertTrue(projectile, format)
            assertTrue(session.observation.units.any { it.unitId == "b" && (it.health ?: 210.0) < 210.0 }, format)
        }
    }

    @Test
    fun each_format_center_accumulates_native_income_and_produces_a_movable_builder() {
        for (format in formats) SandboxSession(load("center", format)).use { session ->
            assertEquals(1750L, session.observation.balance)
            session.select(Position(110.0, 210.0))
            assertEquals("h", session.selectedId)
            assertEquals("builder", assertNotNull(session.productionAction).typeId)
            assertIs<ActionResult.Accepted>(session.produce())
            assertEquals(1250L, session.observation.balance)
            repeat(500) { session.tick() }
            assertEquals(1475L, session.observation.balance)
            assertTrue(session.observation.units.none { it.typeId == "builder" })
            session.tick()
            val builder = session.observation.units.single { it.typeId == "builder" }
            session.select(builder.position)
            assertIs<ActionResult.Accepted>(session.move(Position(170.0, 310.0)))
            repeat(100) { session.tick() }
            assertNotEquals(builder.position, session.observation.units.single { it.unitId == builder.unitId }.position)
        }
    }

    @Test
    fun each_format_fixed_turret_attacks_an_authorized_target_and_causes_damage() {
        for (format in formats) SandboxSession(load("turret", format)).use { session ->
            session.select(Position(280.0, 150.0))
            assertEquals("t", session.selectedId)
            assertIs<ActionResult.Accepted>(session.command(Position(430.0, 210.0)))
            var projectile = false
            repeat(180) { session.tick(); projectile = projectile || session.observation.projectileEvents.any { it.sourceUnitId == "t" } }
            assertTrue(projectile, format)
            assertTrue(session.observation.units.any { it.unitId == "b" && (it.health ?: 210.0) < 210.0 }, format)
        }
    }

    @Test
    fun each_format_laser_intercepts_real_hostile_shots_through_the_session() {
        for (format in formats) SandboxSession(load("point-defense", format)).use { session ->
            session.select(Position(230.0, 150.0))
            assertEquals("d", session.selectedId)
            var born = false
            var deflected = false
            repeat(120) {
                session.tick()
                born = born || session.observation.projectileEvents.any { it.kind == "born" && it.sourceUnitId?.startsWith("b") == true }
                deflected = deflected || session.observation.projectileEvents.any { it.kind == "deflected" }
            }
            assertTrue(born && deflected, format)
        }
    }

    @Test
    fun prepared_native_sources_survive_as_receipts_and_mid_prepare_changes_do_not_publish() = withOwnProduction { root ->
        SandboxLoader().use { loader ->
            loader.request(root)
            val initial = await(loader).getOrThrow()
            val factory = root.resolve("units/landFactory.json")
            assertTrue(factory in initial.contentSources.map { it.path })
            assertEquals(2, initial.contentSources.size)
            val frozen = initial.contentSources.single { it.path == factory }.sha256
            val appearance = initial.renderResources
            val visuals = PreparedVisualAssets(VisualAssetSelection(), "self-owned", "owned-native-fixture",
                appearance.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                    unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
                mapOf("controlled-strip-2" to appearance.tileSets.single().imagePath))
            loader.request(root) {
                Files.writeString(factory, Files.readString(factory) + "\n ")
                visuals
            }
            val failure = assertNotNull(await(loader).exceptionOrNull())
            assertTrue(failure.message.orEmpty().contains("source bytes changed"), failure.toString())
            assertNull(loader.pollPrepared())
            assertEquals(1200.0, initial.scenario.unitDefinitions.getValue("landFactory").maximumHealth)
            assertEquals(frozen, initial.contentSources.single { it.path == factory }.sha256)
            loader.request(root)
            val reopened = await(loader).getOrThrow()
            assertNotEquals(frozen, reopened.contentSources.single { it.path == factory }.sha256)
        }
    }

    @Test
    fun existing_upgrade_and_construction_variants_consume_the_migrated_author_files() {
        for (family in listOf("upgrade", "construction")) for (format in formats) withOwnInput(family, format) { root ->
            if (family == "upgrade") Files.writeString(root.resolve("upgrade.properties"),
                "format=rw-reforge.upgrade-sandbox.v1\nvariant=two-branches\n")
            SandboxLoader().use { loader ->
                loader.request(root)
                val loaded = await(loader).getOrThrow()
                val names = loaded.contentSources.map { it.path.fileName.toString() }
                assertTrue("tank.$format" in names)
                assertTrue("landFactory.$format" in names)
                assertTrue("turret_t1.$format" in names)
                assertTrue("turret_t2_gun.$format" in names)
                assertTrue("turret_t1_artillery.$format" in names)
                assertTrue("turret_common_land.$format" in names)
                if (family == "construction") {
                    assertTrue("commandCenter.$format" in names)
                    assertTrue("laserDefence.$format" in names)
                    assertEquals(700L, assertNotNull(loaded.scenario.unitDefinitions.getValue("landFactory").construction).price)
                }
                // 003 now captures the real legacy builder in this unchanged 002 fixture too.
                assertEquals(if (family == "construction") setOf("builder.properties") else emptySet(),
                    names.filter { it.endsWith(".properties") }.toSet(), "Only the actual retained builder may use a legacy filename")
            }
        }
    }

    @Test
    fun duplicate_missing_and_invalid_native_authors_fail_without_publication_then_repair_and_cancel() = withOwnProduction { root ->
        val author = root.resolve("units/landFactory.json")
        val extra = root.resolve("units/landFactory.properties")
        require(author.toAbsolutePath().normalize().startsWith(root) && extra.toAbsolutePath().normalize().startsWith(root))
        val original = Files.readAllBytes(author)
        SandboxLoader().use { loader ->
            loader.request(root)
            val initial = await(loader).getOrThrow()
            Files.copy(fixtures().resolve("production-combat/production/units/landFactory.properties"), extra)
            loader.request(root)
            val conflict = assertNotNull(await(loader).exceptionOrNull()).message.orEmpty()
            assertTrue(conflict.contains("IDENTITY_CONFLICT") && conflict.contains("landFactory.json") && conflict.contains("landFactory.properties"), conflict)
            assertNull(loader.pollPrepared())
            Files.delete(extra)
            val text = original.toString(Charsets.UTF_8)
            assertTrue(text.contains("\"maxHp\":1200"))
            Files.writeString(author, text.replace("\"maxHp\":1200", "\"maxHp\":\"bad\""))
            loader.request(root)
            val invalid = assertNotNull(await(loader).exceptionOrNull()).message.orEmpty()
            assertTrue(invalid.contains("MALFORMED_SYNTAX") && invalid.contains("maxHp"), invalid)
            Files.delete(author)
            loader.request(root)
            assertTrue(assertNotNull(await(loader).exceptionOrNull()).message.orEmpty().contains("BAD_REFERENCE"))
            Files.write(author, original)
            loader.request(root)
            assertEquals(1200.0, await(loader).getOrThrow().scenario.unitDefinitions.getValue("landFactory").maximumHealth)
            assertEquals(1200.0, initial.scenario.unitDefinitions.getValue("landFactory").maximumHealth)
            loader.request(root)
            loader.cancel()
            assertNull(loader.pollPrepared())
        }
    }

    private val formats = listOf("json", "hjson", "ini")
    private fun fixtures(): Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs").toAbsolutePath().normalize()
    private fun load(family: String, format: String) = ControlledScenario.load(fixtures().resolve("three-format-production/$family/$format"))
    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        repeat(5000) { loader.pollPrepared()?.let { return it }; Thread.sleep(1) }
        error("Actual background load did not complete")
    }
    private fun withOwnProduction(check: (Path) -> Unit) = withOwnInput("production", "json", check)

    private fun withOwnInput(family: String, format: String, check: (Path) -> Unit) {
        val root = Files.createTempDirectory("p008-native-sandbox-").toAbsolutePath().normalize()
        try {
            val source = fixtures().resolve("three-format-production/$family/$format")
            Files.walk(source).use { paths -> paths.forEach { original ->
                val target = root.resolve(source.relativize(original)).toAbsolutePath().normalize()
                require(target.startsWith(root))
                if (Files.isDirectory(original)) Files.createDirectories(target) else Files.copy(original, target)
            } }
            check(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(owned)
            } }
        }
    }
}
