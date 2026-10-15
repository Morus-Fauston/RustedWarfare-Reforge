package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import org.w3c.dom.Element

internal object TmxReader {
    fun read(path: Path): MapDefinition {
        val map = readXml(path)
        if (map.tagName != "map") reject(ContentErrorCode.MALFORMED_SYNTAX, path, map.tagName, reason = "Expected map root")
        attributes(map, path, setOf("version", "tiledversion", "orientation", "renderorder", "width", "height",
            "tilewidth", "tileheight", "nextobjectid", "backgroundcolor"))
        supported(map, path, "orientation", "orthogonal")
        supported(map, path, "renderorder", "right-down")
        val width = integer(map, path, "width", 1)
        val height = integer(map, path, "height", 1)
        val tileWidth = integer(map, path, "tilewidth", 1)
        val tileHeight = integer(map, path, "tileheight", 1)
        if (tileWidth != 20) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "map", "tilewidth", reason = "Only 20 is supported")
        if (tileHeight != 20) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "map", "tileheight", reason = "Only 20 is supported")
        val count = width.toLong() * height
        if (count > Int.MAX_VALUE) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "map", "width", reason = "Grid size exceeds integer range")
        val children = elements(map, path)
        children.forEach {
            if (it.tagName !in setOf("tileset", "layer")) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, it.tagName, reason = "Unsupported map element")
        }
        val tileSets = children.filter { it.tagName == "tileset" }.map { element ->
            val first = integer(element, path, "firstgid", 1)
            if (element.hasAttribute("source")) {
                attributes(element, path, setOf("firstgid", "source"))
                if (elements(element, path).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", reason = "External reference cannot have inline elements")
                val source = resolveReference(path, required(element, path, "source"), "tileset", "source")
                TsxReader.read(source, first, tileWidth, tileHeight)
            } else TsxReader.readInline(element, path, first, tileWidth, tileHeight)
        }.sortedBy { it.firstGid }
        if (tileSets.isEmpty()) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "map", "tileset", reason = "No tilesets defined")
        tileSets.forEachIndexed { index, tileSet ->
            val end = tileSet.firstGid.toLong() + tileSet.tileCount
            if (end > Int.MAX_VALUE.toLong() + 1 || (index > 0 && tileSets[index - 1].firstGid.toLong() + tileSets[index - 1].tileCount > tileSet.firstGid)) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "tileset", "firstgid", tileSet.firstGid.toString(), "Tileset ranges overlap or exceed supported GIDs")
            }
        }
        var ground: IntArray? = null
        for (layer in children.filter { it.tagName == "layer" }) {
            attributes(layer, path, setOf("name", "width", "height"))
            val name = required(layer, path, "name")
            if (integer(layer, path, "width", 1) != width) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "layer", "width", reason = "Layer width differs from map")
            if (integer(layer, path, "height", 1) != height) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "layer", "height", reason = "Layer height differs from map")
            val data = onlyChild(layer, path, "data")
            if (data.hasAttribute("compression")) reject(ContentErrorCode.UNSUPPORTED_ENCODING, path, "data", "compression", data.getAttribute("compression"), "Compressed data is unsupported")
            val encoding = required(data, path, "encoding")
            if (encoding != "csv") reject(ContentErrorCode.UNSUPPORTED_ENCODING, path, "data", "encoding", encoding, "Only CSV is supported")
            attributes(data, path, setOf("encoding"))
            if (elements(data, path, allowText = true).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "data", reason = "CSV cannot contain child elements")
            val tokens = data.textContent.trim().split(',')
            if (tokens.size != count.toInt()) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", reason = "Expected $count cells, found ${tokens.size}")
            val gids = IntArray(tokens.size) { index ->
                val token = tokens[index].trim()
                val parsed = token.toLongOrNull()
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", "gid", token, "Invalid GID at cell $index")
                if (parsed !in 0..Int.MAX_VALUE.toLong()) reject(ContentErrorCode.BAD_REFERENCE, path, "data", "gid", token, "GID outside supported range at cell $index")
                parsed.toInt()
            }
            if (name != "Ground") {
                if (gids.any { it != 0 }) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Only Ground may contain tiles")
                continue
            }
            if (ground != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple Ground layers are unsupported")
            ground = gids
        }
        val gids = ground ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "map", "Ground", reason = "Ground layer is absent")
        val blocked = BooleanArray(gids.size) { index ->
            val gid = gids[index]
            if (gid == 0) true else {
                val tileSet = tileSets.firstOrNull { gid.toLong() >= it.firstGid && gid.toLong() < it.firstGid.toLong() + it.tileCount }
                    ?: reject(ContentErrorCode.BAD_REFERENCE, path, "data", "gid", gid.toString(), "No tileset owns GID at cell $index")
                gid - tileSet.firstGid in tileSet.blockedTileIds
            }
        }
        return MapDefinition(width, height, tileWidth, tileHeight, tileSets, gids, BlockingGrid(width, height, blocked))
    }
}

private fun supported(element: Element, path: Path, field: String, expected: String) {
    val value = required(element, path, field)
    if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, element.tagName, field, value, "Only $expected is supported")
}
