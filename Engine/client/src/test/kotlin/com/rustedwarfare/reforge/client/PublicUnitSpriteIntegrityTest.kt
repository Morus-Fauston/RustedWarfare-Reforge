package com.rustedwarfare.reforge.client

import com.badlogic.gdx.utils.JsonReader
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-P003-003 自有公开占位单位素材全量自动化完整性测试：
 * 1. 验证 `phase-seven-sandbox` 全部 145 张 PNG 像素尺寸（零静默跳过）、多帧可整除性与全部相邻帧动效差异、
 *    活体图合规纯绿通道像素（直接复用 MatchPlayerColors.PURE_GREEN_THRESHOLD 且零非绿灰化误触发），
 *    以及残骸/阴影/护盾/光束/槽位图零纯绿误染；
 * 2. 验证随包 `Engine/client/src/main/resources/visuals/placeholder/units/` 全部 28 张单位图与
 *    `manifest.json` 的尺寸、帧数、相邻帧动效、来源路径逐字节一致、字节数与 SHA-256 严格一致；
 * 3. 验证所有 13 张占位 tilesets（12 地块 + units_markers）完整保留且 `manifest.json` 零 `phase-six-a-sandbox` 来源引用。
 */
class PublicUnitSpriteIntegrityTest {

    private fun repoRoot(): Path {
        var cur = Path.of("").toAbsolutePath().normalize()
        while (cur.parent != null) {
            if (Files.isDirectory(cur.resolve("Engine")) && Files.isDirectory(cur.resolve("SimulationTest"))) {
                return cur
            }
            cur = cur.parent
        }
        error("Cannot locate repository root from ${Path.of("").toAbsolutePath()}")
    }

    private data class PixelAudit(
        val pureGreen: Int,
        val grayedAccidental: Int,
        val opaque: Int,
    )

