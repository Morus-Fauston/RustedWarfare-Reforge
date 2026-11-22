package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * ADR-013 four-tier tileset image resolver & active-GID scanner.
 *
 * Resolution order:
 * 1. Enabled resource pack override (reforge:tileset/<normalized> or legacy reforge:terrain/<layout>)
 * 2. Direct map/TSX relative imagePath (verified readable file)
 * 3. Configured local original assets tilesets/ directory fallback (private local only)
 * 4. Packaged self-owned public placeholder biome tileset fallback
 */
object TilesetAtlasResolver {
    private val extractedPlaceholdersLock = Any()
    @Volatile
    private var cachedPlaceholderDir: Path? = null

    private val placeholderFiles = listOf(
        "base_terrain.png",
        "base_terrain.tsx",
        "units_markers.png",
        "units_markers.tsx",
        "grass.png",
        "dirt.png",
        "sand.png",
        "shallow_water.png",
        "deep_water.png",
        "mountain.png",
        "snow.png",
        "lava.png",
        "ridge.png",
        "bridge.png",
        "misc.png",
    )

    private val builtInDemoMaps = listOf(
        "river_crossing.tmx" to "River Crossing (2p · Built-in)",
        "volcanic_archipelago.tmx" to "Volcanic Archipelago (2p · Built-in)",
    )

    @Volatile
    private var cachedBuiltInMaps: List<BuiltInDemoMap>? = null

    data class BuiltInDemoMap(val id: String, val displayName: String, val path: Path)

    fun builtInSkirmishMaps(): List<BuiltInDemoMap> {
        cachedBuiltInMaps?.let { return it }
        synchronized(extractedPlaceholdersLock) {
            cachedBuiltInMaps?.let { return it }
            val root = extractPackagedRoot()
            val mapsDir = root.resolve("maps/skirmish")
            val result = builtInDemoMaps.map { (file, label) ->
                BuiltInDemoMap(file.removeSuffix(".tmx"), label, mapsDir.resolve(file))
            }
            cachedBuiltInMaps = result
            return result
        }
    }

    fun placeholderTilesetDir(): Path {
        cachedPlaceholderDir?.let { return it }
        synchronized(extractedPlaceholdersLock) {
            cachedPlaceholderDir?.let { return it }
            val root = extractPackagedRoot()
            val dir = root.resolve("visuals/placeholder/tilesets")
            cachedPlaceholderDir = dir
            return dir
        }
    }

    private fun extractPackagedRoot(): Path {
        val root = Files.createTempDirectory("reforge-maps-tilesets-").toAbsolutePath().normalize()
        root.toFile().deleteOnExit()
        val tilesetsDir = Files.createDirectories(root.resolve("visuals/placeholder/tilesets"))
        for (name in placeholderFiles) {
            val stream = TilesetAtlasResolver::class.java.getResourceAsStream("/visuals/placeholder/tilesets/$name")
                ?: error("Missing packaged placeholder tileset resource: /visuals/placeholder/tilesets/$name")
            val target = tilesetsDir.resolve(name)
            stream.use { Files.write(target, it.readBytes()) }
            target.toFile().deleteOnExit()
        }
        val mapsDir = Files.createDirectories(root.resolve("maps/skirmish"))
        for ((file, _) in builtInDemoMaps) {
            val stream = TilesetAtlasResolver::class.java.getResourceAsStream("/maps/skirmish/$file")
                ?: error("Missing packaged built-in skirmish map: /maps/skirmish/$file")
            val target = mapsDir.resolve(file)
            stream.use { Files.write(target, it.readBytes()) }
            target.toFile().deleteOnExit()
        }
        return root
    }

    /** Collects the set of clean non-zero GIDs referenced across all visual layers of a map. */
    fun collectActiveVisualGids(visualLayers: List<MapVisualLayer>): Set<Int> {
        val active = HashSet<Int>(256)
        for (layer in visualLayers) {
            val raw = layer.rawGids
            for (value in raw) {
                val clean = GroundRules.stripFlipFlags(value)
                if (clean != 0) active.add(clean)
            }
        }
        return active
    }

