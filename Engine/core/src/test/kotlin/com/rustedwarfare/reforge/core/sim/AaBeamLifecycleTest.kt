package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamLifecycleTest {
    @TempDir lateinit var temporary: Path

    @Test fun a_visible_origin_cannot_disclose_a_hidden_beam_endpoint_or_event() = WaterTestScene.withMap { map ->
        val aa = AaBeamScene.definition(temporary)
        val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, aa.imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 1,
            durability = DurabilityProfile(1000.0, null))
        val base = AaBeamScene.scenario(map, aa, listOf(
            UnitSpawn("aa", "p1", aa.id, Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", aa.id, Position(350.0, 150.0), 270.0),
            UnitSpawn("witness", "p3", "witness", Position(150.0, 150.0), 0.0)))
        val context = AaBeamScene.open(base.copy(unitDefinitions = base.unitDefinitions + (witness.id to witness),
            players = base.players.map { if (it.playerId == "p1") it.copy(teamId = "friends") else it } +
                Player("p3", PlayerControl.NONE, "friends"),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L, "p3" to 0L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)))
        try {
            var fired = false
            for (tick in 0L..180L) {
                val host = context.advance(Step(tick, 1.0, "sim-tick"))
                val view = context.observePlayer(context.world, "p3")
                assertTrue(view.units.any { it.unitId == "aa" })
                assertTrue(view.units.none { it.unitId == "enemy" })
                assertTrue(view.projectiles.isEmpty())
                assertTrue(view.projectileEvents.isEmpty())
                fired = fired || host.projectileEvents.any { it.kind == "born" }
            }
            assertTrue(fired)
        } finally { context.close() }
    }

    @Test fun authored_beam_resources_follow_the_current_public_source_visibility() = WaterTestScene.withMap { map ->
        val root = AfterburnContentFixture.write(temporary, "json")
        for (name in listOf("aa.json", "afterburn.json")) {
            val path = root.resolve(name)
            Files.writeString(path, Files.readString(path)
                .replace("aaBeamGunship_afterburn", "escort_boosted").replace("aaBeamGunship", "escort"))
        }
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units
        val spawns = listOf(UnitSpawn("aa", "p1", "escort", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "escort", Position(350.0, 150.0), 270.0))
        val base = AaBeamScene.scenario(map, definitions.getValue("escort"), spawns)
        val scenario = base.copy(unitDefinitions = definitions)
        val context = AaBeamScene.open(scenario)
        try {
            var host = context.observe()
            for (tick in 0L..200L) {
                host = context.advance(Step(tick, 1.0, "sim-tick"))
                if (host.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "aa" }) break
            }
            val beam = assertNotNull(host.projectiles.single { it.sourceUnitId == "aa" }.beam)
            assertEquals("escort", beam.resourceUnitType)
            val projector = PlayerObservationProjector(context.world, scenario, "p2")
            fun inspect(vision: VisionSnapshot?) = projector.observe(
                PlayerObservationFrame(host, null, vision, null, 0, true, emptyMap(), emptyMap()))
            val disclosed = inspect(null)
            val active = disclosed.projectiles.single { it.beam?.visibleSourceUnitId == "aa" }
            assertEquals("escort", active.beam?.resourceUnitType)
            assertEquals("escort", disclosed.projectileEvents.first { it.sourceUnitId == "aa" }.beam?.resourceUnitType)

            fun cell(p: Position) = VisionCell((p.x / map.tileWidth).toInt(), (p.y / map.tileHeight).toInt())
            val shown = listOf(cell(active.position), cell(beam.end))
            val origin = cell(host.units.single { it.unitId == "aa" }.position)
            val inBoundsBeam = host.projectiles.single { it.sourceUnitId == "aa" }.copy(alwaysVisibleInFog = true)
            val visible = shown.filter { it != origin }.toSet() + cell(beam.end)
            assertFalse(origin in visible)
            val snapshot = VisionSnapshot(host.worldId, "p2", 1, visible.toList(), visible.toList())
            val withVisibleGeometry = Observation(host.sampleId, host.worldId, host.scenarioId, host.stepIndex,
                host.simulationTime, host.units, emptyList(), emptyList(), listOf(inBoundsBeam),
                projectileEvents = listOf(ProjectileEventObservation("born", inBoundsBeam)))
            val hidden = projector.observe(
                PlayerObservationFrame(withVisibleGeometry, null, snapshot, null, 0, true, emptyMap(), emptyMap()))
            assertTrue(hidden.units.none { it.unitId == "aa" })
            assertNotNull(hidden.projectiles.single().beam).let {
                assertNull(it.visibleSourceUnitId)
                assertNull(it.resourceUnitType)
            }
            assertNull(hidden.projectileEvents.single().sourceUnitId)
            assertNull(assertNotNull(hidden.projectileEvents.single().beam).resourceUnitType)
        } finally { context.close() }
    }

    @Test fun a_cached_beam_tracks_its_moving_muzzle_expires_during_recharge_and_is_newly_allocated_afterward() = WaterTestScene.withMap { map ->
        val aa = AaBeamScene.definition(temporary)
        val context = AaBeamScene.open(AaBeamScene.scenario(map, aa, listOf(
            UnitSpawn("aa", "p1", aa.id, Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", aa.id, Position(350.0, 150.0), 270.0))))
        try {
            var tick = 0L
            var frame = context.observe()
            while (frame.projectiles.none { it.sourceUnitId == "aa" } && tick < 200L)
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
            val first = frame.projectiles.single { it.sourceUnitId == "aa" }
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(180.0, 170.0), context.world)))
            repeat(10) {
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
                val beam = frame.projectiles.single { it.sourceUnitId == "aa" }
                assertEquals(first.objectId, beam.objectId)
                assertEquals(frame.combat!!.weapons.single { it.unitId == "aa" }.muzzle, beam.position)
                assertEquals(frame.units.single { it.unitId == "aa" }.height, beam.height)
            }
            assertNotEquals(first.position, frame.projectiles.single { it.sourceUnitId == "aa" }.position)
            while (frame.units.single { it.unitId == "aa" }.energy!!.recharging.not() && tick < 300L)
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
            assertTrue(frame.units.single { it.unitId == "aa" }.energy!!.recharging)
            repeat(15) { frame = context.advance(Step(tick++, 1.0, "sim-tick")) }
            assertTrue(frame.projectiles.none { it.sourceUnitId == "aa" })
            while (frame.projectiles.none { it.sourceUnitId == "aa" } && tick < 500L)
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
            assertNotEquals(first.objectId, frame.projectiles.single { it.sourceUnitId == "aa" }.objectId)
            assertTrue(frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "aa" })
        } finally { context.close() }
    }
}
