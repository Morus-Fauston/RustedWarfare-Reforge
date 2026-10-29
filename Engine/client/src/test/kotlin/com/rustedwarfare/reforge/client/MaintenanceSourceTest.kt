package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceSourceTest {
    @Test fun a_real_command_center_missile_survives_reclaiming_its_source_and_income_stops() {
        val base = ControlledScenario.load(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input"))
        val spawns = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("m-center", "p1", "commandCenter", Position(190.0, 170.0), 0.0),
            UnitSpawn("z-enemy", "p2", "c_tank", Position(480.0, 170.0), 270.0))
        val scene = base.copy(units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 4L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("weaken", "p1", "a-worker", "m-center", c.world)))
            while (c.observe().units.single { it.unitId == "m-center" }.health!! > 1.02 && step < 4200) tick()
            assertTrue(c.observe().units.single { it.unitId == "m-center" }.health!! in 0.0..1.02)
            assertTrue(c.observe().projectiles.isEmpty())
            assertIs<ActionResult.Accepted>(c.submit(StopAction("pause", "p1", "a-worker", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("terminal", "p1", "a-worker", "m-center", c.world)))
            repeat(75) { tick() }
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("approach", "p2", "z-enemy", Position(350.0, 170.0), c.world)))
            repeat(24) { tick() }
            assertEquals(99f, c.observe().construction!!.workers.single().elapsed)
            val shot = c.observe().projectiles.single { it.sourceUnitId == "m-center" }
            assertNotNull(shot.missile)
            val after = tick()
            assertTrue(after.units.none { it.unitId == "m-center" })
            assertTrue(after.projectiles.any { it.objectId == shot.objectId && it.sourceUnitId == "m-center" })
            assertTrue(after.events.none { it.kind in setOf("unit-died", "unit-damaged") })
            val paid = c.observePlayer(c.world, "p1").credits
            var hit = false
            repeat(220) {
                val view = tick()
                hit = hit || view.units.single { it.unitId == "z-enemy" }.health!! < 210.0
                assertEquals(paid, c.observePlayer(c.world, "p1").credits)
            }
            assertTrue(hit, "The already launched native missile must retain its real source and finish normally")
            assertTrue(c.observe().projectiles.none { it.sourceUnitId == "m-center" })
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("survivor", "p1", "a-worker", Position(150.0, 250.0), c.world)))
            tick()
        } finally { c.close() }
    }
}
