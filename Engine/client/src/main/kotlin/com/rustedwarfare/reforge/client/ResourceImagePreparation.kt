package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.PngImages
import java.nio.file.Files

/** Worker adapter for already prepared built-in/content images; the composer itself stays IO-free. */
object ResourceImagePreparation {
    fun baseLayer(prepared: PreparedVisualAssets, metadata: ResourcePackMetadata): ResourceImageLayer {
        prepared.verify()
        val bindings = prepared.resourcePack.unitBindings.values.flatMap { it.bindings().images.values }
            .associateBy { it.resource }
        val images = prepared.resourcePack.resources.mapValues { (id, path) ->
            val bytes = Files.newInputStream(path).use { it.readNBytes(16 * 1024 * 1024 + 1) }
            val dimensions = PngImages.inspect(bytes, allowRgb = true)
            val count = bindings[id]?.totalFrames ?: 1
            require(dimensions.width % count == 0) { "RESOURCE_PACK_BINDING_INVALID: $id / $path: Invalid base frames" }
            val logical = bindings[id]?.logicalSize
            PreparedResourceImage(path, ResourceImageDeclaration(path.fileName.toString(), logical?.width ?: dimensions.width / count,
                logical?.height ?: dimensions.height, count), dimensions.width, dimensions.height)
        }
        prepared.verify()
        return ResourceImageLayer(metadata, images, prepared.identity)
    }
}