    /** Resolves which VisualTileAtlas owns a clean GID using Tiled latest-firstgid semantics. */
    fun ownerOf(tileSets: List<VisualTileAtlas>, cleanGid: Int): VisualTileAtlas? {
        if (cleanGid == 0) return null
        var candidate: VisualTileAtlas? = null
        for (atlas in tileSets) {
            if (cleanGid.toLong() >= atlas.firstGid.toLong()) {
                if (candidate == null || atlas.firstGid >= candidate.firstGid) {
                    candidate = atlas
                }
            }
        }
        val chosen = candidate ?: return null
        return if (cleanGid.toLong() < chosen.firstGid.toLong() + chosen.tileCount.toLong()) chosen else null
    }

    /** Normalized GPU texture upload key for deduplicating shared PNGs across multiple tilesets. */
    data class TextureUploadKey(val path: Path, val normalizedTransparentColor: String?)

    fun textureUploadKey(atlas: VisualTileAtlas): TextureUploadKey = TextureUploadKey(
        path = atlas.imagePath.toAbsolutePath().normalize(),
        normalizedTransparentColor = atlas.transparentColor?.trim()?.removePrefix("#")?.lowercase()?.takeIf { it.isNotEmpty() },
    )

    /** Deduplicates active atlases into unique (path, normalizedTransparentColor) GPU upload slots. */
    fun uniqueTextureUploadKeys(atlases: List<VisualTileAtlas>): List<TextureUploadKey> =
        atlases.mapTo(LinkedHashSet()) { textureUploadKey(it) }.toList()

    /** Returns only those atlases that own at least one active non-zero GID across visual layers. */
    fun activeAtlases(tileSets: List<VisualTileAtlas>, activeGids: Set<Int>): List<VisualTileAtlas> {
        if (activeGids.isEmpty()) return emptyList()
        val used = LinkedHashSet<VisualTileAtlas>()
        for (gid in activeGids) {
            ownerOf(tileSets, gid)?.let { used.add(it) }
        }
        return used.toList()
    }

    /**
     * Resolves the image path for a tileset atlas according to ADR-013 four-tier priority:
     * 1. Resource pack override (exact tileset key or legacy layout key)
     * 2. Map/TSX direct image path
     * 3. Configured local original assets tilesets/ fallback
     * 4. Packaged public placeholder biome fallback
     */
    fun resolveAtlasImage(
        atlas: VisualTileAtlas,
        visuals: PreparedVisualAssets?,
        originalTilesetRoot: Path? = null,
    ): ResolvedAtlasImage {
        val normalizedKey = atlas.normalizedResourceKey()
        val legacyLayout = atlas.legacyLayoutOrNull()

        // Tier 1: Enabled resource pack override
        if (visuals != null) {
            val packTilesetId = runCatching { ResourceId.parse(normalizedKey) }.getOrNull()
            val packOverride = packTilesetId?.let { visuals.resolvedImages?.images?.get(it) }
            if (packOverride != null && Files.isRegularFile(packOverride.path)) {
                val declW = packOverride.declaration.frameWidth
                val declH = packOverride.declaration.frameHeight
                val pw = if (declW > 0 && packOverride.pixelWidth % declW == 0) {
                    (atlas.tileWidth * (packOverride.pixelWidth / declW)).coerceAtLeast(1)
                } else {
                    atlas.tileWidth
                }
                val ph = if (declH > 0 && packOverride.pixelHeight % declH == 0) {
                    (atlas.tileHeight * (packOverride.pixelHeight / declH)).coerceAtLeast(1)
                } else {
                    atlas.tileHeight
                }
                return ResolvedAtlasImage(packOverride.path, transparentColor = null, pixelTileWidth = pw, pixelTileHeight = ph, tier = 1)
            }
            if (legacyLayout != null) {
                val resolved = visuals.resolvedImages?.images?.get(ResourceId.parse("reforge:terrain/$legacyLayout"))
                val cols = atlas.columns.coerceAtLeast(1)
                val rows = ((atlas.tileCount + cols - 1) / cols).coerceAtLeast(1)
                if (resolved != null) {
                    require(
                        atlas.columns > 0 && resolved.pixelWidth % atlas.columns == 0 && resolved.pixelHeight % rows == 0 &&
                            resolved.declaration.totalFrames == 1 && resolved.declaration.frameWidth == atlas.columns * atlas.tileWidth &&
                            resolved.declaration.frameHeight == rows * atlas.tileHeight
                    ) { "RESOURCE_PACK_BINDING_INVALID: reforge:terrain/$legacyLayout: Invalid atlas sampling layout" }
                }
                val legacyPath = visuals.terrain[legacyLayout]
                if (legacyPath != null) {
                    return ResolvedAtlasImage(
                        path = legacyPath,
                        transparentColor = null,
                        pixelTileWidth = resolved?.let { it.pixelWidth / cols } ?: atlas.tileWidth,
                        pixelTileHeight = resolved?.let { it.pixelHeight / rows } ?: atlas.tileHeight,
                        tier = 1,
                    )
                }
            }
        }

        // Tier 2: Map/TSX direct relative path
        if (Files.isRegularFile(atlas.imagePath) && Files.isReadable(atlas.imagePath)) {
            return ResolvedAtlasImage(atlas.imagePath, atlas.transparentColor, atlas.pixelTileWidth, atlas.pixelTileHeight, tier = 2)
        }

        // Tier 3: Configured local original assets tilesets/ fallback (safe path containment)
        val effectiveOriginalRoot = originalTilesetRoot ?: visuals?.selection?.originalDirectory?.let { dir ->
            val direct = dir.resolve("assets/tilesets")
            val flat = dir.resolve("tilesets")
            when {
                Files.isDirectory(direct) -> direct
                Files.isDirectory(flat) -> flat
                Files.isDirectory(dir.resolve("bitmaps")) -> dir
                else -> null
            }
        }
        if (effectiveOriginalRoot != null && Files.isDirectory(effectiveOriginalRoot)) {
            val baseNorm = effectiveOriginalRoot.toAbsolutePath().normalize()
            val normalizedRel = atlas.imageSource.replace('\\', '/').trim()
            val fileName = normalizedRel.substringAfterLast('/')
            val safeRel = normalizedRel.takeIf { it.isNotEmpty() && !it.startsWith("/") && !it.contains(":") }
            val safeFile = fileName.takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.contains(":") }
            val candidates = buildList {
                if (safeRel != null) runCatching { baseNorm.resolve(safeRel) }.getOrNull()?.let(::add)
                if (safeFile != null) {
                    runCatching { baseNorm.resolve("bitmaps").resolve(safeFile) }.getOrNull()?.let(::add)
                    runCatching { baseNorm.resolve(safeFile) }.getOrNull()?.let(::add)
                }
            }
            for (candidate in candidates) {
                val norm = candidate.toAbsolutePath().normalize()
                if (norm.startsWith(baseNorm) && !norm.isAbsolute.not() && Files.isRegularFile(norm) && Files.isReadable(norm)) {
                    return ResolvedAtlasImage(norm, atlas.transparentColor, atlas.tileWidth, atlas.tileHeight, tier = 3)
                }
            }
        }

