package com.rustedwarfare.reforge.client

import com.badlogic.gdx.utils.JsonReader
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UI-P004-006 standalone compliance contract test:
 * - Zero dependency on private `.scratch` paths (safe for public clone CI).
 * - Verifies all 127 deduplicated Phase 6B PNGs, 49 unitBindings in `visuals/placeholder/manifest.json`,
 *   and all 30 bundled 16-bit PCM WAV sound events in `sounds/sounds.json`.
 */
class UiP004Phase6BAssetsAndSoundsTest {

    private data class PngSpec(
        val id: String,
        val resource: String,
        val width: Int,
        val height: Int,
        val frames: Int,
        val expectPureGreen: Boolean,
        val isDeadWreck: Boolean,
    )

    private data class PixelAudit(
        val opaque: Int,
        val pureGreen: Int,
        val grayedAccidental: Int,
    )

    private val expected127PngSpecs = listOf(
        PngSpec("p6b-gunship", "phase6b/gunship.png", 25, 35, 1, true, false),
        PngSpec("p6b-gunship-dead", "phase6b/gunship-dead.png", 25, 35, 1, false, true),
        PngSpec("p6b-gunship-shadow", "phase6b/gunship-shadow.png", 25, 35, 1, false, false),
        PngSpec("p6b-scout-ship", "phase6b/scout-ship.png", 17, 31, 1, true, false),
        PngSpec("p6b-scout-ship-dead", "phase6b/scout-ship-dead.png", 17, 31, 1, false, true),
        PngSpec("p6b-battle-ship-t2", "phase6b/battle-ship-t2.png", 17, 68, 1, true, false),
        PngSpec("p6b-battle-ship-t2-turret", "phase6b/battle-ship-t2-turret.png", 6, 30, 1, false, false),
        PngSpec("p6b-battle-ship-t2-dead", "phase6b/battle-ship-t2-dead.png", 17, 76, 1, false, true),
        PngSpec("p6b-heavy-tank", "phase6b/heavy-tank.png", 66, 36, 3, true, false),
        PngSpec("p6b-heavy-tank-turret", "phase6b/heavy-tank-turret.png", 16, 42, 1, false, false),
        PngSpec("p6b-heavy-tank-dead", "phase6b/heavy-tank-dead.png", 22, 36, 1, false, true),
        PngSpec("p6b-heavy-hover-tank", "phase6b/heavy-hover-tank.png", 24, 36, 1, true, false),
        PngSpec("p6b-heavy-hover-tank-dead", "phase6b/heavy-hover-tank-dead.png", 24, 36, 1, false, true),
        PngSpec("p6b-heavy-hover-tank-shadow", "phase6b/heavy-hover-tank-shadow.png", 24, 36, 1, false, false),
        PngSpec("p6b-builder-ship", "phase6b/builder-ship.png", 17, 27, 1, true, false),
        PngSpec("p6b-builder-ship-turret", "phase6b/builder-ship-turret.png", 11, 19, 1, false, false),
        PngSpec("p6b-builder-ship-dead", "phase6b/builder-ship-dead.png", 17, 27, 1, false, true),
        PngSpec("p6b-experimental-hovertank", "phase6b/experimental-hovertank.png", 53, 77, 1, true, false),
        PngSpec("p6b-experimental-hovertank-turret", "phase6b/experimental-hovertank-turret.png", 21, 57, 1, false, false),
        PngSpec("p6b-experimental-hovertank-shield", "phase6b/experimental-hovertank-shield.png", 70, 92, 1, false, false),
        PngSpec("p6b-experimental-hovertank-dead", "phase6b/experimental-hovertank-dead.png", 53, 77, 1, false, true),
        PngSpec("p6b-combat-engineer-base", "phase6b/combat-engineer-base.png", 45, 49, 1, true, false),
        PngSpec("p6b-combat-engineer-base-dead", "phase6b/combat-engineer-base-dead.png", 45, 49, 1, false, true),
        PngSpec("p6b-experimental-carrier-experiemental-carrier", "phase6b/experimental-carrier-experiemental-carrier.png", 40, 153, 1, true, false),
        PngSpec("p6b-experimental-carrier-turret-top-cannon", "phase6b/experimental-carrier-turret-top-cannon.png", 18, 152, 1, false, false),
        PngSpec("p6b-experimental-carrier-experiemental-carrier-dead", "phase6b/experimental-carrier-experiemental-carrier-dead.png", 40, 153, 1, false, true),
        PngSpec("p6b-experimental-carrier-anti-air-top-flakgun", "phase6b/experimental-carrier-anti-air-top-flakgun.png", 22, 24, 1, true, false),
        PngSpec("p6b-shared-mega-tank-turret", "phase6b/shared-mega-tank-turret.png", 14, 27, 1, false, false),
        PngSpec("p6b-experimental-dropship-experimental-dropship", "phase6b/experimental-dropship-experimental-dropship.png", 65, 82, 1, true, false),
        PngSpec("p6b-shared-small-laser-turret", "phase6b/shared-small-laser-turret.png", 8, 34, 1, false, false),
        PngSpec("p6b-experimental-dropship-experimental-dropship-dead", "phase6b/experimental-dropship-experimental-dropship-dead.png", 65, 82, 1, false, true),
        PngSpec("p6b-experimental-gunship-body", "phase6b/experimental-gunship-body.png", 96, 96, 1, true, false),
        PngSpec("p6b-shared-mega-tank-turret2", "phase6b/shared-mega-tank-turret2.png", 10, 26, 1, false, false),
        PngSpec("p6b-experimental-gunship-body-dead", "phase6b/experimental-gunship-body-dead.png", 96, 96, 1, false, true),
        PngSpec("p6b-shared-nano-turret", "phase6b/shared-nano-turret.png", 11, 19, 1, true, false),
        PngSpec("p6b-shared-experimental-tank-turret3", "phase6b/shared-experimental-tank-turret3.png", 25, 41, 1, true, false),
        PngSpec("p6b-shared-lighting-charge", "phase6b/shared-lighting-charge.png", 15, 15, 1, false, false),
        PngSpec("p6b-experimental-gunship-turbine", "phase6b/experimental-gunship-turbine.png", 23, 18, 1, false, false),
        PngSpec("p6b-experimental-gunship-door", "phase6b/experimental-gunship-door.png", 20, 28, 1, false, false),
        PngSpec("p6b-experimental-spider-body", "phase6b/experimental-spider-body.png", 70, 90, 1, true, false),
        PngSpec("p6b-experimental-spider-body-dead", "phase6b/experimental-spider-body-dead.png", 70, 90, 1, false, true),
        PngSpec("p6b-shared-turret-small", "phase6b/shared-turret-small.png", 8, 34, 1, false, false),
        PngSpec("p6b-experimental-spider-experimental-tank-turret2", "phase6b/experimental-spider-experimental-tank-turret2.png", 7, 35, 1, false, false),
        PngSpec("p6b-experimental-spider-spider-turret-base", "phase6b/experimental-spider-spider-turret-base.png", 20, 19, 1, true, false),
        PngSpec("p6b-experimental-spider-leg", "phase6b/experimental-spider-leg.png", 10, 68, 1, true, false),
        PngSpec("p6b-experimental-spider-foot", "phase6b/experimental-spider-foot.png", 17, 22, 1, true, false),
        PngSpec("p6b-experimental-tank-experimental-tank", "phase6b/experimental-tank-experimental-tank.png", 186, 86, 3, true, false),
        PngSpec("p6b-experimental-tank-experimental-tank-turret", "phase6b/experimental-tank-experimental-tank-turret.png", 13, 42, 1, true, false),
        PngSpec("p6b-experimental-tank-experimental-tank-dead", "phase6b/experimental-tank-experimental-tank-dead.png", 62, 86, 1, false, true),
        PngSpec("p6b-experimental-tank-experimental-tank-barrel", "phase6b/experimental-tank-experimental-tank-barrel.png", 13, 42, 1, false, false),
        PngSpec("p6b-extractor-extractor-t3-overclocked", "phase6b/extractor-extractor-t3-overclocked.png", 148, 58, 4, true, false),
        PngSpec("p6b-extractor-extractor-back", "phase6b/extractor-extractor-back.png", 51, 44, 1, false, false),
        PngSpec("p6b-extractor-extractor-t3-reinforced", "phase6b/extractor-extractor-t3-reinforced.png", 148, 59, 4, true, false),
        PngSpec("p6b-fire-bee-body", "phase6b/fire-bee-body.png", 86, 57, 2, true, false),
        PngSpec("p6b-fire-bee-small-laser-turret", "phase6b/fire-bee-small-laser-turret.png", 8, 34, 1, false, false),
        PngSpec("p6b-fire-bee-body-dead", "phase6b/fire-bee-body-dead.png", 43, 57, 1, false, true),
        PngSpec("p6b-heavy-aa-ship-heavy-aa-ship", "phase6b/heavy-aa-ship-heavy-aa-ship.png", 40, 68, 1, true, false),
        PngSpec("p6b-heavy-aa-ship-anti-air-top-flakgun", "phase6b/heavy-aa-ship-anti-air-top-flakgun.png", 22, 24, 1, false, false),
        PngSpec("p6b-heavy-aa-ship-heavy-aa-ship-dead", "phase6b/heavy-aa-ship-heavy-aa-ship-dead.png", 40, 68, 1, false, true),
        PngSpec("p6b-heavy-interceptor-base2", "phase6b/heavy-interceptor-base2.png", 32, 35, 1, true, false),
        PngSpec("p6b-heavy-interceptor-turret", "phase6b/heavy-interceptor-turret.png", 5, 5, 1, true, false),
        PngSpec("p6b-heavy-interceptor-base-dead", "phase6b/heavy-interceptor-base-dead.png", 32, 35, 1, false, true),
        PngSpec("p6b-heavy-missile-ship-body", "phase6b/heavy-missile-ship-body.png", 42, 83, 1, true, false),
        PngSpec("p6b-heavy-missile-ship-body-dead", "phase6b/heavy-missile-ship-body-dead.png", 42, 83, 1, false, true),
        PngSpec("p6b-heavy-sub-base", "phase6b/heavy-sub-base.png", 29, 60, 1, true, false),
        PngSpec("p6b-heavy-sub-base-dead", "phase6b/heavy-sub-base-dead.png", 29, 60, 1, false, true),
        PngSpec("p6b-interceptor-interceptor", "phase6b/interceptor-interceptor.png", 24, 22, 1, true, false),
        PngSpec("p6b-interceptor-interceptor-turret", "phase6b/interceptor-interceptor-turret.png", 5, 14, 1, true, false),
        PngSpec("p6b-interceptor-interceptor-dead", "phase6b/interceptor-interceptor-dead.png", 24, 22, 1, false, true),
        PngSpec("p6b-interceptor-interceptor-shadow", "phase6b/interceptor-interceptor-shadow.png", 24, 22, 1, false, false),
        PngSpec("p6b-laser-tank-laser-tank", "phase6b/laser-tank-laser-tank.png", 72, 29, 3, true, false),
        PngSpec("p6b-laser-tank-laser-tank-turret", "phase6b/laser-tank-laser-tank-turret.png", 10, 36, 1, false, false),
        PngSpec("p6b-laser-tank-laser-tank-dead", "phase6b/laser-tank-laser-tank-dead.png", 24, 29, 1, false, true),
        PngSpec("p6b-laser-tank-laser-tank-barrel", "phase6b/laser-tank-laser-tank-barrel.png", 10, 36, 1, false, false),
        PngSpec("p6b-laser-tank-laser-tank-charge", "phase6b/laser-tank-laser-tank-charge.png", 15, 15, 1, false, false),
        PngSpec("p6b-light-gunship-base", "phase6b/light-gunship-base.png", 48, 20, 2, true, false),
        PngSpec("p6b-light-gunship-base-dead", "phase6b/light-gunship-base-dead.png", 24, 21, 1, false, true),
        PngSpec("p6b-light-sub-base", "phase6b/light-sub-base.png", 21, 24, 1, true, false),
        PngSpec("p6b-light-sub-base-dead", "phase6b/light-sub-base-dead.png", 21, 24, 1, false, true),
        PngSpec("p6b-mammoth-tank-mammoth-tank", "phase6b/mammoth-tank-mammoth-tank.png", 90, 41, 3, true, false),
        PngSpec("p6b-mammoth-tank-mammoth-tank-turret", "phase6b/mammoth-tank-mammoth-tank-turret.png", 12, 44, 1, false, false),
        PngSpec("p6b-mammoth-tank-mammoth-tank-dead", "phase6b/mammoth-tank-mammoth-tank-dead.png", 32, 43, 1, false, true),
        PngSpec("p6b-mammoth-tank-mammoth-tank-coil", "phase6b/mammoth-tank-mammoth-tank-coil.png", 12, 44, 1, false, false),
        PngSpec("p6b-mechs-large-topartillery", "phase6b/mechs-large-topartillery.png", 36, 60, 1, true, false),
        PngSpec("p6b-mechs-large-leg", "phase6b/mechs-large-leg.png", 16, 68, 1, true, false),
        PngSpec("p6b-mechs-large-foot-med", "phase6b/mechs-large-foot-med.png", 17, 28, 1, false, false),
        PngSpec("p6b-mechs-large-toplaser", "phase6b/mechs-large-toplaser.png", 39, 100, 1, true, false),
        PngSpec("p6b-mechs-large-foot", "phase6b/mechs-large-foot.png", 17, 36, 1, false, false),
        PngSpec("p6b-mechs-large-toplightning", "phase6b/mechs-large-toplightning.png", 50, 58, 1, true, false),
        PngSpec("p6b-shared-lighting-charge2", "phase6b/shared-lighting-charge2.png", 20, 20, 1, false, false),
        PngSpec("p6b-mechs-large-foot-small", "phase6b/mechs-large-foot-small.png", 17, 36, 1, false, false),
        PngSpec("p6b-mechs-large-topminigun", "phase6b/mechs-large-topminigun.png", 72, 51, 2, true, false),
        PngSpec("p6b-mechs-small-topmissile", "phase6b/mechs-small-topmissile.png", 27, 22, 1, true, false),
        PngSpec("p6b-mechs-small-leg", "phase6b/mechs-small-leg.png", 7, 47, 1, true, false),
        PngSpec("p6b-mechs-small-foot", "phase6b/mechs-small-foot.png", 6, 18, 1, false, false),
        PngSpec("p6b-missile-airship-body", "phase6b/missile-airship-body.png", 31, 41, 1, true, false),
        PngSpec("p6b-missile-airship-body-dead", "phase6b/missile-airship-body-dead.png", 31, 41, 1, false, true),
        PngSpec("p6b-missile-tank-missile-tank", "phase6b/missile-tank-missile-tank.png", 60, 39, 2, true, false),
        PngSpec("p6b-missile-tank-missile-tank-turret", "phase6b/missile-tank-missile-tank-turret.png", 22, 26, 1, false, false),
        PngSpec("p6b-missile-tank-missile-tank-dead", "phase6b/missile-tank-missile-tank-dead.png", 30, 39, 1, false, true),
        PngSpec("p6b-outpost-outpost", "phase6b/outpost-outpost.png", 48, 56, 1, true, false),
        PngSpec("p6b-plasma-tank-plasma-tank", "phase6b/plasma-tank-plasma-tank.png", 52, 29, 2, true, false),
        PngSpec("p6b-plasma-tank-turret", "phase6b/plasma-tank-turret.png", 14, 42, 1, true, false),
        PngSpec("p6b-plasma-tank-plasma-tank-dead", "phase6b/plasma-tank-plasma-tank-dead.png", 24, 29, 1, false, true),
        PngSpec("p6b-scout-base", "phase6b/scout-base.png", 24, 21, 1, true, false),
        PngSpec("p6b-scout-base-dead", "phase6b/scout-base-dead.png", 24, 21, 1, false, true),
        PngSpec("p6b-spy-drone-base", "phase6b/spy-drone-base.png", 45, 47, 1, true, false),
        PngSpec("p6b-spy-drone-base-dead", "phase6b/spy-drone-base-dead.png", 45, 47, 1, false, true),
        PngSpec("p6b-tanks-artillery", "phase6b/tanks-artillery.png", 72, 47, 3, true, false),
        PngSpec("p6b-tanks-artillery-dead", "phase6b/tanks-artillery-dead.png", 24, 47, 1, false, true),
        PngSpec("p6b-tanks-heavy-artillery", "phase6b/tanks-heavy-artillery.png", 58, 38, 2, true, false),
        PngSpec("p6b-tanks-heavy-artillery-turret", "phase6b/tanks-heavy-artillery-turret.png", 13, 65, 1, false, false),
        PngSpec("p6b-tanks-heavy-artillery-dead", "phase6b/tanks-heavy-artillery-dead.png", 29, 38, 1, false, true),
        PngSpec("p6b-turrets-turret-base", "phase6b/turrets-turret-base.png", 35, 32, 1, true, false),
        PngSpec("p6b-turrets-anti-air-top", "phase6b/turrets-anti-air-top.png", 16, 26, 1, false, false),
        PngSpec("p6b-turrets-turret-base-dead", "phase6b/turrets-turret-base-dead.png", 35, 42, 1, false, true),
        PngSpec("p6b-turrets-turret-base2", "phase6b/turrets-turret-base2.png", 43, 36, 1, true, false),
        PngSpec("p6b-turrets-anti-air-top-flakgun", "phase6b/turrets-anti-air-top-flakgun.png", 29, 32, 1, false, false),
        PngSpec("p6b-turrets-anti-air-top-l2", "phase6b/turrets-anti-air-top-l2.png", 25, 24, 1, false, false),
        PngSpec("p6b-turrets-anti-air-top-l3", "phase6b/turrets-anti-air-top-l3.png", 25, 24, 1, false, false),
        PngSpec("p6b-turrets-turret-top-lightning", "phase6b/turrets-turret-top-lightning.png", 22, 31, 1, false, false),
        PngSpec("p6b-turrets-turret-base2b", "phase6b/turrets-turret-base2b.png", 46, 38, 1, true, false),
        PngSpec("p6b-turrets-turret-top-artillery-l2", "phase6b/turrets-turret-top-artillery-l2.png", 19, 39, 1, false, false),
        PngSpec("p6b-turrets-turret-top-flame", "phase6b/turrets-turret-top-flame.png", 14, 46, 1, false, false),
        PngSpec("p6b-shared-light-25", "phase6b/shared-light-25.png", 25, 25, 1, false, false),
        PngSpec("p6b-turrets-turret-top-lightning-l2", "phase6b/turrets-turret-top-lightning-l2.png", 26, 28, 1, false, false),
        PngSpec("p6b-turrets-turret-top-l3", "phase6b/turrets-turret-top-l3.png", 16, 46, 1, false, false),
    )

