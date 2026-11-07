package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import java.nio.file.Path
import java.util.Collections

data class PreparedResourceImage(val path: Path, val declaration: ResourceImageDeclaration,
    val pixelWidth: Int, val pixelHeight: Int)

/** Fixed canonical PCM16 WAV; the original encoded input remains a separate source identity. */
data class PreparedResourceSound(val path: Path, val channels: Int, val sampleRate: Int, val frames: Int,
    val bytes: Int, val sha256: String)

/** Frozen image generation and separately recheckable import sources. Neither method owns GL. */
class PreparedImagePack(val metadata: ResourcePackMetadata, val manifest: Path, val identity: String,
    images: Map<ResourceId, PreparedResourceImage>, sources: List<ContentSourceIdentity>,
    private val verifyPrepared: () -> Unit, private val recheckSources: () -> Unit,
    sounds: Map<ResourceId, PreparedResourceSound> = emptyMap(), soundEvents: Map<ResourceId, ResourceSoundEvent> = emptyMap(),
    private val verifySelected: ((Set<Path>) -> Unit)? = null, val clientHeld: Boolean = false) {
    val images: Map<ResourceId, PreparedResourceImage> = Collections.unmodifiableMap(LinkedHashMap(images))
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(ArrayList(sources))
    val sounds: Map<ResourceId, PreparedResourceSound> = Collections.unmodifiableMap(LinkedHashMap(sounds))
    val soundEvents: Map<ResourceId, ResourceSoundEvent> = Collections.unmodifiableMap(LinkedHashMap(soundEvents))
    val catalogEntry = ResourcePackCatalogEntry(metadata.id.value, manifest, identity)
    init { require(metadata.icon in this.images) { "RESOURCE_PACK_IMAGE_INVALID: ${metadata.id} / ${metadata.icon}: Pack must provide its own icon" } }
    fun verify() = verifyPrepared()
    /** Client-held accepted versions check only the media selected by this match. */
    fun verifyUsed(paths: Set<Path>) { verifySelected?.invoke(paths) ?: verifyPrepared() }
    fun verifySources() = recheckSources()
}