        // Tier 4: Packaged public placeholder biome fallback
        val biomeFile = choosePlaceholderBiomeFile(atlas)
        val fallbackPath = placeholderTilesetDir().resolve(biomeFile)
        return ResolvedAtlasImage(fallbackPath, transparentColor = null, pixelTileWidth = atlas.tileWidth, pixelTileHeight = atlas.tileHeight, tier = 4)
    }

    fun choosePlaceholderBiomeFile(atlas: VisualTileAtlas): String {
        val hint = (atlas.name + " " + atlas.imageSource).lowercase()
        return when {
            "deepwater" in hint || "deep water" in hint -> "deep_water.png"
            "water" in hint -> "shallow_water.png"
            "lava" in hint -> "lava.png"
            "snow" in hint || "ice" in hint -> "snow.png"
            "sand" in hint || "dust" in hint -> "sand.png"
            "bridge" in hint -> "bridge.png"
            "mountain" in hint || "stone" in hint || "rock" in hint -> "mountain.png"
            "ridge" in hint || "cliff" in hint -> "ridge.png"
            "dirt" in hint -> "dirt.png"
            "misc" in hint || "decoration" in hint -> "misc.png"
            else -> "grass.png"
        }
    }

    data class ResolvedAtlasImage(
        val path: Path,
        val transparentColor: String?,
        val pixelTileWidth: Int,
        val pixelTileHeight: Int,
        val tier: Int,
    )
}

/**
 * Skirmish TMX scenario builder: maps original unit aliases to the active ContentSet,
 * creates player slots by team, spawns fallback bases if map has none, and records
 * skipped unsupported units/triggers for in-match amber event notices.
 */
object TmxSkirmishScenarioBuilder {
    const val DEFAULT_STARTING_CREDITS: Long = 4000L

