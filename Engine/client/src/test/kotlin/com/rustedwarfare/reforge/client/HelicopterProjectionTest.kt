package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class HelicopterProjectionTest {
    @Test
    fun the_real_session_projects_physical_height_and_independent_world_rotor_heading_at_two_scales() {
        for (format in listOf("ini", "json", "hjson")) for (scale in listOf(1.0, 2.0)) {
            val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0 * scale, 400.0 * scale))
            SandboxSession(scene(format)).use { session ->
                val initialView = session.observation
                val old = FlightPresentation.project(initialView, projection)
                val initial = old.single { it.unitId == "a" }
                assertEquals((110 * scale).toFloat(), initial.bodyX)
                assertEquals((190 * scale).toFloat(), initial.bodyY)
                assertEquals((190.1 * scale).toFloat(), initial.rotorY)
                assertEquals(-90f, initial.bodyRotation)
                assertEquals(-90f, initial.rotorRotation)
                assertFailsWith<UnsupportedOperationException> { (old as MutableList).clear() }
                session.tick()
                val next = FlightPresentation.project(session.observation, projection).single { it.unitId == "a" }
                assertEquals((110 * scale).toFloat(), next.bodyX)
                assertEquals((190.24 * scale).toFloat(), next.bodyY)
                assertEquals(next.bodyX, next.rotorX)
                assertEquals(next.bodyY, next.rotorY)
                assertEquals(-90f, next.bodyRotation)
                assertEquals(-120f, next.rotorRotation)
                assertEquals(Position(110.0, 210.0), session.observation.units.single { it.unitId == "a" }.position)
                assertEquals(old, FlightPresentation.project(initialView, projection))
                repeat(100) { session.tick() }
                assertNotEquals(next.rotorRotation,
                    FlightPresentation.project(session.observation, projection).single { it.unitId == "a" }.rotorRotation)
            }
        }
    }

    @Test
    fun the_renderer_only_receives_authorized_air_units_and_never_draws_a_dead_rotor() {
        val projection = MapProjection(600.0, 400.0)
        val source = scene("json")
        val hidden = source.copy(units = source.units.map { if (it.unitId == "a") it.copy(position = Position(50.0, 50.0))
            else it.copy(position = Position(550.0, 350.0)) })
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            SandboxSession(hidden, options = SandboxOptions(mode)).use { session ->
                assertEquals(listOf("a"), session.observation.units.map { it.unitId })
                assertEquals(listOf("a"), FlightPresentation.project(session.observation, projection).map { it.unitId })
            }
        }
        val fighting = source.copy(units = listOf(UnitSpawn("a", "p1", "helicopter", Position(100.0, 100.0), 90.0),
            UnitSpawn("b", "p2", "helicopter", Position(200.0, 100.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L))
        SandboxSession(fighting).use { session ->
            var steps = 0
            while (session.observation.units.none { !it.alive } && steps++ < 1000) session.tick()
            val dead = session.observation.units.first { !it.alive }
            assertEquals(0.0, dead.height)
            assertTrue(session.renderResources.units.getValue(dead.typeId).isDrawn(dead))
            assertNotNull(session.renderResources.units.getValue(dead.typeId).deadImagePath)
            assertTrue(FlightPresentation.project(session.observation, projection).none { it.unitId == dead.unitId })
        }
    }

    private fun scene(format: String): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("helicopter-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
        return contentPackScenario(root, ContentPackChoice(pack, "helicopter"))
    }
}
