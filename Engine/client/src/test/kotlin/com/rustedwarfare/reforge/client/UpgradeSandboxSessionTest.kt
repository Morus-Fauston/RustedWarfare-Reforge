package com.rustedwarfare.reforge.client

import java.nio.file.Files
import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class UpgradeSandboxSessionTest {
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-004/20261026-e-upgrade-input-01/input")

    @Test fun real_highest_priority_upgrade_marker_loads_all_three_finite_forms_and_only_the_base_enables_actions() {
        val scene = ControlledScenario.load(root())
        assertEquals("p005-upgrade", scene.scenarioId)
        assertEquals(setOf("a", "f", "t", "b01", "b02", "b03"), scene.units.map { it.unitId }.toSet())
        assertEquals(listOf("upgrade_gun", "upgrade_artillery"), scene.unitDefinitions.getValue("c_turret_t1").upgrades!!.actions.map { it.actionKey })
        for (id in listOf("c_turret_t2_gun", "c_turret_t1_artillery")) assertNull(scene.unitDefinitions.getValue(id).upgrades)
        SandboxSession(scene).use { session ->
            assertEquals(1750L, session.observation.balance)
            assertEquals(700.0, session.observation.units.single { it.unitId == "t" }.health)
            assertEquals(3, session.observation.units.count { it.ownerPlayerId == "p2" && it.health == 210.0 })
        }
    }

    @Test fun session_selection_pays_advances_cancels_and_really_finishes_each_original_branch_with_public_nested_evidence() {
        val scene = ControlledScenario.load(root())
        data class Branch(val key: String, val price: Long, val target: String, val maximum: Double)
        for ((key, price, target, maximum) in listOf(Branch("upgrade_gun", 1000L, "c_turret_t2_gun", 1100.0),
            Branch("upgrade_artillery", 1600L, "c_turret_t1_artillery", 1000.0))) {
            val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
            SandboxSession(scene, ClientEvidence { kind, values -> rows += kind to values }).use { session ->
                assertTrue(session.selectedUpgrades.isEmpty())
                session.select(Position(230.0, 150.0))
                assertEquals(listOf(1000L, 1600L), session.selectedUpgrades.map { it.price })
                assertIs<ActionResult.Accepted>(session.upgrade(key))
                assertEquals(1750L - price, session.observation.balance)
                val old = session.observation
                session.tick()
                assertTrue(session.selectedUpgradeQueue!!.items.single().progress > 0f)
                val sample = (rows.last { it.first == "observation" }.second["sample"] as Map<*, *>)
                val item = (((sample["upgradeQueues"] as List<*>).single() as Map<*, *>)["items"] as List<*>).single() as Map<*, *>
                assertEquals(setOf("actionKey", "targetTypeId", "progress"), item.keys)
                assertIs<ActionResult.Accepted>(session.cancelUpgrade(key))
                assertEquals(1750L, session.observation.balance)
                assertTrue(session.selectedUpgradeQueue!!.items.isEmpty())
                assertIs<ActionResult.Accepted>(session.upgrade(key))
                var ticks = 0
                while (session.observation.units.single { it.unitId == "t" }.typeId == "c_turret_t1" && ticks++ < 2600) session.tick()
                val unit = session.observation.units.single { it.unitId == "t" }
                assertEquals(target, unit.typeId)
                assertEquals(maximum, unit.maxHealth)
                assertEquals(Position(230.0, 150.0), unit.position)
                assertTrue(session.selectedUpgrades.isEmpty())
                assertNull(session.selectedUpgradeQueue)
                assertNotNull(session.renderResources.units[unit.typeId]?.turretImagePath)
                repeat(if (key == "upgrade_artillery") 1000 else 240) { session.tick() }
                assertTrue(rows.any { (_, values) -> (values["sample"] as? Map<*, *>)?.get("projectileEvents").let {
                    (it as? List<*>)?.any { event -> (event as Map<*, *>)["kind"] == "impact" } == true } })
                if (key == "upgrade_artillery") {
                    val samples = rows.mapNotNull { it.second["sample"] as? Map<*, *> }
                    assertTrue(samples.any { sample -> (sample["events"] as List<*>).count {
                        (it as Map<*, *>)["kind"] == "unit-damaged" } >= 2 }, "A real converted artillery shot damages multiple actual units")
                    assertTrue(session.observation.units.any { it.ownerPlayerId == "p2" && !it.alive })
                }
                assertEquals("c_turret_t1", old.units.single { it.unitId == "t" }.typeId)
                assertEquals(0f, old.upgradeQueues.single().items.single().progress)
            }
        }
    }

    @Test fun bad_upgrade_marker_or_missing_completed_form_fails_instead_of_falling_back_to_production() {
        val temporary = Files.createTempDirectory("p005-upgrade-invalid-")
        try {
            Files.walk(root()).use { paths -> paths.forEach { source ->
                val destination = temporary.resolve(root().relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
            } }
            Files.writeString(temporary.resolve("upgrade.properties"), "format=wrong")
            assertFails { ControlledScenario.load(temporary) }
            Files.copy(root().resolve("upgrade.properties"), temporary.resolve("upgrade.properties"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.delete(temporary.resolve("units/turret_t2_gun.ini"))
            assertFails { ControlledScenario.load(temporary) }
        } finally { temporary.toFile().deleteRecursively() }
    }
}
