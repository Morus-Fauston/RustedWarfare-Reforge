package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.GdxNativesLoader
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Filter modes for the shared fullscreen [MapPickerScreen] (UI Supplement §4.3 & §5).
 */
enum class MapPickerFilter {
    SKIRMISH,
    FREE_SANDBOX,
    ALL,
}

/**
 * Immutable playable `.tmx` map entry consumed by [MapPickerScreen], Skirmish Lobby, Free Sandbox, and Match Loading card.
 */
data class MapPickerEntry(
    val id: String,
    val slug: String,
    val titleKeyOrRaw: String,
    val isTranslationKey: Boolean,
    val tmxPath: Path?,
    val catalogSceneId: String? = null,
    val mapDefinition: MapDefinition,
    val spawnPointsNormalized: List<Pair<Float, Float>>,
    val thumbnailPath: Path? = null,
    val explicitMaxPlayers: Int? = null,
) {
    val maxPlayers: Int
        get() = (explicitMaxPlayers ?: spawnPointsNormalized.size).coerceIn(2, 10)

    fun localizedTitle(language: UiLanguage = UiStrings.currentLanguage): String =
        if (isTranslationKey) UiStrings.get(titleKeyOrRaw, language) else titleKeyOrRaw
}

/**
 * Real playable `.tmx` skirmish map catalog (UI Supplement §4.3 & `UI-P004-008`):
 * - Scans configured original installation `assets/maps/skirmish/` `.tmx` files (48 official skirmish maps with native `*_map.png` thumbnails),
 *   isolating campaign/challenge/survival script maps;
 * - Scans user-private `maps/` `.tmx` directory when present;
 * - Falls back to packaged self-owned playable `.tmx` maps ONLY when no original skirmish maps are present (public clone / CI);
 * - Sorts ascending by player count (`2P -> 3P -> 4P -> ... -> 10P`), then alphabetically by title.
 */
object OutGameMapCatalog {
    private val lock = Any()

    private data class CatalogCacheKey(
        val originalDir: Path?,
        val userMapsDir: Path?,
    )

    @Volatile
    private var cachedKey: CatalogCacheKey? = null

    @Volatile
    private var cachedEntries: List<MapPickerEntry>? = null

    @Volatile
    var defaultOriginalDirectory: Path? = null

    @Volatile
    var defaultUserMapsDirectory: Path? = null

    private val bracketPlayerRegex = Regex("""\[(?:[a-zA-Z0-9_]+;)*p(\d+)]""", RegexOption.IGNORE_CASE)
    private val parenPlayerRegex = Regex("""\((\d+)p\)""", RegexOption.IGNORE_CASE)
    private val trailingAuthorRegex = Regex("""\[by[_\s][^\]]+]""", RegexOption.IGNORE_CASE)
    private val leadingBracketTagRegex = Regex("""^\[[^\]]+]\s*""")

    private val packagedFallbackFiles: List<Pair<String, List<Pair<Float, Float>>>> = listOf(
        "river_crossing.tmx" to listOf(0.19f to 0.24f, 0.81f to 0.76f, 0.81f to 0.24f, 0.19f to 0.76f),
        "volcanic_archipelago.tmx" to listOf(0.18f to 0.26f, 0.82f to 0.74f, 0.82f to 0.26f, 0.18f to 0.74f),
        "ring_atoll.tmx" to listOf(
            0.16f to 0.18f, 0.12f to 0.40f, 0.12f to 0.62f, 0.18f to 0.84f, 0.38f to 0.86f,
            0.84f to 0.18f, 0.88f to 0.40f, 0.88f to 0.62f, 0.82f to 0.84f, 0.62f to 0.14f,
        ),
    )

    fun configureDirectories(originalDirectory: Path?, userMapsDirectory: Path?) {
        val normOrig = originalDirectory?.toAbsolutePath()?.normalize()
        val normUser = userMapsDirectory?.toAbsolutePath()?.normalize()
        if (defaultOriginalDirectory != normOrig || defaultUserMapsDirectory != normUser) {
            defaultOriginalDirectory = normOrig
            defaultUserMapsDirectory = normUser
        }
    }

    fun entries(
        filter: MapPickerFilter = MapPickerFilter.SKIRMISH,
        originalDirectory: Path? = defaultOriginalDirectory,
        userMapsDirectory: Path? = defaultUserMapsDirectory,
    ): List<MapPickerEntry> {
        val all = ensureEntries(originalDirectory, userMapsDirectory)
        return when (filter) {
            MapPickerFilter.SKIRMISH, MapPickerFilter.FREE_SANDBOX, MapPickerFilter.ALL -> all
        }
    }

    fun defaultEntry(
        originalDirectory: Path? = defaultOriginalDirectory,
        userMapsDirectory: Path? = defaultUserMapsDirectory,
    ): MapPickerEntry = entries(MapPickerFilter.SKIRMISH, originalDirectory, userMapsDirectory).first()