    data class BuiltSkirmishScenario(
        val scenario: Scenario,
        val mapPath: Path,
        val displayName: String,
        val skippedUnitTypes: Map<String, Int>,
        val ignoredTriggerCount: Int,
        val startupNotices: List<String>,
    )

    private val originalUnitAliases: Map<String, String> = mapOf(
        "commandcenter" to "commandCenter",
        "builder" to "builder",
        "tank" to "c_tank",
        "c_tank" to "c_tank",
        "turret" to "c_turret_t1",
        "turret_t1" to "c_turret_t1",
        "c_turret_t1" to "c_turret_t1",
        "turret_artillery" to "c_turret_t1_artillery",
        "turret_t2" to "c_turret_t2_gun",
        "laserdefence" to "laserDefence",
        "laserdefense" to "laserDefence",
        "repairbay" to "repairbay",
        "extractor" to "extractorT1",
        "extractort1" to "extractorT1",
        "extractort2" to "extractorT2",
        "extractort3" to "extractorT3",
        "fabricator" to "fabricatorT1",
        "fabricatort1" to "fabricatorT1",
        "fabricatort2" to "fabricatorT2",
        "fabricatort3" to "fabricatorT3",
        "landfactory" to "landFactory",
        "airfactory" to "airFactory",
        "airfactoryt2" to "airFactoryT2",
        "seafactory" to "seaFactory",
        "seafactoryt2" to "seaFactoryT2",
        "mechfactory" to "mechFactory",
        "mechfactoryt2" to "mechFactoryT2",
        "experimentallandfactory" to "experimentalLandFactory",
        "hovertank" to "hoverTank",
        "helicopter" to "helicopter",
        "amphibiousjet" to "amphibiousJet",
        "dropship" to "dropship",
        "hovercraft" to "hovercraft",
        "gunboat" to "gunBoat",
        "battleship" to "heavyBattleship",
        "heavybattleship" to "heavyBattleship",
        "attacksubmarine" to "attackSubmarine",
        "modularspider" to "modularSpider",
        "experimentalspider" to "modularSpider",
        "bomber" to "bomber",
        "aabeamgunship" to "aaBeamGunship",
        "mechbunker" to "mechBunker",
        "mechengineer" to "mechEngineer",
        "mechflame" to "mechFlame",
        "heavymech" to "mechLarge",
        "scoutbot" to "scoutBot",
        "nautilussubmarine" to "nautilusSubmarine",
        "nukelauncher" to "nukeLauncherC",
        "antinukelauncher" to "antiNukeLauncherC",
    )

    fun resolveUnitTypeId(rawName: String, definitions: Map<String, UnitDefinition>): String? {
        if (rawName in definitions) return rawName
        val lower = rawName.trim().lowercase()
        originalUnitAliases[lower]?.takeIf { it in definitions }?.let { return it }
        definitions.keys.firstOrNull { it.equals(rawName.trim(), ignoreCase = true) }?.let { return it }
        return null
    }

    fun tilesetFallbackRoots(originalDirectory: Path?): List<Path> {
        val roots = mutableListOf<Path>()
        if (originalDirectory != null) {
            val assetsTilesets = originalDirectory.resolve("assets/tilesets").toAbsolutePath().normalize()
            val directTilesets = originalDirectory.resolve("tilesets").toAbsolutePath().normalize()
            if (Files.isDirectory(assetsTilesets)) roots.add(assetsTilesets)
            else if (Files.isDirectory(directTilesets)) roots.add(directTilesets)
            else if (Files.isDirectory(originalDirectory.resolve("bitmaps"))) roots.add(originalDirectory.toAbsolutePath().normalize())
        }
        roots.add(TilesetAtlasResolver.placeholderTilesetDir())
        return roots
    }

