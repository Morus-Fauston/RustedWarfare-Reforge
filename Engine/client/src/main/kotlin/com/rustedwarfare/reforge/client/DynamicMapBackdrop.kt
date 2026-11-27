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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
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
 * Immutable map entry consumed by [MapPickerScreen], Skirmish Lobby, Free Sandbox, and Match Loading card.
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
) {
    val maxPlayers: Int
        get() = spawnPointsNormalized.size.coerceIn(2, 10)

    fun localizedTitle(language: UiLanguage = UiStrings.currentLanguage): String =
        if (isTranslationKey) UiStrings.get(titleKeyOrRaw, language) else titleKeyOrRaw
}

/**
 * Self-owned built-in skirmish map catalog & deterministic Tiled `.tmx` generator for [MapPickerScreen]
 * and [DynamicMapBackdrop].
 *
 * Includes the 2 packaged `.tmx` maps (`river_crossing`, `volcanic_archipelago`) plus 6 procedural
 * standard `.tmx` skirmish maps (`4P..10P`, including 10-seat `ring_atoll`) so 1–10 player skirmish
 * and free-sandbox flows work out-of-the-box without touching `TilesetAtlasResolver.builtInSkirmishMaps()`.
 */
object OutGameMapCatalog {
    private val lock = Any()

    @Volatile
    private var cachedEntries: List<MapPickerEntry>? = null

    private enum class SynthKind { RIVER, ARCH, RING, OASIS }

    private data class SynthSpec(
        val id: String,
        val titleKey: String,
        val widthTiles: Int,
        val heightTiles: Int,
        val seed: Int,
        val kind: SynthKind,
        val spawns: List<Pair<Float, Float>>,
        val ores: List<Pair<Float, Float>>,
    )

    private val packagedSpawns: Map<String, List<Pair<Float, Float>>> = mapOf(
        "river_crossing" to listOf(0.19f to 0.24f, 0.81f to 0.76f, 0.81f to 0.24f, 0.19f to 0.76f),
        "volcanic_archipelago" to listOf(0.18f to 0.26f, 0.82f to 0.74f, 0.82f to 0.26f, 0.18f to 0.74f),
    )

    private val extraSpecs: List<SynthSpec> = listOf(
        SynthSpec(
            id = "ring_atoll",
            titleKey = "map.ring_atoll",
            widthTiles = 64,
            heightTiles = 64,
            seed = 89,
            kind = SynthKind.RING,
            spawns = listOf(
                0.16f to 0.18f, 0.12f to 0.40f, 0.12f to 0.62f, 0.18f to 0.84f, 0.38f to 0.86f,
                0.84f to 0.18f, 0.88f to 0.40f, 0.88f to 0.62f, 0.82f to 0.84f, 0.62f to 0.14f,
            ),
            ores = listOf(
                0.50f to 0.50f, 0.42f to 0.44f, 0.58f to 0.56f,
                0.30f to 0.30f, 0.70f to 0.70f, 0.70f to 0.30f, 0.30f to 0.70f,
            ),
        ),
        SynthSpec(
            id = "twin_bridge_canyon",
            titleKey = "map.twin_bridge_canyon",
            widthTiles = 52,
            heightTiles = 44,
            seed = 29,
            kind = SynthKind.RIVER,
            spawns = listOf(0.16f to 0.20f, 0.84f to 0.80f, 0.84f to 0.20f, 0.16f to 0.80f),
            ores = listOf(0.26f to 0.48f, 0.74f to 0.52f, 0.46f to 0.22f, 0.54f to 0.78f),
        ),
        SynthSpec(
            id = "desert_oasis",
            titleKey = "map.desert_oasis",
            widthTiles = 56,
            heightTiles = 52,
            seed = 63,
            kind = SynthKind.OASIS,
            spawns = listOf(
                0.20f to 0.20f, 0.80f to 0.80f, 0.80f to 0.20f,
                0.20f to 0.80f, 0.50f to 0.14f, 0.50f to 0.86f,
            ),
            ores = listOf(0.46f to 0.46f, 0.54f to 0.54f, 0.34f to 0.50f, 0.66f to 0.50f),
        ),
        SynthSpec(
            id = "polar_outpost",
            titleKey = "map.polar_outpost",
            widthTiles = 48,
            heightTiles = 44,
            seed = 105,
            kind = SynthKind.ARCH,
            spawns = listOf(0.22f to 0.24f, 0.78f to 0.76f, 0.78f to 0.24f, 0.22f to 0.76f),
            ores = listOf(0.50f to 0.36f, 0.50f to 0.64f, 0.30f to 0.50f, 0.70f to 0.50f),
        ),
        SynthSpec(
            id = "shattered_highland",
            titleKey = "map.shattered_highland",
            widthTiles = 56,
            heightTiles = 48,
            seed = 137,
            kind = SynthKind.RIVER,
            spawns = listOf(
                0.15f to 0.30f, 0.85f to 0.70f, 0.85f to 0.30f,
                0.15f to 0.70f, 0.48f to 0.16f, 0.52f to 0.84f,
            ),
            ores = listOf(0.36f to 0.36f, 0.64f to 0.64f, 0.64f to 0.36f, 0.36f to 0.64f, 0.50f to 0.50f),
        ),
        SynthSpec(
            id = "rusted_bay",
            titleKey = "map.rusted_bay",
            widthTiles = 60,
            heightTiles = 56,
            seed = 173,
            kind = SynthKind.RING,
            spawns = listOf(
                0.18f to 0.22f, 0.18f to 0.78f, 0.82f to 0.22f, 0.82f to 0.78f,
                0.50f to 0.16f, 0.50f to 0.84f, 0.14f to 0.50f, 0.86f to 0.50f,
            ),
            ores = listOf(0.44f to 0.44f, 0.56f to 0.56f, 0.44f to 0.56f, 0.56f to 0.44f),
        ),
    )

