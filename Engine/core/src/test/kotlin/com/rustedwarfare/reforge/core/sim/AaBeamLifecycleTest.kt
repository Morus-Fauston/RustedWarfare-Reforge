package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
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
