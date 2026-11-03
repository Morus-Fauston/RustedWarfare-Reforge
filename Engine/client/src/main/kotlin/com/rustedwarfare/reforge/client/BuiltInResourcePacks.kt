package com.rustedwarfare.reforge.client

import java.util.Collections

/** The legacy source names are adapters to these two declarative pack identities. */
object BuiltInResourcePacks {
    private val packs by lazy {
        Collections.unmodifiableMap(VisualAssetSource.entries.associateWith { source ->
            val path = "/resource-packs/${source.resourcePackId.substringAfter(':')}/pack.json"
            val bytes = requireNotNull(javaClass.getResourceAsStream(path)) {
                "RESOURCE_PACK_METADATA_INVALID: $path: Missing bundled metadata"
            }.use { it.readBytes() }
            ResourcePackMetadata.read(bytes, path).also {
                require(it.id.value == source.resourcePackId) { "RESOURCE_PACK_METADATA_INVALID: $path / id: Wrong built-in identity" }
            }
        })
    }
    val all: List<ResourcePackMetadata> get() = Collections.unmodifiableList(packs.values.toList())
    fun forSource(source: VisualAssetSource): ResourcePackMetadata = packs.getValue(source)
}
