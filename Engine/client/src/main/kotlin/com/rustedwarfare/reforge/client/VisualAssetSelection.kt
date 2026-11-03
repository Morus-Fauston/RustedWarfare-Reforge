package com.rustedwarfare.reforge.client

import java.nio.file.Path

/** Legacy source/CLI names map to one explicit built-in resource-pack identity. */
enum class VisualAssetSource(val value: String, val resourcePackId: String) {
    PLACEHOLDER("placeholder", "reforge:base"),
    ORIGINAL("original", "reforge:original-android-1.15-176")
}

/** Remembering a directory never enables it; only source selects original visuals. */
class VisualAssetSelection(val packs: ResourcePackSelection,
    val originalDirectory: Path? = null, val temporary: Boolean = false) {
    constructor(source: VisualAssetSource = VisualAssetSource.PLACEHOLDER, originalDirectory: Path? = null, temporary: Boolean = false) :
        this(ResourcePackSelection().withSource(source), originalDirectory, temporary)

    val source: VisualAssetSource get() = packs.source
    val resourcePackId: String get() = source.resourcePackId

    fun copy(source: VisualAssetSource? = null, originalDirectory: Path? = this.originalDirectory,
        temporary: Boolean = this.temporary, packs: ResourcePackSelection = this.packs) =
        VisualAssetSelection(source?.let(packs::withSource) ?: packs, originalDirectory, temporary)

    operator fun component1() = source
    operator fun component2() = originalDirectory
    operator fun component3() = temporary
    override fun equals(other: Any?) = other is VisualAssetSelection && packs == other.packs &&
        originalDirectory == other.originalDirectory && temporary == other.temporary
    override fun hashCode() = 31 * (31 * packs.hashCode() + (originalDirectory?.hashCode() ?: 0)) + temporary.hashCode()
    override fun toString() = "VisualAssetSelection(packs=$packs, originalDirectory=$originalDirectory, temporary=$temporary)"
}
