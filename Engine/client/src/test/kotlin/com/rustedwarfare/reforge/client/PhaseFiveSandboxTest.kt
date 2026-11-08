package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class PhaseFiveSandboxTest {
    private val publicRoot: Path get() = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).parent

    @Test fun the_combined_pack_rejects_unaffordable_production_and_cross_terrain_construction_without_changing_the_world() {
        val choice = TestScenarioChoice.open(publicRoot.resolve("scenarios/phase-five-json.json"))
        val scarce = choice.scenario.copy(economyContract = EconomyContract(mapOf("p1" to 499L, "p2" to 0L)))
        SandboxSession(scarce).use { session ->
            session.select(Position(110.0, 150.0))
            val before = session.observation
            val rejected = assertIs<ActionResult.Rejected>(session.produce("builder"))
            assertEquals("insufficient-funds", rejected.error.code)
            assertEquals(before.units, session.observation.units)
            assertEquals(before.balance, session.observation.balance)
            assertEquals(before.credits, session.observation.credits)
            assertTrue(session.selectedFactoryQueue?.items.orEmpty().isEmpty())
        }
        SandboxSession(choice.scenario).use { session ->
            session.select(Position(110.0, 150.0))
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            repeat(800) { session.tick() }
            session.select(Position(109.0, 240.0))
            val before = session.observation
            for ((type, position) in listOf("seaFactory" to Position(250.0, 430.0),
                    "landFactory" to Position(850.0, 150.0), "airFactory" to Position(850.0, 430.0))) {
                assertEquals(PlacementState.BLOCKED, session.previewConstruction(type, position)?.state)
                assertNull(session.build(type, position))
                assertEquals(before, session.observation)
                assertEquals("Cannot build here", session.playerFeedback)
            }
        }
    }

    @Test fun a_changed_combined_resource_prevents_publication_and_repair_then_restart_produces_a_fresh_center_world() {
        val seed = publicRoot.resolve("scenarios/phase-five-mixed.json")
        val source = publicRoot.resolve("inputs/phase-five-sandbox/mixed")
        val input = Files.createTempDirectory("owned-phase-five-repair-").toAbsolutePath().normalize()
        Files.walk(source).use { paths -> paths.forEach { path ->
            val target = input.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        val choice = TestScenarioChoice.open(seed, input)
        val image = input.resolve("package/units/commandCenter/base.png")
        val original = Files.readAllBytes(image)
        Files.write(image, byteArrayOf(1))
        SandboxLoader().use { loader ->
            loader.requestTestScenario(choice)
            val failure = await(loader)
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("TEST_SCENE_CHANGED"))
            assertNull(loader.pollPrepared(), "A failed preparation cannot later publish a partial world")
            Files.write(image, original)
            loader.requestTestScenario(TestScenarioChoice.open(seed, input))
            val repaired = await(loader).getOrThrow()
            SandboxSession(repaired.scenario).use { session ->
                session.select(Position(110.0, 150.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                repeat(800) { session.tick() }
                assertEquals(2, session.observation.units.count { it.ownerPlayerId == "p1" })
            }
            loader.requestTestScenario(TestScenarioChoice.open(seed, input))
            SandboxSession(await(loader).getOrThrow().scenario).use { fresh ->
                assertEquals(listOf("commandCenter"), fresh.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
                assertEquals(30000L, fresh.observation.balance)
                assertEquals(30000.0, fresh.observation.credits)
            }
        }
    }

    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it }
            Thread.sleep(2)
        }
        error("Combined sandbox preparation did not finish")
    }

    @Test fun all_five_normally_produced_representatives_move_stop_and_fire_in_the_paired_mixed_target_world() {
        val expected = setOf("c_tank", "mechGun", "mechHeavyMissile", "helicopter", "heavyBattleship")
        for (format in listOf("ini", "json", "hjson", "mixed")) {
            val seed = publicRoot.resolve("scenarios/phase-five-$format.json")
            val choice = TestScenarioChoice.open(seed)
            val plan = com.badlogic.gdx.utils.JsonReader().parse(java.nio.file.Files.readString(seed))
            val fired = mutableSetOf<String>()
            val stopped = mutableSetOf<String>()
            SandboxSession(choice.scenario).use { session ->
                for (intent in plan.get("intents")) {
                    fun position() = intent.get("position").let { Position(it.getDouble("x"), it.getDouble("y")) }
                    val kind = intent.getString("kind")
                    val selectedType = session.observation.units.firstOrNull { it.unitId == session.selectedId }?.typeId
                    val result = when (kind) {
                        "select" -> { session.select(position()); null }
                        "move" -> session.move(position())
                        "produce" -> session.produce(intent.getString("typeId"))
                        "build" -> session.build(intent.getString("typeId"), position())
                        "upgrade" -> session.upgrade(intent.getString("upgradeId"))
                        "stop" -> session.stop()
                        "attack" -> {
                            assertEquals(intent.getString("targetUnitId"), session.commandTargetAt(position()))
                            session.command(position())
                        }
                        else -> error("Unexpected paired intent")
                    }
                    if (kind != "select") assertIs<ActionResult.Accepted>(result, "$format/${intent.getString("intentId")}")
                    if (kind == "stop") stopped += requireNotNull(selectedType)
                    repeat(intent.getInt("waitSteps", 0)) {
                        session.tick()
                        val sample = session.observation
                        for (event in sample.projectileEvents.filter { it.kind == "born" }) {
                            val source = sample.units.firstOrNull { it.unitId == event.sourceUnitId }
                            if (source?.ownerPlayerId == "p1") fired += source.typeId
                        }
                    }
                }
                assertEquals(expected, stopped, "$format five actual Stop calls")
                val diagnosis = session.observation.units.filter { it.typeId == "mechHeavyMissile" || it.unitId == "airTarget" }
                    .joinToString { "${it.unitId}: alive=${it.alive}, position=${it.position}, height=${it.height}, target=${it.attackTargetId}, tasks=${it.tasks}" }
                assertTrue(fired.containsAll(expected), "$format missing actual projectile births: ${expected - fired}; $diagnosis")
                for (id in listOf("tankTarget", "mechTarget", "helicopterTarget", "airTarget"))
                    assertFalse(session.observation.units.single { it.unitId == id }.alive, "$format/$id must take real combat damage")
                assertEquals(13550.0, session.observation.credits)
            }
        }
    }

    @Test fun the_paired_ordinary_chain_constructs_and_upgrades_four_factories_then_keeps_all_five_products_in_one_world() {
        for (format in listOf("ini", "json", "hjson", "mixed")) {
            val seed = publicRoot.resolve("scenarios/phase-five-$format.json")
            val choice = TestScenarioChoice.open(seed)
            val plan = com.badlogic.gdx.utils.JsonReader().parse(java.nio.file.Files.readString(seed))
            SandboxSession(choice.scenario).use { session ->
                val factoryIds = mutableMapOf<String, String>()
                var steps = 0
                for (intent in plan.get("intents")) {
                    fun position() = intent.get("position").let { Position(it.getDouble("x"), it.getDouble("y")) }
                    val result = when (intent.getString("kind")) {
                        "select" -> { session.select(position()); null }
                        "move" -> session.move(position())
                        "produce" -> session.produce(intent.getString("typeId"))
                        "build" -> session.build(intent.getString("typeId"), position())
                        "upgrade" -> session.upgrade(intent.getString("upgradeId"))
                        else -> error("Unexpected production intent")
                    }
                    if (intent.getString("kind") != "select") assertIs<ActionResult.Accepted>(result, "$format/${intent.getString("intentId")}")
                    repeat(intent.getInt("waitSteps", 0)) { session.tick() }
                    steps += intent.getInt("waitSteps", 0)
                    session.observation.units.filter { it.ownerPlayerId == "p1" && it.typeId in setOf("mechFactory", "seaFactory") }
                        .forEach { factoryIds[it.typeId] = it.unitId }
                    if (steps == 15000) break
                }
                assertEquals(15000, steps)
                val owned = session.observation.units.filter { it.ownerPlayerId == "p1" && it.alive }
                for (type in listOf("c_tank", "mechGun", "mechHeavyMissile", "helicopter", "heavyBattleship"))
                    assertEquals(1, owned.count { it.typeId == type }, "$format/$type must be normally produced")
                assertEquals(factoryIds.getValue("mechFactory"), owned.single { it.typeId == "mechFactoryT2" }.unitId)
                assertEquals(factoryIds.getValue("seaFactory"), owned.single { it.typeId == "seaFactoryT2" }.unitId)
                assertEquals(10400L, session.observation.balance)
                assertEquals(10400.0, session.observation.credits)
                assertEquals(11, owned.size)
            }
        }
    }

    @Test fun the_three_formats_and_mixed_pack_start_with_a_center_and_normally_produce_the_builder() {
        for (format in listOf("ini", "json", "hjson", "mixed")) {
            val choice = TestScenarioChoice.open(publicRoot.resolve("scenarios/phase-five-$format.json"))
            assertEquals("commandCenter", choice.content.unitId)
            assertEquals(setOf("commandCenter", "builder", "landFactory", "mechFactory", "mechFactoryT2",
                "airFactory", "seaFactory", "seaFactoryT2", "c_tank", "mechGun", "mechHeavyMissile", "helicopter", "heavyBattleship"),
                choice.content.content.units.keys)
            choice.verifyStable()
            SandboxSession(choice.scenario).use { session ->
                assertEquals(listOf("commandCenter"), session.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
                session.select(Position(110.0, 150.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                repeat(800) { session.tick() }
                val builder = session.observation.units.single { it.ownerPlayerId == "p1" && it.typeId == "builder" }
                assertTrue(builder.alive)
                assertEquals(29860L, session.observation.balance)
                session.select(Position(109.0, 240.0))
                assertEquals(builder.unitId, session.selectedId)
                assertEquals(setOf("landFactory", "mechFactory", "airFactory", "seaFactory"), session.constructionActions.map { it.typeId }.toSet())
            }
        }
    }
}
