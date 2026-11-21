package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import org.w3c.dom.Element

internal object TmxReader {
    /** Hard upper bound on map cells (2048x2048 = 4,194,304 cells, 16 MiB raw layer payload) to prevent OOM / zip bombs. */
    private const val MAX_MAP_CELLS: Long = 4_194_304L
    private const val MAX_BASE64_CHARS: Int = 32 * 1024 * 1024

    private val mapAttributes = setOf(
        "version", "tiledversion", "orientation", "renderorder", "width", "height",
        "tilewidth", "tileheight", "infinite", "nextobjectid", "nextlayerid", "backgroundcolor",
    )
    private val layerAttributes = setOf(
        "id", "name", "width", "height", "visible", "opacity", "locked", "offsetx", "offsety", "x", "y",
    )
    private val objectGroupAttributes = setOf(
        "id", "name", "color", "visible", "opacity", "locked", "offsetx", "offsety", "draworder", "width", "height",
    )

    fun read(path: Path, tilesetRoots: List<Path> = emptyList()): MapDefinition =
        read(
            path = path,
            tilesetRoots = tilesetRoots,
            packageMode = false,
            read = Files::readAllBytes,
            openImage = { Files.newInputStream(it) },
        )

    fun readPackage(path: Path, read: (Path) -> ByteArray): MapDefinition =
        read(
            path = path,
            tilesetRoots = emptyList(),
            packageMode = true,
            read = read,
            openImage = { ByteArrayInputStream(read(it)) },
        )

