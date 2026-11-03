package com.rustedwarfare.reforge.client

import java.util.Collections

class ResolvedResourceSounds internal constructor(order: List<String>, sounds: Map<ResourceId, PreparedResourceSound>,
    events: Map<ResourceId, ResourceSoundEvent>, soundProviders: Map<ResourceId, String>, eventProviders: Map<ResourceId, String>,
    candidateProviders: Map<ResourceId, List<String>> = emptyMap()) {
    val packOrder: List<String> = Collections.unmodifiableList(ArrayList(order))
    val sounds: Map<ResourceId, PreparedResourceSound> = Collections.unmodifiableMap(LinkedHashMap(sounds))
    val events: Map<ResourceId, ResourceSoundEvent> = Collections.unmodifiableMap(LinkedHashMap(events))
    val soundProviders: Map<ResourceId, String> = Collections.unmodifiableMap(LinkedHashMap(soundProviders))
    val eventProviders: Map<ResourceId, String> = Collections.unmodifiableMap(LinkedHashMap(eventProviders))
    val candidateProviders: Map<ResourceId, List<String>> = Collections.unmodifiableMap(candidateProviders.mapValues {
        Collections.unmodifiableList(ArrayList(it.value))
    })
    fun resolve(event: ResourceId): PreparedResourceSound? = events[event]?.let { sounds.getValue(it.sound) }
    /** Half-open cumulative integer intervals preserve exact declared weight ratios. */
    fun choose(event: ResourceId, ticket: Long): ResourceSoundCandidate? {
        val table = events[event] ?: return null
        require(ticket >= 0 && ticket < table.totalWeight) { "RESOURCE_SOUND_TICKET_INVALID: Expected a ticket inside the accepted weight total" }
        var remaining = ticket
        for (candidate in table.candidates) {
            if (remaining < candidate.weight.toLong()) return candidate
            remaining -= candidate.weight.toLong()
        }
        error("RESOURCE_SOUND_SELECTION_INVALID: Accepted candidate table has an inconsistent total")
    }
}

/** Uses the already checked image stack's exact layers/order; no second priority configuration or IO. */
object ResourceSoundComposer {
    fun compose(stack: ResolvedResourceImages): ResolvedResourceSounds {
        val sounds = linkedMapOf<ResourceId, PreparedResourceSound>()
        val events = linkedMapOf<ResourceId, ResourceSoundEvent>()
        val soundProviders = linkedMapOf<ResourceId, String>(); val eventProviders = linkedMapOf<ResourceId, String>()
        val candidateProviders = linkedMapOf<ResourceId, List<String>>()
        fun bad(layer: ResourceImageLayer, id: ResourceId, reason: String): Nothing {
            val source = layer.sources.firstOrNull { it.path.fileName.toString() in setOf("sounds.json", "sounds.hjson") }?.path ?: "sounds"
            throw IllegalArgumentException("RESOURCE_PACK_COMBINATION_INVALID: ${layer.metadata.id} / $source / $id: $reason")
        }
        for (layer in stack.layers) for ((id, sound) in layer.sounds) {
            if (sound.channels !in 1..2 || sound.sampleRate <= 0 || sound.frames <= 0 || sound.bytes !in 44..16 * 1024 * 1024 ||
                44L + sound.frames.toLong() * sound.channels * 2 != sound.bytes.toLong() ||
                !sound.path.isAbsolute || sound.path.normalize() != sound.path || !sound.sha256.matches(Regex("[0-9a-f]{64}")))
                bad(layer, id, "Invalid prepared PCM sound contract")
            if (sounds.putIfAbsent(id, sound) == null) soundProviders[id] = layer.metadata.id.value
        }
        for (layer in stack.layers.asReversed()) for ((id, event) in layer.soundEvents) {
            // All declarations must resolve, even if a higher pack hides this event.
            for ((index, candidate) in event.candidates.withIndex())
                if (candidate.sound !in sounds) bad(layer, id, "candidates[$index]: Missing sound resource ${candidate.sound}")
            val lower = events[id]
            val append = event.append && lower != null
            val candidates = if (append) event.candidates + checkNotNull(lower).candidates else event.candidates
            val providers = List(event.candidates.size) { layer.metadata.id.value } +
                (if (append) candidateProviders.getValue(id) else emptyList())
            events[id] = try { ResourceSoundEvent(candidates) }
            catch (_: ArithmeticException) { bad(layer, id, "Combined candidate weight total overflow") }
            eventProviders[id] = layer.metadata.id.value
            candidateProviders[id] = providers
        }
        return ResolvedResourceSounds(stack.packOrder, sounds, events, soundProviders, eventProviders, candidateProviders)
    }
}