    private val expected49Phase6BUnits = setOf(
        "gunShip", "missileShip", "battleShip", "heavyTank", "heavyHoverTank",
        "builderShip", "experimentalHoverTank", "combatEngineer", "experiementalCarrier", "experimentalDropship",
        "experimentalGunship", "experimentalGunshipLanded", "experimentalSpider", "c_experimentalTank", "extractorT3_overclocked",
        "extractorT3_reinforced", "fireBee", "heavyAAShip", "heavyInterceptor", "heavyMissileShip",
        "heavySub", "c_interceptor", "c_laserTank", "lightGunship", "lightSub",
        "c_mammothTank", "mechArtillery", "mechLaser", "mechLightning", "mechMinigun",
        "mechMissile", "missileAirship", "missileTank", "outpostT1", "outpostT2",
        "plasmaTank", "scout", "spyDrone", "c_artillery", "heavyArtillery",
        "c_antiAirTurret", "antiAirTurretFlak", "c_antiAirTurretT2", "c_antiAirTurretT3", "c_turret_t1_lightning",
        "c_turret_t2_artillery", "c_turret_t2_flame", "c_turret_t2_lightning", "c_turret_t3_gun",
    )

    private val expected30SoundEventIds = listOf(
        "tank-fire",
        "naval-main-fire",
        "naval-missile-fire",
        "nuke-launch",
        "nuke-explode",
        "cannon-fire",
        "gun-fire",
        "autocannon-fire",
        "heavy-autocannon-fire",
        "flak-cannon-fire",
        "missile-hit",
        "plasma-fire",
        "heavy-plasma-fire",
        "lightning-burst",
        "laser-deflect-charge",
        "point-defense-intercept",
        "unit-explode",
        "unit-explode-small",
        "building-explode",
        "bug-die",
        "bug-attack",
        "order-move",
        "order-attack",
        "order-attack-alt",
        "ui-click",
        "ui-queue-add",
        "ui-queue-remove",
        "ui-error",
        "alert-warning",
        "ui-message",
    )