    fun entries(filter: MapPickerFilter = MapPickerFilter.SKIRMISH): List<MapPickerEntry> {
        val all = ensureEntries()
        return when (filter) {
            MapPickerFilter.SKIRMISH, MapPickerFilter.FREE_SANDBOX, MapPickerFilter.ALL -> all
        }
    }

    fun defaultEntry(): MapPickerEntry = entries().first()

    fun findByIdOrPath(idOrPath: String?): MapPickerEntry? {
        if (idOrPath.isNullOrBlank()) return null
        val norm = idOrPath.trim()
        val list = ensureEntries()
        list.firstOrNull { it.id.equals(norm, ignoreCase = true) || it.slug.equals(norm, ignoreCase = true) }
            ?.let { return it }
        val asPath = runCatching { Path.of(norm).toAbsolutePath().normalize() }.getOrNull()
        if (asPath != null) {
            list.firstOrNull { it.tmxPath?.toAbsolutePath()?.normalize() == asPath }?.let { return it }
        }
        return null
    }

    fun entryFromCustomTmx(path: Path, displayName: String, map: MapDefinition): MapPickerEntry {
        val slug = path.fileName.toString().removeSuffix(".tmx")
        val derivedSpawns = extractNormalizedSpawns(map)
        return MapPickerEntry(
            id = "custom:$slug:${path.toAbsolutePath().normalize()}",
            slug = slug,
            titleKeyOrRaw = displayName,
            isTranslationKey = false,
            tmxPath = path.toAbsolutePath().normalize(),
            mapDefinition = map,
            spawnPointsNormalized = derivedSpawns,
        )
    }

    fun extractNormalizedSpawns(map: MapDefinition): List<Pair<Float, Float>> {
        val worldW = (map.widthTiles * map.tileWidth).coerceAtLeast(1).toFloat()
        val worldH = (map.heightTiles * map.tileHeight).coerceAtLeast(1).toFloat()
        val byTeam = LinkedHashMap<Int, Pair<Float, Float>>()
        for (u in map.startingUnits) {
            val teamIdx = u.team?.trim()?.toIntOrNull() ?: continue
            if (teamIdx < 0 || teamIdx in byTeam) continue
            val nx = (u.worldX.toFloat() / worldW).coerceIn(0.08f, 0.92f)
            val ny = (u.worldY.toFloat() / worldH).coerceIn(0.08f, 0.92f)
            byTeam[teamIdx] = nx to ny
        }
        if (byTeam.size >= 2) {
            return byTeam.entries.sortedBy { it.key }.map { it.value }.take(10)
        }
        return listOf(0.20f to 0.24f, 0.80f to 0.76f, 0.80f to 0.24f, 0.20f to 0.76f)
    }

