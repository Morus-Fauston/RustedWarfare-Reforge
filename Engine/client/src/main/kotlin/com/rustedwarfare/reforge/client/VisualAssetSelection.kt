package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.util.Collections

/** Legacy source/CLI names map to one explicit built-in resource-pack identity. */
enum class VisualAssetSource(val value: String, val resourcePackId: String) {
    PLACEHOLDER("placeholder", "reforge:base"),
    ORIGINAL("original", "reforge:original-android-1.15-176"),
}

/**
 * Immutable resource-pack selection plus per-pack declarative variant option values (ADR-014).
 */
class VisualAssetSelection(
    val packs: ResourcePackSelection,
    val originalDirectory: Path? = null,
    val temporary: Boolean = false,
    packOptions: Map<String, Map<String, String>> = emptyMap(),
) {
    val packOptions: Map<String, Map<String, String>> = Collections.unmodifiableMap(
        LinkedHashMap<String, Map<String, String>>().apply {
            for ((packId, opts) in packOptions) {
                val cleanId = packId.trim()
                if (cleanId.isEmpty() || opts.isEmpty()) continue
                val cleanOpts = LinkedHashMap<String, String>()
                for ((k, v) in opts) {
                    if (k.isNotBlank() && v.isNotBlank()) cleanOpts[k.trim()] = v.trim()
                }
                if (cleanOpts.isNotEmpty()) put(cleanId, Collections.unmodifiableMap(cleanOpts))
            }
        },
    )

    constructor(
        source: VisualAssetSource = VisualAssetSource.PLACEHOLDER,
        originalDirectory: Path? = null,
        temporary: Boolean = false,
    ) : this(ResourcePackSelection().withSource(source), originalDirectory, temporary, emptyMap())

    val source: VisualAssetSource get() = packs.source
    val resourcePackId: String get() = source.resourcePackId

    fun optionsFor(packId: String): Map<String, String> = packOptions[packId].orEmpty()

    fun withPackOption(packId: String, optionId: String, value: String): VisualAssetSelection {
        val nextOuter = LinkedHashMap(packOptions)
        val nextInner = LinkedHashMap(nextOuter[packId].orEmpty())
        nextInner[optionId] = value
        nextOuter[packId] = nextInner
        return copy(packOptions = nextOuter)
    }

    fun copy(
        source: VisualAssetSource? = null,
        originalDirectory: Path? = this.originalDirectory,
        temporary: Boolean = this.temporary,
        packs: ResourcePackSelection = this.packs,
        packOptions: Map<String, Map<String, String>> = this.packOptions,
    ) = VisualAssetSelection(
        source?.let(packs::withSource) ?: packs,
        originalDirectory,
        temporary,
        packOptions,
    )

    operator fun component1() = source
    operator fun component2() = originalDirectory
    operator fun component3() = temporary

    override fun equals(other: Any?) =
        other is VisualAssetSelection &&
            packs == other.packs &&
            originalDirectory == other.originalDirectory &&
            temporary == other.temporary &&
            packOptions == other.packOptions

    override fun hashCode(): Int {
        var h = packs.hashCode()
        h = 31 * h + (originalDirectory?.hashCode() ?: 0)
        h = 31 * h + temporary.hashCode()
        h = 31 * h + packOptions.hashCode()
        return h
    }

    override fun toString() =
        "VisualAssetSelection(packs=$packs, originalDirectory=$originalDirectory, temporary=$temporary, packOptions=$packOptions)"
}