    fun findByIdOrPath(
        idOrPath: String?,
        originalDirectory: Path? = defaultOriginalDirectory,
        userMapsDirectory: Path? = defaultUserMapsDirectory,
    ): MapPickerEntry? {
        if (idOrPath.isNullOrBlank()) return null
        val norm = idOrPath.trim()
        val list = ensureEntries(originalDirectory, userMapsDirectory)
        list.firstOrNull { it.id.equals(norm, ignoreCase = true) || it.slug.equals(norm, ignoreCase = true) }
            ?.let { return it }
        val asPath = runCatching { Path.of(norm).toAbsolutePath().normalize() }.getOrNull()
        if (asPath != null) {
            list.firstOrNull { it.tmxPath?.toAbsolutePath()?.normalize() == asPath }?.let { return it }
        }
        return null
    }

    fun entryFromCustomTmx(
        path: Path,
        displayName: String,
        map: MapDefinition,
        thumbnailPath: Path? = resolveSiblingMapThumbnail(path),
    ): MapPickerEntry {
        val stem = path.fileName.toString().removeSuffix(".tmx")
        val parsedMeta = parseMapStemMetadata(stem)
        val cleanTitle = if (displayName == stem) parsedMeta.cleanTitle else displayName
        val derivedSpawns = extractNormalizedSpawns(map, parsedMeta.playerCountHint)
        return MapPickerEntry(
            id = "custom:${parsedMeta.slug}:${path.toAbsolutePath().normalize()}",
            slug = parsedMeta.slug,
            titleKeyOrRaw = cleanTitle,
            isTranslationKey = false,
            tmxPath = path.toAbsolutePath().normalize(),
            mapDefinition = map,
            spawnPointsNormalized = derivedSpawns,
            thumbnailPath = thumbnailPath,
            explicitMaxPlayers = parsedMeta.playerCountHint,
        )
    }

    data class ParsedMapStem(
        val cleanTitle: String,
        val slug: String,
        val playerCountHint: Int?,
    )

    /**
     * Parses Rusted Warfare skirmish `.tmx` stem conventions such as:
     * - `[p2]Beach landing (2p) [by hxyy]` -> `cleanTitle = "Beach landing"`, `playerCountHint = 2`
     * - `[z;p10]Valley Arena (10p) [by_uber]` -> `cleanTitle = "Valley Arena"`, `playerCountHint = 10`
     */
    fun parseMapStemMetadata(rawStem: String): ParsedMapStem {
        val trimmed = rawStem.trim()
        val pBracket = bracketPlayerRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val pParen = parenPlayerRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val playerHint = (pBracket ?: pParen)?.coerceIn(2, 10)

        var cleaned = trimmed
            .replace(leadingBracketTagRegex, "")
            .replace(trailingAuthorRegex, "")
            .replace('_', ' ')
            .trim()
        cleaned = cleaned.replace(parenPlayerRegex, "").trim()
        cleaned = cleaned.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', '_')
        if (cleaned.isEmpty()) cleaned = trimmed

        val slug = cleaned.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
            .ifEmpty { "skirmish_map" }

        return ParsedMapStem(
            cleanTitle = cleaned,
            slug = slug,
            playerCountHint = playerHint,
        )
    }

    fun resolveSiblingMapThumbnail(tmxPath: Path): Path? {
        val abs = tmxPath.toAbsolutePath().normalize()
        val stem = abs.fileName?.toString()?.removeSuffix(".tmx") ?: return null
        val sibling = abs.resolveSibling("${stem}_map.png")
        return sibling.takeIf { Files.isRegularFile(it) }
    }

    fun extractNormalizedSpawns(map: MapDefinition, expectedPlayerCount: Int? = null): List<Pair<Float, Float>> {
        val worldW = (map.widthTiles * map.tileWidth).coerceAtLeast(1).toFloat()
        val worldH = (map.heightTiles * map.tileHeight).coerceAtLeast(1).toFloat()
        val byTeam = LinkedHashMap<Int, MutableList<Pair<Float, Float>>>()
        for (u in map.startingUnits) {
            val teamIdx = u.team?.trim()?.toIntOrNull() ?: continue
            if (teamIdx < 0) continue
            val nx = (u.worldX.toFloat() / worldW).coerceIn(0.06f, 0.94f)
            val ny = (u.worldY.toFloat() / worldH).coerceIn(0.06f, 0.94f)
            // Prioritize commandCenter / builder if multiple units belong to the same team
            val list = byTeam.getOrPut(teamIdx) { ArrayList() }
            if (u.unitType.equals("commandCenter", ignoreCase = true)) {
                list.add(0, nx to ny)
            } else {
                list.add(nx to ny)
            }
        }
        if (byTeam.size >= 2) {
            val extracted = byTeam.entries.sortedBy { it.key }.map { it.value.first() }.take(10).toMutableList()
            val target = (expectedPlayerCount ?: extracted.size).coerceIn(extracted.size, 10)
            while (extracted.size < target) {
                val idx = extracted.size
                val angle = -PI * 0.5 + (2.0 * PI * idx.toDouble() / target.toDouble())
                extracted += (0.50f + 0.36f * cos(angle).toFloat()).coerceIn(0.10f, 0.90f) to
                    (0.50f + 0.36f * sin(angle).toFloat()).coerceIn(0.10f, 0.90f)
            }
            return extracted
        }
        val count = (expectedPlayerCount ?: 4).coerceIn(2, 10)
        return if (count == 2) {
            listOf(0.20f to 0.24f, 0.80f to 0.76f)
        } else if (count == 4) {
            listOf(0.20f to 0.24f, 0.80f to 0.76f, 0.80f to 0.24f, 0.20f to 0.76f)
        } else {
            List(count) { idx ->
                val angle = -PI * 0.5 + (2.0 * PI * idx.toDouble() / count.toDouble())
                (0.50f + 0.36f * cos(angle).toFloat()).coerceIn(0.10f, 0.90f) to
                    (0.50f + 0.36f * sin(angle).toFloat()).coerceIn(0.10f, 0.90f)
            }
        }
    }

