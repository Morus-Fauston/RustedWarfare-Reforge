package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MissileEvidencePairTest {
    @Test fun hidden_missile_retarget_geometry_never_changes_the_complete_local_player_evidence() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val base = ControlledScenario.load(repo.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/scene-input-01"))
        val builder = base.unitDefinitions.getValue("builder")
        val own = UnitDefinition("own", builder.className, builder.radius, builder.displayRadius, 1,
            builder.imagePath, 1.0, builder.movement, emptySet(), fogOfWarSightRange = 1, durability = builder.durability)
        val fragile = UnitDefinition("fragile", builder.className, builder.radius, builder.displayRadius, 1,
            builder.imagePath, 1.0, builder.movement, emptySet(), durability = builder.durability!!.copy(maxHp = 25.0))
        val tank = base.unitDefinitions.getValue("c_tank")
        val gun = checkNotNull(tank.combat)
        val killer = UnitDefinition("killer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, 1.0, tank.movement, emptySet(), combat = gun.copy(shootDelay = 1000.0,
                turretTurnSpeed = 999.0, projectile = gun.projectile.copy(speed = 10000.0)))
        val playerTraces = mutableListOf<List<Map<String, Any?>>>()
        val hostTargets = mutableListOf<List<Position>>()
        for (position in listOf(Position(400.0, 270.0), Position(390.0, 260.0))) {
            val input = base.copy(unitDefinitions = base.unitDefinitions + mapOf(own.id to own, fragile.id to fragile, killer.id to killer),
                players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "two"),
                    Player("p3", PlayerControl.NONE, "three")),
                units = listOf(UnitSpawn("own", "p1", own.id, Position(30.0, 30.0), 0.0),
                    UnitSpawn("a", "p2", killer.id, Position(510.0, 110.0), 180.0),
                    UnitSpawn("h", "p2", "commandCenter", Position(310.0, 210.0), 0.0),
                    UnitSpawn("b", "p3", fragile.id, Position(510.0, 210.0), 0.0),
                    UnitSpawn("c", "p3", builder.id, position, 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("own" to 7L, "a" to 8L, "h" to 10L, "b" to 11L, "c" to 12L), 13L),
                economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)),
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("aim", "p2", "h", "b", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill", "p2", "a", "b", c.world)))
                val trace = mutableListOf<Map<String, Any?>>()
                val host = mutableListOf<Position>()
                repeat(50) { step ->
                    val row = c.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    assertTrue(row.units.single { it.unitId == "c" }.alive)
                    host += row.projectiles.single { it.sourceUnitId == "h" }.position
                    c.observePlayer(c.world, "p2")
                    val local = c.observePlayer(c.world, "p1")
                    assertEquals(listOf("own"), local.units.map { it.unitId })
                    assertTrue(local.projectiles.isEmpty() && local.projectileEvents.isEmpty())
                    trace += local.fields()
                }
                playerTraces += trace
                hostTargets += host
            } finally { c.close() }
        }
        assertNotEquals(hostTargets[0], hostTargets[1], "The hidden worlds must actually diverge")
        assertEquals(playerTraces[0], playerTraces[1], "Compare complete actual diagnostic maps, including nested values")
    }
}
