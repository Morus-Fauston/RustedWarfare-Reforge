package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.AuthorizedSoundBatch
import com.rustedwarfare.reforge.core.sim.SoundListener
import java.nio.file.Path
import kotlin.random.Random

interface PlaybackSound : AutoCloseable {
    fun play(volume: Float, pitch: Float): Long
    fun stop()
}

fun interface PlaybackSoundFactory { fun load(sound: PreparedResourceSound): PlaybackSound }

fun MapProjection.soundListener() = SoundListener(camera.center, (mapArea.width / scale / 2).toFloat(), camera.zoom.toFloat())

/** One accepted world and one set of fixed sound objects, owned by the presentation thread. */
class SoundPlayback internal constructor(initial: AuthorizedSoundBatch, private val resources: ResolvedResourceSounds,
    factory: PlaybackSoundFactory, private val evidence: ClientEvidence, private val drawTicket: (Long) -> Long) : AutoCloseable {
    constructor(initial: AuthorizedSoundBatch, resources: ResolvedResourceSounds, factory: PlaybackSoundFactory,
        evidence: ClientEvidence = ClientEvidence.NONE) : this(initial, resources, factory, evidence, { Random.Default.nextLong(it) })
    private val world = initial.world
    private var serial = initial.serial
    private var step = initial.stepIndex
    private val sounds = linkedMapOf<Path, PlaybackSound>()
    private val played = mutableSetOf<Path>()
    private var closed = false
    private var frame = 0L

    init {
        try {
            for (sound in resources.sounds.values) if (sound.path !in sounds) sounds[sound.path] = factory.load(sound)
            evidence.recordWhenEnabled("sound-objects-ready") { mapOf("objects" to sounds.size,
                "resources" to resources.sounds.mapKeys { it.key.value }.mapValues { (_, sound) ->
                    mapOf("path" to sound.path.toString(), "sha256" to sound.sha256) }) }
        } catch (failure: Exception) {
            try { close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun beginFrame() { if (!closed) { played.clear(); frame = Math.addExact(frame, 1L) } }

    fun consume(batch: AuthorizedSoundBatch) {
        if (closed || batch.world !== world || batch.serial <= serial || batch.stepIndex <= step) return
        serial = batch.serial; step = batch.stepIndex
        for (request in batch.sounds) {
            val event = ResourceId.parse(request.eventId)
            val table = resources.events[event] ?: continue
            val resource = checkNotNull(resources.choose(event, drawTicket(table.totalWeight))).sound
            val prepared = resources.sounds.getValue(resource)
            // Admission is already complete. Aliases share the accepted fixed file and backend object.
            if (!played.add(prepared.path)) continue
            val fields = mapOf("eventId" to event.value, "resourceId" to resource.value,
                "path" to prepared.path.toString(), "sha256" to prepared.sha256,
                "volume" to request.volume, "pitch" to request.pitch, "pan" to 0f,
                "step" to batch.stepIndex, "serial" to batch.serial, "frame" to frame)
            try {
                val voice = sounds.getValue(prepared.path).play(request.volume, request.pitch)
                check(voice >= 0) { "Audio backend rejected the playback request" }
                evidence.record("sound-play-requested", fields + ("voice" to voice))
            } catch (failure: Exception) {
                evidence.record("sound-play-failed", fields + ("message" to (failure.message ?: failure.javaClass.name)))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true; played.clear()
        val count = sounds.size
        var first: Exception? = null
        for (sound in sounds.values) for (cleanup in listOf<() -> Unit>({ sound.stop() }, { sound.close() })) {
            try { cleanup() } catch (failure: Exception) { if (first == null) first = failure else first.addSuppressed(failure) }
        }
        sounds.clear()
        evidence.recordWhenEnabled("sound-objects-closed") { mapOf("objects" to count, "cleanupPassed" to (first == null)) }
        first?.let { throw it }
    }
}
