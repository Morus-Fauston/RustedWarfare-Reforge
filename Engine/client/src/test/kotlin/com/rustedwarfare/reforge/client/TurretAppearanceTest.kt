package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class TurretAppearanceTest {
    @Test fun real_resources_keep_static_body_mount_separate_from_private_weapon_state() {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/turret") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input"))
        val scene = ControlledScenario.load(root)
        val resources = SandboxRenderResources.from(scene)
        val tower = resources.units.getValue("c_turret_t1")
        assertEquals(TurretMount(0.0, 6.0), tower.turretMount)
        assertNull(tower.backImagePath)
        assertNotNull(resources.units.getValue("landFactory").backImagePath)
        assertEquals(TurretMount(), resources.units.getValue("c_tank").turretMount)
        SandboxSession(scene).use { session ->
            val unit = session.observation.units.single { it.unitId == "t" }
            for ((heading, expected) in listOf(0.0 to Position(280.0, 144.0), 90.0 to Position(286.0, 150.0),
                180.0 to Position(280.0, 156.0), 270.0 to Position(274.0, 150.0))) {
                val actual = tower.turretPosition(unit.copy(heading = heading, turretHeading = 37.0, cooldown = null))
                assertEquals(expected.x, actual.x, 1e-9); assertEquals(expected.y, actual.y, 1e-9)
                val side = tower.copy(turretMount = TurretMount(4.0, 6.0))
                val offset = when (heading) { 0.0 -> Position(4.0, 0.0); 90.0 -> Position(0.0, 4.0)
                    180.0 -> Position(-4.0, 0.0); else -> Position(0.0, -4.0) }
                val p = side.turretPosition(unit.copy(heading = heading))
                assertEquals(expected.x + offset.x, p.x, 1e-9); assertEquals(expected.y + offset.y, p.y, 1e-9)
            }
        }
    }
}
