package com.rustedwarfare.reforge.client

import java.util.Collections
import com.rustedwarfare.reforge.core.content.ContentSourceIdentity

/** Shared prepared pack layer. Construction and composition never open media paths. */
class ResourceImageLayer private constructor(val metadata: ResourcePackMetadata, images: Map<ResourceId, PreparedResourceImage>,
    val identity: String?, sources: List<ContentSourceIdentity>, private val recheckInputs: () -> Unit,
    sounds: Map<ResourceId, PreparedResourceSound>, soundEvents: Map<ResourceId, ResourceSoundEvent>,
    staticBaseContracts: Map<ResourceId, ResourceImageDeclaration>) {
    constructor(metadata: ResourcePackMetadata, images: Map<ResourceId, PreparedResourceImage>, identity: String? = null,
        sources: List<ContentSourceIdentity> = emptyList(), recheckInputs: () -> Unit = {}) :
        this(metadata, images, identity, sources, recheckInputs, emptyMap(), emptyMap(), emptyMap())
    val images: Map<ResourceId, PreparedResourceImage> = Collections.unmodifiableMap(LinkedHashMap(images))
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(ArrayList(sources))
    val sounds: Map<ResourceId, PreparedResourceSound> = Collections.unmodifiableMap(LinkedHashMap(sounds))
    val soundEvents: Map<ResourceId, ResourceSoundEvent> = Collections.unmodifiableMap(LinkedHashMap(soundEvents))
    /** Only a platform's verified static-source adapter supplies these; pack JSON cannot. */
    val staticBaseContracts: Map<ResourceId, ResourceImageDeclaration> = Collections.unmodifiableMap(LinkedHashMap(staticBaseContracts))
    fun verifyInputs() = recheckInputs()
    fun withSounds(sounds: Map<ResourceId, PreparedResourceSound>, events: Map<ResourceId, ResourceSoundEvent>) =
        ResourceImageLayer(metadata, images, identity, sources, recheckInputs, sounds, events, staticBaseContracts)
    fun withStaticBaseContracts(expectedBase: Map<ResourceId, ResourceImageDeclaration>): ResourceImageLayer {
        require(images.keys.containsAll(expectedBase.keys))
        return ResourceImageLayer(metadata, images, identity, sources, recheckInputs, sounds, soundEvents, expectedBase)
    }
}

class ResolvedResourceImages internal constructor(layers: List<ResourceImageLayer>,
    images: Map<ResourceId, PreparedResourceImage>, providers: Map<ResourceId, String>) {
    val layers: List<ResourceImageLayer> = Collections.unmodifiableList(ArrayList(layers))
    val packs: List<ResourcePackMetadata> = Collections.unmodifiableList(this.layers.map { it.metadata })
    val packOrder: List<String> = Collections.unmodifiableList(this.packs.map { it.id.value })
    val images: Map<ResourceId, PreparedResourceImage> = Collections.unmodifiableMap(LinkedHashMap(images))
    val providers: Map<ResourceId, String> = Collections.unmodifiableMap(LinkedHashMap(providers))
}

/** Resolves only presentation resources against the current content's immutable image baseline. */
object ResourceImageComposer {
    fun compose(base: ResourceImageLayer, highToLow: List<ResourceImageLayer>): ResolvedResourceImages {
        val baseId = VisualAssetSource.PLACEHOLDER.resourcePackId
        val originalId = VisualAssetSource.ORIGINAL.resourcePackId
        require(base.metadata.id.value == baseId) { "RESOURCE_PACK_COMBINATION_INVALID: Expected public base last" }
        val layers = highToLow.toList() + base
        val order = layers.map { it.metadata.id.value }
        require(order.distinct().size == order.size) { "RESOURCE_PACK_COMBINATION_INVALID: Duplicate enabled pack ID: $order" }
        // Original's already-adapted static contract is retained even if a user
        // layer wins the lookup; toggling original can therefore invalidate a
        // user's incompatible animation layout instead of silently changing it.
        val contracts = LinkedHashMap(base.images)
        layers.filter { it.metadata.id.value == originalId }.forEach { contracts.putAll(it.images) }
        for (layer in layers.asReversed()) for ((id, expectedBase) in layer.staticBaseContracts) {
            val current = base.images[id]?.declaration ?: continue
            if (current.frameWidth == expectedBase.frameWidth && current.frameHeight == expectedBase.frameHeight &&
                current.totalFrames == expectedBase.totalFrames) contracts[id] = layer.images.getValue(id)
        }
        fun bad(layer: ResourceImageLayer, id: ResourceId, image: PreparedResourceImage, reason: String): Nothing =
            throw IllegalArgumentException("RESOURCE_PACK_COMBINATION_INVALID: ${layer.metadata.id} / ${image.declaration.file} / $id: $reason")
        for (layer in layers) for ((id, image) in layer.images) {
            val layout = image.declaration
            if (layout.frameWidth <= 0 || layout.frameHeight <= 0 || layout.totalFrames <= 0 || image.pixelWidth <= 0 || image.pixelHeight <= 0 ||
                image.pixelWidth % layout.totalFrames != 0 ||
                (image.pixelWidth / layout.totalFrames).toLong() * layout.frameHeight != image.pixelHeight.toLong() * layout.frameWidth)
                bad(layer, id, image, "Prepared image has an invalid physical/logical layout")
            if (layer !== base && layer.metadata.id.value != originalId) contracts[id]?.declaration?.let { expected ->
                if (layout.frameWidth != expected.frameWidth || layout.frameHeight != expected.frameHeight || layout.totalFrames != expected.totalFrames)
                    bad(layer, id, image, "Layout differs from the current content/built-in contract; expected=${expected.frameWidth}x${expected.frameHeight}/${expected.totalFrames}, actual=${layout.frameWidth}x${layout.frameHeight}/${layout.totalFrames}")
            }
        }
        val images = linkedMapOf<ResourceId, PreparedResourceImage>()
        val providers = linkedMapOf<ResourceId, String>()
        for (layer in layers) for ((id, image) in layer.images) if (images.putIfAbsent(id, image) == null)
            providers[id] = layer.metadata.id.value
        return ResolvedResourceImages(layers, images, providers)
    }
}
