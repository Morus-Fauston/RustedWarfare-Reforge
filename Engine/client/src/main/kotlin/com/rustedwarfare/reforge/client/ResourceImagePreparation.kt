package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.PngImages
import java.nio.file.Files

/** Worker adapter for already prepared built-in/content images; the composer itself stays IO-free. */
object ResourceImagePreparation {
    fun baseLayer(prepared: PreparedVisualAssets, metadata: ResourcePackMetadata): ResourceImageLayer {
        prepared.verify()
        val frames = prepared.resourcePack.unitBindings.values.associate { it.body to it.totalFrames }
        val nominal = prepared.resourcePack.unitBindings.values.flatMap { binding ->
            listOf(binding.body to binding.logicalImages?.body, binding.back to binding.logicalImages?.back,
                binding.turret to binding.logicalImages?.turret, binding.dead to binding.logicalImages?.dead,
                binding.leg to binding.logicalImages?.leg, binding.foot to binding.logicalImages?.foot,
                binding.rotor to binding.logicalImages?.rotor)
        }.filter { it.first != null && it.second != null }.associate { requireNotNull(it.first) to requireNotNull(it.second) }
        val images = prepared.resourcePack.resources.mapValues { (id, path) ->
            val bytes = Files.newInputStream(path).use { it.readNBytes(16 * 1024 * 1024 + 1) }
            val dimensions = PngImages.inspect(bytes, allowRgb = true)
            val count = frames[id] ?: 1
            require(dimensions.width % count == 0) { "RESOURCE_PACK_BINDING_INVALID: $id / $path: Invalid base frames" }
            val logical = nominal[id]
            PreparedResourceImage(path, ResourceImageDeclaration(path.fileName.toString(), logical?.width ?: dimensions.width / count,
                logical?.height ?: dimensions.height, count), dimensions.width, dimensions.height)
        }
        prepared.verify()
        return ResourceImageLayer(metadata, images, prepared.identity)
    }
}
