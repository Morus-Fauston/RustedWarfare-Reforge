package com.rustedwarfare.reforge.pc

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** Test-only profile construction; production never exposes a profile option. */
internal class VisualAssetTestFixture(recipe: String = """"unitOutputs":{"body":{"file":"units/body.png","operation":"crop","source":"own","rect":[0,0,2,1],"target":[2,1]}},"terrainOutputs":{}""",
    path: String = "own.png", transformProfile: (String) -> String = { it },
    transformImage: (ByteArray) -> ByteArray = { it }) : AutoCloseable {
    private val temporary = Files.createTempDirectory("visual-assets-自有-")
    val root = Files.createDirectory(temporary.resolve("source"))
    val cache = temporary.resolve("cache")
    val settings = temporary.resolve("settings.properties")
    val source = root.resolve(path)
    val profile: VisualAssetProfile
    init {
        val manifest = "self-owned visual test".toByteArray()
        Files.write(root.resolve("AndroidManifest.xml"), manifest)
        val image = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xffff0000.toInt())
        image.setRGB(1, 0, 0xff00ff00.toInt())
        image.setRGB(2, 0, 0x00000000)
        image.setRGB(3, 0, 0x800000ff.toInt())
        Files.createDirectories(source.parent)
        ImageIO.write(image, "png", source.toFile())
        val bytes = transformImage(Files.readAllBytes(source))
        Files.write(source, bytes)
        profile = VisualAssetProfile.parse(transformProfile("""{"schemaVersion":1,"profileId":"self-owned",
          "identity":{"manifestPath":"AndroidManifest.xml","manifestBytes":${manifest.size},"manifestSha256":"${visualHash(manifest)}"},
          "sources":[{"id":"own","path":"$path","bytes":${bytes.size},"sha256":"${visualHash(bytes)}","width":4,"height":1}],
          "unitBindings":{"own":{"body":"body"}},$recipe} """).toByteArray())
    }
    fun service() = PcVisualAssetService(cache, profile)
    override fun close() { temporary.toFile().deleteRecursively() }
}
