package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseSiegeScenarioTest {
    @Test fun a_worker_from_the_finite_start_keeps_building_and_producing_after_the_center_is_destroyed() {
        withSiegeInput { root ->
            val scene = ControlledScenario.load(root)
            SandboxSession(scene).use { s ->
                var ticks = 0
                var centerFired = false
                var enemyWasDamaged = false
                val observedEnemyDeaths = mutableSetOf<String>()
                fun tick() {
                    s.tick(); ticks++
                    centerFired = centerFired || s.observation.projectileEvents.any { it.sourceUnitId == "h" }
                    enemyWasDamaged = enemyWasDamaged || s.observation.units.any {
                        it.ownerPlayerId == "p2" && it.health!! < it.maxHealth!!
                    }
                    observedEnemyDeaths += s.observation.units.filter { it.ownerPlayerId == "p2" && !it.alive }.map { it.unitId }
                }
                fun await(label: String, limit: Int, condition: () -> Boolean) {
                    repeat(limit) { if (condition()) return; tick() }
                    assertTrue(condition(), "$label at tick=$ticks credits=${s.observation.credits}; " +
                        s.observation.units.joinToString { "${it.unitId}:${it.typeId}:${it.alive}:${it.health}:${it.position}:${it.constructionProgress}" })
                }
                fun unit(id: String) = s.observation.units.single { it.unitId == id }
                s.select(Position(110.0, 210.0))
                assertIs<ActionResult.Accepted>(s.produce())
                await("first worker produced", 550) { s.observation.units.any { it.typeId == "builder" } }
                val worker = s.observation.units.single { it.typeId == "builder" }.unitId
                assertTrue(unit("h").alive)
                s.select(unit(worker).position)
                assertIs<ActionResult.Accepted>(s.move(Position(50.0, 330.0)))
                await("real enemy destroys center", 10000) { !unit("h").alive }
                assertTrue(centerFired && enemyWasDamaged)
                assertTrue(unit(worker).alive)
                val stoppedCenterIncome = s.observation.credits
                repeat(40) { tick() }
                assertEquals(stoppedCenterIncome, s.observation.credits)
                fun moveTo(p: Position) {
                    s.select(unit(worker).position)
                    assertIs<ActionResult.Accepted>(s.move(p))
                    await("worker reaches $p", 2000) {
                        val u = unit(worker)
                        assertTrue(u.alive, "The route must preserve its actual worker")
                        kotlin.math.hypot(u.position.x - p.x, u.position.y - p.y) <= 6.0
                    }
                }
                moveTo(Position(50.0, 390.0))
                moveTo(Position(450.0, 390.0))
                moveTo(Position(450.0, 210.0))
                fun build(type: String, p: Position): String {
                    s.select(unit(worker).position)
                    val preview = assertNotNull(s.previewConstruction(type, p))
                    assertEquals(PlacementState.CLEAR, preview.state, "$type at $p: $preview")
                    val before = s.observation.units.map { it.unitId }.toSet()
                    assertIs<ActionResult.Accepted>(s.build(type, p))
                    await("new $type appears", 2000) { s.observation.units.any { it.unitId !in before && it.typeId == type } }
                    val id = s.observation.units.single { it.unitId !in before && it.typeId == type }.unitId
                    await("new $type completes", 4000) { unit(id).alive && unit(id).constructionProgress == 1f }
                    return id
                }
                fun reclaim(id: String) {
                    s.select(unit(worker).position)
                    assertIs<ActionResult.Accepted>(s.reclaim(id))
                    await("real reclaim completes", 4000) { s.observation.units.none { it.unitId == id } }
                }
                val first = build("c_turret_t1", Position(370.0, 210.0))
                assertEquals(Position(360.0, 200.0), unit(first).position)
                val inner = setOf("z07", "z08", "z09", "z10", "z11", "z12")
                await("first turret defeats inner column", 4000) { observedEnemyDeaths.containsAll(inner) }
                reclaim(first)
                val allEnemies = scene.units.filter { it.ownerPlayerId == "p2" }.map { it.unitId }.toSet()
                if (!observedEnemyDeaths.containsAll(allEnemies)) {
                    val second = build("c_turret_t1", Position(330.0, 190.0))
                    assertEquals(Position(320.0, 180.0), unit(second).position)
                    await("second turret defeats outer column", 4000) { observedEnemyDeaths.containsAll(allEnemies) }
                    reclaim(second)
                }
                val extractor = build("extractorT1", Position(190.0, 290.0))
                val beforeIncome = s.observation.credits!!
                repeat(40) { tick() }
                assertTrue(s.observation.credits!! > beforeIncome)
                val factory = build("landFactory", Position(270.0, 330.0))
                await("surviving income funds both factory products", 4000) { s.observation.credits!! >= 850.0 }
                s.select(unit(factory).position)
                assertIs<ActionResult.Accepted>(s.produce("builder"))
                assertIs<ActionResult.Accepted>(s.produce("c_tank"))
                await("factory really produces both types", 4000) {
                    s.observation.units.count { it.alive && it.ownerPlayerId == "p1" && it.typeId == "builder" } == 2 &&
                        s.observation.units.any { it.alive && it.ownerPlayerId == "p1" && it.typeId == "c_tank" }
                }
                // T1 extractor pays 8 each 40 ticks: even a zero balance funds 1200 within 6040 ticks.
                await("surviving income funds point defense", 6040) { s.observation.credits!! >= 1200.0 }
                val shield = build("laserDefence", Position(450.0, 110.0))
                assertFalse(unit("h").alive)
                assertTrue(unit(worker).alive && unit(extractor).alive && unit(factory).alive && unit(shield).alive)
                assertEquals(12, observedEnemyDeaths.size)
                assertTrue(s.observation.credits!! >= 0.0)
                println("siege-public-session-route ticks=$ticks credits=${s.observation.credits}")
            }
        }
    }

    @Test fun the_explicit_siege_variant_preserves_the_finite_construction_start_with_twelve_original_tanks() {
        withSiegeInput { root ->
            val scene = ControlledScenario.load(root)
            assertEquals("p006-construction", scene.scenarioId)
            assertEquals(600, scene.map.widthTiles * scene.map.tileWidth)
            assertEquals(400, scene.map.heightTiles * scene.map.tileHeight)
            assertEquals(mapOf("p1" to 1750L, "p2" to 0L), scene.economyContract!!.initialBalances)
            assertEquals(listOf("h"), scene.units.filter { it.ownerPlayerId == "p1" }.map { it.unitId })
            assertEquals(Position(110.0, 210.0), scene.units.single { it.unitId == "h" }.position)
            assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence"),
                scene.unitDefinitions.getValue("builder").builder!!.buildingTypeIds)
            assertEquals(listOf("builder", "c_tank"), scene.unitDefinitions.getValue("landFactory").production!!.unitTypeIds)
            assertEquals(setOf("commandCenter", "builder", "landFactory", "c_tank", "extractorT1", "extractorT2",
                "extractorT3", "fabricatorT1", "fabricatorT2", "fabricatorT3", "c_turret_t1", "c_turret_t2_gun",
                "c_turret_t1_artillery", "laserDefence"), scene.unitDefinitions.keys)
            assertEquals(listOf(
                Position(190.0, 150.0), Position(190.0, 172.0), Position(190.0, 194.0),
                Position(190.0, 216.0), Position(190.0, 238.0), Position(190.0, 260.0),
                Position(212.0, 150.0), Position(212.0, 172.0), Position(212.0, 194.0),
                Position(212.0, 216.0), Position(212.0, 238.0), Position(212.0, 260.0)),
                scene.units.filter { it.ownerPlayerId == "p2" }.map { it.position })
            assertTrue(scene.units.filter { it.ownerPlayerId == "p2" }.all { it.typeId == "c_tank" && it.heading == 270.0 })
            assertEquals(listOf(PlayerControl.LOCAL, PlayerControl.NONE), scene.players.map { it.control })
            assertEquals(12345, scene.combatContract!!.worldSeed)
            assertEquals(-1, scene.combatContract!!.initialFrameCounter)
            assertEquals(10L, scene.combatContract!!.unitObjectIds.getValue("h"))
            assertEquals(22L, scene.combatContract!!.unitObjectIds.getValue("z12"))
            assertEquals(23L, scene.combatContract!!.nextObjectId)
            assertNotNull(scene.maintenanceContract)
            val result = SimulationContext.initialize(scene)
            val c = assertIs<InitResult.Accepted>(result, result.toString()).context
            try { assertEquals(1750.0, c.observePlayer(c.world, "p1").credits) }
            finally { c.close() }
        }
    }

    private fun withSiegeInput(block: (Path) -> Unit) {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val source = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val temp = Files.createTempDirectory("p006-siege-input-")
        try {
            Files.walk(source).use { paths -> paths.forEach { p ->
                val target = temp.resolve(source.relativize(p))
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            } }
            Files.writeString(temp.resolve("construction.properties"),
                "format=rw-reforge.construction-sandbox.v1\nvariant=defense-construction-siege\n")
            block(temp)
        } finally {
            val resolved = temp.toRealPath()
            require(resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()) &&
                resolved.fileName.toString().startsWith("p006-siege-input-"))
            temp.toFile().deleteRecursively()
        }
    }
}