    private fun read(
        path: Path,
        tilesetRoots: List<Path>,
        packageMode: Boolean,
        read: (Path) -> ByteArray,
        openImage: (Path) -> InputStream,
    ): MapDefinition {
        val map = readXml(path, read)
        if (map.tagName != "map") reject(ContentErrorCode.MALFORMED_SYNTAX, path, map.tagName, reason = "Expected map root")
        attributes(map, path, mapAttributes)
        supported(map, path, "orientation", "orthogonal")
        if (map.hasAttribute("renderorder")) supported(map, path, "renderorder", "right-down")
        if (map.hasAttribute("infinite")) {
            val inf = required(map, path, "infinite")
            if (inf != "0" && !inf.equals("false", ignoreCase = true)) {
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "map", "infinite", inf, "Infinite maps are unsupported")
            }
        }
        val width = integer(map, path, "width", 1)
        val height = integer(map, path, "height", 1)
        val tileWidth = integer(map, path, "tilewidth", 1)
        val tileHeight = integer(map, path, "tileheight", 1)
        if (tileWidth != 20) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "map", "tilewidth", reason = "Only 20 is supported")
        if (tileHeight != 20) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "map", "tileheight", reason = "Only 20 is supported")
        val count = width.toLong() * height
        if (count > MAX_MAP_CELLS) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "map", "width", reason = "Grid size $count exceeds supported limit $MAX_MAP_CELLS")
        val cellCount = count.toInt()
        val backgroundColor = map.getAttribute("backgroundcolor").takeIf { map.hasAttribute("backgroundcolor") && it.isNotBlank() }

        val children = elements(map, path)
        children.forEach {
            if (it.tagName !in setOf("properties", "tileset", "layer", "objectgroup")) {
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, it.tagName, reason = "Unsupported map element")
            }
        }

        val diagnostics = mutableListOf<String>()
        val mapProperties = linkedMapOf<String, String>()
        for (propsElement in children.filter { it.tagName == "properties" }) {
            for (prop in elements(propsElement, path)) {
                if (prop.tagName != "property") continue
                val key = prop.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                mapProperties[key] = prop.getAttribute("value")
            }
        }

        val normalizedRoots = tilesetRoots.map { it.toAbsolutePath().normalize() }
        val resolveTsx: (Path, String) -> Path = { referencingFile, source ->
            resolveReferencePath(referencingFile, source, normalizedRoots, packageMode, isImage = false, section = "tileset", field = "source")
        }
        val resolveImage: (Path, String) -> Path = { referencingFile, source ->
            resolveReferencePath(referencingFile, source, normalizedRoots, packageMode, isImage = true, section = "image", field = "source")
        }

        val tileSets = children.filter { it.tagName == "tileset" }.map { element ->
            val first = integer(element, path, "firstgid", 1)
            if (element.hasAttribute("source")) {
                attributes(element, path, setOf("firstgid", "source"))
                if (elements(element, path).isNotEmpty()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "tileset", reason = "External reference cannot have inline elements")
                val sourceRaw = required(element, path, "source")
                val source = resolveTsx(path, sourceRaw)
                TsxReader.read(source, first, tileWidth, tileHeight, sourceRaw, read, openImage, resolveImage, diagnostics)
            } else {
                TsxReader.readInline(element, path, first, tileWidth, tileHeight, openImage, resolveImage, diagnostics)
            }
        }.sortedBy { it.firstGid }

        if (tileSets.isEmpty()) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "map", "tileset", reason = "No tilesets defined")
        tileSets.forEachIndexed { index, tileSet ->
            val end = tileSet.firstGid.toLong() + tileSet.tileCount
            if (end > Int.MAX_VALUE.toLong() + 1) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "tileset", "firstgid", tileSet.firstGid.toString(), "Tileset range exceeds supported GIDs")
            }
            if (index > 0 && tileSets[index - 1].firstGid == tileSet.firstGid) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "tileset", "firstgid", tileSet.firstGid.toString(), "Duplicate tileset firstgid")
            }
            if (index > 0 && tileSets[index - 1].firstGid.toLong() + tileSets[index - 1].tileCount > tileSet.firstGid) {
                val prev = tileSets[index - 1]
                // Original RW skirmish maps commonly overlap when an external TSX or updated PNG expanded after
                // the map was saved. Allow overlap when fallback tileset roots are active or when the preceding
                // tileset is an external TSX whose tilecount grew; strictly reject explicit conflicts otherwise.
                val externalExpanded = prev.tsxSource != null && normalizedRoots.isNotEmpty()
                val pngExpanded = prev.tsxSource == null && normalizedRoots.isNotEmpty()
                if (!externalExpanded && !pngExpanded) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "tileset", "firstgid", tileSet.firstGid.toString(),
                        "Tileset ranges overlap or exceed supported GIDs")
                } else {
                    diagnostics.add(
                        "Tileset '${prev.name}' (firstgid=${prev.firstGid}, count=${prev.tileCount}) overlaps '${tileSet.name}' (firstgid=${tileSet.firstGid}); using Tiled latest-firstgid ownership"
                    )
                }
            }
        }

        var groundRaw: IntArray? = null
        var groundDetailsRaw: IntArray? = null
        var groundDetails2Raw: IntArray? = null
        var itemsRaw: IntArray? = null
        var pathingOverrideRaw: IntArray? = null
        var unitsRaw: IntArray? = null

        for (layer in children.filter { it.tagName == "layer" }) {
            attributes(layer, path, layerAttributes)
            val name = required(layer, path, "name")
            if (layer.hasAttribute("width") && integer(layer, path, "width", 1) != width) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, "layer", "width", reason = "Layer width differs from map")
            }
            if (layer.hasAttribute("height") && integer(layer, path, "height", 1) != height) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, "layer", "height", reason = "Layer height differs from map")
            }
            val lower = name.trim().lowercase()
            if (lower == "set" || lower == "set-disabled") {
                // Tiled auto-tiling helper layers are ignored per original RW (b.smali).
                continue
            }
            val data = layerDataElement(layer, path)
            val gids = decodeLayerData(data, path, cellCount)

            when (lower) {
                "ground" -> {
                    if (groundRaw != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple Ground layers are unsupported")
                    groundRaw = gids
                }
                "grounddetails" -> {
                    if (groundDetailsRaw != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple GroundDetails layers are unsupported")
                    groundDetailsRaw = gids
                }
                "grounddetails2" -> {
                    if (groundDetails2Raw != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple GroundDetails2 layers are unsupported")
                    groundDetails2Raw = gids
                }
                "items", "objects" -> {
                    if (itemsRaw != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple Items/Objects layers are unsupported")
                    itemsRaw = gids
                }
                "pathingoverride" -> {
                    if (pathingOverrideRaw != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple PathingOverride layers are unsupported")
                    pathingOverrideRaw = gids
                }
                "units" -> {
                    if (unitsRaw != null) reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Multiple Units layers are unsupported")
                    unitsRaw = gids
                }
                else -> {
                    if (gids.any { it != 0 }) {
                        reject(ContentErrorCode.UNSUPPORTED_LAYER, path, "layer", "name", name, "Unsupported non-empty layer")
                    }
                }
            }
        }

        val rawGround = groundRaw ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "map", "Ground", reason = "Ground layer is absent")

        fun validateLayerGids(layerGids: IntArray?) {
            if (layerGids == null) return
            for (index in layerGids.indices) {
                val raw = layerGids[index]
                val clean = GroundRules.stripFlipFlags(raw)
                if (clean == 0 && raw != 0) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "data", "gid", raw.toUInt().toString(),
                        "Flip flags set on empty GID 0 at cell $index")
                }
                if (clean != 0 && !GroundRules.owns(tileSets, clean)) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "data", "gid", clean.toString(), "No tileset owns GID at cell $index")
                }
            }
        }
        validateLayerGids(rawGround)
        validateLayerGids(groundDetailsRaw)
        validateLayerGids(groundDetails2Raw)
        validateLayerGids(itemsRaw)
        validateLayerGids(pathingOverrideRaw)
        validateLayerGids(unitsRaw)

        val startingUnits = mutableListOf<MapStartingUnit>()
        val itemsVisualRaw = itemsRaw?.copyOf()

        val landBlocked = BooleanArray(cellCount)
        val waterBlocked = BooleanArray(cellCount)
        val hoverBlocked = BooleanArray(cellCount)
        val overCliffBlocked = BooleanArray(cellCount)
        val overCliffWaterBlocked = BooleanArray(cellCount)
        val resourcePools = BooleanArray(cellCount)
        val buildingBlocked = BooleanArray(cellCount)
        val waterCells = BooleanArray(cellCount)
        val nativeLiquidCells = BooleanArray(cellCount)
        val cliffCells = BooleanArray(cellCount)

        for (index in 0 until cellCount) {
            val col = index % width
            val row = index / width
            val worldX = (col + 0.5) * tileWidth
            val worldY = (row + 0.5) * tileHeight

            val gClean = GroundRules.stripFlipFlags(rawGround[index])
            val iClean = itemsRaw?.let { GroundRules.stripFlipFlags(it[index]) } ?: 0
            val pClean = pathingOverrideRaw?.let { GroundRules.stripFlipFlags(it[index]) } ?: 0
            val uClean = unitsRaw?.let { GroundRules.stripFlipFlags(it[index]) } ?: 0

            if (gClean != 0) {
                GroundRules.unitSpawn(tileSets, gClean)?.let { meta ->
                    startingUnits.add(MapStartingUnit(meta.unitType, meta.team, col, row, worldX, worldY, meta.variantType, meta.isCustomUnit))
                }
            }

            if (iClean != 0) {
                val itemUnit = GroundRules.unitSpawn(tileSets, iClean)
                if (itemUnit != null) {
                    startingUnits.add(MapStartingUnit(itemUnit.unitType, itemUnit.team, col, row, worldX, worldY, itemUnit.variantType, itemUnit.isCustomUnit))
                    if (itemsVisualRaw != null) itemsVisualRaw[index] = 0
                }
            }

            if (uClean != 0) {
                val unitMeta = GroundRules.unitSpawn(tileSets, uClean)
                if (unitMeta != null) {
                    startingUnits.add(MapStartingUnit(unitMeta.unitType, unitMeta.team, col, row, worldX, worldY, unitMeta.variantType, unitMeta.isCustomUnit))
                } else {
                    diagnostics.add("Ignored non-unit GID $uClean on Units layer at ($col,$row)")
                }
            }

            val groundSet = if (gClean == 0) null else GroundRules.owner(tileSets, gClean)
            val groundLocal = if (groundSet != null) gClean - groundSet.firstGid else -1

            var cellLand = if (gClean == 0) true else GroundRules.blocked(tileSets, gClean, MovementType.LAND)
            var cellWater = if (gClean == 0) true else GroundRules.blocked(tileSets, gClean, MovementType.WATER)
            var cellHover = if (gClean == 0) true else GroundRules.blocked(tileSets, gClean, MovementType.HOVER)
            var cellOverCliff = if (gClean == 0) true else GroundRules.blocked(tileSets, gClean, MovementType.OVER_CLIFF)
            var cellOverCliffWater = if (gClean == 0) true else GroundRules.blocked(tileSets, gClean, MovementType.OVER_CLIFF_WATER)
            var cellPool = if (gClean == 0) false else GroundRules.resourcePool(tileSets, gClean)
            var cellBuildBlocked = if (gClean == 0) false else GroundRules.buildingBlocked(tileSets, gClean)
            var cellIsWater = if (gClean == 0) false else GroundRules.liquid(tileSets, gClean)
            var cellIsNativeLiquid = if (gClean == 0) false else GroundRules.nativeLiquid(tileSets, gClean)
            var cellIsCliff = if (gClean == 0) false else GroundRules.cliff(tileSets, gClean)
            val groundHardBlocked = groundSet == null || groundLocal in groundSet.blockedTileIds
            val groundHoverHardBlocked = groundSet == null || groundLocal in groundSet.hoverBlockedTileIds
            val groundOverCliffHardBlocked = groundSet == null || groundLocal in groundSet.overCliffBlockedTileIds
            val groundIsLava = groundSet != null && groundLocal in groundSet.lavaTileIds

            if (iClean != 0) {
                val itemSet = GroundRules.owner(tileSets, iClean)
                if (itemSet != null) {
                    val local = iClean - itemSet.firstGid
                    if (local in itemSet.resourcePoolTileIds) cellPool = true
                    if (local in itemSet.buildingBlockedTileIds) cellBuildBlocked = true

                    val itemIsBridge = local in itemSet.waterBridgeTileIds
                    val itemIsWater = local in itemSet.waterTileIds
                    val itemIsLava = local in itemSet.lavaTileIds
                    val itemBlocked = local in itemSet.blockedTileIds
                    val itemHoverBlocked = local in itemSet.hoverBlockedTileIds
                    val itemOverCliffBlocked = local in itemSet.overCliffBlockedTileIds

                    if (itemIsBridge) {
                        if (cellIsWater && !groundHardBlocked) {
                            cellLand = itemBlocked
                            cellOverCliff = itemOverCliffBlocked || groundOverCliffHardBlocked
                            cellWater = itemBlocked
                            cellIsWater = false
                            cellIsNativeLiquid = groundIsLava || itemIsLava
                        } else {
                            if (itemBlocked) {
                                cellLand = true
                                cellWater = true
                            }
                            if (itemHoverBlocked) cellHover = true
                            if (itemOverCliffBlocked) {
                                cellOverCliff = true
                                cellOverCliffWater = true
                            }
                        }
                        if (itemHoverBlocked || groundHoverHardBlocked) cellHover = true
                    } else {
                        if (itemIsWater) {
                            cellLand = true
                            cellOverCliff = true
                            cellIsWater = true
                            cellIsNativeLiquid = true
                            cellWater = groundHardBlocked || itemBlocked
                        }
                        if (itemIsLava) {
                            cellIsNativeLiquid = true
                        }
                        if (itemBlocked) {
                            cellLand = true
                            cellWater = true
                        }
                        if (itemHoverBlocked) cellHover = true
                        if (itemOverCliffBlocked) {
                            cellOverCliff = true
                            cellOverCliffWater = true
                        }
                    }
                    if (itemHoverBlocked && !itemOverCliffBlocked) {
                        cellIsCliff = true
                    } else if (itemOverCliffBlocked) {
                        cellIsCliff = false
                    }
                }
            }

            if (pClean != 0) {
                cellLand = GroundRules.blocked(tileSets, pClean, MovementType.LAND)
                cellWater = GroundRules.blocked(tileSets, pClean, MovementType.WATER)
                cellHover = GroundRules.blocked(tileSets, pClean, MovementType.HOVER)
                cellOverCliff = GroundRules.blocked(tileSets, pClean, MovementType.OVER_CLIFF)
                cellOverCliffWater = GroundRules.blocked(tileSets, pClean, MovementType.OVER_CLIFF_WATER)
                cellIsWater = GroundRules.liquid(tileSets, pClean)
                cellIsNativeLiquid = GroundRules.nativeLiquid(tileSets, pClean)
                cellIsCliff = GroundRules.cliff(tileSets, pClean)
                cellBuildBlocked = GroundRules.buildingBlocked(tileSets, pClean)
                if (GroundRules.resourcePool(tileSets, pClean)) cellPool = true
            }

            landBlocked[index] = cellLand
            waterBlocked[index] = cellWater
            hoverBlocked[index] = cellHover
            overCliffBlocked[index] = cellOverCliff
            overCliffWaterBlocked[index] = cellOverCliffWater
            resourcePools[index] = cellPool
            buildingBlocked[index] = cellBuildBlocked
            waterCells[index] = cellIsWater
            nativeLiquidCells[index] = cellIsNativeLiquid
            cliffCells[index] = cellIsCliff
        }

        val triggerInfo = parseObjectGroups(children.filter { it.tagName == "objectgroup" }, path, diagnostics)

        val visualLayers = mutableListOf<MapVisualLayer>()
        visualLayers.add(MapVisualLayer("Ground", MapVisualLayerKind.GROUND, rawGround))
        groundDetailsRaw?.let { visualLayers.add(MapVisualLayer("GroundDetails", MapVisualLayerKind.GROUND_DETAILS, it)) }
        groundDetails2Raw?.let { visualLayers.add(MapVisualLayer("GroundDetails2", MapVisualLayerKind.GROUND_DETAILS_2, it)) }
        itemsVisualRaw?.let { visualLayers.add(MapVisualLayer("Items", MapVisualLayerKind.ITEMS, it)) }

        val cleanGroundGids = IntArray(cellCount) { GroundRules.stripFlipFlags(rawGround[it]) }

        return MapDefinition(
            widthTiles = width,
            heightTiles = height,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            tileSets = tileSets,
            groundGids = cleanGroundGids,
            blocking = BlockingGrid(width, height, landBlocked),
            waterObstacles = emptySet(),
            visualLayers = visualLayers,
            waterBlockingOverride = BlockingGrid(width, height, waterBlocked),
            hoverBlockingOverride = BlockingGrid(width, height, hoverBlocked),
            overCliffBlockingOverride = BlockingGrid(width, height, overCliffBlocked),
            overCliffWaterBlockingOverride = BlockingGrid(width, height, overCliffWaterBlocked),
            resourcePoolsOverride = resourcePools,
            buildingBlockedOverride = buildingBlocked,
            waterCellsOverride = waterCells,
            nativeLiquidCellsOverride = nativeLiquidCells,
            cliffCellsOverride = cliffCells,
            startingUnits = startingUnits,
            triggerInfo = triggerInfo,
            ignoredDiagnostics = diagnostics,
            mapProperties = mapProperties,
            backgroundColor = backgroundColor,
        )
    }

    private fun layerDataElement(layer: Element, path: Path): Element {
        val children = elements(layer, path)
        var data: Element? = null
        for (child in children) {
            when (child.tagName) {
                "data" -> {
                    if (data != null) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "layer", "data", reason = "Expected exactly one data element")
                    data = child
                }
                "properties" -> Unit
                else -> reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, child.tagName, reason = "Unsupported child of layer")
            }
        }
        return data ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "layer", "data", reason = "Missing data element")
    }

    private fun decodeLayerData(data: Element, path: Path, cellCount: Int): IntArray {
        val encoding = required(data, path, "encoding")
        val compression = data.getAttribute("compression").takeIf { data.hasAttribute("compression") }
        attributes(data, path, setOf("encoding", "compression"))
        if (elements(data, path, allowText = true).isNotEmpty()) {
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "data", reason = "Layer data cannot contain child elements")
        }
        return when (encoding) {
            "csv" -> {
                if (compression != null) {
                    reject(ContentErrorCode.UNSUPPORTED_ENCODING, path, "data", "compression", compression, "CSV compression is unsupported")
                }
                val text = data.textContent.trim()
                val tokens = if (text.isEmpty()) emptyList() else text.split(',')
                if (tokens.size != cellCount) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", reason = "Expected $cellCount cells, found ${tokens.size}")
                }
                IntArray(cellCount) { index ->
                    val token = tokens[index].trim()
                    val parsed = token.toLongOrNull()
                        ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", "gid", token, "Invalid GID at cell $index")
                    if (parsed !in 0L..0xFFFFFFFFL) {
                        reject(ContentErrorCode.BAD_REFERENCE, path, "data", "gid", token, "GID outside 32-bit unsigned range at cell $index")
                    }
                    parsed.toInt()
                }
            }
            "base64" -> {
                if (compression != null && compression !in setOf("gzip", "zlib")) {
                    reject(ContentErrorCode.UNSUPPORTED_ENCODING, path, "data", "compression", compression, "Unsupported base64 compression '$compression'")
                }
                val rawText = data.textContent
                if (rawText.length > MAX_BASE64_CHARS) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", "encoding", encoding,
                        "Base64 layer payload exceeds maximum allowed size")
                }
                val stripped = rawText.filterNot { it.isWhitespace() }
                val decoded = try {
                    Base64.getDecoder().decode(stripped)
                } catch (failure: IllegalArgumentException) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", "encoding", encoding,
                        "Invalid base64 layer payload: ${failure.message ?: "decode error"}")
                }
                val expectedBytes = cellCount.toLong() * 4L
                val rawBytes = try {
                    when (compression) {
                        null -> decoded
                        "gzip" -> decompressBounded(GZIPInputStream(ByteArrayInputStream(decoded)), expectedBytes, path)
                        "zlib" -> decompressBounded(InflaterInputStream(ByteArrayInputStream(decoded)), expectedBytes, path)
                        else -> error("unreachable")
                    }
                } catch (failure: IOException) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", "compression", compression,
                        "Failed to decompress $compression layer data: ${failure.message ?: "corrupt stream"}")
                }
                if (rawBytes.size.toLong() != expectedBytes) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", reason = "Expected $expectedBytes bytes for $cellCount cells, found ${rawBytes.size}")
                }
                val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(cellCount) { buffer.int }
            }
            else -> reject(ContentErrorCode.UNSUPPORTED_ENCODING, path, "data", "encoding", encoding, "Unsupported data encoding '$encoding'")
        }
    }

    private fun decompressBounded(stream: InputStream, expectedBytes: Long, path: Path): ByteArray {
        stream.use { input ->
            val output = ByteArrayOutputStream(expectedBytes.coerceAtMost(1024 * 1024).toInt())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > expectedBytes) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, "data", reason = "Decompressed layer data exceeds expected $expectedBytes bytes")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun parseObjectGroups(
        groups: List<Element>,
        path: Path,
        diagnostics: MutableList<String>,
    ): MapTriggerInfo {
        var mapType: String? = null
        var fog: String? = null
        val mapInfoProps = linkedMapOf<String, String>()
        var ignoredObjects = 0

        for (group in groups) {
            attributes(group, path, objectGroupAttributes)
            val groupName = group.getAttribute("name").trim()
            val lowerGroup = groupName.lowercase()
            if (lowerGroup != "triggers" && lowerGroup != "unitobjects") {
                diagnostics.add("Ignored unsupported objectgroup '$groupName' in ${path.fileName}")
                ignoredObjects += elements(group, path).count { it.tagName == "object" }
                continue
            }
            for (child in elements(group, path)) {
                if (child.tagName != "object") continue
                val objName = child.getAttribute("name").trim()
                val objType = child.getAttribute("type").trim()
                if (objName.equals("map_info", ignoreCase = true) || objType.equals("map_info", ignoreCase = true)) {
                    val props = extractProperties(child, path)
                    mapInfoProps.putAll(props)
                    props["type"]?.takeIf { it.isNotBlank() }?.let { mapType = it.trim() }
                    props["fog"]?.takeIf { it.isNotBlank() }?.let { fog = it.trim() }
                } else {
                    ignoredObjects++
                    diagnostics.add("Ignored unimplemented trigger/object '${objName.ifEmpty { objType.ifEmpty { "unnamed" } }}' in objectgroup '$groupName'")
                }
            }
        }
        return MapTriggerInfo(
            mapType = mapType,
            fog = fog,
            properties = mapInfoProps,
            ignoredObjectCount = ignoredObjects,
        )
    }

    private fun extractProperties(element: Element, path: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (child in elements(element, path)) {
            if (child.tagName != "properties") continue
            for (prop in elements(child, path)) {
                if (prop.tagName != "property") continue
                val key = prop.getAttribute("name").trim()
                if (key.isNotEmpty()) {
                    result[key] = prop.getAttribute("value")
                }
            }
        }
        return result
    }

    private fun resolveReferencePath(
        referencingFile: Path,
        source: String,
        tilesetRoots: List<Path>,
        packageMode: Boolean,
        isImage: Boolean,
        section: String,
        field: String,
    ): Path {
        val normalizedSource = source.replace('\\', '/')
        val primary = try {
            referencingFile.parent.resolve(normalizedSource).toAbsolutePath().normalize()
        } catch (failure: InvalidPathException) {
            reject(ContentErrorCode.BAD_REFERENCE, referencingFile, section, field, source, "Invalid reference path")
        }

        if (packageMode) {
            return primary
        }

        val mapDir = referencingFile.parent.toAbsolutePath().normalize()
        val allowedPrimary = if (tilesetRoots.isEmpty()) {
            true
        } else {
            primary.startsWith(mapDir) || tilesetRoots.any { primary.startsWith(it) }
        }
        if (allowedPrimary && Files.isRegularFile(primary) && Files.isReadable(primary)) {
            return primary
        }

        val fileName = try {
            Path.of(normalizedSource).fileName?.toString()
        } catch (_: InvalidPathException) {
            null
        }
        val candidates = mutableListOf<Path>()
        for (root in tilesetRoots) {
            safeResolveInside(root, normalizedSource)?.let { candidates.add(it) }
            if (fileName != null && fileName != "." && fileName != "..") {
                if (isImage) {
                    safeResolveInside(root, "bitmaps/$fileName")?.let { candidates.add(it) }
                    safeResolveInside(root, fileName)?.let { candidates.add(it) }
                } else {
                    safeResolveInside(root, fileName)?.let { candidates.add(it) }
                    safeResolveInside(root, "terrain/$fileName")?.let { candidates.add(it) }
                    safeResolveInside(root, "ridges/$fileName")?.let { candidates.add(it) }
                }
            }
        }
        for (candidate in candidates) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return candidate
            }
        }
        val detail = if (!allowedPrimary) "Reference path escapes allowed map/tileset roots: $source"
        else "Reference is not a readable file: $primary"
        reject(ContentErrorCode.BAD_REFERENCE, referencingFile, section, field, source, detail)
    }

    private fun safeResolveInside(root: Path, relative: String): Path? {
        return try {
            val relPath = Path.of(relative)
            if (relPath.isAbsolute) return null
            val resolved = root.resolve(relPath).toAbsolutePath().normalize()
            if (resolved.startsWith(root)) resolved else null
        } catch (_: InvalidPathException) {
            null
        }
    }
}

private fun supported(element: Element, path: Path, field: String, expected: String) {
    val value = required(element, path, field)
    if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, element.tagName, field, value, "Only $expected is supported")
}