    private fun resolveOriginalSkirmishDir(originalDirectory: Path?): Path? {
        if (originalDirectory == null) return null
        val norm = originalDirectory.toAbsolutePath().normalize()
        val candidates = listOf(
            norm.resolve("assets/maps/skirmish"),
            norm.resolve("maps/skirmish"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }

    private fun scanTmxDirectory(
        dir: Path,
        fallbackRoots: List<Path>,
        idPrefix: String,
    ): List<MapPickerEntry> {
        if (!Files.isDirectory(dir)) return emptyList()
        val tmxFiles = Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tmx", ignoreCase = true) }
                .sorted(Comparator.comparing { it.fileName.toString().lowercase(Locale.ROOT) })
                .toList()
        }
        val out = ArrayList<MapPickerEntry>(tmxFiles.size)
        val usedIds = HashSet<String>()
        for (tmx in tmxFiles) {
            val normPath = tmx.toAbsolutePath().normalize()
            val loaded = when (val res = ContentLoader.loadMap(normPath, fallbackRoots)) {
                is ContentResult.Success -> res.value
                is ContentResult.Failure -> continue
            }
            val stem = normPath.fileName.toString().removeSuffix(".tmx")
            val meta = parseMapStemMetadata(stem)
            val spawns = extractNormalizedSpawns(loaded, meta.playerCountHint)
            val maxP = (meta.playerCountHint ?: spawns.size).coerceIn(2, 10)
            var candidateId = "$idPrefix:${meta.slug}"
            var suffix = 2
            while (!usedIds.add(candidateId)) {
                candidateId = "$idPrefix:${meta.slug}_${suffix++}"
            }
            out += MapPickerEntry(
                id = candidateId,
                slug = meta.slug,
                titleKeyOrRaw = meta.cleanTitle,
                isTranslationKey = false,
                tmxPath = normPath,
                mapDefinition = loaded,
                spawnPointsNormalized = spawns,
                thumbnailPath = resolveSiblingMapThumbnail(normPath),
                explicitMaxPlayers = maxP,
            )
        }
        return out
    }

    private fun ensureEntries(originalDirectory: Path?, userMapsDirectory: Path?): List<MapPickerEntry> {
        val key = CatalogCacheKey(
            originalDir = originalDirectory?.toAbsolutePath()?.normalize(),
            userMapsDir = userMapsDirectory?.toAbsolutePath()?.normalize(),
        )
        if (cachedKey == key) {
            cachedEntries?.let { return it }
        }
        synchronized(lock) {
            if (cachedKey == key) {
                cachedEntries?.let { return it }
            }
            val fallbackRoots = TmxSkirmishScenarioBuilder.tilesetFallbackRoots(key.originalDir)
            val discovered = ArrayList<MapPickerEntry>()

            resolveOriginalSkirmishDir(key.originalDir)?.let { skDir ->
                discovered += scanTmxDirectory(skDir, fallbackRoots, idPrefix = "orig")
            }
            key.userMapsDir?.let { uDir ->
                discovered += scanTmxDirectory(uDir, fallbackRoots, idPrefix = "user")
            }

            if (discovered.isEmpty()) {
                val tilesetDir = TilesetAtlasResolver.placeholderTilesetDir()
                val extractedRoot = tilesetDir.parent.parent
                val mapsDir = Files.createDirectories(extractedRoot.resolve("maps/skirmish"))
                for ((fileName, fallbackSpawns) in packagedFallbackFiles) {
                    val targetTmx = mapsDir.resolve(fileName)
                    if (!Files.isRegularFile(targetTmx)) {
                        val stream = OutGameMapCatalog::class.java.getResourceAsStream("/maps/skirmish/$fileName") ?: continue
                        stream.use { Files.write(targetTmx, it.readBytes()) }
                        targetTmx.toFile().deleteOnExit()
                    }
                    val loaded = when (val res = ContentLoader.loadMap(targetTmx, listOf(tilesetDir))) {
                        is ContentResult.Success -> res.value
                        is ContentResult.Failure -> error("Failed to load packaged skirmish map $fileName: ${res.error.describe()}")
                    }
                    val id = fileName.removeSuffix(".tmx")
                    val spawns = extractNormalizedSpawns(loaded, fallbackSpawns.size).ifEmpty { fallbackSpawns }
                    discovered += MapPickerEntry(
                        id = id,
                        slug = id,
                        titleKeyOrRaw = "map.$id",
                        isTranslationKey = true,
                        tmxPath = targetTmx.toAbsolutePath().normalize(),
                        mapDefinition = loaded,
                        spawnPointsNormalized = if (spawns.size >= fallbackSpawns.size) spawns else fallbackSpawns,
                        explicitMaxPlayers = max(spawns.size, fallbackSpawns.size),
                    )
                }
            }

            val sorted = discovered.sortedWith(
                compareBy<MapPickerEntry> { it.maxPlayers }
                    .thenBy { it.localizedTitle(UiLanguage.EN).lowercase(Locale.ROOT) }
                    .thenBy { it.id },
            )
            cachedKey = key
            cachedEntries = sorted
            return sorted
        }
    }
}

/**
 * Pure [UiTheme]-token map preview, native thumbnail adapter & HTML Demo `mapSVG` constructivist blueprint renderer.
 */
object OutGameMapPreviewRenderer {
    init {
        runCatching { GdxNativesLoader.load() }
    }

