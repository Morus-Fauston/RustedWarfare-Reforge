package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PointDefenseContentLoadTest {
    private fun file(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input/units/laserDefence.properties")

    @Test fun native_two_frame_sheet_and_fixed_profile_are_consumed_without_an_offensive_weapon() {
        val defense = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadPointDefense(file())).value
        assertEquals(2, defense.totalFrames, "The fixed constructor divides the 72px native sheet into two 36px frames")
        assertEquals(500.0, defense.maximumHealth)
        assertEquals(19.0, defense.radius)
        assertEquals(BuildingFootprint(0, 0, 1, 1), defense.building!!.footprint)
        assertNull(defense.building.anchor)
        assertNull(defense.combat)
        assertNull(defense.production)
        assertEquals(DirectHitTargetKind.NATIVE_BUILDING, defense.directHitTargetKind)
        assertEquals(PointDefenseProfile(160.0, 0.0, -13.0, .0004f, .11f, 1f), defense.pointDefense)
        assertEquals("laser_defence.png", defense.imagePath.fileName.toString())
        assertEquals("laser_defence_dead.png", assertNotNull(defense.durability!!.deadImagePath).fileName.toString())
    }

    @Test fun malformed_unknown_duplicate_missing_and_unsupported_native_values_fail_closed() {
        val changes = listOf("maxHp=500" to "maxHp=NaN", "radius=19" to "radius=Infinity",
            "maxHp=500" to "maxHp=499", "radius=19" to "radius=18", "footprint=0,0,1,1" to "footprint=-1,0,1,1",
            "movementType=NONE" to "movementType=LAND", "fogOfWarSightRange=15" to "fogOfWarSightRange=-1",
            "fogOfWarSightRange=15" to "fogOfWarSightRange=16", "name=laserDefence" to "name=other",
            "maxHp=500" to "maxHp=500\nmaxHp=500", "maxHp=500" to "", "maxHp=500" to "maxHp=500\nupgrade=2")
        for ((from, to) in changes) withText({ it.replace(from, to) }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadPointDefense(path)).error
            assertTrue(error.code in setOf(ContentErrorCode.MALFORMED_SYNTAX, ContentErrorCode.MISSING_REQUIRED_FIELD,
                ContentErrorCode.UNSUPPORTED_FEATURE, ContentErrorCode.UNSUPPORTED_FIELD), "$from -> $to: $error")
            assertEquals(path.toAbsolutePath().normalize().toString(), error.file)
            assertNotNull(error.field)
        }
    }

    @Test fun missing_or_corrupt_indexed_png_is_rejected_instead_of_publishing_partial_content() {
        withText({ it.replace(Regex("(?m)^image_dead=.*$"), "image_dead=missing-pd-dead.png") }) {
            assertEquals(ContentErrorCode.BAD_REFERENCE, assertIs<ContentResult.Failure>(ContentLoader.loadPointDefense(it)).error.code)
        }
        val png = Files.createTempFile("p005-indexed-png-", ".png")
        try {
            val original = Files.readAllBytes(file().resolveSibling("laser_defence.png"))
            for (bytes in listOf(byteArrayOf(1, 2, 3), original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })) {
                Files.write(png, bytes)
                withText({ it.replace(Regex("(?m)^image=.*$"), "image=$png") }) {
                    val error = assertIs<ContentResult.Failure>(ContentLoader.loadPointDefense(it)).error
                    assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
                    assertEquals("image", error.field)
                }
            }
        } finally { Files.deleteIfExists(png) }
    }

    private fun withText(change: (String) -> String, body: (Path) -> Unit) {
        val source = file()
        val text = Files.readString(source).replace(Regex("(?m)^(image|image_dead)=(.*)$")) {
            "${it.groupValues[1]}=${source.parent.resolve(it.groupValues[2]).toAbsolutePath().normalize()}"
        }
        val path = Files.createTempFile("p005-defense-content-", ".properties")
        try { Files.writeString(path, change(text)); body(path) } finally { Files.deleteIfExists(path) }
    }
}
