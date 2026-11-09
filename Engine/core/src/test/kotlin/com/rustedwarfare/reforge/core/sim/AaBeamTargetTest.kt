package com.rustedwarfare.reforge.core.sim

import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamTargetTest {
    @TempDir lateinit var temporary: Path

    @Test fun the_units_air_only_envelope_rejects_ground_even_though_its_turret_allows_land() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("ground", "p2", "builder", Position(350.0, 150.0), 0.0))))
        try {
            assertIs<ActionResult.Rejected>(context.submit(AttackAction("attack-ground", "p1", "aa", "ground", context.world)))
            repeat(100) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(170.0, context.observe().units.single { it.unitId == "ground" }.health)
            assertEquals(6f, context.observe().units.single { it.unitId == "aa" }.energy!!.current)
        } finally { context.close() }
    }
}
