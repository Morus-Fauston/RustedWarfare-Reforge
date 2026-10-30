package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class PointDefenseSandboxSessionTest {
    private fun root(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/point-defense") }
        ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input"))

    @Test fun actual_native_defense_fixture_loads_and_initializes_the_full_representative_scene() {
        val scene = ControlledScenario.load(root())
        assertEquals("p005-point-defense", scene.scenarioId)
        assertEquals(15, scene.units.size)
        val defense = scene.unitDefinitions.getValue("laserDefence")
        assertEquals(500.0, defense.maximumHealth)
        assertEquals(19.0, defense.radius)
        assertEquals(MovementType.NONE, defense.movement.type)
        assertNull(defense.combat)
        assertEquals(Position(230.0, 150.0), scene.units.single { it.unitId == "d" }.position)
        val initialized = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene))
        val context = initialized.context
        try {
            assertEquals(500.0, context.observe().units.single { it.unitId == "d" }.health)
            assertEquals(1750L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun actual_ordinary_enemy_fire_reaches_defense_and_is_intercepted_before_a_later_hit() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(ControlledScenario.load(root()))).context
        try {
            val born = mutableSetOf<Long>()
            var first: ProjectileEventObservation? = null
            val terminal = mutableMapOf<Long, MutableList<String>>()
            repeat(120) { step ->
                val observation = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                observation.projectileEvents.forEach { event ->
                    if (event.kind == "born") born += event.projectile.objectId
                    if (event.kind == "deflected" && first == null) first = event
                    if (event.kind in setOf("impact", "intercepted", "expired", "target-ended"))
                        terminal.getOrPut(event.projectile.objectId) { mutableListOf() } += event.kind
                }
            }
            assertTrue(born.isNotEmpty(), "The fixture must produce real ordinary enemy shots")
            val intercepted = assertNotNull(first, "A real eligible shot must be deflected")
            assertTrue(intercepted.projectile.objectId in born)
            assertTrue(intercepted.projectile.sourceUnitId.startsWith("b"))
            assertEquals("f", intercepted.projectile.targetUnitId)
            assertEquals(listOf("intercepted"), terminal[intercepted.projectile.objectId].orEmpty().toList(),
                "This first distant shot must terminate once and never damage the target later")
        } finally { context.close() }
    }

    @Test fun the_real_session_diagnostic_contains_owner_energy_and_authorized_beams_without_host_arithmetic() {
        val scene = ControlledScenario.load(root())
        val rows = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene, ClientEvidence { kind, values -> if (kind == "observation") rows += values }).use { session ->
            session.select(Position(230.0, 150.0))
            assertEquals("d", session.selectedId)
            val resources = SandboxRenderResources.from(scene)
            var sawBeam = false
            repeat(120) {
                session.tick()
                resources.defenseBeams(session.observation).forEach { beam ->
                    assertEquals(Position(230.0, 137.0), beam.start)
                    assertTrue(session.observation.projectileEvents.any { it.kind == "deflected" && it.position == beam.end })
                    sawBeam = true
                }
            }
            assertTrue(sawBeam, "The actual authorized event must reach the renderer projection")
            val samples = rows.map { it.getValue("sample") as Map<*, *> }
            val initial = samples.first()["units"] as List<*>
            val defense = initial.map { it as Map<*, *> }.single { it["unitId"] == "d" }
            val energy = assertNotNull(defense["pointDefense"] as? Map<*, *>, "The owner diagnostic must publish real energy")
            assertEquals(1f, energy["energy"])
            assertEquals(false, energy["exhausted"])
            val beams = samples.flatMap { (it["projectileEvents"] as List<*>).map { row -> row as Map<*, *> } }
                .filter { it["kind"] == "deflected" }
            assertTrue(beams.isNotEmpty())
            assertTrue(beams.all { it["interceptorUnitId"] == "d" })
            fun rejectHost(value: Any?) {
                when (value) {
                    is Map<*, *> -> { assertTrue(value.keys.none { it in setOf("objectId", "age", "deflectionHealth", "intercepted") }); value.values.forEach(::rejectHost) }
                    is Iterable<*> -> value.forEach(::rejectHost)
                }
            }
            samples.forEach(::rejectHost)
        }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val other = context.observePlayer(context.world, "p2").fields()["units"] as List<*>
            assertNull(other.map { it as Map<*, *> }.single { it["unitId"] == "d" }["pointDefense"])
        } finally { context.close() }
    }

    @Test fun hidden_defense_actually_intercepts_without_changing_any_owner_frame_or_nested_client_payload() {
        val base = ControlledScenario.load(root())
        val tank = base.unitDefinitions.getValue("c_tank")
        val observer = UnitDefinition("observer", tank.className, tank.radius, tank.displayRadius,
            tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (observer.id to observer),
            players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(UnitSpawn("a", "p1", observer.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("d", "p2", "laserDefence", Position(430.0, 90.0), 0.0),
                UnitSpawn("z", "p3", "c_tank", Position(510.0, 90.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "d" to 11L, "z" to 12L), 24L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)))
        // A public host witness proves the hidden variant really exercises defense, not only an inert sprite.
        val witness = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try { assertTrue((0L..24L).any { step -> witness.advance(Step(step, 1.0, "sim-tick")).projectileEvents.any { it.kind == "deflected" } }) }
        finally { witness.close() }
        val absent = scene.copy(units = scene.units.take(1),
            combatContract = scene.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L)))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sessions = listOf(scene, absent).mapIndexed { index, input ->
                SandboxSession(input, ClientEvidence { kind, values -> rows[index] += kind to values }, SandboxOptions(mode))
            }
            try {
                val resources = SandboxRenderResources.from(scene)
                repeat(26) { step ->
                    val a = sessions[0].observation; val b = sessions[1].observation
                    assertEquals(a.fields(), b.fields(), "Complete public fields including tokens and sample identity: $mode/$step")
                    assertEquals(a.vision!!.basicCells, b.vision!!.basicCells)
                    assertEquals(a.vision!!.effectiveCells, b.vision!!.effectiveCells)
                    assertEquals(a.knowledge!!.revision, b.knowledge!!.revision)
                    assertEquals(sessions[0].frame.terrain, sessions[1].frame.terrain)
                    assertTrue(resources.defenseBeams(a).isEmpty())
                    assertTrue(a.units.none { it.unitId == "d" || it.unitId == "z" })
                    assertEquals(rows[0], rows[1], "Actual ClientEvidence including every nested value")
                    rows.forEach { it.clear() }
                    sessions.forEach { it.tick() }
                }
            } finally { sessions.forEach { it.close() } }
        }
    }
}
