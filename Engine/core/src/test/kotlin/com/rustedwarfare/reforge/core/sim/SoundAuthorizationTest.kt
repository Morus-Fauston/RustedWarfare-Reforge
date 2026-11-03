package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import java.nio.file.Path
import kotlin.test.*

class SoundAuthorizationTest {
    @Test fun all_three_public_tank_formats_produce_the_same_real_gameplay_sound_event() {
        val inputs = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val tank = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(
                inputs.resolve("three-format-tanks/$format/units/tank.$format"))).value
            val context = open(input().copy(unitDefinitions = mapOf(tank.id to tank)))
            try {
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(2, frame.events.count { it.kind == "projectile-fired" }, format)
                assertEquals(listOf("reforge:events/tank-fire", "reforge:events/tank-fire"),
                    context.consumeSounds(context.world, "p1", listener).sounds.map { it.eventId }, format)
            } finally { context.close() }
        }
    }
    @Test fun original_distance_zoom_and_integer_listener_boundaries_apply_before_playback() {
        data class Case(val offset: Double, val zoom: Float, val volumeBits: Int?)
        // Independently worked binary32 results for B=.3, H=25 and the fixed facts:
        // d=25 -> .3; d=43=1.72H -> .08399999; z=.25,d=33 -> .051000003.
        for (case in listOf(Case(25.9, 1f, 0x3e99999a), Case(43.0, 1f, 0x3dac0830),
            Case(44.0, 1f, null), Case(33.0, .25f, 0x3d50e561), Case(34.0, .25f, null))) {
            val base = input()
            val tank = base.unitDefinitions.getValue("c_tank")
            // Keep the opponent's real combat, but give only a the sound trigger under test.
            val quiet = UnitDefinition("quiet", tank.className, tank.radius, tank.displayRadius,
                tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed,
                tank.fogOfWarSightRange, checkNotNull(tank.combat).copy(fireSound = null))
            val context = open(base.copy(unitDefinitions = base.unitDefinitions + ("quiet" to quiet),
                units = base.units.map { if (it.unitId == "b") it.copy(typeId = "quiet") else it }))
            try {
                val first = context.advance(Step(0, 1.0, "sim-tick"))
                val muzzle = first.projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "a" }.projectile.position
                val sounds = context.consumeSounds(context.world, "p1", SoundListener(
                    Position(muzzle.x + case.offset, muzzle.y), 25f, zoom = case.zoom)).sounds
                if (case.volumeBits == null) assertTrue(sounds.isEmpty(), case.toString())
                else {
                    assertEquals(1, sounds.size, case.toString())
                    assertEquals(case.volumeBits, sounds.single().volume.toRawBits(), case.toString())
                }
            } finally { context.close() }
        }
    }
    private val listener = SoundListener(Position(165.0, 210.0), 300f)
    private fun input() = CombatTestContent.scenario().let { base -> base.copy(units = base.units.map {
        if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
    }) }
    private fun open(scene: Scenario = input()) = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context

    @Test fun real_shots_create_authorized_parameters_once_without_changing_combat_or_exposing_sources() {
        val context = open(); val control = open()
        try {
            assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty())
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val expected = control.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(2, first.projectileEvents.count { it.kind == "born" })
            val batch = context.consumeSounds(context.world, "p1", listener)
            assertSame(context.world, batch.world)
            assertEquals(0L, batch.stepIndex)
            assertEquals(2, batch.sounds.size)
            assertTrue(batch.sounds.all { it.eventId == "reforge:events/tank-fire" && it.volume == .3f && it.pitch in .93f..1.07f })
            val fields = batch.sounds.first().javaClass.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet()
            assertEquals(setOf("eventId", "volume", "pitch"), fields, "No source coordinate, distance, unit or host object ID may escape")
            assertTrue(context.consumeSounds(context.world, "p1", listener.copy(center = Position(300.0, 200.0))).sounds.isEmpty())
            context.observePlayer(context.world, "p1")
            context.submit(SelectAction("select", "p1", "a", context.world))
            assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty())
            assertEquals(expected.combat, context.observe().combat)
            assertEquals(expected.projectiles, context.observe().projectiles)
            assertEquals(expected.units, context.observe().units)
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty(), "Cooldown must not be mistaken for a new shot")
            assertTrue(second.events.none { it.kind == "projectile-fired" })
        } finally { context.close(); control.close() }
    }

    @Test fun hidden_shots_can_be_heard_without_releasing_hidden_state_or_requiring_visual_events() {
        val base = input()
        val scene = base.copy(players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(base.units[0].copy(position = Position(40.0, 40.0)),
                base.units[1].copy(position = Position(310.0, 210.0), heading = 90.0),
                UnitSpawn("c", "p3", "c_tank", Position(420.0, 210.0), 270.0)),
            combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        // Far enemies are outside p1's ordinary sight; the camera is permitted to hear there.
        val context = open(scene)
        try {
            context.advance(Step(0, 1.0, "sim-tick"))
            val visual = context.observePlayer(context.world, "p1")
            assertTrue(visual.units.none { it.unitId in setOf("b", "c") })
            assertTrue(visual.projectileEvents.isEmpty())
            val batch = context.consumeSounds(context.world, "p1", SoundListener(Position(365.0, 210.0), 200f))
            assertEquals(2, batch.sounds.size)
            assertTrue(batch.sounds.all { it.volume == .3f })
        } finally { context.close() }
    }

    @Test fun mute_and_distance_consume_the_step_and_a_camera_change_cannot_requery_historical_shots() {
        for (inaudible in listOf(listener.copy(enabled = false), listener.copy(masterVolume = 0f),
            SoundListener(Position(570.0, 370.0), 20f))) {
            val context = open()
            try {
                context.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(context.consumeSounds(context.world, "p1", inaudible).sounds.isEmpty())
                assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty())
            } finally { context.close() }
        }
    }

    @Test fun invalid_audience_old_world_and_failed_candidate_publish_no_sound_batch() {
        val scene = input().let { it.copy(combatContract = checkNotNull(it.combatContract).copy(nextObjectId = Long.MAX_VALUE - 1)) }
        val context = open(scene)
        try {
            val before = context.observe()
            assertFailsWith<ArithmeticException> { context.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, context.observe())
            assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty(), "First shot in the aborted candidate must roll back too")
            assertFails { context.consumeSounds(context.world, "unknown", listener) }
            for (invalid in listOf(listener.copy(center = Position(Double.NaN, 210.0)),
                listener.copy(center = Position(-1.0, 10.0)), listener.copy(halfWidth = 0f),
                listener.copy(halfWidth = Float.POSITIVE_INFINITY), listener.copy(zoom = 0f), listener.copy(gameVolume = 2f))) {
                assertFailsWith<IllegalArgumentException> { context.consumeSounds(context.world, "p1", invalid) }
            }
            val old = context.world
            context.close(); assertIs<InitResult.Accepted>(context.reopen(input()))
            assertFails { context.consumeSounds(old, "p1", listener) }
            assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty())
        } finally { context.close() }
    }
}
