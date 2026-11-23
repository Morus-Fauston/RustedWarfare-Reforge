package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcOriginalDirectLaunchTest {

    @Test
    fun `bundled PC original image profile maps all 66 units including repairbay experimentalLandFactory silos and hoverTank`() {
        val pcProfile = PcOriginalImageProfile.bundled()
        val mapped = requireNotNull(pcProfile.mappedImages) { "PC original profile must carry mappedImages" }
        val unitBindings = mapped.json.get("unitBindings")
        val names = mutableSetOf<String>()
        var node = unitBindings.child
        while (node != null) {
            node.name?.let(names::add)
            node = node.next
        }
        assertEquals(66, names.size, "Expected 66 mapped units in PC original profile")
        for (required in listOf(
            "repairbay",
            "experimentalLandFactory",
            "nukeLauncherC",
            "antiNukeLauncherC",
            "hoverTank",
            "modularSpider",
            "nautilusSubmarine",
            "heavyBattleship",
        )) {
            assertTrue(required in names, "Missing expected PC original unit mapping: $required")
        }
    }

    @Test
    fun `direct --original-assets launch selects PC mapped profile when game-lib jar is present`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedPcMappedInstallation(fixture.root)
            val options = WindowsLaunchOptions.parse(
                arrayOf("--mode", "demo", "--original-assets", source.toString()),
                fixture.root,
            )
            val backend = PcVisualAssetSettings(
                fixture.store,
                fixture.cache,
                fixture.root,
                options,
                imageExtractionProfile = profile,
            )
            assertEquals(VisualAssetSource.ORIGINAL, backend.initialSelection.source)
            val prepared = backend.prepare(backend.initialSelection)
            prepared.verifyForPublication()
            assertEquals(profile.mappedImages!!.id, prepared.profileId)
            assertTrue(prepared.units.containsKey("c_tank"))
            assertEquals(profile.mappedImages.id, backend.preparationDiagnostics["activeProfileId"])
            assertEquals(prepared.units.size, backend.preparationDiagnostics["activeUnitVisualCount"])
        }
    }

    private fun ownedPcMappedInstallation(root: Path): Pair<Path, PcOriginalImageProfile> {
        val dir = Files.createDirectories(root.resolve("synthetic-pc-install"))
        val jarBytes = "synthetic-pc-game-lib".toByteArray(Charsets.UTF_8)
        Files.write(dir.resolve("game-lib.jar"), jarBytes)
        val resDir = Files.createDirectories(dir.resolve("res/drawable"))
        val png = java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 20) for (y in 0 until 20) png.setRGB(x, y, 0xFF10C810.toInt())
        val imgFile = resDir.resolve("tank.png")
        javax.imageio.ImageIO.write(png, "png", imgFile.toFile())
        val imgBytes = Files.readAllBytes(imgFile)
        val imgSha = visualHash(imgBytes)
        val jarSha = visualHash(jarBytes)
        val mappedJson = """
            {
              "schemaVersion": 1,
              "profileId": "synthetic.pc.mapped.v1",
              "identity": {
                "manifestPath": "game-lib.jar",
                "manifestBytes": ${jarBytes.size},
                "manifestSha256": "$jarSha",
                "scope": "Synthetic test"
              },
              "sources": [
                {"id": "tank", "path": "res/drawable/tank.png", "bytes": ${imgBytes.size}, "sha256": "$imgSha", "width": 20, "height": 20}
              ],
              "unitOutputs": {
                "body": {"file": "units/body.png", "operation": "crop", "source": "tank", "rect": [0, 0, 20, 20], "target": [20, 20], "frames": 1}
              },
              "terrainOutputs": {},
              "unitBindings": {
                "c_tank": {"body": "body"}
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val mappedProfile = VisualAssetProfile.parse(mappedJson)
        val pcProfile = PcOriginalImageProfile(
            id = "synthetic.pc.v1",
            jarBytes = jarBytes.size,
            jarSha256 = jarSha,
            images = listOf(PcOriginalImageSource("res/drawable/tank.png", imgBytes.size, imgSha, "PNG", 20, 20)),
            mappedImages = mappedProfile,
        )
        return dir to pcProfile
    }
}
