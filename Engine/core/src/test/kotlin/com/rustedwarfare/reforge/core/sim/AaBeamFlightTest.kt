package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

internal object AaBeamScene {
    fun definition(root: Path, format: String = "json"): UnitDefinition =
        assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            AaBeamContentFixture.write(root, format))).value.units.getValue("aaBeamGunship")

    fun scenario(map: MapDefinition, aa: UnitDefinition, spawns: List<UnitSpawn>): Scenario =
        LandingCraftScene.scenario(map, spawns = spawns).let { base ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(
                ContentLoader.loadContentPack(aa.imagePath.parent)).value
            base.copy(scenarioId = "aa-beam", unitDefinitions = base.unitDefinitions + pack.units + (aa.id to aa))
        }

    fun open(input: Scenario): SimulationContext {
        val result = SimulationContext.initialize(input)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}

class AaBeamFlightTest {
    @TempDir lateinit var temporary: Path

    @Test fun the_rotorless_aa_gunship_takes_off_and_remains_airborne_after_stop() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(200.0, 150.0), 90.0))))
        try {
            assertEquals(0.0, context.observe().units.single().height)
            repeat(200) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single().height in 34.0..36.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(300.0, 150.0), context.world)))
            repeat(30) { context.advance(Step(it + 200L, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single().position.x > 200.0)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "aa", context.world)))
            repeat(100) { context.advance(Step(it + 230L, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single().height in 34.0..36.0)
        } finally { context.close() }
    }
}
