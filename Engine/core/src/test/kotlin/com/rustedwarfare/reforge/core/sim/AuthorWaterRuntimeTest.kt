package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class AuthorWaterRuntimeTest {
    @Test fun loaded_author_surface_hulls_initialize_at_radius_forty_with_independent_named_slots() {
        for (format in listOf("ini", "json", "hjson")) {
            val scene = scene(format)
            val result = SimulationContext.initialize(scene)
            val context = assertIs<InitResult.Accepted>(result, "$format: $result").context
            try {
                assertEquals(listOf(0.0, 0.0), context.observe().units.map { it.height })
                val weapons = assertNotNull(context.observe().combat).weapons
                assertEquals(listOf("port", "starboard"), weapons.filter { it.unitId == "a" }.map { it.weaponName })
                assertEquals(listOf("port", "starboard"), weapons.filter { it.unitId == "b" }.map { it.weaponName })
            } finally { context.close() }
        }
    }
    @Test fun authored_surface_spawns_reject_nonzero_heights() {
        for (format in listOf("ini", "json", "hjson")) {
            val original = scene(format)
            for (height in listOf(-1.0, 1.0)) {
                val result = SimulationContext.initialize(original.copy(units = original.units.map { it.copy(height = height) }))
                if (result is InitResult.Accepted) result.context.close()
                assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(result, "$format height=$height").error.code)
            }
        }
    }
    @Test fun authored_water_hulls_cannot_spawn_on_land_or_cross_map_bounds_and_valid_input_can_reopen() {
        val original = scene("json")
        for (position in listOf(Position(110.0, 210.0), Position(790.0, 300.0), Position(Double.NaN, 300.0))) {
            val result = SimulationContext.initialize(original.copy(units = original.units.map {
                if (it.unitId == "a") it.copy(position = position) else it
            }))
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(result, "$position").error.code)
            val accepted = assertIs<InitResult.Accepted>(SimulationContext.initialize(original)).context
            try { assertEquals(Position(500.0, 300.0), accepted.observe().units.single { it.unitId == "a" }.position) }
            finally { accepted.close() }
        }
    }

    private fun scene(format: String): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("author-water/$format/package"))).value
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
                fixtures.resolve("naval-production/ini/maps/naval-shore.tmx"))).value
            return Scenario("p010-author-water", map, pack.units,
                listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
                listOf(UnitSpawn("a", "p1", "spcruiser", Position(500.0, 300.0), 90.0),
                    UnitSpawn("b", "p2", "spcruiser", Position(650.0, 300.0), 270.0)),
                CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
                TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L))
    }
}
