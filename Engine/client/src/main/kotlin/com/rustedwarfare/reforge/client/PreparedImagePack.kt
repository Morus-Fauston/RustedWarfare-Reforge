package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import java.nio.file.Path
import java.util.Collections

data class PreparedResourceImage(
    val path: Path,
    val declaration: ResourceImageDeclaration,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

/** Fixed canonical PCM16 WAV; the original encoded input remains a separate source identity. */
data class PreparedResourceSound(
    val path: Path,
    val channels: Int,
    val sampleRate: Int,
    val frames: Int,
    val bytes: Int,
    val sha256: String,
)

/** Frozen image/sound/variant generation and separately recheckable import sources. Neither method owns GL. */
class PreparedImagePack(
    val metadata: ResourcePackMetadata,
    val manifest: Path,
    val identity: String,
    images: Map<ResourceId, PreparedResourceImage>,
    sources: List<ContentSourceIdentity>,
    private val verifyPrepared: () -> Unit,
    private val recheckSources: () -> Unit,
    sounds: Map<ResourceId, PreparedResourceSound> = emptyMap(),
    soundEvents: Map<ResourceId, ResourceSoundEvent> = emptyMap(),
    private val verifySelected: ((Set<Path>) -> Unit)? = null,
    val clientHeld: Boolean = false,
    staticBaseContracts: Map<ResourceId, ResourceImageDeclaration> = emptyMap(),
    val baseUiThemePatch: UiThemePatch? = null,
    val baseUiThemeRawBytes: ByteArray? = null,
    variants: Map<PackVariantKey, PreparedPackVariantOverlay> = emptyMap(),
) {
    val images: Map<ResourceId, PreparedResourceImage> = Collections.unmodifiableMap(LinkedHashMap(images))
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(ArrayList(sources))
    val sounds: Map<ResourceId, PreparedResourceSound> = Collections.unmodifiableMap(LinkedHashMap(sounds))
    val soundEvents: Map<ResourceId, ResourceSoundEvent> = Collections.unmodifiableMap(LinkedHashMap(soundEvents))

    /** A platform-qualified original mapping, retained only in authenticated client-held versions. */
    val staticBaseContracts: Map<ResourceId, ResourceImageDeclaration> = Collections.unmodifiableMap(LinkedHashMap(staticBaseContracts))
    val variants: Map<PackVariantKey, PreparedPackVariantOverlay> = Collections.unmodifiableMap(LinkedHashMap(variants))
    val catalogEntry = ResourcePackCatalogEntry(metadata.id.value, manifest, identity)

    init {
        require(metadata.icon in this.images) {
            "RESOURCE_PACK_IMAGE_INVALID: ${metadata.id} / ${metadata.icon}: Pack must provide its own icon"
        }
    }

    /**
     * Resolves intra-pack base assets + active [ResourcePackOption] overlays in `metadata.options`
     * declaration order (`options[0]` -> `options[1]` -> ...), falling back to `default` for any
     * missing or obsolete option value (ADR-014 & UI Supplement §7 / Q7).
     */
    fun resolveActiveVariant(selectedOptions: Map<String, String> = emptyMap()): ResolvedPackVariant {
        if (metadata.options.isEmpty() && variants.isEmpty()) {
            return ResolvedPackVariant(
                images = images,
                sounds = sounds,
                soundEvents = soundEvents,
                uiThemePatchesLowToHigh = listOfNotNull(baseUiThemePatch),
            )
        }
        val mergedImages = LinkedHashMap(images)
        val mergedSounds = LinkedHashMap(sounds)
        val mergedEvents = LinkedHashMap(soundEvents)
        val themePatches = ArrayList<UiThemePatch>()
        baseUiThemePatch?.let(themePatches::add)

        for (opt in metadata.options) {
            val chosenVal = opt.resolveValueOrDefault(selectedOptions[opt.id])
            val overlay = variants[PackVariantKey(opt.id, chosenVal)] ?: continue
            mergedImages.putAll(overlay.images)
            mergedSounds.putAll(overlay.sounds)
            mergedEvents.putAll(overlay.soundEvents)
            overlay.uiThemePatch?.let(themePatches::add)
        }
        return ResolvedPackVariant(
            images = mergedImages,
            sounds = mergedSounds,
            soundEvents = mergedEvents,
            uiThemePatchesLowToHigh = themePatches,
        )
    }

    fun verify() = verifyPrepared()

    /** Client-held accepted versions check only the media selected by this match. */
    fun verifyUsed(paths: Set<Path>) {
        verifySelected?.invoke(paths) ?: verifyPrepared()
    }

    fun verifySources() = recheckSources()
}
