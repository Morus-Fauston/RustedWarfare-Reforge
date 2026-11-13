package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class MechEngineerSandboxTest {
    @Test fun engineer_four_leg_art_uses_its_own_vertical_crop_and_integer_half_width() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/ini")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        val base = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
        val scene = base.copy(units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L))
        val resources = SandboxRenderResources.from(scene)
        SandboxSession(scene).use { session ->
            session.tick()
            for (scale in listOf(1.0, .5)) {
                val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0 * scale, 400.0 * scale))
                val legs = MechPresentation.project(session.observation, projection, resources).single().legs
                assertEquals(4, legs.size)
                for (leg in legs) {
                    val shaft = assertNotNull(leg.shaft)
                    // Original 19x82 image: half height 41, rounded sqrt(11²+11²) = 16.
                    assertEquals(25, shaft.sourceY)
                    assertEquals(32, shaft.sourceHeight)
                    assertEquals(82, shaft.sourceImageHeight)
                    assertEquals((18.0 * scale).toFloat(), shaft.width)
                    assertEquals((32.0 * scale).toFloat(), shaft.height)
                }
            }
        }
    }

    @Test fun mobile_project_preview_and_payment_reject_other_bodies_strictly_inside_the_native_radius() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/ini")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        val base = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
        for ((blockerX, blocked) in listOf(210.0 to true, 232.999 to true, 233.0 to false)) {
            val scene = base.copy(units = listOf(
                UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0),
                UnitSpawn("blocker", "p1", "builder", Position(blockerX, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "blocker" to 9L), 10L))
            SandboxSession(scene).use { session ->
                session.select(Position(110.0, 210.0))
                assertEquals(if (blocked) PlacementState.BLOCKED else PlacementState.CLEAR,
                    session.previewConstruction("mechGun", Position(210.0, 210.0))?.state, "$blockerX")
                assertEquals(PlacementState.CLEAR,
                    session.previewConstruction("mechGun", Position(110.0, 210.0))?.state, "Ignore only the creating engineer")
            }
            val opened = SimulationContext.initialize(scene)
            val c = assertIs<InitResult.Accepted>(opened, opened.toString()).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "engineer", "mechGun",
                    Position(210.0, 210.0), "f".repeat(32), c.world)))
                c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (blocked) 2 else 3, c.observe().units.size, "$blockerX")
                assertEquals(if (blocked) 16000L else 15400L, c.observePlayer(c.world, "p1").balance)
                assertEquals(!blocked, c.observe().construction!!.nonces["p1"]?.contains("f".repeat(32)) == true)
            } finally { c.close() }
        }
    }

    @Test fun active_nano_line_uses_only_authorized_work_target_and_named_muzzle_then_clears_on_stop() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/ini")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        val base = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
        val scene = base.copy(units = listOf(
            UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0),
            UnitSpawn("target", "p1", "builder", Position(170.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L), 10L))
        val resources = SandboxRenderResources.from(scene)
        SandboxSession(scene).use { session ->
            assertTrue(resources.nanoBeams(session.observation).isEmpty())
            session.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(session.reclaim("target"))
            session.tick()
            val view = session.observation
            val worker = view.units.single { it.unitId == "engineer" }
            assertEquals("target", worker.constructionTargetId)
            val muzzle = view.weapons.single { it.unitId == "engineer" && it.weaponName == "nanoTurret" }.muzzle
            val beam = resources.nanoBeams(view).single()
            assertEquals(Position(muzzle.x, muzzle.y - worker.height), beam.start)
            assertEquals(Position(170.0, 210.0), beam.end)
            assertIs<ActionResult.Accepted>(session.stop())
            assertTrue(resources.nanoBeams(session.observation).isEmpty())
        }
    }

    @Test fun normal_paid_engineer_chain_uses_a_visible_body_icon_and_the_original_prices() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
            val scene = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
            val appearance = SandboxRenderResources.from(scene).units.getValue("mechEngineer")
            val bodyIcon = pack.units.getValue("mechEngineer").combat!!.turretImagePath
            assertEquals("body", appearance.iconWeaponName)
            assertEquals(bodyIcon, appearance.weaponImages.getValue(checkNotNull(appearance.iconWeaponName)).imagePath)
            assertEquals("blank.png", appearance.imagePath.fileName.toString())
            SandboxSession(scene).use { session ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format: ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600) { session.observation.units.any { it.typeId == "builder" } }
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                assertEquals(1000L, session.constructionActions.single().price)
                assertIs<ActionResult.Accepted>(session.build("mechFactory", Position(270.0, 150.0)))
                until(1800) { session.observation.units.any { it.typeId == "mechFactory" && it.constructionProgress == 1f } }
                session.select(Position(270.0, 150.0))
                assertEquals(4000L, session.actionMenu.upgrades.single().price)
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                until(2600) { session.observation.units.any { it.typeId == "mechFactoryT2" } }
                val card = session.productionActions.single { it.typeId == "mechEngineer" }
                assertEquals("Engineer mech", card.title)
                assertEquals(5000L, card.price)
                assertEquals(bodyIcon, card.imagePath)
                assertIs<ActionResult.Accepted>(session.produce("mechEngineer"))
                until(1400) { session.observation.units.any { it.typeId == "mechEngineer" } }
                val engineer = session.observation.units.single { it.typeId == "mechEngineer" }
                assertEquals(2000.0, engineer.health)
                assertEquals(4, engineer.mechPose!!.legs.size)
                session.select(engineer.position)
                assertEquals(listOf("repairbay", "mechFactory", "mechFactoryT2", "mechGun"), session.constructionActions.map { it.typeId })
                assertTrue(session.actionMenu.canRepair)
            }
        }
    }

    @Test fun checked_engineer_choices_start_with_command_center_and_reopen_without_free_units() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                root.resolve("production-package"))).value
            for (choice in listOf("commandCenter", "builder", "mechFactory", "mechFactoryT2", "mechEngineer", "repairbay", "mechGun")) {
                val loaded = SandboxLoader().use { loader ->
                    loader.requestPack(root, ContentPackChoice(pack, choice))
                    val deadline = System.nanoTime() + 5_000_000_000L
                    var result: LoadedSandbox? = null
                    while (result == null && System.nanoTime() < deadline) {
                        result = loader.pollPrepared()?.getOrThrow()
                        if (result == null) Thread.sleep(5)
                    }
                    assertNotNull(result)
                }
                assertEquals("p009-engineer-production", loaded.scenario.scenarioId)
                assertTrue(NativeActionPanel.supports(loaded.scenario.scenarioId))
                SandboxSession(loaded.scenario).use { session ->
                    assertEquals(16000L, session.observation.balance)
                    assertNull(session.selectedId)
                    assertEquals(listOf("commandCenter"), session.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
                    assertTrue(session.observation.factoryQueues.single().items.isEmpty())
                    assertTrue(session.observation.upgradeQueues.isEmpty())
                }
            }
        }
    }

    @Test fun engineer_repair_card_and_range_circle_use_the_mobile_nano_range() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/ini")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("production-package"))).value
        val base = contentPackScenario(root, ContentPackChoice(pack, "builder"))
        val scene = base.copy(units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L))
        assertEquals(140.0, SandboxRenderResources.from(scene).units.getValue("mechEngineer").repairRange)
        assertEquals(230.0, SandboxRenderResources.from(scene).units.getValue("repairbay").repairRange)
        SandboxSession(scene).use { session ->
            session.select(Position(110.0, 210.0))
            assertTrue(session.actionMenu.canRepair)
            assertTrue(session.actionMenu.repairTooltip.contains("Range 140"))
            assertEquals("Engineer mech", session.actionMenu.selectedTitle)
        }
    }

    @Test fun mobile_nano_construction_preview_and_paid_creation_use_the_same_single_cell() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val root = fixtures.resolve("mech-engineer-production/ini")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("production-package"))).value
        val base = contentPackScenario(root, ContentPackChoice(pack, "builder"))
        val scene = base.copy(units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(Position(110.0, 210.0))
            val preview = assertNotNull(session.previewConstruction("mechGun", Position(190.0, 210.0)))
            assertEquals(PlacementState.CLEAR, preview.state)
            assertEquals(Position(190.0, 210.0), preview.position)
            assertEquals(listOf(9 to 10), preview.cells)
            assertIs<ActionResult.Accepted>(session.build("mechGun", Position(190.0, 210.0)))
            session.tick()
            val project = session.observation.units.single { it.typeId == "mechGun" }
            assertEquals(preview.position, project.position)
            assertEquals(0f, project.constructionProgress)
            assertEquals(15400L, session.observation.balance)
            assertNull(pack.units.getValue("mechGun").building)
        }
    }
}