    fun build(
        mapPath: Path,
        definitions: Map<String, UnitDefinition>,
        originalDirectory: Path? = null,
        displayNameOverride: String? = null,
    ): BuiltSkirmishScenario {
        val absolutePath = mapPath.toAbsolutePath().normalize()
        val loadedMap = when (val result = ContentLoader.loadMap(absolutePath, tilesetFallbackRoots(originalDirectory))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        return buildFromMap(absolutePath, loadedMap, definitions, displayNameOverride)
    }

    fun buildFromMap(
        mapPath: Path,
        map: MapDefinition,
        definitions: Map<String, UnitDefinition>,
        displayNameOverride: String? = null,
    ): BuiltSkirmishScenario {
        val displayName = displayNameOverride ?: mapPath.fileName.toString().removeSuffix(".tmx")
        val skippedCounts = linkedMapOf<String, Int>()
        val spawns = mutableListOf<UnitSpawn>()
        val occupiedBuildingCells = linkedSetOf<Pair<Int, Int>>()
        val playersByTeamKey = linkedMapOf<String, Player>()

        fun ensurePlayer(teamRaw: String?): String {
            val trimmed = teamRaw?.trim()?.lowercase()
            if (trimmed == null || trimmed.isEmpty() || trimmed == "none" || trimmed == "-1") {
                return playersByTeamKey.getOrPut("neutral") {
                    Player("neutral", PlayerControl.NONE, "neutral")
                }.playerId
            }
            val parsedNum = trimmed.toIntOrNull()
            return if (parsedNum != null && parsedNum >= 0) {
                val key = "team-$parsedNum"
                playersByTeamKey.getOrPut(key) {
                    val pid = "p${parsedNum + 1}"
                    val control = if (parsedNum == 0) PlayerControl.LOCAL else PlayerControl.NONE
                    Player(pid, control, "team-${parsedNum + 1}")
                }.playerId
            } else {
                playersByTeamKey.getOrPut("neutral") {
                    Player("neutral", PlayerControl.NONE, "neutral")
                }.playerId
            }
        }

        // Always ensure local player p1 and at least one opponent p2 exist.
        ensurePlayer("0")
        ensurePlayer("1")

        var nextIndex = 1
        for (declared in map.startingUnits) {
            val resolvedType = resolveUnitTypeId(declared.unitType, definitions)
            if (resolvedType == null) {
                skippedCounts[declared.unitType] = (skippedCounts[declared.unitType] ?: 0) + 1
                continue
            }
            val def = definitions.getValue(resolvedType)
            val ownerId = ensurePlayer(declared.team)
            val position = findValidSpawnPosition(map, def, Position(declared.worldX, declared.worldY), occupiedBuildingCells)
            if (position == null) {
                skippedCounts[declared.unitType] = (skippedCounts[declared.unitType] ?: 0) + 1
                continue
            }
            def.building?.let { building ->
                val geom = ConstructionPlacement.footprint(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, position, building.footprint, building.anchor)
                if (geom.withinMap) occupiedBuildingCells.addAll(geom.cells)
            }
            spawns.add(UnitSpawn("map-u${nextIndex++}", ownerId, resolvedType, position, 0.0))
        }

        // Ensure p1 has at least one unit (fallback commandCenter + builder if map had none for p1 or none at all)
        if (spawns.none { it.ownerPlayerId == "p1" }) {
            spawnFallbackBase(map, definitions, "p1", preferTopLeft = true, occupiedBuildingCells, spawns) { "map-u${nextIndex++}" }
        }
        if (spawns.none { it.ownerPlayerId != "p1" && it.ownerPlayerId != "neutral" }) {
            spawnFallbackBase(map, definitions, "p2", preferTopLeft = false, occupiedBuildingCells, spawns) { "map-u${nextIndex++}" }
        }

        val players = playersByTeamKey.values.toList()
        val objectIds = spawns.mapIndexed { idx, spawn -> spawn.unitId to (8L + idx) }.toMap()
        val credits = players.associate { player ->
            player.playerId to if (player.playerId == "neutral") 0L else DEFAULT_STARTING_CREDITS
        }
        val scenario = Scenario(
            scenarioId = "tmx-skirmish:${VisualTileAtlas.normalizeAtlasKey(displayName)}",
            map = map,
            unitDefinitions = definitions,
            players = players,
            units = spawns,
            coordinateModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            movementContract = MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, objectIds, 8L + spawns.size),
            economyContract = EconomyContract(credits),
        )

        val notices = mutableListOf<String>()
        val skippedUnitTotal = skippedCounts.values.sum()
        val ignoredTriggers = map.triggerInfo.ignoredObjectCount
        if (skippedUnitTotal > 0 || ignoredTriggers > 0) {
            val parts = mutableListOf<String>()
            if (skippedUnitTotal > 0) parts.add("$skippedUnitTotal 个暂未支持的地图单位")
            if (ignoredTriggers > 0) parts.add("$ignoredTriggers 个触发器对象")
            notices.add("已跳过 " + parts.joinToString("与"))
        }

        return BuiltSkirmishScenario(
            scenario = scenario,
            mapPath = mapPath,
            displayName = displayName,
            skippedUnitTypes = Collections.unmodifiableMap(LinkedHashMap(skippedCounts)),
            ignoredTriggerCount = ignoredTriggers,
            startupNotices = Collections.unmodifiableList(notices),
        )
    }