    private fun ensureEntries(): List<MapPickerEntry> {
        cachedEntries?.let { return it }
        synchronized(lock) {
            cachedEntries?.let { return it }
            val tilesetDir = TilesetAtlasResolver.placeholderTilesetDir()
            val extractedRoot = tilesetDir.parent.parent
            val mapsDir = Files.createDirectories(extractedRoot.resolve("maps/skirmish"))
            val fallbackRoots = listOf(tilesetDir)
            val built = mutableListOf<MapPickerEntry>()

            for (demo in TilesetAtlasResolver.builtInSkirmishMaps()) {
                val loaded = when (val res = ContentLoader.loadMap(demo.path, fallbackRoots)) {
                    is ContentResult.Success -> res.value
                    is ContentResult.Failure -> error("Failed to load built-in skirmish map ${demo.id}: ${res.error.describe()}")
                }
                val key = "map.${demo.id}"
                val spawns = packagedSpawns[demo.id] ?: extractNormalizedSpawns(loaded)
                built += MapPickerEntry(
                    id = demo.id,
                    slug = demo.id,
                    titleKeyOrRaw = key,
                    isTranslationKey = true,
                    tmxPath = demo.path.toAbsolutePath().normalize(),
                    mapDefinition = loaded,
                    spawnPointsNormalized = spawns,
                )
            }

            for (spec in extraSpecs) {
                val targetTmx = mapsDir.resolve("${spec.id}.tmx")
                if (!Files.isRegularFile(targetTmx)) {
                    Files.writeString(targetTmx, generateTmxXml(spec))
                    targetTmx.toFile().deleteOnExit()
                }
                val loaded = when (val res = ContentLoader.loadMap(targetTmx, fallbackRoots)) {
                    is ContentResult.Success -> res.value
                    is ContentResult.Failure -> error("Failed to load generated skirmish map ${spec.id}: ${res.error.describe()}")
                }
                built += MapPickerEntry(
                    id = spec.id,
                    slug = spec.id,
                    titleKeyOrRaw = spec.titleKey,
                    isTranslationKey = true,
                    tmxPath = targetTmx.toAbsolutePath().normalize(),
                    mapDefinition = loaded,
                    spawnPointsNormalized = spec.spawns,
                )
            }

            cachedEntries = built
            return built
        }
    }

    private fun hash2(x: Int, y: Int, s: Int): Float {
        var n = x * 374761393 + y * 668265263 + s * 1442695041
        n = (n xor (n ushr 13)) * 1274126177
        val unsigned = (n xor (n ushr 16)).toLong() and 0xFFFFFFFFL
        return (unsigned.toDouble() / 4294967296.0).toFloat()
    }

    private fun valueNoise(x: Float, y: Float, s: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = xf * xf * (3f - 2f * xf)
        val v = yf * yf * (3f - 2f * yf)
        return hash2(xi, yi, s) * (1f - u) * (1f - v) +
            hash2(xi + 1, yi, s) * u * (1f - v) +
            hash2(xi, yi + 1, s) * (1f - u) * v +
            hash2(xi + 1, yi + 1, s) * u * v
    }

    private fun fbm(x: Float, y: Float, s: Int): Float =
        valueNoise(x, y, s) * 0.58f +
            valueNoise(x * 2.1f + 5f, y * 2.1f + 3f, s + 11) * 0.28f +
            valueNoise(x * 4.3f, y * 4.3f, s + 29) * 0.14f

