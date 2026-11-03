package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedImagePack
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

internal data class OwnPackImage(val id: String, val frames: Int = 3, val width: Int = 20, val height: Int = 20,
    val scale: Int = 2, val color: Int = 0xffc08020.toInt())

/** Only self-authored tiny PNGs; source and cache are distinct owned temporary domains. */
internal class ResourceImagePackApplicationFixture : AutoCloseable {
    val root = Files.createTempDirectory("image-pack-application-").toAbsolutePath().normalize()
    val cache = root.resolve("cache")
    val settings = root.resolve("settings.properties")
    val store = VisualAssetSettingsStore(settings)
    fun create(id: String, images: List<OwnPackImage>): PreparedImagePack {
        val directory = Files.createDirectories(root.resolve("source-${id.substringAfter(':')}"))
        val definitions = images.mapIndexed { index, image ->
            val png = BufferedImage(image.width * image.frames * image.scale, image.height * image.scale, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until png.width) for (y in 0 until png.height) png.setRGB(x, y, image.color)
            ImageIO.write(png, "png", directory.resolve("image$index.png").toFile())
            "\"${image.id}\":{\"file\":\"image$index.png\",\"frameWidth\":${image.width},\"frameHeight\":${image.height},\"totalFrames\":${image.frames}}"
        }.joinToString(",")
        Files.writeString(directory.resolve("images.json"), "{\"formatVersion\":1,\"images\":{$definitions}}")
        Files.writeString(directory.resolve("pack.json"), """{"formatVersion":1,"requiresResourceFormat":1,"id":"$id","name":"$id 自有","description":"Actual self-owned PNG application fixture","icon":"${images.first().id}"}""")
        return PcResourcePackImporter(cache).load(directory)
    }

    fun backend(arguments: Array<String> = arrayOf("--mode", "demo")) = PcVisualAssetSettings(store, cache, root,
        WindowsLaunchOptions.parse(arguments, root))

    override fun close() {
        require(root.isAbsolute && root.fileName.toString().startsWith("image-pack-application-"))
        root.toFile().deleteRecursively()
    }
}