    private fun spawnFallbackBase(
        map: MapDefinition,
        definitions: Map<String, UnitDefinition>,
        ownerId: String,
        preferTopLeft: Boolean,
        occupied: MutableSet<Pair<Int, Int>>,
        out: MutableList<UnitSpawn>,
        nextId: () -> String,
    ) {
        val primaryType = definitions["commandCenter"]?.id ?: definitions.values.firstOrNull { it.building != null }?.id ?: definitions.keys.firstOrNull() ?: return
        val primaryDef = definitions.getValue(primaryType)
        val anchor = if (preferTopLeft) {
            Position(map.widthTiles * map.tileWidth * 0.2, map.heightTiles * map.tileHeight * 0.25)
        } else {
            Position(map.widthTiles * map.tileWidth * 0.8, map.heightTiles * map.tileHeight * 0.75)
        }
        val basePos = findValidSpawnPosition(map, primaryDef, anchor, occupied, maxRadiusTiles = maxOf(map.widthTiles, map.heightTiles))
            ?: return
        primaryDef.building?.let { building ->
            val geom = ConstructionPlacement.footprint(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, basePos, building.footprint, building.anchor)
            if (geom.withinMap) occupied.addAll(geom.cells)
        }
        out.add(UnitSpawn(nextId(), ownerId, primaryType, basePos, 0.0))

        val builderDef = definitions["builder"] ?: definitions.values.firstOrNull { it.builder != null && it.building == null }
        if (builderDef != null) {
            val builderPos = findValidSpawnPosition(
                map, builderDef, Position(basePos.x + map.tileWidth * 3.0, basePos.y), occupied, maxRadiusTiles = 10,
            )
            if (builderPos != null) {
                out.add(UnitSpawn(nextId(), ownerId, builderDef.id, builderPos, 0.0))
            }
        }
    }

    private fun findValidSpawnPosition(
        map: MapDefinition,
        def: UnitDefinition,
        desired: Position,
        occupied: Set<Pair<Int, Int>>,
        maxRadiusTiles: Int = 8,
    ): Position? {
        val baseCol = (desired.x / map.tileWidth).toInt().coerceIn(0, map.widthTiles - 1)
        val baseRow = (desired.y / map.tileHeight).toInt().coerceIn(0, map.heightTiles - 1)

        fun candidateAt(col: Int, row: Int): Position? {
            if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) return null
            val pos = Position((col + 0.5) * map.tileWidth, (row + 0.5) * map.tileHeight)
            val building = def.building
            if (building != null) {
                if (building.requiresResourcePool && !map.isResourcePool(col, row)) return null
                val geom = try {
                    ConstructionPlacement.footprint(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, pos, building.footprint, building.anchor)
                } catch (_: IllegalArgumentException) {
                    return null
                }
                if (!geom.withinMap) return null
                for (cell in geom.cells) {
                    if ((!building.requiresResourcePool && map.isFoundationBlocked(cell.first, cell.second, def.foundationMovementType)) || cell in occupied) {
                        return null
                    }
                }
                return pos
            } else {
                if (def.movement.type != MovementType.AIR) {
                    if ((col to row) in occupied) return null
                    val blocked = when (def.movement.type) {
                        MovementType.WATER -> map.waterBlocking.isBlocked(col, row)
                        MovementType.HOVER -> map.hoverBlocking.isBlocked(col, row)
                        MovementType.OVER_CLIFF -> map.overCliffBlocking.isBlocked(col, row)
                        MovementType.OVER_CLIFF_WATER -> map.overCliffWaterBlocking.isBlocked(col, row)
                        else -> map.blocking.isBlocked(col, row)
                    }
                    if (blocked) return null
                }
                return pos
            }
        }

        candidateAt(baseCol, baseRow)?.let { return it }
        for (radius in 1..maxRadiusTiles) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dy) != radius) continue
                    candidateAt(baseCol + dx, baseRow + dy)?.let { return it }
                }
            }
        }
        return null
    }
}
