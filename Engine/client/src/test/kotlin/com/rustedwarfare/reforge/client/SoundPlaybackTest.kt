package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.nio.file.Path
import kotlin.test.*

class SoundPlaybackTest {
    @Test fun actual_portrait_map_viewports_enter_tick_and_play_without_clamping_the_world_half_width() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            root.resolve("resource-sound-packs/sandbox/maps/portrait.tmx"))).value
        val portrait = scene().copy(map = map, units = scene().units.map {
            if (it.unitId == "c") it.copy(position = Position(310.0, 600.0)) else it
        })
        assertEquals(400.0, map.widthTiles.toDouble() * map.tileWidth)
        assertEquals(800.0, map.heightTiles.toDouble() * map.tileHeight)
        for ((area, expectedHalfWidth) in listOf(MapArea(16.0, 64.0, 704.0, 512.0) to 550f,
                MapArea(24.0, 100.0, 600.0, 400.0) to 600f)) {
            val projection = MapProjection(400.0, 800.0, area)
            val listener = projection.soundListener()
            assertEquals(expectedHalfWidth, listener.halfWidth, "Use the real letterboxed viewport, never the map width cap")
            SandboxSession(portrait).use { session ->
                val initial = session.consumeSounds(listener)
                assertTrue(initial.sounds.isEmpty(), "Entering a legal portrait map must succeed even before the first tick")
                val sink = Sink()
                SoundPlayback(initial, resources(), PlaybackSoundFactory { sink }).use { player ->
                    player.beginFrame(); session.tick()
                    val fired = session.consumeSounds(listener)
                    assertTrue(fired.sounds.size >= 2, "Actual in-range tanks fire through the normal committed step")
                    player.consume(fired)
                    assertEquals(1, sink.calls.size)
                    assertTrue(session.consumeSounds(listener).sounds.isEmpty())
                    for (invalid in listOf(listener.copy(halfWidth = Float.NaN), listener.copy(halfWidth = -1f),
                            listener.copy(halfWidth = Float.NEGATIVE_INFINITY), listener.copy(center = Position(200.0, 801.0)),
                            listener.copy(zoom = 5f), listener.copy(masterVolume = -1f))) {
                        assertFailsWith<IllegalArgumentException> { session.consumeSounds(invalid) }
                    }
                }
            }
        }
        val zoomed = MapProjection(400.0, 800.0, MapArea(16.0, 64.0, 704.0, 512.0))
        // Four existing zoom increments give 2.0736x and a worked world half-width of 265.2392.
        zoomed.zoomAt(368.0, 320.0, -4.0)
        assertEquals(265.2392f, zoomed.soundListener().halfWidth)
    }

    private val event = ResourceId.parse("reforge:events/tank-fire")
    private val soundId = ResourceId.parse("owned:sounds/fire")
    private val listener = SoundListener(Position(165.0, 210.0), 300f)
    private fun scene(): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/combat")
        return ControlledScenario.load(root).let { source -> source.copy(units = source.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        }) }
    }
    private fun resources(): ResolvedResourceSounds {
        val sound = PreparedResourceSound(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("owned-sound.wav"),
            1, 22050, 10, 64, "a".repeat(64))
        return ResolvedResourceSounds(listOf("owned:test"), linkedMapOf(soundId to sound, ResourceId.parse("owned:sounds/alias") to sound),
            mapOf(event to ResourceSoundEvent(soundId)), emptyMap(), emptyMap())
    }
    private class Sink(var fail: Boolean = false) : PlaybackSound {
        val calls = mutableListOf<Pair<Float, Float>>()
        var stopped = 0; var closed = 0
        override fun play(volume: Float, pitch: Float): Long { calls += volume to pitch; return if (fail) -1L else calls.size.toLong() }
        override fun stop() { stopped++ }
        override fun close() { closed++ }
    }
    private fun nextShot(session: SandboxSession): AuthorizedSoundBatch {
        repeat(800) {
            session.tick()
            val batch = session.consumeSounds(listener)
            if (batch.sounds.isNotEmpty()) return batch
        }
        error("The actual combat session produced no next shot")
    }

    @Test fun actual_session_plays_a_valid_candidate_from_a_weighted_event_and_releases_all_candidate_objects() {
        SandboxSession(scene()).use { session ->
            val first = resources().sounds.getValue(soundId)
            val secondId = ResourceId.parse("owned:sounds/second")
            val second = first.copy(path = first.path.resolveSibling("second.wav"))
            val weighted = ResolvedResourceSounds(listOf("owned:test"), mapOf(soundId to first, secondId to second),
                mapOf(event to ResourceSoundEvent(listOf(ResourceSoundCandidate(soundId, 2), ResourceSoundCandidate(secondId)))),
                emptyMap(), emptyMap())
            val a = Sink(); val b = Sink(); val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SoundPlayback(session.consumeSounds(listener), weighted, PlaybackSoundFactory { if (it.path == first.path) a else b },
                ClientEvidence { kind, values -> records += kind to values }).use { player ->
                player.beginFrame(); val batch = nextShot(session)
                player.consume(batch)
                val calls = a.calls.size + b.calls.size
                assertTrue(calls in 1..2)
                assertTrue(a.calls.size <= 1 && b.calls.size <= 1, "Each actual sound object is requested at most once in the outer frame")
                val plays = records.filter { it.first == "sound-play-requested" }
                assertEquals(calls, plays.size)
                assertTrue(plays.all { it.second["resourceId"] in setOf(soundId.value, secondId.value) })
                assertTrue(records.none { it.first == "sound-play-failed" })
                player.consume(batch)
                assertEquals(calls, a.calls.size + b.calls.size, "A repeated authorized batch must not select another candidate")
            }
            assertEquals(1, a.closed); assertEquals(1, b.closed)
            assertEquals(1, a.stopped); assertEquals(1, b.stopped)
        }
    }

    @Test fun legal_weight_tickets_select_before_object_dedup_and_history_never_draws_again() {
        SandboxSession(scene()).use { session ->
            val first = resources().sounds.getValue(soundId)
            val secondId = ResourceId.parse("owned:sounds/second")
            val second = first.copy(path = first.path.resolveSibling("second.wav"))
            val weighted = ResolvedResourceSounds(listOf("owned:test"), mapOf(soundId to first, secondId to second),
                mapOf(event to ResourceSoundEvent(listOf(ResourceSoundCandidate(soundId, 2), ResourceSoundCandidate(secondId)))), emptyMap(), emptyMap())
            val a = Sink(); val b = Sink(); var draws = 0
            val player = SoundPlayback(session.consumeSounds(listener), weighted,
                PlaybackSoundFactory { if (it.path == first.path) a else b }, ClientEvidence.NONE) { total ->
                assertEquals(3L, total)
                if (draws++ % 2 == 0) 0L else 2L
            }
            player.beginFrame(); val firstShot = nextShot(session); assertEquals(2, firstShot.sounds.size)
            player.consume(firstShot)
            assertEquals(1, a.calls.size); assertEquals(1, b.calls.size); assertEquals(2, draws)
            player.consume(firstShot); assertEquals(2, draws)
            player.consume(nextShot(session))
            assertEquals(4, draws, "Each new authorized request selects once before dedup; no redraw for an already-played object")
            assertEquals(1, a.calls.size); assertEquals(1, b.calls.size)
            player.beginFrame(); player.consume(firstShot); assertEquals(4, draws)
            SandboxSession(scene()).use { other -> player.consume(nextShot(other)); assertEquals(4, draws) }
            player.close(); player.consume(firstShot); assertEquals(4, draws)
        }
    }

    @Test fun weighted_candidates_and_more_actual_play_requests_leave_core_units_projectiles_rng_and_object_ids_unchanged() {
        val input = scene()
        val actual = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val first = resources().sounds.getValue(soundId)
        val secondId = ResourceId.parse("owned:sounds/second")
        val second = first.copy(path = first.path.resolveSibling("second.wav"))
        val weighted = ResolvedResourceSounds(listOf("owned:test"), mapOf(soundId to first, secondId to second),
            mapOf(event to ResourceSoundEvent(listOf(ResourceSoundCandidate(soundId, 2), ResourceSoundCandidate(secondId)))), emptyMap(), emptyMap())
        val a = Sink(); val b = Sink(); val plain = Sink(); var draws = 0
        try {
            SoundPlayback(actual.consumeSounds(actual.world, "p1", listener), weighted,
                PlaybackSoundFactory { if (it.path == first.path) a else b }, ClientEvidence.NONE) { if (draws++ % 2 == 0) 0L else 2L }.use { player ->
                SoundPlayback(control.consumeSounds(control.world, "p1", listener), resources(), PlaybackSoundFactory { plain }).use { baseline ->
                    repeat(180) { tick ->
                        if (tick % 8 == 0) { player.beginFrame(); baseline.beginFrame() }
                        val expected = control.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                        actual.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                        baseline.consume(control.consumeSounds(control.world, "p1", listener))
                        player.consume(actual.consumeSounds(actual.world, "p1", listener))
                        val observed = actual.observe()
                        assertEquals(expected.units, observed.units)
                        assertEquals(expected.projectiles, observed.projectiles)
                        assertEquals(expected.combat, observed.combat, "Includes deterministic combat random counters and next object identity")
                    }
                    assertTrue(a.calls.size + b.calls.size > plain.calls.size)
                }
            }
        } finally { actual.close(); control.close() }
    }

    @Test fun weighted_aliases_share_one_object_and_backend_failure_never_redraws_or_replays_muted_history() {
        SandboxSession(scene()).use { session ->
            val shared = resources().sounds.getValue(soundId)
            val alias = ResourceId.parse("owned:sounds/alias")
            val weighted = ResolvedResourceSounds(listOf("owned:test"), mapOf(soundId to shared, alias to shared),
                mapOf(event to ResourceSoundEvent(listOf(ResourceSoundCandidate(soundId, 2), ResourceSoundCandidate(alias)))), emptyMap(), emptyMap())
            val sink = Sink(true); var loads = 0; var draws = 0
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            SoundPlayback(session.consumeSounds(listener), weighted, PlaybackSoundFactory { loads++; sink },
                ClientEvidence { kind, values -> records += kind to values }) { if (draws++ % 2 == 0) 0L else 2L }.use { player ->
                assertEquals(1, loads)
                player.beginFrame(); val fired = nextShot(session); assertEquals(2, fired.sounds.size)
                player.consume(fired)
                assertEquals(2, draws); assertEquals(1, sink.calls.size)
                assertEquals(1, records.count { it.first == "sound-play-failed" })
                player.consume(fired); assertEquals(2, draws)
                sink.fail = false; player.beginFrame()
                player.consume(nextShot(session)); assertEquals(4, draws); assertEquals(2, sink.calls.size)
                repeat(80) {
                    session.tick(); player.consume(session.consumeSounds(listener.copy(masterVolume = 0f)))
                }
                assertEquals(4, draws, "Muted committed steps consume their history without choosing candidates")
                player.consume(session.consumeSounds(listener)); assertEquals(4, draws)
            }
            assertEquals(1, sink.stopped); assertEquals(1, sink.closed)
        }
    }

    @Test fun actual_session_batches_share_one_object_per_render_and_reject_history_and_other_worlds() {
        SandboxSession(scene()).use { session ->
            val sink = Sink(); var loads = 0
            SoundPlayback(session.consumeSounds(listener), resources(), PlaybackSoundFactory { loads++; sink }).use { player ->
                assertEquals(1, loads, "Two resource aliases share the fixed sound object")
                player.beginFrame()
                val first = nextShot(session)
                assertTrue(first.sounds.size >= 2, "Both actual tanks fire in this committed step")
                player.consume(first); player.consume(first)
                player.consume(nextShot(session))
                assertEquals(1, sink.calls.size, "All ticks in the same outer render share deduplication")
                player.beginFrame(); player.consume(first)
                assertEquals(1, sink.calls.size, "A new render must not replay an old batch")
                player.consume(nextShot(session))
                assertEquals(2, sink.calls.size)
                assertTrue(sink.calls.all { it.first in .05f..0.3f && it.second in .93f..1.07f })
                SandboxSession(scene()).use { reopened ->
                    player.beginFrame(); player.consume(nextShot(reopened))
                    assertEquals(2, sink.calls.size, "Same scenario and repeatable IDs cannot cross actual world identity")
                }
            }
            assertEquals(1, sink.stopped); assertEquals(1, sink.closed)
        }
    }

    @Test fun backend_rejection_is_diagnostic_consumes_this_frame_and_close_blocks_late_batches() {
        SandboxSession(scene()).use { session ->
            val sink = Sink(true); val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            val player = SoundPlayback(session.consumeSounds(listener), resources(), PlaybackSoundFactory { sink },
                ClientEvidence { kind, values -> records += kind to values })
            player.beginFrame(); val first = nextShot(session)
            player.consume(first); player.consume(first); player.consume(nextShot(session))
            assertEquals(1, sink.calls.size)
            assertEquals(1, records.count { it.first == "sound-play-failed" })
            assertTrue(records.none { it.first == "sound-play-requested" })
            assertTrue(records.flatMap { it.second.keys }.none { it in setOf("position", "distance", "unitId", "projectileId") })
            sink.fail = false; player.beginFrame(); player.consume(nextShot(session))
            assertEquals(2, sink.calls.size)
            assertEquals(1, records.count { it.first == "sound-play-requested" })
            player.close(); player.close(); player.beginFrame(); player.consume(first)
            assertEquals(2, sink.calls.size); assertEquals(1, sink.closed); assertEquals(1, sink.stopped)
        }
    }

    @Test fun rebuilding_after_pause_does_not_replay_the_consumed_step_and_disposes_each_generation() {
        SandboxSession(scene()).use { session ->
            val before = Sink()
            val first = SoundPlayback(session.consumeSounds(listener), resources(), PlaybackSoundFactory { before })
            first.beginFrame(); val old = nextShot(session); first.consume(old); first.close()
            assertEquals(1, before.calls.size); assertEquals(1, before.closed)
            val after = Sink()
            SoundPlayback(session.consumeSounds(listener), resources(), PlaybackSoundFactory { after }).use { resumed ->
                resumed.beginFrame(); resumed.consume(old)
                assertTrue(after.calls.isEmpty(), "Rebuilding native buffers must not replay pre-pause requests")
                resumed.consume(nextShot(session))
                assertEquals(1, after.calls.size)
            }
            assertEquals(1, after.closed); assertEquals(1, after.stopped)
        }
    }

    @Test fun loading_failure_releases_every_earlier_native_object_before_publishing_a_player() {
        SandboxSession(scene()).use { session ->
            val resources = resources()
            val other = ResourceId.parse("owned:sounds/second")
            val firstSound = resources.sounds.getValue(soundId)
            val two = ResolvedResourceSounds(resources.packOrder,
                mapOf(soundId to firstSound, other to firstSound.copy(path = firstSound.path.resolveSibling("second.wav"))),
                resources.events, resources.soundProviders, resources.eventProviders)
            val sink = Sink(); var count = 0
            val error = assertFailsWith<IllegalStateException> {
                SoundPlayback(session.consumeSounds(listener), two, PlaybackSoundFactory {
                    if (++count == 2) error("Owned backend load failure") else sink
                })
            }
            assertEquals("Owned backend load failure", error.message)
            assertEquals(1, sink.closed); assertEquals(1, sink.stopped)
        }
    }
}
