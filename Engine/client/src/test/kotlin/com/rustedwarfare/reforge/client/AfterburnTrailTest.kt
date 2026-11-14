package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AfterburnTrailTest {
    @TempDir lateinit var temporary: Path

    @Test fun an_authored_gunship_uses_its_own_beam_and_boosted_trail_images() {
        val loaded = AaBeamSandboxScene.load()
        val units = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("aa-beam-production/json/production-package/units")
        for (image in listOf("aa.png", "aa_dead.png", "aa_shadow.png", "aa_turret.png",
                "aa_beam.png", "aa_beam_start.png", "aa_beam_end.png", "aa_trail.png")) {
            Files.copy(units.resolve(image), temporary.resolve("escort-$image"))
        }
        for (name in listOf("aaBeamGunship", "aaBeamGunship_afterburn")) {
            val renamed = name.replace("aaBeamGunship_afterburn", "escort_boosted").replace("aaBeamGunship", "escort")
            Files.writeString(temporary.resolve("$renamed.json"), Files.readString(units.resolve("$name.json"))
                .replace("aaBeamGunship_afterburn", "escort_boosted").replace("aaBeamGunship", "escort")
                .replace("\"aa_", "\"escort-aa_").replace("\"aa.png", "\"escort-aa.png"))
        }
        Files.writeString(temporary.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1", "id":"escort", "name":"Escort",
            "units":[{"type":"aa-beam-gunship","file":"escort.json"},
                     {"type":"aa-beam-gunship","file":"escort_boosted.json"}]
        }""")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(temporary)).value
        val scene = loaded.scenario.copy(unitDefinitions = loaded.scenario.unitDefinitions + pack.units, units = listOf(
            UnitSpawn("aa", "p1", "escort", Position(150.0, 300.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 300.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("aa" to 8L, "enemy" to 9L), 10L))
        val resources = SandboxRenderResources.from(scene)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var tick = 0L
            fun step() = context.advance(Step(tick++, 1.0, "sim-tick"))
            fun view() = context.observePlayer(context.world, "p1")
            while (view().projectiles.none { it.beam?.resourceUnitType == "escort" } && tick < 200L) step()
            val escortBeam = BeamPresentation.project(view(), resources).single {
                it.images.beamImagePath == temporary.resolve("escort-aa_beam.png")
            }
            assertEquals(temporary.resolve("escort-aa_beam_start.png"), escortBeam.images.beamStartImagePath)
            assertEquals(temporary.resolve("escort-aa_beam_end.png"), escortBeam.images.beamEndImagePath)

            val trails = AfterburnTrailPresentation()
            assertTrue(trails.project(view(), resources).isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(300.0, 230.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("boost", "p1", "aa", "afterBurn", context.world)))
            repeat(3) { step(); assertTrue(trails.project(view(), resources).isEmpty()) }
            step()
            val particles = trails.project(view(), resources)
            assertEquals(2, particles.size)
            assertTrue(particles.all { it.images.afterburnTrailImagePath == temporary.resolve("escort-aa_trail.png") })
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("cancel", "p1", "aa", "cancelBurn", context.world)))
            step()
            assertEquals("escort", view().units.single { it.unitId == "aa" }.typeId)
            repeat(31) { step(); trails.project(view(), resources) }
            assertTrue(trails.project(view(), resources).isEmpty())
        } finally { context.close() }
    }

    @Test fun moving_afterburn_emits_two_height_projected_trails_and_cancel_stops_emission_without_replaying_draws() {
        val loaded = AaBeamSandboxScene.load()
        val scene = loaded.scenario.copy(units = listOf(UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("aa" to 8L), 9L))
        val opened = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            repeat(200) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val presentation = AfterburnTrailPresentation()
            fun project() = presentation.project(context.observePlayer(context.world, "p1"), loaded.renderResources)
            assertTrue(project().isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(700.0, 300.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            repeat(3) { context.advance(Step(200L + it, 1.0, "sim-tick")); assertTrue(project().isEmpty()) }
            context.advance(Step(203, 1.0, "sim-tick"))
            val first = project()
            assertEquals(2, first.size)
            assertEquals(first, project(), "Drawing the same simulation observation must not emit twice")
            val unit = context.observe().units.single()
            assertEquals(unit.position.x - 22.0, first.first().center.x, .000001)
            assertEquals(unit.position.y + 10.0 - unit.height, first.first().center.y, .000001)
            assertEquals(20.0, first.first().center.y - first.last().center.y, .000001)
            assertEquals(.7f, first.first().scale)
            assertEquals(0f, first.first().alpha)
            assertNotNull(first.first().images.afterburnTrailImagePath)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("cancel", "p1", "aa", "cancelBurn", context.world)))
            repeat(6) { context.advance(Step(204L + it, 1.0, "sim-tick")); project() }
            val fading = project()
            assertEquals(2, fading.size)
            assertEquals(.84f, fading.first().scale, .000001f)
            assertEquals(.56f, fading.first().alpha, .000001f)
            assertEquals(first.first().center.x - 1.2, fading.first().center.x, .000001)
            repeat(24) { context.advance(Step(210L + it, 1.0, "sim-tick")); project() }
            assertTrue(project().isEmpty())
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertTrue(project().isEmpty(), "A new world cannot retain particles from the old world")
        } finally { context.close() }
    }
}
