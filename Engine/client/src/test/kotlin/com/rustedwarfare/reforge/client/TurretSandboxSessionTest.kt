package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TurretSandboxSessionTest {
    internal fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261025-subscription-e-contract-03/input")

    @Test
    fun fixed_turret_marker_takes_precedence_and_loads_the_actual_six_object_scene() {
        val input = ControlledScenario.load(root())
        assertEquals("p005-turret", input.scenarioId)
        assertEquals(listOf("a", "f", "b", "t", "u", "g"), input.units.map { it.unitId })
        assertEquals(Position(280.0, 100.0), input.units.single { it.unitId == "t" }.position)
        assertEquals(Position(500.0, 100.0), input.units.single { it.unitId == "u" }.position)
        assertEquals(Position(430.0, 100.0), input.units.single { it.unitId == "g" }.position)
        val combat = assertNotNull(input.combatContract)
        assertEquals(mapOf("a" to 8L, "b" to 9L, "f" to 10L, "t" to 11L, "u" to 12L, "g" to 13L), combat.unitObjectIds)
        assertEquals(14L, combat.nextObjectId)
        assertEquals(1750L, input.economyContract!!.initialBalances["p1"])
        assertEquals(PlayerControl.NONE, input.players.single { it.playerId == "p2" }.control)
    }

    @Test
    fun real_session_selects_commands_fires_and_produces_a_real_attacker_without_a_fake_ui() {
        val input = ControlledScenario.load(root())
        SandboxSession(input).use { session ->
            val initial = session.frame
            session.select(Position(280.0, 100.0))
            assertEquals("t", session.selectedId)
            assertNull(session.productionPrice)
            assertNull(session.selectedFactoryQueue)
            assertIs<ActionResult.Accepted>(session.command(Position(430.0, 100.0)))
            repeat(30) { session.tick() }
            val tower = session.observation.units.single { it.unitId == "t" }
            assertEquals("g", tower.attackTargetId)
            assertEquals(Position(280.0, 100.0), tower.position)
            assertTrue(tower.pathPoints.isEmpty())
            assertTrue(session.observation.units.single { it.unitId == "g" }.health!! < 1200.0)
            assertEquals(1, session.moveSelected(Position(350.0, 300.0)).rejected)
            session.select(Position(110.0, 210.0), SelectionMode.ADD)
            val batch = session.moveSelected(Position(250.0, 300.0))
            assertEquals(1, batch.accepted)
            assertEquals(1, batch.rejected)
            session.select(Position(310.0, 210.0))
            assertIs<ActionResult.Accepted>(session.produce())
            repeat(501) { session.tick() }
            assertEquals(1400L, session.observation.balance)
            val born = session.observation.units.single { it.unitId.startsWith("produced:p1:") }
            session.select(born.position)
            assertEquals(born.unitId, session.selectedId)
            assertIs<ActionResult.Accepted>(session.stop())
            assertIs<ActionResult.Accepted>(session.command(Position(430.0, 100.0)))
            var fired = false
            repeat(250) {
                session.tick()
                fired = fired || session.observation.projectileEvents.any { it.sourceUnitId == born.unitId && it.kind == "born" }
            }
            assertTrue(fired, "the actually produced tank must enter the real factory attack loop")
            assertEquals(1200.0, initial.observation.units.single { it.unitId == "g" }.health)
            assertEquals(1750L, initial.observation.balance)
        }
    }

    @Test
    fun invalid_turret_marker_or_missing_tower_never_silently_falls_back_to_production() {
        withInput { directory ->
            Files.writeString(directory.resolve("turret.properties"), "format=bad\nvariant=basic-turret\n")
            assertFailsWith<IllegalArgumentException> { ControlledScenario.load(directory) }
        }
        withInput { directory ->
            Files.delete(directory.resolve("units/turret_t1.ini"))
            assertFailsWith<IllegalStateException> { ControlledScenario.load(directory) }
        }
    }

    private fun withInput(check: (Path) -> Unit) {
        val directory = Files.createTempDirectory("eng003-client-").toRealPath()
        try {
            Files.walk(root()).use { paths -> paths.forEach { source ->
                val target = directory.resolve(root().relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            } }
            check(directory)
        } finally {
            kotlin.check(directory.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                kotlin.check(it.toAbsolutePath().normalize().startsWith(directory)); Files.delete(it)
            } }
        }
    }
}
