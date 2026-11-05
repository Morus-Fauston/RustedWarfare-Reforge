package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

internal object NavalSandboxScene {
    fun load(format: String = "ini", choice: String = "builder"): LoadedSandbox {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val folder = root.resolve("naval-production/$format")
        val result = ContentLoader.loadContentPack(folder.resolve("production-package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
        return SandboxLoader().use { loader ->
            loader.requestPack(folder, ContentPackChoice(pack, choice))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The actual naval production scene did not load")
        }
    }
}

class NavalProductionSandboxTest {
    @Test fun the_fixed_underwater_evidence_target_stays_in_content_but_cannot_be_a_player_start_choice() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val playable = setOf("builder", "seaFactory", "seaFactoryT2", "heavyBattleship", "c_tank", "helicopter")
        for (format in listOf("ini", "json", "hjson")) ContentPackSelectionSession().use { state ->
            state.edit(root.resolve("naval-production/$format/production-package")); state.checkPackage()
            val deadline = System.nanoTime() + 5_000_000_000L
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
            val deadline = System.nanoTime() + 5_000_000_000L
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
