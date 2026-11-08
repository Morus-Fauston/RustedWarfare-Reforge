package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.util.Collections
import java.util.Locale

data class VisualUnitResourceIds(val body: ResourceId, val back: ResourceId?, val turret: ResourceId?,
    val dead: ResourceId?, val totalFrames: Int, val logicalImages: UnitImageLogicalSizes? = null,
    val leg: ResourceId? = null, val foot: ResourceId? = null, val rotor: ResourceId? = null,
    val weaponTurrets: Map<String, ResourceId> = emptyMap(), val shadow: ResourceId? = null, val shield: ResourceId? = null)

/** Immutable logical bindings for an already prepared generation; lookup never performs IO. */
class PreparedResourcePack private constructor(val metadata: ResourcePackMetadata, resources: Map<ResourceId, Path>,
    unitBindings: Map<String, VisualUnitResourceIds>, terrainBindings: Map<String, ResourceId>) {
    val resources: Map<ResourceId, Path> = Collections.unmodifiableMap(LinkedHashMap(resources))
    val unitBindings: Map<String, VisualUnitResourceIds> = Collections.unmodifiableMap(LinkedHashMap(unitBindings))
    val terrainBindings: Map<String, ResourceId> = Collections.unmodifiableMap(LinkedHashMap(terrainBindings))

    fun resolve(id: ResourceId): Path = requireNotNull(resources[id]) {
        "RESOURCE_PACK_BINDING_INVALID: ${metadata.id} / $id: Missing prepared resource"
    }

    companion object {
        internal fun fromBindings(metadata: ResourcePackMetadata, units: Map<String, VisualUnitImages>,
            terrain: Map<String, Path>): PreparedResourcePack {
            val resources = linkedMapOf<ResourceId, Path>()
            fun bind(address: String, path: Path): ResourceId {
                val id = ResourceId.parse(address)
                require(resources.putIfAbsent(id, path) == null) {
                    "RESOURCE_PACK_BINDING_INVALID: ${metadata.id} / $id: Conflicting logical resource identity"
                }
                return id
            }
            val unitBindings = units.mapValues { (unitId, images) ->
                val prefix = "reforge:units/${unitId.lowercase(Locale.ROOT)}/"
                require(images.totalFrames > 0) { "RESOURCE_PACK_BINDING_INVALID: ${metadata.id} / $unitId: Invalid frame count" }
                images.bindings().map { part, image ->
                    BoundUnitImage(bind(prefix + part.suffix, image.resource), image.logicalSize, image.totalFrames)
                }.resourceIds()
            }
            val terrainBindings = terrain.mapValues { (layout, image) -> bind("reforge:terrain/$layout", image) }
            return PreparedResourcePack(metadata, resources, unitBindings, terrainBindings)
        }
    }
}
