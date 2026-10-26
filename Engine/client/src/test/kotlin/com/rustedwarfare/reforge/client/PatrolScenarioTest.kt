package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class PatrolScenarioTest {
    @Test fun the_fixed_pc_input_really_chases_damages_kills_and_returns_to_its_stored_patrol_point() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-007/20261026-e-patrol-pc-01/input")
        val scene = ControlledScenario.load(root)
        assertEquals("p005-patrol", scene.scenarioId)
        SandboxSession(scene).use { s ->
            s.select(Position(110.0, 210.0))
            val home = Position(30.0, 210.0)
            assertIs<ActionResult.Accepted>(s.patrol(home, false))
            var chased = false; var damaged = false; var dead = false; var returned = false
            repeat(750) {
                s.tick()
                val unit = s.observation.units.single { it.unitId == "a" }
                val enemy = s.observation.units.single { it.unitId == "b" }
                chased = chased || (unit.attackTargetId == "b" && unit.position.x > 150.0)
                damaged = damaged || (enemy.health!! < enemy.maxHealth!!)
                dead = dead || !enemy.alive
                returned = returned || (dead && unit.position.x < 110.0 && unit.attackTargetId == null)
            }
            assertTrue(chased && damaged && dead && returned)
            assertEquals(home, s.observation.units.single { it.unitId == "a" }.tasks.single().target)
            assertEquals(1750L, s.observation.balance)
            assertTrue(s.observation.factoryQueues.single().items.isEmpty())
        }
    }
}
