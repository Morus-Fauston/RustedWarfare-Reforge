package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import org.w3c.dom.Element

internal object TsxReader {
    private val blockingProperties = setOf(
        "trees", "tree", "large-rock", "small-rock", "cliff", "large-cliff",
        "small-cliff", "cliff-soft", "lava", "lava-cliff", "block-land",
    )
    private val defaultWaterImageNames = setOf("shallowwater.png", "water.png", "deepwater.png")
    private val tilesetAttributes = setOf(
        "version", "tiledversion", "name", "tilewidth", "tileheight", "tilecount", "columns", "spacing", "margin",
    )

    fun read(
        path: Path,
        firstGid: Int,
        tileWidth: Int,
        tileHeight: Int,
        tsxSource: String,
        read: (Path) -> ByteArray,
        openImage: (Path) -> InputStream,
        resolveImage: (Path, String) -> Path,
        diagnostics: MutableList<String>,
    ): TileSetDefinition = parse(
        readXml(path, read), path, firstGid, tileWidth, tileHeight, false, tsxSource,
        openImage, resolveImage, diagnostics,
    )

    fun readInline(
        element: Element,
        path: Path,
        firstGid: Int,
        tileWidth: Int,
        tileHeight: Int,
        openImage: (Path) -> InputStream,
        resolveImage: (Path, String) -> Path,
        diagnostics: MutableList<String>,
    ): TileSetDefinition = parse(
        element, path, firstGid, tileWidth, tileHeight, true, null,
        openImage, resolveImage, diagnostics,
    )

