package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Path
import kotlin.test.*
import java.nio.file.Files

class TurretSandboxSessionTest {
    private fun root(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/turret") }
        ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input"))

    @Test fun current_single_selection_uses_display_radius_and_preserves_core_ownership_and_immobility_gates() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            s.select(Position(297.0, 150.0)); assertNull(s.selectedId)
            s.select(Position(296.0, 150.0)); assertEquals("t", s.selectedId)
            assertNull(s.selectedFactoryQueue); assertNull(s.productionPrice)
            assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(s.move(Position(300.0, 150.0))).error.code)
            assertIs<ActionResult.Accepted>(s.command(Position(500.0, 100.0)))
            repeat(5) { s.tick() }
            val t = s.observation.units.single { it.unitId == "t" }
            assertEquals(Position(280.0, 150.0), t.position)
            assertEquals("u", t.attackTargetId); assertNull(t.moveTarget); assertTrue(t.pathPoints.isEmpty())
            assertIs<ActionResult.Accepted>(s.stop())
            assertNull(s.observation.units.single { it.unitId == "t" }.attackTargetId)
            s.select(Position(500.0, 100.0)); assertEquals("u", s.selectedId)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(s.stop()).error.code)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(s.command(Position(280.0, 150.0))).error.code)
            assertNull(s.produce()); assertNull(s.cancelProduction())
        }
    }

    private fun unarmed(unit: UnitDefinition, sight: Int) = UnitDefinition(unit.id, unit.className,
        unit.radius, unit.displayRadius, unit.totalFrames, unit.imagePath, unit.imageScale, unit.movement,
        unit.retainedUnconsumed, sight, null, unit.durability, unit.building, unit.production, unit.build, unit.directHitTargetKind)

    @Test fun hidden_tower_presence_does_not_change_complete_session_frames_or_nested_client_evidence() {
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val base = ControlledScenario.load(root())
            val scene = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, unit) -> unarmed(unit, 1) },
                players = base.players.map { it.copy(teamId = "allied") },
                units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                    UnitSpawn("t", "p2", "c_turret_t1", Position(310.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 14L))
            val empty = scene.copy(units = scene.units.filter { it.unitId != "t" },
                combatContract = scene.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L)))
            val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sessions = listOf(scene, empty).mapIndexed { index, input ->
                SandboxSession(input, ClientEvidence { kind, values -> rows[index] += kind to values }, SandboxOptions(mode))
            }
            fun same() {
                val a = sessions[0].observation; val b = sessions[1].observation
                assertEquals(a.fields(), b.fields())
                assertEquals(a.vision!!.basicCells, b.vision!!.basicCells)
                assertEquals(a.vision!!.effectiveCells, b.vision!!.effectiveCells)
                assertEquals(a.knowledge!!.revision, b.knowledge!!.revision)
                assertEquals(sessions[0].frame.terrain, sessions[1].frame.terrain)
                assertEquals(rows[0], rows[1]); rows.forEach { it.clear() }
            }
            try {
                same()
                sessions.forEach {
                    assertTrue(it.observation.units.none { u -> u.unitId == "t" })
                    it.select(Position(110.0, 210.0))
                    assertIs<ActionResult.Accepted>(it.command(Position(510.0, 210.0)))
                }
                same()
                repeat(100) { sessions.forEach { it.tick() }; same() }
                sessions.forEach { it.select(Position(310.0, 210.0)); assertNull(it.selectedId) }
                same()
            } finally { sessions.forEach { it.close() } }
        }
    }

    @Test fun losing_sight_and_real_original_health_death_clear_selection_and_hud() {
        val base = ControlledScenario.load(root())
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val scene = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, unit) -> unarmed(unit, 4) },
                players = base.players.map { it.copy(teamId = "allied") },
                units = listOf(UnitSpawn("a", "p1", "c_tank", Position(250.0, 210.0), 270.0),
                    UnitSpawn("t", "p2", "c_turret_t1", Position(310.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 14L))
            SandboxSession(scene, options = SandboxOptions(mode)).use { s ->
                s.select(Position(250.0, 210.0)); assertEquals("a", s.selectedId)
                assertIs<ActionResult.Accepted>(s.move(Position(110.0, 210.0)))
                s.select(Position(310.0, 210.0)); assertEquals("t", s.selectedId)
                repeat(220) { s.tick() }
                assertTrue(s.observation.units.none { it.unitId == "t" })
                assertNull(s.selectedId); assertNull(s.selectedFactoryQueue); assertNull(s.productionPrice)
            }
        }
        val tower = unarmed(base.unitDefinitions.getValue("c_turret_t1"), 15)
        val enemies = (1..6).map { UnitSpawn("z$it", "p2", "c_tank", Position(400.0, 150.0), 270.0) }
        val units = listOf(UnitSpawn("t", "p1", tower.id, Position(280.0, 150.0), 90.0)) + enemies
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (tower.id to tower), units = units,
            combatContract = CombatContract(12345, -1, units.mapIndexed { i, u -> u.unitId to i + 20L }.toMap(), 30L))
        SandboxSession(scene).use { s ->
            s.select(Position(280.0, 150.0)); assertEquals("t", s.selectedId)
            val old = s.frame
            repeat(700) { s.tick() }
            val dead = s.observation.units.single { it.unitId == "t" }
            assertFalse(dead.alive); assertEquals(0.0, dead.health)
            assertNull(s.selectedId); assertNull(s.selectedFactoryQueue); assertNull(s.productionPrice)
            assertEquals(700.0, old.observation.units.single { it.unitId == "t" }.health)
        }
    }

    @Test fun a_bad_turret_marker_or_missing_real_image_never_falls_back_to_the_production_scene() {
        for (bad in listOf("marker", "image", "definition")) {
            val temporary = Files.createTempDirectory("p005-turret-session-")
            try {
                Files.walk(root()).use { paths -> paths.forEach { p ->
                    val target = temporary.resolve(root().relativize(p))
                    if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
                } }
                when (bad) {
                    "marker" -> Files.writeString(temporary.resolve("turret.properties"), "invalid")
                    "image" -> Files.delete(temporary.resolve("units/turret_base.png"))
                    else -> Files.writeString(temporary.resolve("units/turret_t1.ini"), "[core]\nname: broken")
                }
                assertFails { ControlledScenario.load(temporary) }
            } finally { temporary.toFile().deleteRecursively() }
        }
    }

    @Test
    fun fixed_turret_input_loads_real_buildings_and_exposes_the_selected_tower_through_the_session() {
        val scene = ControlledScenario.load(root())
        assertEquals("p005-turret", scene.scenarioId)
        assertEquals(6, scene.units.size)
        val tower = assertNotNull(scene.unitDefinitions["c_turret_t1"])
        assertEquals(700.0, tower.maximumHealth)
        SandboxSession(scene).use { session ->
            session.select(Position(280.0, 150.0))
            assertEquals("t", session.selectedId)
            val selected = session.observation.units.single { it.unitId == "t" }
            assertEquals("c_turret_t1", selected.typeId)
            assertEquals(700.0, selected.health)
            assertEquals(1750L, session.observation.balance)
        }
    }
}
