package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*
import com.badlogic.gdx.Input

internal object RepairBaySandboxScene {
    fun load(format: String = "json"): LoadedSandbox {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("repairbay-production/$format")
        val content = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(root.resolve("normal-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(content, "commandCenter"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var result: LoadedSandbox? = null
            while (result == null && System.nanoTime() < deadline) {
                result = loader.pollPrepared()?.getOrThrow()
                if (result == null) Thread.sleep(5)
            }
            assertNotNull(result)
        }
    }
}

class RepairBaySandboxTest {
    @Test fun the_native_bay_exposes_body_death_and_selection_range_without_a_weapon() {
        val scene = RepairBaySandboxScene.load().scenario
        val bay = scene.unitDefinitions.getValue("repairbay")
        val appearance = SandboxRenderResources.from(scene).units.getValue("repairbay")
        assertEquals(bay.imagePath, appearance.imagePath)
        assertEquals(bay.durability!!.deadImagePath, appearance.deadImagePath)
        assertNull(appearance.turretImagePath)
        assertEquals(230.0, appearance.repairRange)
    }

    @Test fun explicit_repair_uses_an_owned_target_press_and_cancels_stale_selection_and_modes() {
        for (change in listOf("none", "selection", "mode", "viewport", "escape")) {
            val base = RepairBaySandboxScene.load().scenario
            val scene = base.copy(units = listOf(
                UnitSpawn("bay", "p1", "repairbay", Position(110.0, 210.0), 270.0),
                UnitSpawn("worker", "p1", "builder", Position(170.0, 210.0), 90.0)),
                combatContract = CombatContract(1, -1, mapOf("bay" to 1L, "worker" to 2L), 3L))
            SandboxSession(scene).use { session ->
                session.select(Position(110.0, 210.0))
                session.reclaim("worker"); session.tick(); session.stop()
                assertTrue(session.actionMenu.canRepair)
                assertTrue(session.actionMenu.repairTooltip.contains("unit"))
                assertTrue(session.selectedWorkerQueueText!!.contains("Idle"))
                val map = MapProjection(600.0, 400.0)
                val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                    { true }, { false }, { false }, insideViewport = { x, _ -> x < 650 })
                val x = map.x(170.0).toInt(); val y = map.y(210.0).toInt()
                assertTrue(input.beginRepair())
                assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
                assertTrue(session.observation.units.single { it.unitId == "bay" }.tasks.isEmpty())
                when (change) {
                    "selection" -> { session.select(Position(170.0, 210.0)); session.select(Position(110.0, 210.0)) }
                    "mode" -> assertTrue(input.beginReclaim())
                    "viewport" -> { input.touchDragged(700, y, 0); input.touchDragged(x, y, 0) }
                    "escape" -> assertTrue(input.keyDown(Input.Keys.ESCAPE))
                }
                input.touchUp(x, y, 0, Input.Buttons.LEFT)
                if (change != "none") {
                    assertTrue(session.observation.units.single { it.unitId == "bay" }.tasks.isEmpty(), change)
                    assertTrue(input.beginRepair())
                    input.touchDown(x, y, 0, Input.Buttons.LEFT)
                    input.touchUp(x, y, 0, Input.Buttons.LEFT)
                }
                val task = session.observation.units.single { it.unitId == "bay" }.tasks.single()
                assertEquals(UnitTaskKind.CONSTRUCT, task.kind)
                assertEquals("worker", task.targetUnitId)
                assertFalse(task.automaticRepair)
                assertFalse(input.repairMode)
                assertEquals("Tasks 1\nRepair Builder", session.selectedWorkerQueueText)
                session.tick()
                assertEquals(169.17999267578125, session.observation.units.single { it.unitId == "worker" }.health)
            }
        }
    }

    @Test fun three_formats_start_with_command_center_and_normally_build_a_paid_usable_repair_bay() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = RepairBaySandboxScene.load(format)
            assertEquals("p009-repairbay-production", loaded.scenario.scenarioId)
            assertEquals(listOf("commandCenter"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            assertEquals(16000L, loaded.scenario.economyContract!!.initialBalances.getValue("p1"))
            assertTrue(NativeActionPanel.supports(loaded.scenario.scenarioId))
            SandboxSession(loaded.scenario).use { session ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format: ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600) { session.observation.units.any { it.typeId == "builder" } }
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                assertEquals(1500L, session.constructionActions.single { it.typeId == "repairbay" }.price)
                assertIs<ActionResult.Accepted>(session.build("repairbay", Position(270.0, 150.0)))
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                assertEquals("Tasks 1\nBuild Repair bay", session.selectedWorkerQueueText)
                until(1800) { session.observation.units.any { it.typeId == "repairbay" && it.constructionProgress == 1f } }
                session.select(Position(270.0, 150.0))
                assertEquals("Repair bay", session.actionMenu.selectedTitle)
                assertTrue(session.canMaintain)
                val worker = session.observation.units.single { it.typeId == "builder" }
                assertIs<ActionResult.Accepted>(session.reclaim(worker.unitId))
                assertEquals("Tasks 1\nReclaim Builder", session.selectedWorkerQueueText)
                session.tick()
                assertEquals(168.97999572753906, session.observation.units.single { it.unitId == worker.unitId }.health)
                assertIs<ActionResult.Accepted>(session.stop())
                assertIs<ActionResult.Accepted>(session.command(worker.position))
                assertEquals("Tasks 1\nRepair Builder", session.selectedWorkerQueueText)
                session.tick()
                assertTrue(session.observation.units.single { it.unitId == worker.unitId }.health!! > 168.97999572753906)
            }
        }
    }
}