    private fun encodeLayerGzipBase64(gids: IntArray): String {
        val byteBuf = ByteBuffer.allocate(gids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (gid in gids) byteBuf.putInt(gid)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip -> gzip.write(byteBuf.array()) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    private fun generateTmxXml(spec: SynthSpec): String {
        val w = spec.widthTiles
        val h = spec.heightTiles
        val ground = IntArray(w * h) { 1 }
        val details = IntArray(w * h)
        val items = IntArray(w * h)
        val units = IntArray(w * h)

        for (r in 0 until h) {
            for (c in 0 until w) {
                val u = (c + 0.5f) / w.toFloat()
                val v = (r + 0.5f) / h.toFloat()
                val n = fbm(u * 6.5f, v * 4.2f, spec.seed)
                val gid = when (spec.kind) {
                    SynthKind.RIVER -> {
                        val rc = 0.50f + 0.055f * sin((v * PI * 2.1 + 0.4).toFloat()) + (fbm(1.7f, v * 3f, spec.seed + 5) - 0.5f) * 0.08f
                        val d = abs(u - rc)
                        val ford = abs(v - 0.28f) < 0.055f || abs(v - 0.72f) < 0.055f
                        when {
                            d < 0.032f && !ford -> 6 // deep water
                            d < 0.052f -> if (ford) 12 else 5 // bridge or shallow water
                            d < 0.072f -> 3 // sand
                            n > 0.73f -> 15 // cliff ridge
                            n > 0.60f -> 2 // high ground
                            else -> 1
                        }
                    }
                    SynthKind.ARCH -> {
                        var isl = 0f
                        for ((px, py) in (spec.spawns + (0.5f to 0.5f))) {
                            isl = max(isl, 1f - hypot((u - px) * 1.25f, v - py) * 3.1f)
                        }
                        val e = n * 0.52f + isl * 0.68f
                        when {
                            e < 0.40f -> 6
                            e < 0.48f -> 5
                            e < 0.54f -> 3
                            e > 0.80f -> 15
                            else -> 1
                        }
                    }
                    SynthKind.OASIS -> {
                        val d = hypot(u - 0.5f, v - 0.5f)
                        when {
                            d < 0.10f -> 6
                            d < 0.15f -> 5
                            d < 0.24f -> 1
                            n > 0.72f -> 15
                            else -> 3
                        }
                    }
                    SynthKind.RING -> {
                        val d = hypot((u - 0.5f) * 1.15f, v - 0.5f)
                        when {
                            d > 0.48f || (d < 0.15f && d > 0.05f) -> 6
                            d > 0.44f || d < 0.19f -> 5
                            n > 0.72f -> 15
                            else -> 1
                        }
                    }
                }
                ground[r * w + c] = gid
            }
        }

        // Carve guaranteed walkable flat pads around every spawn point and resource pool
        fun clearPad(normX: Float, normY: Float, radius: Int) {
            val cx = (normX * w).roundToInt().coerceIn(radius + 1, w - radius - 2)
            val cy = (normY * h).roundToInt().coerceIn(radius + 1, h - radius - 2)
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    ground[(cy + dy) * w + (cx + dx)] = 1
                }
            }
        }

        for ((sx, sy) in spec.spawns) clearPad(sx, sy, radius = 3)
        for ((ox, oy) in spec.ores) {
            clearPad(ox, oy, radius = 2)
            val cx = (ox * w).roundToInt().coerceIn(2, w - 3)
            val cy = (oy * h).roundToInt().coerceIn(2, h - 3)
            items[cy * w + cx] = 19 // res_pool
        }

        // Place default p1 and p2 starting Command Center + Builder markers
        if (spec.spawns.isNotEmpty()) {
            val (s0x, s0y) = spec.spawns[0]
            val c0 = (s0x * w).roundToInt().coerceIn(3, w - 4)
            val r0 = (s0y * h).roundToInt().coerceIn(3, h - 4)
            units[r0 * w + c0] = 31
            units[r0 * w + (c0 + 2).coerceAtMost(w - 2)] = 32
        }
        if (spec.spawns.size >= 2) {
            val (s1x, s1y) = spec.spawns[1]
            val c1 = (s1x * w).roundToInt().coerceIn(3, w - 4)
            val r1 = (s1y * h).roundToInt().coerceIn(3, h - 4)
            units[r1 * w + c1] = 33
            units[r1 * w + (c1 - 2).coerceAtLeast(1)] = 34
        }

        val gData = encodeLayerGzipBase64(ground)
        val dData = encodeLayerGzipBase64(details)
        val iData = encodeLayerGzipBase64(items)
        val uData = encodeLayerGzipBase64(units)

        return """<?xml version='1.0' encoding='UTF-8'?>
<map version="1.2" tiledversion="1.4.3" orientation="orthogonal" renderorder="right-down" width="$w" height="$h" tilewidth="20" tileheight="20" infinite="0">
 <tileset firstgid="1" source="../../visuals/placeholder/tilesets/base_terrain.tsx" />
 <tileset firstgid="31" source="../../visuals/placeholder/tilesets/units_markers.tsx" />
 <layer name="Ground" width="$w" height="$h">
  <data encoding="base64" compression="gzip">$gData</data>
 </layer>
 <layer name="GroundDetails" width="$w" height="$h">
  <data encoding="base64" compression="gzip">$dData</data>
 </layer>
 <layer name="Items" width="$w" height="$h">
  <data encoding="base64" compression="gzip">$iData</data>
 </layer>
 <layer name="Units" width="$w" height="$h">
  <data encoding="base64" compression="gzip">$uData</data>
 </layer>
 <objectgroup name="Triggers">
  <object id="1" name="map_info" x="20" y="20" width="100" height="60">
   <properties>
    <property name="type" value="skirmish" />
    <property name="fog" value="los" />
   </properties>
  </object>
 </objectgroup>
</map>"""
    }
}

