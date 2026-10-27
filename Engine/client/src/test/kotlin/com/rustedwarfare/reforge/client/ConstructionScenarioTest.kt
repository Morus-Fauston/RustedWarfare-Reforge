package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionScenarioTest {
    @Test fun real_native_center_defends_then_dies_while_its_produced_builder_can_keep_moving() {
        val temp = Files.createTempDirectory("p006-center-defense-")
        try {
            Files.walk(inputRoot()).use { files -> files.forEach { source ->
                val target = temp.resolve(inputRoot().relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            } }
            Files.writeString(temp.resolve("construction.properties"),
                "format=rw-reforge.construction-sandbox.v1\nvariant=command-center-defense\n")
            val input = ControlledScenario.load(temp)
            assertEquals(12, input.units.count { it.ownerPlayerId == "p2" })
            SandboxSession(input).use { s ->
                s.select(Position(110.0, 210.0))
                assertIs<ActionResult.Accepted>(s.produce())
                var shot = false
                var enemyDamaged = false
                repeat(501) {
                    s.tick()
                    shot = shot || s.observation.projectileEvents.isNotEmpty()
                    enemyDamaged = enemyDamaged || s.observation.units.any { it.ownerPlayerId == "p2" && it.health!! < it.maxHealth!! }
                }
                assertTrue(s.observation.units.single { it.unitId == "h" }.alive, "Native center must survive long enough to produce")
                val born = s.observation.units.single { it.typeId == "builder" }
                s.select(born.position)
                assertIs<ActionResult.Accepted>(s.move(Position(50.0, 330.0)))
                var steps = 501
                while (s.observation.units.single { it.unitId == "h" }.alive && steps++ < 10000) s.tick()
                assertTrue(shot && enemyDamaged)
                assertFalse(s.observation.units.single { it.unitId == "h" }.alive,
                    "Finite original enemies must destroy the center: " +
                        s.observation.units.joinToString { "${it.unitId}:${it.health}:${it.alive}:${it.position}" })
                val survivor = s.observation.units.single { it.unitId == born.unitId }
                assertTrue(survivor.alive)
                val funds = s.observation.credits
                s.select(survivor.position)
                assertIs<ActionResult.Accepted>(s.move(Position(70.0, 310.0)))
                repeat(100) { s.tick() }
                assertEquals(funds, s.observation.credits)
                assertNotEquals(survivor.position, s.observation.units.single { it.unitId == born.unitId }.position)
                println("native-center-death-step=$steps")
            }
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test fun the_real_start_has_one_center_and_pays_refunds_produces_moves_and_reopens() {
        val scene = ControlledScenario.load(inputRoot())
        assertEquals("p006-construction", scene.scenarioId)
        assertEquals("commandCenter", scene.units.single { it.ownerPlayerId == "p1" }.typeId)
        assertEquals(2, scene.units.count { it.ownerPlayerId == "p2" })
        assertTrue(scene.units.none { it.typeId == "builder" })
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(scene, ClientEvidence { kind, values -> rows += kind to values }).use { s ->
            assertEquals(1750.0, s.observation.credits)
            s.select(Position(110.0, 210.0))
            assertEquals("h", s.selectedId)
            assertEquals("Command center selected", s.playerFeedback)
            assertEquals("builder", s.productionAction!!.typeId)
            repeat(3) { assertIs<ActionResult.Accepted>(s.produce()) }
            assertEquals(250.0, s.observation.credits)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(s.produce()).error.code)
            assertEquals("Insufficient funds", s.playerFeedback)
            assertFalse(s.productionAction!!.affordable)
            assertIs<ActionResult.Accepted>(s.cancelProduction())
            assertEquals(750.0, s.observation.credits)
            assertIs<ActionResult.Accepted>(s.cancelProduction())
            repeat(500) { s.tick() }
            assertTrue(s.observation.units.none { it.typeId == "builder" })
            assertEquals(1475.0, s.observation.credits)
            s.tick()
            val builder = s.observation.units.single { it.typeId == "builder" }
            assertEquals("Builder created", s.productionNotice?.text)
            assertEquals(170.0, builder.health)
            s.select(builder.position)
            assertEquals(builder.unitId, s.selectedId)
            assertEquals("Builder selected", s.playerFeedback)
            assertFalse(s.playerFeedback.contains(builder.unitId))
            assertNull(s.productionAction)
            assertIs<ActionResult.Accepted>(s.move(Position(170.0, 310.0)))
            repeat(200) { s.tick() }
            val moved = s.observation.units.single { it.unitId == builder.unitId }.position
            assertTrue(kotlin.math.hypot(moved.x - builder.position.x, moved.y - builder.position.y) > 40.0)
            assertTrue(rows.any { it.first == "action" && it.second["type"] == "ProduceAction" })
            assertTrue(rows.any { it.first == "action" && it.second["type"] == "CancelProductionAction" })
        }
        SandboxSession(scene).use { fresh ->
            assertEquals(1750.0, fresh.observation.credits)
            assertTrue(fresh.observation.factoryQueues.single().items.isEmpty())
            assertNull(fresh.selectedId)
            assertNull(fresh.productionNotice)
            assertTrue(fresh.observation.units.none { it.typeId == "builder" })
        }
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/scene-input-01")
}