    @Test
    fun `all 127 Phase 6B sprites decode as RGBA match manifest dimensions pureGreen rules and distinct SHA-256`() {
        assertEquals(127, expected127PngSpecs.size)
        assertEquals(31, expected127PngSpecs.count { it.isDeadWreck }, "Expected 31 wreck sprites among 127 PNGs")
        assertEquals(96, expected127PngSpecs.count { !it.isDeadWreck }, "Expected 96 non-wreck sprites among 127 PNGs")

        val manifestBytes = readResourceBytes("/visuals/placeholder/manifest.json")
        val manifest = JsonReader().parse(manifestBytes.toString(Charsets.UTF_8))
        assertEquals(1, manifest.getInt("schemaVersion"))

        val manifestById = linkedMapOf<String, com.badlogic.gdx.utils.JsonValue>()
        var fileEntry = manifest.get("files").child
        while (fileEntry != null) {
            val id = fileEntry.getString("id")
            assertFalse(id in manifestById, "Duplicate manifest file id: $id")
            manifestById[id] = fileEntry
            fileEntry = fileEntry.next
        }

        val shaSet = mutableSetOf<String>()
        for (spec in expected127PngSpecs) {
            val entry = assertNotNull(manifestById[spec.id], "Missing ${spec.id} in placeholder/manifest.json")
            assertEquals(spec.resource, entry.getString("resource"), "Resource path mismatch for ${spec.id}")
            assertEquals(spec.width, entry.getInt("width"), "Manifest width mismatch for ${spec.id}")
            assertEquals(spec.height, entry.getInt("height"), "Manifest height mismatch for ${spec.id}")
            assertEquals(spec.frames, entry.getInt("frames"), "Manifest frames mismatch for ${spec.id}")

            val raw = readResourceBytes("/visuals/placeholder/${spec.resource}")
            assertEquals(entry.getInt("bytes"), raw.size, "Byte count mismatch for ${spec.resource}")
            val sha = sha256Hex(raw)
            assertEquals(entry.getString("sha256"), sha, "SHA-256 mismatch for ${spec.resource}")
            assertTrue(shaSet.add(sha), "Duplicate PNG SHA-256 detected for ${spec.resource}")

            val img = assertNotNull(ImageIO.read(ByteArrayInputStream(raw)), "Failed to decode PNG: ${spec.resource}")
            assertTrue(img.width > 0 && img.height > 0)
            assertEquals(spec.width, img.width, "Decoded width mismatch for ${spec.resource}")
            assertEquals(spec.height, img.height, "Decoded height mismatch for ${spec.resource}")
            assertTrue(img.colorModel.hasAlpha(), "PNG must carry alpha channel (RGBA): ${spec.resource}")

            if (spec.frames > 1) {
                assertEquals(0, img.width % spec.frames, "Width ${img.width} not divisible by frames ${spec.frames} in ${spec.resource}")
                val fw = img.width / spec.frames
                for (f in 0 until spec.frames - 1) {
                    val left = img.getRGB(f * fw, 0, fw, img.height, null, 0, fw)
                    val right = img.getRGB((f + 1) * fw, 0, fw, img.height, null, 0, fw)
                    assertFalse(left.contentEquals(right), "Adjacent frames $f and ${f + 1} are identical in ${spec.resource}")
                }
            }

            val audit = auditPixels(img)
            assertTrue(audit.opaque > 0, "Sprite ${spec.resource} has no opaque/visible pixels")
            assertEquals(0, audit.grayedAccidental, "Sprite ${spec.resource} contains accidental shader-branch non-green pixels")
            if (spec.isDeadWreck) {
                assertFalse(spec.expectPureGreen)
                assertEquals(0, audit.pureGreen, "Wreck sprite ${spec.resource} must contain 0 pure-green pixels")
            } else if (spec.expectPureGreen) {
                assertTrue(audit.pureGreen > 0, "Team-colored sprite ${spec.resource} must contain pure-green pixels")
            } else {
                assertEquals(0, audit.pureGreen, "Non-team-colored sprite ${spec.resource} must contain 0 pure-green pixels")
            }
        }
        assertEquals(127, shaSet.size, "All 127 Phase 6B PNGs must have distinct SHA-256 digests")

        // Verify all 49 Phase 6B units are bound in placeholder/manifest.json and reference valid file IDs
        val bindings = manifest.get("unitBindings")
        val boundUnitNames = mutableSetOf<String>()
        var unitNode = bindings.child
        while (unitNode != null) {
            val uname = assertNotNull(unitNode.name)
            boundUnitNames += uname
            assertNotNull(unitNode.getString("body", null), "Unit $uname missing body binding")
            var slot = unitNode.child
            while (slot != null) {
                if (slot.name == "weaponTurrets") {
                    var wt = slot.child
                    while (wt != null) {
                        assertTrue(wt.asString() in manifestById, "Unit $uname weaponTurret ${wt.name} references unknown file ${wt.asString()}")
                        wt = wt.next
                    }
                } else {
                    assertTrue(slot.asString() in manifestById, "Unit $uname slot ${slot.name} references unknown file ${slot.asString()}")
                }
                slot = slot.next
            }
            unitNode = unitNode.next
        }
        assertEquals(49, expected49Phase6BUnits.size)
        for (u in expected49Phase6BUnits) {
            assertTrue(u in boundUnitNames, "Missing Phase 6B unit binding in placeholder/manifest.json: $u")
        }
        assertEquals(65, boundUnitNames.size, "Expected 16 existing + 49 Phase 6B = 65 unitBindings in placeholder/manifest.json")
    }