    private enum class CellTerrain { DEEP_WATER, SHALLOW_WATER, SAND, LOW_LAND, MID_LAND, HIGH_LAND, RIDGE }

    private fun classifyTile(map: MapDefinition, col: Int, row: Int): CellTerrain {
        val waterPassable = !map.waterBlocking.isBlocked(col, row)
        val landBlocked = map.blocking.isBlocked(col, row)
        if (waterPassable && landBlocked) {
            val nearShore = (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val nc = (col + dx).coerceIn(0, map.widthTiles - 1)
                    val nr = (row + dy).coerceIn(0, map.heightTiles - 1)
                    !map.blocking.isBlocked(nc, nr)
                }
            }
            return if (nearShore) CellTerrain.SHALLOW_WATER else CellTerrain.DEEP_WATER
        }
        if (map.hoverBlocking.isBlocked(col, row) && !map.overCliffWaterBlocking.isBlocked(col, row)) {
            return CellTerrain.RIDGE
        }
        if (landBlocked) {
            return CellTerrain.HIGH_LAND
        }
        val nearWater = (-1..1).any { dy ->
            (-1..1).any { dx ->
                val nc = (col + dx).coerceIn(0, map.widthTiles - 1)
                val nr = (row + dy).coerceIn(0, map.heightTiles - 1)
                !map.waterBlocking.isBlocked(nc, nr) && map.blocking.isBlocked(nc, nr)
            }
        }
        if (nearWater) return CellTerrain.SAND
        val subtle = ((col * 73 + row * 131) xor ((col / 3) * 19 + (row / 3) * 37)) and 7
        return when {
            subtle >= 6 -> CellTerrain.HIGH_LAND
            subtle >= 3 -> CellTerrain.MID_LAND
            else -> CellTerrain.LOW_LAND
        }
    }

    private fun fillBufRect(buf: IntArray, bufW: Int, bufH: Int, rx: Int, ry: Int, rw: Int, rh: Int, rgba: Int) {
        val x0 = rx.coerceAtLeast(0)
        val y0 = ry.coerceAtLeast(0)
        val x1 = (rx + rw).coerceAtMost(bufW)
        val y1 = (ry + rh).coerceAtMost(bufH)
        for (py in y0 until y1) {
            val rowOff = py * bufW
            for (px in x0 until x1) {
                buf[rowOff + px] = rgba
            }
        }
    }

    private fun blendOverRgba(dstRgba: Int, src: Color): Int {
        val a = src.a.coerceIn(0f, 1f)
        if (a <= 0.001f) return dstRgba
        if (a >= 0.999f) return Color.rgba8888(src.r, src.g, src.b, 1f)
        val dr = ((dstRgba ushr 24) and 0xFF) / 255f
        val dg = ((dstRgba ushr 16) and 0xFF) / 255f
        val db = ((dstRgba ushr 8) and 0xFF) / 255f
        val or = (dr * (1f - a) + src.r * a).coerceIn(0f, 1f)
        val og = (dg * (1f - a) + src.g * a).coerceIn(0f, 1f)
        val ob = (db * (1f - a) + src.b * a).coerceIn(0f, 1f)
        return Color.rgba8888(or, og, ob, 1f)
    }