/**
 * Pure [UiTheme]-token map preview & blueprint backdrop renderer (zero hardcoded color literals).
 *
 * Shared by:
 * - [DynamicMapBackdrop] (theme-harmonized blueprint terrain under `mapScrim` / `mapScrimDeep`)
 * - [MapPickerScreen] `1:1` square map cards
 * - Skirmish Lobby, Free Sandbox, and Match Loading `1:1` map previews
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
            // Distinguish coastal shallow water from deeper interior water
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

    /**
     * Pure Kotlin tactical map preview `RGBA8888` buffer (zero JNI dependency, zero static `UiTheme.DEFAULT` coupling).
     */
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

    /**
     * Renders a crisp tactical preview [Pixmap] (water, shore, elevation contours, subtle grid, and ore pools)
     * using colors derived strictly from [UiTheme] semantic tokens.
     */
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
     * Pure Kotlin constructivist blueprint map `RGBA8888` buffer (`80x45` cells at `20px` = `1600x900` crisp 1px grid
     * with `2x2` quad ore markers, matching `Docs/规格/八期界面演示稿` `mapSVG` fidelity with zero JNI dependency).
     */
    fun renderBackdropBlueprintRgbaBuffer(
        map: MapDefinition,
        theme: UiTheme,
        widthPx: Int = 1600,
        heightPx: Int = 900,
    ): IntArray {
        val wPx = widthPx.coerceIn(160, 1920)
        val hPx = heightPx.coerceIn(90, 1080)
        val buf = IntArray(wPx * hPx)

        val c = theme.colors
        val isDark = c.solid.r < 0.35f
        val waterCol = if (isDark) c.well.cpy().lerp(c.ink, 0.35f) else c.well.cpy().lerp(c.line, 0.28f)
        val lowCol = if (isDark) c.solid.cpy().lerp(c.cell, 0.45f) else c.cell.cpy()
        val midCol = if (isDark) c.cell.cpy().lerp(c.line, 0.38f) else c.well.cpy().lerp(c.cell, 0.45f)
        val highCol = if (isDark) c.line.cpy().lerp(c.dim, 0.22f) else c.well.cpy().lerp(c.line, 0.48f)
        val oreCol = if (isDark) c.bone.cpy().lerp(c.dim, 0.25f) else c.line.cpy().lerp(c.dim, 0.35f)

        val water = Color.rgba8888(waterCol.apply { a = 1f })
        val low = Color.rgba8888(lowCol.apply { a = 1f })
        val mid = Color.rgba8888(midCol.apply { a = 1f })
        val high = Color.rgba8888(highCol.apply { a = 1f })
        val ore = Color.rgba8888(oreCol.apply { a = 1f })

        val cellPx = (wPx / 80).coerceIn(4, 24)
        val gridCols = (wPx + cellPx - 1) / cellPx
        val gridRows = (hPx + cellPx - 1) / cellPx
        val mapW = map.widthTiles.coerceAtLeast(1)
        val mapH = map.heightTiles.coerceAtLeast(1)

        val cellColors = Array(gridRows) { gr ->
            val mRow = ((gr.toLong() * mapH) / gridRows).toInt().coerceIn(0, mapH - 1)
            IntArray(gridCols) { gc ->
                val mCol = ((gc.toLong() * mapW) / gridCols).toInt().coerceIn(0, mapW - 1)
                when (classifyTile(map, mCol, mRow)) {
                    CellTerrain.DEEP_WATER, CellTerrain.SHALLOW_WATER -> water
                    CellTerrain.SAND, CellTerrain.LOW_LAND -> low
                    CellTerrain.MID_LAND -> mid
                    CellTerrain.HIGH_LAND, CellTerrain.RIDGE -> high
                }
            }
        }

        for (py in 0 until hPx) {
            val gr = (py / cellPx).coerceAtMost(gridRows - 1)
            val isGridY = py % cellPx == 0
            val rowOff = py * wPx
            for (px in 0 until wPx) {
                val gc = (px / cellPx).coerceAtMost(gridCols - 1)
                val baseRgba = cellColors[gr][gc]
                if (isGridY || px % cellPx == 0) {
                    // Subtle 1px constructivist grid line blended over cell color
                    val tmp = Color()
                    Color.rgba8888ToColor(tmp, baseRgba)
                    tmp.lerp(if (isDark) c.ink else c.line, if (isDark) 0.36f else 0.16f)
                    tmp.a = 1f
                    buf[rowOff + px] = Color.rgba8888(tmp)
                } else {
                    buf[rowOff + px] = baseRgba
                }
            }
        }

        // Draw crisp 2x2 quad ore cluster icons matching the mockup blueprint style
        val subDot = (cellPx * 0.38f).roundToInt().coerceIn(2, 8)
        val subPad = ((cellPx - subDot) / 2).coerceAtLeast(1)
        for (row in 0 until mapH) {
            for (col in 0 until mapW) {
                if (!map.isResourcePool(col, row)) continue
                val baseGc = ((col.toLong() * gridCols) / mapW).toInt().coerceIn(0, (gridCols - 2).coerceAtLeast(0))
                val baseGr = ((row.toLong() * gridRows) / mapH).toInt().coerceIn(0, (gridRows - 2).coerceAtLeast(0))
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = (baseGc + dx) * cellPx + subPad
                        val py = (baseGr + dy) * cellPx + subPad
                        fillBufRect(buf, wPx, hPx, px, py, subDot, subDot, ore)
                    }
                }
            }
        }
        return buf
    }

    /**
     * Renders a theme-harmonized constructivist blueprint map [Pixmap] for [DynamicMapBackdrop].
     */
    fun renderBackdropBlueprintPixmap(
        map: MapDefinition,
        theme: UiTheme,
        widthPx: Int = 1600,
        heightPx: Int = 900,
    ): Pixmap {
        val wPx = widthPx.coerceIn(160, 1920)
        val hPx = heightPx.coerceIn(90, 1080)
        val buf = renderBackdropBlueprintRgbaBuffer(map, theme, wPx, hPx)
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
 * Shared out-game slowly panning darkened map backdrop (UI Supplement §2):
 * - Renders the active selected skirmish map (or built-in default `river_crossing` when none selected);
 * - Applies `theme.outGame.mapScrim` on `MAIN_MENU` / `LOADING` and `theme.outGame.mapScrimDeep` on secondary pages;
 * - Slowly pans over a 200-second alternate cycle with **zero simulation running**.
 */
class DynamicMapBackdrop(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val activeMapProvider: () -> Pair<String, MapDefinition>?,
    private val deepScrimProvider: () -> Boolean,
) : Actor(), AutoCloseable {

    private data class TextureCacheKey(
        val mapKey: String,
        val themeId: String,
        val wellBits: Int,
        val cellBits: Int,
        val lineBits: Int,
    )

    private var cachedKey: TextureCacheKey? = null
    private var backdropTexture: Texture? = null
    private var backdropRegion: TextureRegion? = null
    private var elapsedSeconds: Float = 0f

    /** Exposes current normalized `[0..1]` pan phase for deterministic headless assertions. */
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
        val (mapKey, mapDef) = activeMapProvider() ?: run {
            val fallback = OutGameMapCatalog.defaultEntry()
            fallback.id to fallback.mapDefinition
        }

        val key = TextureCacheKey(
            mapKey = mapKey,
            themeId = theme.id,
            wellBits = Color.rgba8888(theme.colors.well),
            cellBits = Color.rgba8888(theme.colors.cell),
            lineBits = Color.rgba8888(theme.colors.line),
        )
        if (cachedKey != key || backdropTexture == null) {
            backdropTexture?.dispose()
            val pm = OutGameMapPreviewRenderer.renderBackdropBlueprintPixmap(mapDef, theme)
            val tex = try {
                Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
            } finally {
                pm.dispose()
            }
            backdropTexture = tex
            backdropRegion = TextureRegion(tex)
            cachedKey = key
        }

        val savedColor = batch.packedColor
        val white = skin.getRegion("white")
        OutGameDraw.fillRect(batch, white, 0f, 0f, stageW, stageH, theme.colors.well, parentAlpha)

        backdropRegion?.let { region ->
            val overscanW = max(stageW * 1.25f, stageW + 240f)
            val overscanH = max(stageH * 1.25f, stageH + 140f)
            val phase = panProgress
            val drawX = -(overscanW - stageW) * phase
            val drawY = -(overscanH - stageH) * phase
            batch.setColor(1f, 1f, 1f, parentAlpha)
            batch.draw(region, drawX, drawY, overscanW, overscanH)
        }

        val scrim = if (deepScrimProvider()) theme.outGame.mapScrimDeep else theme.outGame.mapScrim
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
