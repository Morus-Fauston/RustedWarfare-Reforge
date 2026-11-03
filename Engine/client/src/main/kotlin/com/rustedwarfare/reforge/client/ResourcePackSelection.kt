package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.util.Collections

data class ResourcePackCatalogEntry(val id: String, val manifest: Path, val identity: String) {
    init {
        ResourceId.parse(id)
        require(VisualAssetSource.entries.none { it.resourcePackId == id }) { "A user pack cannot claim a built-in ID: $id" }
        require(manifest.isAbsolute && manifest == manifest.normalize() &&
            manifest.fileName?.toString() in setOf("pack.json", "pack.hjson")) { "Expected an absolute normalized pack metadata path: $manifest" }
        require(identity.matches(Regex("[0-9a-f]{64}"))) { "Expected confirmed SHA-256 identity for $id" }
    }
}

/** User catalog and enabled order are separate. The public base is always implicitly last. */
class ResourcePackSelection(catalog: List<ResourcePackCatalogEntry> = emptyList(), enabled: List<String> = emptyList()) {
    val catalog: List<ResourcePackCatalogEntry> = Collections.unmodifiableList(ArrayList(catalog))
    val enabled: List<String> = Collections.unmodifiableList(ArrayList(enabled))
    val source: VisualAssetSource get() = if (VisualAssetSource.ORIGINAL.resourcePackId in enabled)
        VisualAssetSource.ORIGINAL else VisualAssetSource.PLACEHOLDER

    init {
        require(this.catalog.map { it.id }.distinct().size == this.catalog.size) { "Duplicate catalog pack ID" }
        require(this.catalog.map { it.manifest }.distinct().size == this.catalog.size) { "Duplicate catalog metadata path" }
        require(this.enabled.distinct().size == this.enabled.size) { "Duplicate enabled resource pack ID" }
        val known = this.catalog.map { it.id }.toSet() + VisualAssetSource.ORIGINAL.resourcePackId
        require(this.enabled.all { it in known }) { "Unknown enabled resource pack ID (base is implicit)" }
    }

    fun withSource(value: VisualAssetSource): ResourcePackSelection = if (source == value) this else
        ResourcePackSelection(catalog, if (value == VisualAssetSource.ORIGINAL) enabled + value.resourcePackId
            else enabled.filterNot { it == VisualAssetSource.ORIGINAL.resourcePackId })

    override fun equals(other: Any?) = other is ResourcePackSelection && catalog == other.catalog && enabled == other.enabled
    override fun hashCode() = 31 * catalog.hashCode() + enabled.hashCode()
    override fun toString() = "ResourcePackSelection(catalog=$catalog, enabled=$enabled)"
}