    fun renderTacticalRgbaBuffer(
        map: MapDefinition,
        targetWidthPx: Int = 256,
        targetHeightPx: Int = 256,
        theme: UiTheme,
    ): IntArray {
        val wPx = targetWidthPx.coerceIn(64, 1024)
        val hPx = targetHeightPx.coerceIn(64, 1024)
        val buf = IntArray(wPx * hPx)

        val c = theme.colors
        val og = theme.outGame
        val darkAnchor = if (c.well.r < c.boneHi.r) c.well else c.boneHi
        val midAnchor = darkAnchor.cpy().lerp(c.line, 0.22f)
        val lightAnchor = if (c.well.r < c.boneHi.r) c.bone else c.well
        val pBlue = theme.palettes.playerColor(2)
        val pCyan = theme.palettes.playerColor(4)

        val deepCol = Color.rgba8888(darkAnchor.cpy().lerp(pBlue, 0.16f).apply { a = 1f })
        val shalCol = Color.rgba8888(darkAnchor.cpy().lerp(pCyan, 0.24f).apply { a = 1f })
        val sandCol = Color.rgba8888(midAnchor.cpy().lerp(c.amber, 0.18f).apply { a = 1f })
        val lowCol = Color.rgba8888(darkAnchor.cpy().lerp(og.ally, 0.18f).apply { a = 1f })
        val midCol = Color.rgba8888(midAnchor.cpy().lerp(og.ally, 0.18f).apply { a = 1f })
        val highCol = Color.rgba8888(midAnchor.cpy().lerp(lightAnchor, 0.20f).apply { a = 1f })
        val ridgeCol = Color.rgba8888(midAnchor.cpy().lerp(lightAnchor, 0.34f).apply { a = 1f })
        val contourCol = Color.rgba8888(midAnchor.cpy().lerp(lightAnchor, 0.26f).apply { a = 1f })
        val gridDark = Color.rgba8888(darkAnchor.cpy().lerp(midAnchor, 0.25f).apply { a = 1f })

        val mapW = map.widthTiles.coerceAtLeast(1)
        val mapH = map.heightTiles.coerceAtLeast(1)
        val classified = Array(mapH) { row -> IntArray(mapW) { col -> classifyTile(map, col, row).ordinal } }

        fun colorForOrdinal(ord: Int): Int = when (CellTerrain.entries[ord]) {
            CellTerrain.DEEP_WATER -> deepCol
            CellTerrain.SHALLOW_WATER -> shalCol
            CellTerrain.SAND -> sandCol
            CellTerrain.LOW_LAND -> lowCol
            CellTerrain.MID_LAND -> midCol
            CellTerrain.HIGH_LAND -> highCol
            CellTerrain.RIDGE -> ridgeCol
        }

        for (py in 0 until hPx) {
            val row = ((py.toLong() * mapH) / hPx).toInt().coerceIn(0, mapH - 1)
            val prevRow = (((py - 1).coerceAtLeast(0).toLong() * mapH) / hPx).toInt().coerceIn(0, mapH - 1)
            val isGridY = py % 16 == 0
            val rowOff = py * wPx
            for (px in 0 until wPx) {
                val col = ((px.toLong() * mapW) / wPx).toInt().coerceIn(0, mapW - 1)
                val prevCol = (((px - 1).coerceAtLeast(0).toLong() * mapW) / wPx).toInt().coerceIn(0, mapW - 1)
                val ord = classified[row][col]
                val isContour = classified[prevRow][col] != ord || classified[row][prevCol] != ord
                val isGridX = px % 16 == 0
                buf[rowOff + px] = when {
                    isContour -> contourCol
                    isGridX || isGridY -> if (ord == CellTerrain.DEEP_WATER.ordinal) gridDark else colorForOrdinal(ord)
                    else -> colorForOrdinal(ord)
                }
            }
        }

        val oreBorder = Color.rgba8888(darkAnchor.cpy().apply { a = 1f })
        val oreFill = Color.rgba8888(darkAnchor.cpy().lerp(lightAnchor, 0.52f).apply { a = 1f })
        val oreShine = Color.rgba8888(lightAnchor.cpy().apply { a = 1f })
        val markerHalf = (minOf(wPx, hPx) / 42).coerceIn(3, 8)

        for (row in 0 until mapH) {
            for (col in 0 until mapW) {
                if (!map.isResourcePool(col, row)) continue
                val cx = (((col + 0.5f) / mapW) * wPx).roundToInt()
                val cy = (((row + 0.5f) / mapH) * hPx).roundToInt()
                val sz = markerHalf * 2
                fillBufRect(buf, wPx, hPx, cx - markerHalf - 1, cy - markerHalf - 1, sz + 2, sz + 2, oreBorder)
                fillBufRect(buf, wPx, hPx, cx - markerHalf + 1, cy - markerHalf + 1, (sz - 2).coerceAtLeast(2), (sz - 2).coerceAtLeast(2), oreFill)
                val shineSz = (sz / 3).coerceAtLeast(2)
                fillBufRect(buf, wPx, hPx, cx - markerHalf + 2, cy - markerHalf + 2, shineSz, shineSz, oreShine)
            }
        }
        return buf
    }