    private fun parse(
        element: Element,
        path: Path,
        firstGid: Int,
        mapTileWidth: Int,
        mapTileHeight: Int,
        inline: Boolean,
        tsxSource: String?,
        openImage: (Path) -> InputStream,
        resolveImage: (Path, String) -> Path,
        diagnostics: MutableList<String>,
    ): TileSetDefinition {
        if (element.tagName != "tileset") reject(ContentErrorCode.MALFORMED_SYNTAX, path, element.tagName, reason = "Expected tileset root")
        attributes(element, path, if (inline) tilesetAttributes + "firstgid" else tilesetAttributes)
        val name = if (inline) element.getAttribute("name") else required(element, path, "name")
        val width = integer(element, path, "tilewidth", 1)
        val height = integer(element, path, "tileheight", 1)
        if (width != mapTileWidth) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", "tilewidth", reason = "Tileset differs from map tile width")
        if (height != mapTileHeight) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", "tileheight", reason = "Tileset differs from map tile height")
        val children = elements(element, path)
        children.firstOrNull { it.tagName !in setOf("image", "tile", "properties", "terraintypes", "tileoffset", "grid", "wangsets") }?.let {
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, it.tagName, reason = "Unsupported tileset element")
        }
        val images = children.filter { it.tagName == "image" }
        if (images.isEmpty()) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "tileset", "image", reason = "Missing atlas image")
        if (images.size != 1) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", "image", reason = "Only single-image tilesets are supported")
        val image = images.single()
        attributes(image, path, setOf("source", "width", "height", "trans", "format"))
        if (elements(image, path).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "image", reason = "Image cannot have child elements")
        val imageSource = required(image, path, "source")
        val imagePath = resolveImage(path, imageSource)
        val declaredWidth = if (image.hasAttribute("width")) integer(image, path, "width", 1) else null
        val declaredHeight = if (image.hasAttribute("height")) integer(image, path, "height", 1) else null
        val (actualWidth, actualHeight) = readPngDimensions(imagePath, path, imageSource, openImage)
        if (declaredWidth != null && actualWidth < declaredWidth) {
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, "image", "width", imageSource,
                "Declared region $declaredWidth exceeds PNG width $actualWidth")
        }
        if (declaredHeight != null && actualHeight < declaredHeight) {
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, "image", "height", imageSource,
                "Declared region $declaredHeight exceeds PNG height $actualHeight")
        }
        val spacing = integer(element, path, "spacing", 0, 0)
        val margin = integer(element, path, "margin", 0, 0)
        fun extent(pixels: Int, tileSize: Int, field: String): Int {
            val available = pixels.toLong() - 2L * margin + spacing
            val pitch = tileSize.toLong() + spacing
            if (available < pitch) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, "image", field, imageSource, "Image dimensions are smaller than one tile")
            }
            return (available / pitch).toInt()
        }
        // Always derive atlas upper capacity from actual PNG dimensions (already validated >= declared).
        // When tilecount is omitted and an external TSX holds stale smaller image attributes (e.g. Stone Sand - Flat.tsx),
        // actual PNG dimensions ensure newly added atlas rows remain addressable.
        val imageWidth = maxOf(declaredWidth ?: 0, actualWidth)
        val imageHeight = maxOf(declaredHeight ?: 0, actualHeight)
        val atlasColumns = extent(imageWidth, width, "width")
        val rows = extent(imageHeight, height, "height")
        val capacity = atlasColumns.toLong() * rows
        if (capacity > Int.MAX_VALUE) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tileset", "tilecount", reason = "Atlas exceeds supported tile count")
        val defaultColumns = extent(declaredWidth ?: actualWidth, width, "width")
        val columns = integer(element, path, "columns", 1, defaultColumns)
        if (columns > atlasColumns) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tileset", "columns", reason = "Columns exceed image geometry")
        val defaultRows = extent(if (tsxSource != null && !element.hasAttribute("tilecount")) actualHeight else (declaredHeight ?: actualHeight), height, "height")
        val defaultTileCount = (columns.toLong() * defaultRows).coerceAtMost(capacity)
        val tileCount = integer(element, path, "tilecount", 1, defaultTileCount.toInt())
        if (tileCount > capacity) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tileset", "tilecount", reason = "Tile count exceeds atlas capacity")

        val seen = mutableSetOf<Int>()
        val blocked = mutableSetOf<Int>()
        val hoverBlocked = mutableSetOf<Int>()
        val overCliffBlocked = mutableSetOf<Int>()
        val resourcePools = mutableSetOf<Int>()
        val water = mutableSetOf<Int>()
        val bridges = mutableSetOf<Int>()
        val lava = mutableSetOf<Int>()
        val buildingBlocked = mutableSetOf<Int>()
        val trees = mutableSetOf<Int>()
        val unitTiles = linkedMapOf<Int, TileUnitMetadata>()
        val declaredIdsWithAnyProperty = mutableSetOf<Int>()

        for (tile in children.filter { it.tagName == "tile" }) {
            attributes(tile, path, setOf("id", "type", "terrain", "probability"))
            val id = integer(tile, path, "id", 0)
            if (id >= tileCount) reject(ContentErrorCode.BAD_REFERENCE, path, "tile", "id", id.toString(), "Tile id is outside atlas")
            if (!seen.add(id)) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tile", "id", id.toString(), "Duplicate tile id")
            val tileChildren = elements(tile, path)
            val propertiesNodes = tileChildren.filter { it.tagName == "properties" }
            if (propertiesNodes.size > 1) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tile", "properties", id.toString(), "Expected at most one properties element")
            }
            for (extra in tileChildren) {
                when (extra.tagName) {
                    "properties" -> Unit
                    "animation", "objectgroup" -> diagnostics.add("Ignored tile child <${extra.tagName}> on tile $id in tileset '$name' (${path.fileName})")
                    else -> reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, extra.tagName, reason = "Unsupported child of tile")
                }
            }
            val propertiesNode = propertiesNodes.singleOrNull() ?: continue
            attributes(propertiesNode, path, emptySet())
            val propMap = linkedMapOf<String, String>()
            for (property in elements(propertiesNode, path)) {
                if (property.tagName != "property") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, property.tagName, reason = "Expected property element")
                attributes(property, path, setOf("name", "value", "type"))
                val propertyName = required(property, path, "name")
                if (!property.hasAttribute("value")) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "property", "value", propertyName, "Property value attribute is required (may be empty)")
                if (propMap.put(propertyName, property.getAttribute("value")) != null) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "property", "name", propertyName, "Duplicate property")
                }
                if (elements(property, path).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "property", reason = "Property cannot contain children")
            }
            if (propMap.isEmpty()) continue
            declaredIdsWithAnyProperty.add(id)

            var unitName: String? = null
            var isCustomUnit = false
            var teamValue: String? = null
            var variantType: String? = null

            for ((propertyName, propertyValue) in propMap) {
                when (propertyName) {
                    "res_pool" -> resourcePools.add(id)
                    "water" -> water.add(id)
                    "water-bridge" -> bridges.add(id)
                    "block-buildings" -> buildingBlocked.add(id)
                    "unit" -> {
                        val trimmed = propertyValue.trim()
                        if (trimmed.equals("tree", ignoreCase = true)) {
                            // Align with DEC-P008-002 / trees blocking semantics (blocks LAND, HOVER, OVER_CLIFF, OVER_CLIFF_WATER).
                            trees.add(id)
                            blocked.add(id)
                            hoverBlocked.add(id)
                            overCliffBlocked.add(id)
                        } else if (trimmed.isNotEmpty()) {
                            unitName = trimmed
                            isCustomUnit = false
                        }
                    }
                    "customUnit" -> {
                        val trimmed = propertyValue.trim()
                        if (trimmed.isNotEmpty()) {
                            unitName = trimmed
                            isCustomUnit = true
                        }
                    }
                    "team" -> teamValue = propertyValue.trim().takeIf { it.isNotEmpty() }
                    "type" -> variantType = propertyValue.trim().takeIf { it.isNotEmpty() }
                    "randomRotate", "showFog" -> Unit
                    in blockingProperties -> {
                        if (propertyName == "trees" || propertyName == "tree") {
                            trees.add(id)
                        }
                        blocked.add(id)
                        if (propertyName == "lava" || propertyName == "lava-cliff") lava.add(id)
                        if (propertyName in setOf("trees", "tree", "large-rock", "large-cliff", "lava", "lava-cliff", "block-land")) {
                            hoverBlocked.add(id)
                        }
                        if (propertyName in setOf("trees", "tree", "large-rock", "lava", "lava-cliff", "block-land")) {
                            overCliffBlocked.add(id)
                        }
                    }
                    else -> {
                        diagnostics.add("Ignored unknown tile property '$propertyName' on tile $id in tileset '$name' (${path.fileName})")
                    }
                }
            }

            if (unitName != null) {
                unitTiles[id] = TileUnitMetadata(
                    unitType = unitName,
                    team = teamValue,
                    variantType = variantType,
                    isCustomUnit = isCustomUnit,
                )
            }
        }

        // Original RW fallback (game/b/i.smali): when local tile 0 declares no explicit properties
        // on shallowwater.png / water.png / deepwater.png, default local tile 0 to water.
        val imageFileName = imagePath.fileName?.toString()?.lowercase()
            ?: imageSource.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (imageFileName in defaultWaterImageNames && 0 !in declaredIdsWithAnyProperty && tileCount > 0) {
            water.add(0)
        }

        return TileSetDefinition(
            firstGid = firstGid,
            name = name,
            tileWidth = width,
            tileHeight = height,
            imagePath = imagePath,
            blockedTileIds = blocked,
            tileCount = tileCount,
            columns = columns,
            spacing = spacing,
            margin = margin,
            transparentColor = image.getAttribute("trans").takeIf { image.hasAttribute("trans") },
            resourcePoolTileIds = resourcePools,
            waterTileIds = water,
            waterBridgeTileIds = bridges,
            hoverBlockedTileIds = hoverBlocked,
            overCliffBlockedTileIds = overCliffBlocked,
            lavaTileIds = lava,
            buildingBlockedTileIds = buildingBlocked,
            treeTileIds = trees,
            unitTiles = unitTiles,
            imageSource = imageSource,
            tsxSource = tsxSource,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    private fun readPngDimensions(
        image: Path,
        file: Path,
        source: String,
        openImage: (Path) -> InputStream,
    ): Pair<Int, Int> {
        // Read only the PNG IHDR dimensions; no desktop graphics API is required by core.
        try {
            DataInputStream(openImage(image)).use { input ->
                if (input.readLong() != 0x89504E470D0A1A0AuL.toLong() || input.readInt() != 13 || input.readInt() != 0x49484452) {
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, file, "image", "source", source, "Expected PNG atlas with IHDR header")
                }
                val actualWidth = input.readInt()
                val actualHeight = input.readInt()
                if (actualWidth <= 0 || actualHeight <= 0) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, file, "image", "source", source, "Invalid PNG dimensions ${actualWidth}x${actualHeight}")
                }
                return actualWidth to actualHeight
            }
        } catch (failure: IOException) {
            reject(ContentErrorCode.BAD_REFERENCE, file, "image", "source", source, failure.message ?: "Cannot read PNG dimensions")
        }
    }
}