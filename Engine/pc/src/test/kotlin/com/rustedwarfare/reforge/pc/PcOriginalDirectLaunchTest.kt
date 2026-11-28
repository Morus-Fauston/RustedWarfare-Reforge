package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.BootstrapScreen
import com.rustedwarfare.reforge.client.UserSettings
import com.rustedwarfare.reforge.client.UserSettingsStore
import com.rustedwarfare.reforge.client.VisualAssetSettingsSession
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcOriginalDirectLaunchTest {

    @Test
    fun `bundled PC original image profile maps all 115 units including repairbay experimentalLandFactory silos and hoverTank`() {
        val pcProfile = PcOriginalImageProfile.bundled()
        val mapped = requireNotNull(pcProfile.mappedImages) { "PC original profile must carry mappedImages" }
        val unitBindings = mapped.json.get("unitBindings")
        val names = mutableSetOf<String>()
        var node = unitBindings.child
        while (node != null) {
            node.name?.let(names::add)
            node = node.next
        }
        assertEquals(115, names.size, "Expected 115 mapped units in PC original profile")
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

    @Test
    fun `when local Steam Rusted Warfare is present on workstation, direct ORIGINAL resource pack prepares cleanly`() {
        val steamRoot = Path.of("E:/SteamLibrary/steamapps/common/Rusted Warfare")
        if (!Files.isRegularFile(steamRoot.resolve("game-lib.jar"))) return
        ResourceImagePackApplicationFixture().use { fixture ->
            val options = WindowsLaunchOptions.parse(
                arrayOf("--mode", "demo", "--original-assets", steamRoot.toString()),
                fixture.root,
            )
            val backend = PcVisualAssetSettings(
                fixture.store,
                fixture.cache,
                fixture.root,
                options,
            )
            val prepared = backend.prepare(backend.initialSelection)
            prepared.verifyForPublication()
            assertEquals(65, prepared.units.size)
        }
    }

    @Test
    fun `temporary CLI visual override never overwrites UserSettingsStore and CATALOG is preserved across startup reconciliation`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedPcMappedInstallation(fixture.root)
            // 1. Create and import an external pack so catalog is non-empty on restart (`Operation.CATALOG`)
            val extDir = fixture.create("fixture:ext-pack", listOf(OwnPackImage("reforge:units/c_tank/body"))).manifest.parent
            val initOpts = WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root)
            val initBackend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root, initOpts, imageExtractionProfile = profile)
            VisualAssetSettingsSession(initBackend).use { s ->
                s.importImagePack(extDir)
                awaitIdle(s)
                s.apply()
                awaitIdle(s)
            }

            // 2. Launch with temporary CLI `--original-assets`: must NEVER persist `reforge:original-android-1.15-176` into settings.json
            val userStore = UserSettingsStore(fixture.root.resolve("settings.json"))
            val cliOpts = WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--original-assets", source.toString()), fixture.root)
            val cliBackend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root, cliOpts, imageExtractionProfile = profile)
            assertTrue(cliBackend.initialSelection.temporary)
            val cliScreen = BootstrapScreen(visualSettings = cliBackend, userSettingsStore = userStore)
            try {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (System.nanoTime() < deadline) {
                    cliScreen.pollVisualStateForTest()
                    if (cliScreen.currentVisualRowsForTest.firstOrNull { it.id == "fixture:ext-pack" }?.canEnable == true) break
                    Thread.sleep(5)
                }
                assertEquals(listOf(UserSettings.BASE_RESOURCE_PACK_ID), userStore.saved.enabledResourcePacks)
            } finally {
                cliScreen.dispose()
            }

            // 3. Restart normally while settings.json wants `ORIGINAL` AND `Operation.CATALOG` runs on startup:
            //    `CATALOG` must NOT be cancelled (`fixture:ext-pack` stays `canEnable == true`), and `ensureOriginalDirectoryIfMissing`
            //    preserves discovered `originalDirectory` across `PREVIEW` completion!
            val normalBackend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root, initOpts, imageExtractionProfile = profile)
            VisualAssetSettingsSession(normalBackend).use { previewSession ->
                awaitIdle(previewSession)
                assertFalse(previewSession.isPreviewBusy)
                assertTrue(previewSession.ensurePreviews())
                assertTrue(previewSession.isPreviewBusy)
                previewSession.ensureOriginalDirectoryIfMissing(source)
                awaitIdle(previewSession)
                assertEquals(source.toAbsolutePath().normalize(), previewSession.selection.originalDirectory)
            }

            userStore.update {
                it.copy(enabledResourcePacks = listOf(VisualAssetSource.ORIGINAL.resourcePackId, UserSettings.BASE_RESOURCE_PACK_ID))
            }
            val restartBackend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root, initOpts, imageExtractionProfile = profile)
            val restartScreen = BootstrapScreen(visualSettings = restartBackend, userSettingsStore = userStore)
            try {
                // `Operation.CATALOG` is active right after construction -> reconcile defers instead of cancelling `CATALOG`
                restartScreen.reconcileSavedResourcePacksForTest()
                val deadline = System.nanoTime() + 10_000_000_000L
                while (System.nanoTime() < deadline) {
                    restartScreen.pollVisualStateForTest()
                    if (restartScreen.currentVisualRowsForTest.firstOrNull { it.id == "fixture:ext-pack" }?.canEnable == true) break
                    Thread.sleep(5)
                }
                val extRow = restartScreen.currentVisualRowsForTest.first { it.id == "fixture:ext-pack" }
                assertTrue(extRow.canEnable, "Saved external pack must retain checked metadata (`canEnable == true`), error=${extRow.error}")
            } finally {
                restartScreen.dispose()
            }
        }
    }

    private fun awaitIdle(session: VisualAssetSettingsSession) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (session.busy && System.nanoTime() < deadline) {
            session.poll()
            Thread.sleep(5)
        }
        session.poll()
        assertFalse(session.busy, "Timed out waiting for VisualAssetSettingsSession")
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