    private fun auditPixels(image: BufferedImage): PixelAudit {
        var pureGreen = 0
        var grayedAccidental = 0
        var opaque = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                if (a == 0) continue
                opaque++
                val r = ((argb ushr 16) and 0xFF) / 255.0f
                val g = ((argb ushr 8) and 0xFF) / 255.0f
                val b = (argb and 0xFF) / 255.0f
                if (g > 0.0f && abs(r - b) <= MatchPlayerColors.PURE_GREEN_THRESHOLD) {
                    if (g > r) {
                        pureGreen++
                    } else {
                        grayedAccidental++
                    }
                }
            }
        }
        return PixelAudit(pureGreen, grayedAccidental, opaque)
    }

    private fun assertAllAdjacentFramesDiffer(image: BufferedImage, frames: Int, label: String) {
        assertEquals(0, image.width % frames, "Width must be divisible by $frames frames for $label")
        val frameW = image.width / frames
        for (f in 0 until frames - 1) {
            var diffs = 0
            for (y in 0 until image.height) {
                for (x in 0 until frameW) {
                    if (image.getRGB(f * frameW + x, y) != image.getRGB((f + 1) * frameW + x, y)) {
                        diffs++
                    }
                }
            }
            assertTrue(diffs > 0, "Animated sheet $label frame $f -> ${f + 1} must have visible motion")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun phase_seven_sandbox_all_145_pngs_match_contract_dimensions_frames_and_pure_green_rules() {
        val packageRoot = repoRoot().resolve("SimulationTest/public/inputs/phase-seven-sandbox/json/package")
        assertTrue(Files.isDirectory(packageRoot), "Missing phase-seven-sandbox package root: $packageRoot")

        val allPngs = Files.walk(packageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .sorted()
                .toList()
        }
        assertEquals(145, allPngs.size, "Expected exactly 145 PNG files in phase-seven-sandbox")

        val expectedDimensions = mapOf(
            "extra/air-products/units/aa.png" to (33 to 52),
            "extra/air-products/units/aa_beam.png" to (13 to 20),
            "extra/air-products/units/aa_beam_end.png" to (20 to 20),
            "extra/air-products/units/aa_beam_start.png" to (20 to 20),
            "extra/air-products/units/aa_dead.png" to (33 to 52),
            "extra/air-products/units/aa_shadow.png" to (33 to 52),
            "extra/air-products/units/aa_trail.png" to (32 to 32),
            "extra/air-products/units/aa_turret.png" to (16 to 26),
            "extra/air-products/units/dropship.png" to (45 to 47),
            "extra/air-products/units/dropship_dead.png" to (45 to 47),
            "extra/air-products/units/dropship_shadow.png" to (45 to 47),
            "extra/air-products/units/jet.png" to (15 to 26),
            "extra/air-products/units/jet_dead.png" to (15 to 26),
            "extra/air-products/units/jet_left.png" to (30 to 14),
            "extra/air-products/units/jet_right.png" to (30 to 14),
            "extra/air-products/units/jet_shadow.png" to (15 to 26),
            "extra/bunker/units/bunker/body.png" to (39 to 49),
            "extra/bunker/units/bunker/foot.png" to (17 to 36),
            "extra/bunker/units/bunker/leg.png" to (16 to 68),
            "extra/bunker/units/bunker/shadow.png" to (39 to 49),
            "extra/bunker/units/bunker/shield.png" to (92 to 92),
            "extra/bunker/units/bunker/turret.png" to (14 to 57),
            "extra/command-center/units/commandCenter/base.png" to (212 to 68),
            "extra/command-center/units/commandCenter/base_back.png" to (53 to 68),
            "extra/command-center/units/commandCenter/base_dead.png" to (53 to 74),
            "extra/flame/units/flame/body.png" to (45 to 87),
            "extra/flame/units/flame/foot.png" to (17 to 36),
            "extra/flame/units/flame/leg.png" to (16 to 68),
            "extra/flame/units/flame/shadow.png" to (45 to 87),
            "extra/nautilus/nautilus/body.png" to (42 to 90),
            "extra/nautilus/nautilus/dead.png" to (42 to 90),
            "extra/nautilus/nautilus/foot.png" to (17 to 36),
            "extra/nautilus/nautilus/hover/shadow.png" to (17 to 24),
            "extra/nautilus/nautilus/hover/tank.png" to (17 to 24),
            "extra/nautilus/nautilus/hover/tank_dead.png" to (17 to 24),
            "extra/nautilus/nautilus/leg.png" to (16 to 68),
            "extra/nautilus/nautilus/scout/base.png" to (24 to 21),
            "extra/nautilus/nautilus/scout/dead.png" to (24 to 21),
            "extra/nautilus/nautilus/scout/foot.png" to (8 to 8),
            "extra/nautilus/nautilus/scout/leg.png" to (24 to 102),
            "extra/nautilus/nautilus/scout/turret.png" to (8 to 15),
            "extra/nautilus/units/experimental_back.png" to (100 to 115),
            "extra/nautilus/units/experimental_dead.png" to (100 to 115),
            "extra/nautilus/units/experimental_front.png" to (100 to 115),
            "extra/silos/anti-arm1.png" to (13 to 11),
            "extra/silos/anti-arm2.png" to (12 to 11),
            "extra/silos/anti-back.png" to (40 to 48),
            "extra/silos/anti-body.png" to (40 to 48),
            "extra/silos/anti-dead.png" to (40 to 48),
            "extra/silos/arm1.png" to (38 to 29),
            "extra/silos/arm2.png" to (42 to 29),
            "extra/silos/back.png" to (84 to 70),
            "extra/silos/body.png" to (84 to 70),
            "extra/silos/dead.png" to (84 to 70),
            "units/air-factory/bomber_body.png" to (45 to 47),
            "units/air-factory/bomber_dead.png" to (45 to 47),
            "units/air-factory/bomber_shadow.png" to (45 to 47),
            "units/air-factory/units/air_factory.png" to (40 to 61),
            "units/air-factory/units/air_factory_dead.png" to (40 to 61),
            "units/air-factory/units/air_factory_t2.png" to (40 to 63),
            "units/air-factory/units/body.png" to (26 to 46),
            "units/air-factory/units/dead.png" to (26 to 46),
            "units/air-factory/units/rotor.png" to (46 to 20),
            "units/body.png" to (18 to 31),
            "units/economy/extractor_back.png" to (51 to 44),
            "units/economy/extractor_t1.png" to (148 to 59),
            "units/economy/extractor_t2.png" to (148 to 59),
            "units/economy/extractor_t3.png" to (148 to 59),
            "units/economy/fabricatorT1.png" to (141 to 60),
            "units/economy/fabricatorT2.png" to (141 to 60),
            "units/economy/fabricatorT3.png" to (141 to 60),
            "units/economy/fabricator_dead.png" to (47 to 60),
            "units/economy/factory.png" to (50 to 74),
            "units/economy/factory_back.png" to (50 to 74),
            "units/economy/factory_dead.png" to (50 to 69),
            "units/economy/laser_defence.png" to (72 to 36),
            "units/economy/laser_defence_dead.png" to (36 to 36),
            "units/economy/turret.png" to (40 to 40),
            "units/economy/turret_base2.png" to (40 to 40),
            "units/economy/turret_dead.png" to (40 to 40),
            "units/economy/turret_top.png" to (40 to 40),
            "units/economy/turret_top_artillery.png" to (40 to 40),
            "units/economy/turret_top_l2.png" to (40 to 40),
            "units/foot.png" to (6 to 18),
            "units/leg.png" to (47 to 7),
            "units/mech-factory/builder.png" to (20 to 20),
            "units/mech-factory/builder_dead.png" to (20 to 20),
            "units/mech-factory/engineer/blank.png" to (1 to 1),
            "units/mech-factory/engineer/body.png" to (40 to 44),
            "units/mech-factory/engineer/cannon.png" to (28 to 35),
            "units/mech-factory/engineer/dead.png" to (38 to 44),
            "units/mech-factory/engineer/foot.png" to (26 to 33),
            "units/mech-factory/engineer/leg.png" to (19 to 82),
            "units/mech-factory/factory.png" to (74 to 106),
            "units/mech-factory/factory_back.png" to (74 to 106),
            "units/mech-factory/factory_dead.png" to (72 to 88),
            "units/mech-factory/factory_t2.png" to (74 to 106),
            "units/mech-factory/heavy/body.png" to (25 to 39),
            "units/mech-factory/heavy/foot.png" to (11 to 18),
            "units/mech-factory/heavy/leg.png" to (16 to 68),
            "units/mech-factory/heavy/shield.png" to (92 to 92),
            "units/mech-factory/heavy/small_turret.png" to (11 to 24),
            "units/mech-factory/heavy/turret.png" to (35 to 28),
            "units/repairbay/body.png" to (62 to 76),
            "units/repairbay/dead.png" to (60 to 76),
            "units/sea-factory/hovercraft.png" to (20 to 32),
            "units/sea-factory/hovercraft_dead.png" to (20 to 32),
            "units/sea-factory/land/tank.png" to (60 to 20),
            "units/sea-factory/land/tank_dead.png" to (20 to 20),
            "units/sea-factory/land/tank_turret.png" to (20 to 20),
            "units/sea-factory/naval/gunboat-wreck.png" to (15 to 27),
            "units/sea-factory/naval/gunboat.png" to (15 to 27),
            "units/sea-factory/naval/heavy_battleship.png" to (20 to 102),
            "units/sea-factory/naval/heavy_battleship_dead.png" to (20 to 102),
            "units/sea-factory/naval/large_turret.png" to (12 to 34),
            "units/sea-factory/sea_factory.png" to (90 to 99),
            "units/sea-factory/sea_factory_dead.png" to (90 to 99),
            "units/sea-factory/sea_factory_t2.png" to (90 to 99),
            "units/sea-factory/submarine/body.png" to (12 to 40),
            "units/sea-factory/submarine/dead.png" to (12 to 40),
            "units/spider/aa-t2.png" to (25 to 24),
            "units/spider/aa.png" to (16 to 26),
            "units/spider/anti-nuke.png" to (22 to 21),
            "units/spider/artillery-base.png" to (20 to 19),
            "units/spider/artillery.png" to (7 to 35),
            "units/spider/blink.png" to (22 to 21),
            "units/spider/body.png" to (81 to 113),
            "units/spider/dead.png" to (70 to 90),
            "units/spider/empty.png" to (1 to 1),
            "units/spider/fabricator-t2.png" to (20 to 19),
            "units/spider/fabricator.png" to (20 to 19),
            "units/spider/flak.png" to (29 to 32),
            "units/spider/foot.png" to (17 to 22),
            "units/spider/gun-t2.png" to (16 to 46),
            "units/spider/gun.png" to (14 to 46),
            "units/spider/laser-defense.png" to (20 to 19),
            "units/spider/leg.png" to (10 to 84),
            "units/spider/lightning.png" to (22 to 31),
            "units/spider/nano.png" to (11 to 19),
            "units/spider/shadow.png" to (81 to 113),
            "units/spider/shield.png" to (22 to 21),
            "units/spider/slot-ui.png" to (82 to 114),
            "units/spider/small-laser-t2.png" to (14 to 24),
            "units/spider/small-laser.png" to (8 to 34),
            "units/spider/speed.png" to (22 to 21),
        )

        val allRelPaths = allPngs.map { packageRoot.relativize(it).toString().replace('\\', '/') }.toSet()
        assertEquals(expectedDimensions.keys, allRelPaths, "Expected dimension contract to cover all 145 PNGs with zero omissions")

        val multiFrameSheets = mapOf(
            "extra/command-center/units/commandCenter/base.png" to 4,
            "units/economy/extractor_t1.png" to 4,
            "units/economy/extractor_t2.png" to 4,
            "units/economy/extractor_t3.png" to 4,
            "units/economy/fabricatorT1.png" to 3,
            "units/economy/fabricatorT2.png" to 3,
            "units/economy/fabricatorT3.png" to 3,
            "units/economy/laser_defence.png" to 2,
            "units/sea-factory/land/tank.png" to 3,
        )

        val nonTintExact = setOf(
            "extra/air-products/units/aa_beam.png",
            "extra/air-products/units/aa_beam_end.png",
            "extra/air-products/units/aa_beam_start.png",
            "extra/air-products/units/aa_trail.png",
            "extra/bunker/units/bunker/shield.png",
            "units/mech-factory/heavy/shield.png",
            "units/mech-factory/engineer/blank.png",
            "units/spider/empty.png",
            "units/spider/slot-ui.png",
        )

        for (file in allPngs) {
            val rel = packageRoot.relativize(file).toString().replace('\\', '/')
            val img = ImageIO.read(file.toFile())
            assertTrue(img != null, "Failed to decode PNG: $rel")

            val (ew, eh) = requireNotNull(expectedDimensions[rel]) { "Missing expected dimension entry for $rel" }
            assertEquals(ew, img.width, "Width mismatch for $rel")
            assertEquals(eh, img.height, "Height mismatch for $rel")

            multiFrameSheets[rel]?.let { frames ->
                assertAllAdjacentFramesDiffer(img, frames, rel)
            }

            val audit = auditPixels(img)
            val low = rel.lowercase()
            val isNonTint = rel in nonTintExact || "dead" in low || "wreck" in low || "shadow" in low
            if (isNonTint) {
                assertEquals(0, audit.pureGreen, "Non-tint sprite $rel must have 0 pure-green pixels, found ${audit.pureGreen}")
            } else {
                assertTrue(audit.opaque > 0, "Live sprite $rel must not be empty")
                assertTrue(audit.pureGreen > 0, "Live sprite $rel must contain compliant pure-green player-color pixels")
                assertEquals(0, audit.grayedAccidental, "Live sprite $rel must not have accidental r~=b non-green pixels entering shader branch")
            }
        }
    }

    @Test
    fun builtin_placeholder_28_units_and_manifest_hashes_are_synced_and_pure_green_compliant() {
        val root = repoRoot()
        val placeholderRoot = root.resolve("Engine/client/src/main/resources/visuals/placeholder")
        val manifestPath = placeholderRoot.resolve("manifest.json")
        val manifest = JsonReader().parse(Files.readString(manifestPath))
        val files = manifest.get("files")

        var unitCount = 0
        var terrainCount = 0
        var entry = files.child
        while (entry != null) {
            val resource = entry.getString("resource")
            val source = entry.getString("source")
            val expectedBytes = entry.getInt("bytes")
            val expectedSha = entry.getString("sha256")
            val expectedW = entry.getInt("width")
            val expectedH = entry.getInt("height")
            val expectedFrames = entry.getInt("frames")

            assertFalse(source.contains("phase-six-a-sandbox"), "Manifest source must not reference phase-six-a-sandbox: $source")

            val filePath = placeholderRoot.resolve(resource)
            assertTrue(Files.isRegularFile(filePath), "Missing manifest resource: $resource")
            val rawBytes = Files.readAllBytes(filePath)
            assertEquals(expectedBytes, rawBytes.size, "Byte size mismatch in manifest for $resource")
            assertEquals(expectedSha, sha256Hex(rawBytes), "SHA-256 mismatch in manifest for $resource")

            val sourcePath = root.resolve(source)
            assertTrue(Files.isRegularFile(sourcePath), "Manifest declared source must exist on disk: $source")
            assertEquals(expectedSha, sha256Hex(Files.readAllBytes(sourcePath)), "Manifest resource $resource must match declared source $source")

            val img = ImageIO.read(filePath.toFile())
            assertEquals(expectedW, img.width, "Width mismatch for $resource")
            assertEquals(expectedH, img.height, "Height mismatch for $resource")
            if (expectedFrames > 1) {
                assertAllAdjacentFramesDiffer(img, expectedFrames, resource)
            } else {
                assertEquals(0, img.width % expectedFrames, "Frames mismatch for $resource")
            }

            if (resource.startsWith("units/")) {
                unitCount++
                val audit = auditPixels(img)
                if ("dead" in resource.lowercase()) {
                    assertEquals(0, audit.pureGreen, "Built-in wreck $resource must have 0 pure-green pixels")
                } else {
                    assertTrue(audit.opaque > 0 && audit.pureGreen > 0, "Built-in live sprite $resource must have pure-green pixels")
                    assertEquals(0, audit.grayedAccidental, "Built-in live sprite $resource must not have accidental shader-branch non-green pixels")
                }
            } else if (resource.startsWith("terrain/")) {
                terrainCount++
            }
            entry = entry.next
        }
        assertEquals(28, unitCount, "Expected exactly 28 built-in unit PNGs in manifest.json")
        assertEquals(6, terrainCount, "Expected 6 built-in terrain PNGs in manifest.json")

        val unitsOnDisk = Files.list(placeholderRoot.resolve("units")).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }.count().toInt()
        }
        assertEquals(28, unitsOnDisk, "Built-in units directory must contain exactly 28 PNG files")

        val tilesetsOnDisk = Files.list(placeholderRoot.resolve("tilesets")).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }.count().toInt()
        }
        assertEquals(13, tilesetsOnDisk, "Placeholder tilesets directory must remain intact (13 PNGs: 12 terrain + units_markers)")
    }
}