    fun renderTacticalPixmap(
        map: MapDefinition,
        targetWidthPx: Int = 256,
        targetHeightPx: Int = 256,
        theme: UiTheme,
    ): Pixmap {
        val wPx = targetWidthPx.coerceIn(64, 1024)
        val hPx = targetHeightPx.coerceIn(64, 1024)
        val buf = renderTacticalRgbaBuffer(map, wPx, hPx, theme)
        val pm = Pixmap(wPx, hPx, Pixmap.Format.RGBA8888)
        pm.blending = Pixmap.Blending.None
        for (py in 0 until hPx) {
            val rowOff = py * wPx
            for (px in 0 until wPx) pm.drawPixel(px, py, buf[rowOff + px])
        }
        return pm
    }

    /**
     * Loads a real `*_map.png` thumbnail when available (aspect-fitted onto `theme.colors.well` so no corner bases
     * are cropped), or falls back to [renderTacticalPixmap].
     */
    fun loadOrRenderSquareCardPixmap(
        entry: MapPickerEntry,
        sizePx: Int,
        theme: UiTheme,
    ): Pixmap {
        val thumb = entry.thumbnailPath?.takeIf { Files.isRegularFile(it) }
        if (thumb != null) {
            val raw = runCatching { Pixmap(com.badlogic.gdx.files.FileHandle(thumb.toFile())) }.getOrNull()
            if (raw != null) {
                try {
                    val out = Pixmap(sizePx, sizePx, Pixmap.Format.RGBA8888)
                    out.blending = Pixmap.Blending.None
                    out.filter = Pixmap.Filter.BiLinear
                    out.setColor(theme.colors.well.r, theme.colors.well.g, theme.colors.well.b, 1f)
                    out.fill()
                    val scale = min(sizePx.toFloat() / raw.width.coerceAtLeast(1).toFloat(), sizePx.toFloat() / raw.height.coerceAtLeast(1).toFloat())
                    val dw = (raw.width * scale).roundToInt().coerceIn(1, sizePx)
                    val dh = (raw.height * scale).roundToInt().coerceIn(1, sizePx)
                    val dx = (sizePx - dw) / 2
                    val dy = (sizePx - dh) / 2
                    out.drawPixmap(raw, 0, 0, raw.width, raw.height, dx, dy, dw, dh)
                    return out
                } finally {
                    raw.dispose()
                }
            }
        }
        return renderTacticalPixmap(entry.mapDefinition, sizePx, sizePx, theme)
    }

    // Exact port of `八期界面演示稿.html` `hSvg` + `vnSvg` + `mapSVG` (`{cols:80, rows:45, t:20, seed:3.1, rw:2.2, sc:9, ore:26}`)
    private fun hSvg(x: Int, y: Int, s: Double): Double {
        val v = sin(x * 127.1 + y * 311.7 + s * 74.7) * 43758.5453
        return v - floor(v)
    }

