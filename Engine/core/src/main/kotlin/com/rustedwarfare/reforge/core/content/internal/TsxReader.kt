package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.w3c.dom.Element

internal object TsxReader {
    private val blockingProperties = setOf("trees", "large-rock", "small-rock", "cliff", "large-cliff")
    private val tilesetAttributes = setOf("name", "tilewidth", "tileheight", "tilecount", "columns", "spacing", "margin")

    fun read(path: Path, firstGid: Int, tileWidth: Int, tileHeight: Int): TileSetDefinition =
        parse(readXml(path), path, firstGid, tileWidth, tileHeight, false)

    fun readInline(element: Element, path: Path, firstGid: Int, tileWidth: Int, tileHeight: Int): TileSetDefinition =
        parse(element, path, firstGid, tileWidth, tileHeight, true)

    private fun parse(element: Element, path: Path, firstGid: Int, mapTileWidth: Int, mapTileHeight: Int, inline: Boolean): TileSetDefinition {
        if (element.tagName != "tileset") reject(ContentErrorCode.MALFORMED_SYNTAX, path, element.tagName, reason = "Expected tileset root")
        attributes(element, path, if (inline) tilesetAttributes + "firstgid" else tilesetAttributes)
        val name = if (inline) element.getAttribute("name") else required(element, path, "name")
        val width = integer(element, path, "tilewidth", 1)
        val height = integer(element, path, "tileheight", 1)
        if (width != mapTileWidth) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", "tilewidth", reason = "Tileset differs from map tile width")
        if (height != mapTileHeight) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", "tileheight", reason = "Tileset differs from map tile height")
        val children = elements(element, path)
        children.firstOrNull { it.tagName !in setOf("image", "tile") }?.let {
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, it.tagName, reason = "Unsupported tileset element")
        }
        val images = children.filter { it.tagName == "image" }
        if (images.isEmpty()) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "tileset", "image", reason = "Missing atlas image")
        if (images.size != 1) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", "image", reason = "Only one atlas image is supported")
        val image = images.single()
        attributes(image, path, setOf("source", "trans", "width", "height"))
        if (elements(image, path).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "image", reason = "Image cannot have child elements")
        val imageSource = required(image, path, "source")
        val imagePath = resolveReference(path, imageSource, "image", "source")
        val imageWidth = integer(image, path, "width", 1)
        val imageHeight = integer(image, path, "height", 1)
        verifyPngDimensions(imagePath, path, imageSource, imageWidth, imageHeight)
        val spacing = integer(element, path, "spacing", 0, 0)
        val margin = integer(element, path, "margin", 0, 0)
        fun extent(pixels: Int, tileSize: Int, field: String): Int {
            val available = pixels.toLong() - 2L * margin + spacing
            val pitch = tileSize.toLong() + spacing
            if (available < pitch || available % pitch != 0L) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "image", field, imageSource, "Image dimensions do not form a tile atlas")
            return (available / pitch).toInt()
        }
        val atlasColumns = extent(imageWidth, width, "width")
        val rows = extent(imageHeight, height, "height")
        val capacity = atlasColumns.toLong() * rows
        if (capacity > Int.MAX_VALUE) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tileset", "tilecount", reason = "Atlas exceeds supported tile count")
        val columns = integer(element, path, "columns", 1, atlasColumns)
        if (columns != atlasColumns) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tileset", "columns", reason = "Columns differ from image geometry")
        val tileCount = integer(element, path, "tilecount", 1, capacity.toInt())
        if (tileCount > capacity) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tileset", "tilecount", reason = "Tile count exceeds atlas capacity")
        val seen = mutableSetOf<Int>()
        val blocked = mutableSetOf<Int>()
        val resourcePools = mutableSetOf<Int>()
        for (tile in children.filter { it.tagName == "tile" }) {
            attributes(tile, path, setOf("id"))
            val id = integer(tile, path, "id", 0)
            if (id >= tileCount) reject(ContentErrorCode.BAD_REFERENCE, path, "tile", "id", id.toString(), "Tile id is outside atlas")
            if (!seen.add(id)) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "tile", "id", id.toString(), "Duplicate tile id")
            val tileChildren = elements(tile, path)
            if (tileChildren.isEmpty()) continue
            val properties = onlyChild(tile, path, "properties")
            attributes(properties, path, emptySet())
            val names = mutableSetOf<String>()
            for (property in elements(properties, path)) {
                if (property.tagName != "property") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, property.tagName, reason = "Expected property element")
                attributes(property, path, setOf("name", "value"))
                val propertyName = required(property, path, "name")
                if (propertyName !in blockingProperties && propertyName != "res_pool") reject(ContentErrorCode.UNSUPPORTED_PROPERTY, path, "property", "name", propertyName, "Unsupported tile property")
                if (!property.hasAttribute("value")) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "property", "value", propertyName, "Property value attribute is required (may be empty)")
                if (!names.add(propertyName)) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "property", "name", propertyName, "Duplicate property")
                if (elements(property, path).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "property", reason = "Property cannot contain children")
                if (propertyName == "res_pool") resourcePools.add(id) else blocked.add(id)
            }
        }
        return TileSetDefinition(firstGid, name, width, height, imagePath, blocked, tileCount, columns, spacing, margin,
            image.getAttribute("trans").takeIf { image.hasAttribute("trans") }, resourcePools)
    }

    private fun verifyPngDimensions(image: Path, file: Path, source: String, width: Int, height: Int) {
        // Read only the PNG IHDR dimensions; no desktop graphics API is required by core.
        try {
            DataInputStream(Files.newInputStream(image)).use { input ->
                if (input.readLong() != 0x89504E470D0A1A0AuL.toLong() || input.readInt() != 13 || input.readInt() != 0x49484452) {
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, file, "image", "source", source, "Expected PNG atlas with IHDR header")
                }
                val actualWidth = input.readInt()
                val actualHeight = input.readInt()
                if (actualWidth < width) reject(ContentErrorCode.MALFORMED_SYNTAX, file, "image", "width", source, "Declared region $width exceeds PNG width $actualWidth")
                if (actualHeight < height) reject(ContentErrorCode.MALFORMED_SYNTAX, file, "image", "height", source, "Declared region $height exceeds PNG height $actualHeight")
            }
        } catch (failure: IOException) {
            reject(ContentErrorCode.BAD_REFERENCE, file, "image", "source", source, failure.message ?: "Cannot read PNG dimensions")
        }
    }
}
