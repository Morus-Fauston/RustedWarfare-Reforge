package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoadFailureTest {
    private fun negative(name: String) = inputs().resolve("negative/$name")

    @Test fun l01MissingRadius() {
        error(ContentLoader.loadUnitVariant(negative("L01.ini")), ContentErrorCode.MISSING_REQUIRED_FIELD, "core", "radius")
    }
    @Test fun l02aMissingExternalTileset() {
        error(ContentLoader.loadMap(negative("L02a.tmx")), ContentErrorCode.BAD_REFERENCE, "tileset", "source", "missing.tsx")
    }
    @Test fun l02bMissingUnitImage() {
        error(ContentLoader.loadUnitVariant(negative("L02b.ini")), ContentErrorCode.BAD_REFERENCE, "graphics", "image", "missing.png")
    }
    @Test fun l03aWeaponSection() {
        error(ContentLoader.loadUnitVariant(negative("L03a.ini")), ContentErrorCode.WEAPON_SECTION_PRESENT, "attack", null)
    }
    @Test fun l03bBase64Encoding() {
        error(ContentLoader.loadMap(negative("L03b.tmx")), ContentErrorCode.UNSUPPORTED_ENCODING, "data", "encoding")
    }
    @Test fun l03cUnsupportedTileProperty() {
        val result = error(ContentLoader.loadMap(negative("L03c.tmx")), ContentErrorCode.UNSUPPORTED_PROPERTY, "property", "name", "water")
        assertEquals(negative("L03c.tsx").toAbsolutePath().normalize().toString(), result.file)
    }
    @Test fun l04FailuresNeverContaminateSubsequentSuccessfulLoads() {
        for (name in listOf("L01.ini", "L02b.ini", "L03a.ini")) {
            assertIs<ContentResult.Failure>(ContentLoader.loadUnitVariant(negative(name)))
            assertEquals("p001_weaponless_tank", assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadUnitVariant(unitPath())).value.id)
        }
        for (name in listOf("L02a.tmx", "L03b.tmx", "L03c.tmx")) {
            assertIs<ContentResult.Failure>(ContentLoader.loadMap(negative(name)))
            assertEquals(35, assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(mapPath())).value.blocking.blockedCount)
        }
    }

    @Test fun rejectsUnknownFieldsSectionsAndRemovedGraphics() {
        withUnit(Files.readString(unitPath()) + "\n[extra]\nfield: 1\n") {
            error(ContentLoader.loadUnitVariant(it), ContentErrorCode.UNSUPPORTED_FIELD, "extra", null)
        }
        withUnit(Files.readString(unitPath()).replace("radius: 11", "radius: 11\nunknown: 1")) {
            error(ContentLoader.loadUnitVariant(it), ContentErrorCode.UNSUPPORTED_FIELD, "core", "unknown")
        }
        withUnit(Files.readString(unitPath()).replace("imageScale: 0.8", "imageScale: 0.8\nimage_turret: NONE")) {
            error(ContentLoader.loadUnitVariant(it), ContentErrorCode.UNSUPPORTED_FIELD, "graphics", "image_turret")
        }
    }
    @Test fun rejectsReplacementAndOriginalIdentity() {
        for ((needle, replacement, field) in listOf(
            Triple("name: p001_weaponless_tank", "name: c_tank", "name"),
            Triple("radius: 11", "radius: 11\noverrideAndReplace: tank", "overrideAndReplace"),
        )) withUnit(Files.readString(unitPath()).replace(needle, replacement)) {
            error(ContentLoader.loadUnitVariant(it), ContentErrorCode.IDENTITY_CONFLICT, "core", field)
        }
    }
    @Test fun rejectsUnsupportedMovementAndMetadataClass() {
        for ((needle, replacement, section, field) in listOf(
            listOf("movementType: LAND", "movementType: AIR", "movement", "movementType"),
            listOf("moveSlidingMode: false", "moveSlidingMode: true", "movement", "moveSlidingMode"),
            listOf("moveIgnoringBody: false", "moveIgnoringBody: true", "movement", "moveIgnoringBody"),
            listOf("class: CustomUnitMetadata", "class: Other", "core", "class"),
        )) withUnit(Files.readString(unitPath()).replace(needle, replacement)) {
            error(ContentLoader.loadUnitVariant(it), ContentErrorCode.UNSUPPORTED_FEATURE, section, field)
        }
    }
    @Test fun rejectsDuplicateAndMalformedIniAndNonFiniteNumbers() {
        for (replacement in listOf("radius: NaN", "radius: Infinity", "radius: -1", "radius: bad", "radius: 11\nradius: 12")) {
            withUnit(Files.readString(unitPath()).replace("radius: 11", replacement)) {
                error(ContentLoader.loadUnitVariant(it), ContentErrorCode.MALFORMED_SYNTAX, "core", "radius")
            }
        }
        withUnit("[core]\nname p001\n") {
            error(ContentLoader.loadUnitVariant(it), ContentErrorCode.MALFORMED_SYNTAX, "core", null)
        }
    }
    @Test fun rejectsUnknownGidCsvCountAndCompressedData() {
        changedMap("<data encoding=\"csv\">", "<data encoding=\"csv\">999,") {
            error(ContentLoader.loadMap(it), ContentErrorCode.MALFORMED_SYNTAX, "data", null)
        }
        changedMap(">1,1,1,", ">999,1,1,") {
            error(ContentLoader.loadMap(it), ContentErrorCode.BAD_REFERENCE, "data", "gid", "999")
        }
        changedMap("encoding=\"csv\"", "encoding=\"csv\" compression=\"gzip\"") {
            error(ContentLoader.loadMap(it), ContentErrorCode.UNSUPPORTED_ENCODING, "data", "compression")
        }
    }
    @Test fun rejectsUnsupportedXmlLayersFeaturesAndAttributes() {
        changedMap("name=\"Ground\"", "name=\"Units\"") {
            error(ContentLoader.loadMap(it), ContentErrorCode.UNSUPPORTED_LAYER, "layer", "name")
        }
        for (element in listOf("objectgroup", "imagelayer", "group")) changedMap("</map>", "<$element/></map>") {
            error(ContentLoader.loadMap(it), ContentErrorCode.UNSUPPORTED_FEATURE, element, null)
        }
        changedMap("orientation=\"orthogonal\"", "orientation=\"hexagonal\"") {
            error(ContentLoader.loadMap(it), ContentErrorCode.UNSUPPORTED_FEATURE, "map", "orientation")
        }
        changedMap("renderorder=\"right-down\"", "renderorder=\"right-down\" infinite=\"1\"") {
            error(ContentLoader.loadMap(it), ContentErrorCode.UNSUPPORTED_FIELD, "map", "infinite")
        }
    }
    @Test fun rejectsDoctypeMalformedXmlAndMissingFile() {
        withMap("<!DOCTYPE map [<!ENTITY xxe SYSTEM 'file:///missing'>]><map>&xxe;</map>") {
            error(ContentLoader.loadMap(it), ContentErrorCode.MALFORMED_SYNTAX, null, null)
        }
        withMap("<map>") { error(ContentLoader.loadMap(it), ContentErrorCode.MALFORMED_SYNTAX, null, null) }
        val path = negative("not-present.tmx")
        error(ContentLoader.loadMap(path), ContentErrorCode.BAD_REFERENCE, null, null)
    }
    @Test fun rejectsOverlappingTilesetsAndMismatchedLayerDimensions() {
        changedMap("firstgid=\"41\"", "firstgid=\"2\"") {
            error(ContentLoader.loadMap(it), ContentErrorCode.BAD_REFERENCE, "tileset", "firstgid")
        }
        changedMap("name=\"Ground\" width=\"30\"", "name=\"Ground\" width=\"29\"") {
            error(ContentLoader.loadMap(it), ContentErrorCode.MALFORMED_SYNTAX, "layer", "width")
        }
    }

    @Test fun rejectsImageSmallerThanDeclaredRegionAtExternalTsxLocation() {
        val source = inputs().resolve("tilesets/p001-rock.tsx")
        val image = source.parent.resolve("../../../../Rusted Warfare/assets/tilesets/bitmaps/mountain.png")
            .toAbsolutePath().normalize().toString().replace("&", "&amp;")
        val path = Files.createTempFile("p001-oversized-region-", ".tsx")
        try {
            val text = Files.readString(source).replace("height=\"100\"", "height=\"120\"")
                .replace("../../../../Rusted Warfare/assets/tilesets/bitmaps/mountain.png", image)
            Files.writeString(path, text)
            changedMap("../tilesets/p001-rock.tsx", path.toString().replace("&", "&amp;")) {
                val result = error(ContentLoader.loadMap(it), ContentErrorCode.MALFORMED_SYNTAX,
                    "image", "height", image.replace("&amp;", "&"))
                assertEquals(path.toAbsolutePath().normalize().toString(), result.file)
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun everyRequiredUnitFieldMustBePresent() {
        val fields = mapOf(
            "core" to listOf("name", "class", "radius", "displayRadius"),
            "graphics" to listOf("total_frames", "image"),
            "movement" to listOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
                "maxTurnSpeed", "turnAcceleration", "moveSlidingMode", "moveIgnoringBody"),
        )
        for ((section, keys) in fields) for (key in keys) {
            val text = Files.readString(unitPath()).lineSequence()
                .filterNot { it.substringBefore(':').trim() == key }.joinToString("\n")
            withUnit(text) { error(ContentLoader.loadUnitVariant(it), ContentErrorCode.MISSING_REQUIRED_FIELD, section, key) }
        }
    }
}

private fun error(result: ContentResult<*>, code: ContentErrorCode, section: String?, field: String?, ref: String? = null): ContentError {
    val failure = assertIs<ContentResult.Failure>(result)
    assertEquals(code, failure.error.code, failure.error.describe())
    assertEquals(section, failure.error.section, failure.error.describe())
    assertEquals(field, failure.error.field, failure.error.describe())
    if (ref != null) assertEquals(ref, failure.error.objectRef, failure.error.describe())
    assertTrue(Path.of(failure.error.file).isAbsolute)
    assertTrue(failure.error.reason.isNotBlank())
    assertTrue(failure.error.describe().contains("code=$code"))
    return failure.error
}

private fun withUnit(text: String, action: (Path) -> Unit) {
    val path = Files.createTempFile("p001-unit-negative-", ".ini")
    val image = unitPath().parent.resolve("../../../../Rusted Warfare/assets/units/tanks/tank.png")
        .toAbsolutePath().normalize()
    val relocated = text.replace("../../../../Rusted Warfare/assets/units/tanks/tank.png", image.toString())
    try { Files.writeString(path, relocated); action(path) } finally { Files.deleteIfExists(path) }
}

private fun changedMap(old: String, replacement: String, action: (Path) -> Unit) {
    var text = Files.readString(mapPath())
    check(text.contains(old)) { "Test replacement is not present: $old" }
    text = text.replace(old, replacement)
    for (name in listOf("p001-ground", "p001-rock")) {
        val absolute = inputs().resolve("tilesets/$name.tsx").toAbsolutePath().normalize()
            .toString().replace("&", "&amp;")
        text = text.replace("../tilesets/$name.tsx", absolute)
    }
    withMap(text, action)
}