    @Test
    fun `sounds manifest and all 30 bundled WAVs have valid PCM16 RIFF headers non-silence peak under 0_95 and distinct SHA-256`() {
        assertEquals(30, expected30SoundEventIds.size)
        assertEquals(30, expected30SoundEventIds.toSet().size)

        val soundsJsonBytes = readResourceBytes("/sounds/sounds.json")
        val manifest = ResourceSoundManifest.read(soundsJsonBytes, "bundled/sounds.json")
        assertEquals(30, manifest.sounds.size, "Expected 30 sound declarations in sounds.json")

        val wavShaSet = mutableSetOf<String>()
        for (eventId in expected30SoundEventIds) {
            val soundKey = ResourceId.parse("reforge:sounds/$eventId")
            val eventKey = ResourceId.parse("reforge:events/$eventId")
            val soundDecl = assertNotNull(manifest.sounds[soundKey], "Missing sound declaration: $soundKey")
            assertEquals("$eventId.wav", soundDecl.file, "Unexpected WAV filename for $soundKey")
            val eventDecl = assertNotNull(manifest.events[eventKey], "Missing primary event declaration: $eventKey")
            assertEquals(soundKey, eventDecl.sound, "Primary event $eventKey must map to $soundKey")

            val wavBytes = readResourceBytes("/sounds/${soundDecl.file}")
            val sha = sha256Hex(wavBytes)
            assertTrue(wavShaSet.add(sha), "Duplicate WAV SHA-256 detected for ${soundDecl.file}")

            // Parse and validate standard 44-byte RIFF/WAVE 16-bit PCM header
            assertTrue(wavBytes.size > 44, "WAV file too short: ${soundDecl.file}")
            val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            val riffTag = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val riffChunkSize = buf.int
            val waveTag = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val fmtTag = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val fmtSize = buf.int
            val audioFormat = buf.short.toInt() and 0xFFFF
            val channels = buf.short.toInt() and 0xFFFF
            val sampleRate = buf.int
            val byteRate = buf.int
            val blockAlign = buf.short.toInt() and 0xFFFF
            val bitsPerSample = buf.short.toInt() and 0xFFFF
            val dataTag = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val dataSize = buf.int

            assertEquals("RIFF", riffTag, "Invalid RIFF magic in ${soundDecl.file}")
            assertEquals(wavBytes.size - 8, riffChunkSize, "RIFF chunk size mismatch in ${soundDecl.file}")
            assertEquals("WAVE", waveTag, "Invalid WAVE magic in ${soundDecl.file}")
            assertEquals("fmt ", fmtTag, "Invalid fmt chunk in ${soundDecl.file}")
            assertEquals(16, fmtSize, "Expected 16-byte PCM fmt chunk in ${soundDecl.file}")
            assertEquals(1, audioFormat, "Expected PCM format (1) in ${soundDecl.file}")
            assertEquals(1, channels, "Expected mono (1 channel) in ${soundDecl.file}")
            assertEquals(22050, sampleRate, "Expected 22050 Hz sample rate in ${soundDecl.file}")
            assertEquals(sampleRate * channels * 2, byteRate, "ByteRate mismatch in ${soundDecl.file}")
            assertEquals(channels * 2, blockAlign, "BlockAlign mismatch in ${soundDecl.file}")
            assertEquals(16, bitsPerSample, "Expected 16-bit PCM in ${soundDecl.file}")
            assertEquals("data", dataTag, "Invalid data chunk tag in ${soundDecl.file}")
            assertEquals(wavBytes.size - 44, dataSize, "Data size mismatch in ${soundDecl.file}")
            assertTrue(dataSize > 0 && dataSize % 2 == 0, "Invalid PCM16 data byte length in ${soundDecl.file}")

            val sampleCount = dataSize / 2
            var maxAbs = 0
            var minSample = Int.MAX_VALUE
            var maxSample = Int.MIN_VALUE
            for (i in 0 until sampleCount) {
                val sample = buf.short.toInt()
                maxAbs = max(maxAbs, abs(sample))
                if (sample < minSample) minSample = sample
                if (sample > maxSample) maxSample = sample
            }
            val peak = maxAbs / 32768.0
            assertTrue(minSample < 0 && maxSample > 0, "WAV ${soundDecl.file} must oscillate across zero (non-silent AC waveform)")
            assertTrue(peak >= 0.25 && peak < 0.95, "WAV ${soundDecl.file} peak=$peak must satisfy 0.25 <= peak < 0.95")
        }
        assertEquals(30, wavShaSet.size, "All 30 bundled WAVs must have mutually distinct SHA-256 digests")

        // Verify legacy secondary gameplay aliases remain bound to valid bundled sounds
        for (alias in listOf(
            "hover-fire", "amphibious-lightning-fire", "spider-lightning-fire",
            "scout-plasma-fire", "spider-gun-fire", "nuke-intercept", "bomber-impact",
        )) {
            val ev = assertNotNull(manifest.events[ResourceId.parse("reforge:events/$alias")], "Missing alias event $alias")
            assertTrue(ev.sound in manifest.sounds, "Alias $alias points to undeclared sound ${ev.sound}")
        }
    }

    private fun auditPixels(img: BufferedImage): PixelAudit {
        var opaque = 0
        var pureGreen = 0
        var grayedAccidental = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val argb = img.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                if (a == 0) continue
                opaque++
                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                if (g > 0f && abs(r - b) <= MatchPlayerColors.PURE_GREEN_THRESHOLD) {
                    if (g > r) pureGreen++ else grayedAccidental++
                }
            }
        }
        return PixelAudit(opaque, pureGreen, grayedAccidental)
    }

    private fun readResourceBytes(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "Missing classpath resource: $path" }
            .use { it.readBytes() }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