    private fun vnSvg(x: Double, y: Double, s: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = xf * xf * (3.0 - 2.0 * xf)
        val v = yf * yf * (3.0 - 2.0 * yf)
        val a = hSvg(xi, yi, s)
        val b = hSvg(xi + 1, yi, s)
        val c = hSvg(xi, yi + 1, s)
        val d = hSvg(xi + 1, yi + 1, s)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    /**
     * Renders the exact `八期界面演示稿.html` `mapSVG` procedural constructivist blueprint tile (`80×45` cells of `20px`
     * = `1600×900` repeatable unit, or arbitrary `cols × rows` at `cellPx`), driven strictly by [UiTheme.outGame]
     * blueprint tokens (`backdropWater / backdropLow / backdropMid / backdropHigh / backdropOre / backdropGrid`).
     */
    fun renderDemoMapSvgBlueprintRgbaBuffer(
        theme: UiTheme,
        cols: Int = 80,
        rows: Int = 45,
        cellPx: Int = 20,
        seed: Double = 3.1,
        riverWidth: Double = 2.2,
        scale: Double = 9.0,
        oreCount: Int = 26,
    ): IntArray {
        val safeCols = cols.coerceIn(16, 200)
        val safeRows = rows.coerceIn(9, 120)
        val t = cellPx.coerceIn(4, 32)
        val wPx = safeCols * t
        val hPx = safeRows * t
        val buf = IntArray(wPx * hPx)

        val og = theme.outGame
        val waterRgba = Color.rgba8888(og.backdropWater.r, og.backdropWater.g, og.backdropWater.b, 1f)
        val lowRgba = Color.rgba8888(og.backdropLow.r, og.backdropLow.g, og.backdropLow.b, 1f)
        val midRgba = Color.rgba8888(og.backdropMid.r, og.backdropMid.g, og.backdropMid.b, 1f)
        val highRgba = Color.rgba8888(og.backdropHigh.r, og.backdropHigh.g, og.backdropHigh.b, 1f)
        val oreRgba = Color.rgba8888(og.backdropOre.r, og.backdropOre.g, og.backdropOre.b, 1f)
        val gridColor = og.backdropGrid

        // 0 = water, 1 = low, 2 = mid, 3 = high
        val grid = Array(safeRows) { y ->
            IntArray(safeCols) { x ->
                val n = vnSvg(x / scale, y / scale, seed) * 0.65 +
                    vnSvg(x / (scale * 0.4), y / (scale * 0.4), seed + 9.0) * 0.35
                // Periodic meandering river across 80x45 reference tiles so larger viewports stay naturally proportioned
                val localX = (x % 80).toDouble()
                val localY = (y % 45).toDouble()
                val riverCenterY = 45.0 * 0.5 + sin((localX / 80.0) * PI * 3.0 + seed) * (45.0 * 0.22)
                val d = abs(localY - riverCenterY)
                when {
                    d < riverWidth || n < 0.20 -> 0
                    n > 0.70 -> 3
                    n > 0.42 -> 2
                    else -> 1
                }
            }
        }

        for (gy in 0 until safeRows) {
            for (gx in 0 until safeCols) {
                val fill = when (grid[gy][gx]) {
                    0 -> waterRgba
                    3 -> highRgba
                    2 -> midRgba
                    else -> lowRgba
                }
                fillBufRect(buf, wPx, hPx, gx * t, gy * t, t, t, fill)
            }
        }

        // 2x2 quad ore clusters (`x*t + t*0.28`, `t*0.44`) tiled per 80x45 sector
        val dotOff = (t * 0.28f).roundToInt().coerceAtLeast(1)
        val dotSz = (t * 0.44f).roundToInt().coerceAtLeast(2)
        val sectorCols = ceil(safeCols / 80.0).toInt().coerceAtLeast(1)
        val sectorRows = ceil(safeRows / 45.0).toInt().coerceAtLeast(1)
        for (sy in 0 until sectorRows) {
            for (sx in 0 until sectorCols) {
                val secSeed = seed + sx * 5.3 + sy * 11.7
                for (k in 0 until oreCount) {
                    val cx = sx * 80 + floor(hSvg(k, 1, secSeed) * 80.0).toInt()
                    val cy = sy * 45 + floor(hSvg(k, 2, secSeed) * 45.0).toInt()
                    for (j in 0 until 4) {
                        val ox = cx + (j and 1)
                        val oy = cy + (j shr 1)
                        if (oy in 0 until safeRows && ox in 0 until safeCols && grid[oy][ox] != 0) {
                            fillBufRect(buf, wPx, hPx, ox * t + dotOff, oy * t + dotOff, dotSz, dotSz, oreRgba)
                        }
                    }
                }
            }
        }

        // Crisp 1px blueprint grid lines (`stroke-width="1"`, `shape-rendering="crispEdges"`)
        for (py in 0 until hPx) {
            val rowOff = py * wPx
            if (py % t == 0) {
                for (px in 0 until wPx) {
                    buf[rowOff + px] = blendOverRgba(buf[rowOff + px], gridColor)
                }
            } else {
                var px = 0
                while (px < wPx) {
                    buf[rowOff + px] = blendOverRgba(buf[rowOff + px], gridColor)
                    px += t
                }
            }
        }
        return buf
    }

    /** Retains compatible overload signature for tests while delegating to HTML Demo `mapSVG` blueprint generator. */
    fun renderBackdropBlueprintRgbaBuffer(
        @Suppress("UNUSED_PARAMETER") map: MapDefinition? = null,
        theme: UiTheme,
        widthPx: Int = 1600,
        heightPx: Int = 900,
    ): IntArray {
        val cellPx = 20
        val cols = ((widthPx.coerceIn(160, 3200) + cellPx - 1) / cellPx).coerceAtLeast(8)
        val rows = ((heightPx.coerceIn(90, 2000) + cellPx - 1) / cellPx).coerceAtLeast(5)
        return renderDemoMapSvgBlueprintRgbaBuffer(theme = theme, cols = cols, rows = rows, cellPx = cellPx)
    }

    fun renderBackdropBlueprintPixmap(
        theme: UiTheme,
        cols: Int = 96,
        rows: Int = 56,
        cellPx: Int = 20,
    ): Pixmap {
        val safeCols = cols.coerceIn(16, 200)
        val safeRows = rows.coerceIn(9, 120)
        val t = cellPx.coerceIn(4, 32)
        val wPx = safeCols * t
        val hPx = safeRows * t
        val buf = renderDemoMapSvgBlueprintRgbaBuffer(theme = theme, cols = safeCols, rows = safeRows, cellPx = t)
        val pm = Pixmap(wPx, hPx, Pixmap.Format.RGBA8888)
        pm.blending = Pixmap.Blending.None
        for (py in 0 until hPx) {
            val rowOff = py * wPx
            for (px in 0 until wPx) pm.drawPixel(px, py, buf[rowOff + px])
        }
        return pm
    }
}

/**
 * Shared out-game slowly panning constructivist blueprint backdrop (`八期界面演示稿.html` `mapSVG` & UI Supplement §2.2):
 * - Renders 1:1 unstretched `20px` crisp blueprint grid + meandering river + `2x2` quad ore markers via `TextureFilter.Nearest`;
 * - Applies `theme.outGame.mapScrim` on `MAIN_MENU` / `LOADING` and `theme.outGame.mapScrimDeep` on secondary pages;
 * - Slowly pans over a 200-second alternate cycle with **zero simulation running**.
 */
class DynamicMapBackdrop(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    @Suppress("UNUSED_PARAMETER") private val activeMapProvider: () -> Pair<String, MapDefinition>? = { null },
    private val deepScrimProvider: () -> Boolean,
) : Actor(), AutoCloseable {

    private data class TextureCacheKey(
        val themeId: String,
        val cols: Int,
        val rows: Int,
        val waterBits: Int,
        val lowBits: Int,
        val midBits: Int,
        val highBits: Int,
        val oreBits: Int,
        val gridBits: Int,
    )

    private var cachedKey: TextureCacheKey? = null
    private var backdropTexture: Texture? = null
    private var backdropRegion: TextureRegion? = null
    private var elapsedSeconds: Float = 0f

    val panProgress: Float
        get() {
            val cycle = (elapsedSeconds / PAN_HALF_PERIOD_SECONDS) % 2f
            return if (cycle <= 1f) cycle else 2f - cycle
        }

    init {
        touchable = Touchable.disabled
    }

    override fun act(delta: Float) {
        if (!isVisible) return
        super.act(delta)
        if (delta > 0f && delta.isFinite()) {
            elapsedSeconds = (elapsedSeconds + delta) % (PAN_HALF_PERIOD_SECONDS * 2f)
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val stageW = stage?.viewport?.worldWidth ?: width
        val stageH = stage?.viewport?.worldHeight ?: height
        if (stageW <= 0f || stageH <= 0f) return

        val theme = themeProvider.currentTheme()
        val og = theme.outGame
        val cellPx = 20
        val panSpanX = 160f
        val panSpanY = 110f
        val neededCols = ceil((stageW + panSpanX + 40f) / cellPx.toFloat()).toInt().coerceIn(80, 160)
        val neededRows = ceil((stageH + panSpanY + 40f) / cellPx.toFloat()).toInt().coerceIn(45, 96)

        val key = TextureCacheKey(
            themeId = theme.id,
            cols = neededCols,
            rows = neededRows,
            waterBits = Color.rgba8888(og.backdropWater),
            lowBits = Color.rgba8888(og.backdropLow),
            midBits = Color.rgba8888(og.backdropMid),
            highBits = Color.rgba8888(og.backdropHigh),
            oreBits = Color.rgba8888(og.backdropOre),
            gridBits = Color.rgba8888(og.backdropGrid),
        )
        if (cachedKey != key || backdropTexture == null) {
            backdropTexture?.dispose()
            val pm = OutGameMapPreviewRenderer.renderBackdropBlueprintPixmap(
                theme = theme,
                cols = neededCols,
                rows = neededRows,
                cellPx = cellPx,
            )
            val tex = try {
                Texture(pm).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
            } finally {
                pm.dispose()
            }
            backdropTexture = tex
            backdropRegion = TextureRegion(tex)
            cachedKey = key
        }

        val savedColor = batch.packedColor
        val white = skin.getRegion("white")
        OutGameDraw.fillRect(batch, white, 0f, 0f, stageW, stageH, og.backdropWater, parentAlpha)

        backdropRegion?.let { region ->
            val texW = region.regionWidth.toFloat()
            val texH = region.regionHeight.toFloat()
            val phase = panProgress
            // Snap to integer logical coordinates so 1px blueprint grid lines remain 100% razor-sharp!
            val drawX = (-panSpanX * phase).roundToInt().toFloat()
            val drawY = (-panSpanY * phase).roundToInt().toFloat()
            batch.setColor(1f, 1f, 1f, parentAlpha)
            batch.draw(region, drawX, drawY, texW, texH)
        }

        val scrim = if (deepScrimProvider()) og.mapScrimDeep else og.mapScrim
        OutGameDraw.fillRect(batch, white, 0f, 0f, stageW, stageH, scrim, parentAlpha)
        batch.packedColor = savedColor
    }

    override fun close() {
        backdropTexture?.dispose()
        backdropTexture = null
        backdropRegion = null
        cachedKey = null
    }

    companion object {
        const val PAN_HALF_PERIOD_SECONDS: Float = 200f
    }
}
