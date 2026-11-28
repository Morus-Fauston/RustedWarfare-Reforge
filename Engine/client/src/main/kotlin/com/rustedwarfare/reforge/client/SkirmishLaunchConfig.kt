package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.MapDefinition
import java.nio.file.Path
import java.util.Locale

/**
 * Victory rule modes exposed in the Skirmish Lobby (Phase VIII Spec §3.1 & UI Supplement §4.2).
 */
enum class SkirmishWinMode(val labelKey: String) {
    STANDARD("lobby.win.standard"),
    ELIMINATION("lobby.win.elimination"),
    SANDBOX("lobby.win.sandbox"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)

    data class MappingResult(
        val mode: SkirmishWinMode,
        val fallbackNoticeKey: String? = null,
    )

    companion object {
        /**
         * Maps original `.tmx` `winCondition` metadata to [SkirmishWinMode] (UI Supplement §4.2 & DEC-P006-010 §7):
         * - `none` -> [SANDBOX]
         * - `allUnitsAndBuildings` -> [ELIMINATION]
         * - `allBuildings` / `mainBuildings` / `commandCenter` / absent -> [STANDARD]
         * - `requiredObjectives` -> [STANDARD] + localized fallback notice (`lobby.win.objectivesFallbackNotice`)
         */
        fun mapFromWinCondition(rawWinCondition: String?): MappingResult {
            val normalized = rawWinCondition?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when (normalized) {
                "none" -> MappingResult(SANDBOX, null)
                "allunitsandbuildings" -> MappingResult(ELIMINATION, null)
                "requiredobjectives" -> MappingResult(STANDARD, "lobby.win.objectivesFallbackNotice")
                "", "allbuildings", "mainbuildings", "mainbuilings", "commandcenter" -> MappingResult(STANDARD, null)
                else -> MappingResult(STANDARD, null)
            }
        }

        fun extractRawWinCondition(map: MapDefinition): String? {
            for ((k, v) in map.triggerInfo.properties) {
                if (k.equals("winCondition", ignoreCase = true) || k.equals("win_condition", ignoreCase = true)) {
                    return v
                }
            }
            for ((k, v) in map.mapProperties) {
                if (k.equals("winCondition", ignoreCase = true) || k.equals("win_condition", ignoreCase = true)) {
                    return v
                }
            }
            return null
        }

        fun mapFromMapDefinition(map: MapDefinition): MappingResult =
            mapFromWinCondition(extractRawWinCondition(map))
    }
}

/**
 * 4-tier Fog of War selector (Phase VIII Spec §4 & UI Supplement §4.2).
 */
enum class SkirmishFogMode(val labelKey: String) {
    NONE("lobby.fog.none"),
    BASIC_TERRAIN("lobby.fog.basic"),
    MEMORY("lobby.fog.memory"),
    HARDCORE("lobby.fog.hardcore"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)

    /**
     * Safe Phase VIII pt1 launch bridge into existing [SandboxMode] (full `BASIC_TERRAIN` simulation lands in pt2).
     */
    fun toCurrentSandboxMode(): SandboxMode = when (this) {
        NONE -> SandboxMode.NO_FOG
        BASIC_TERRAIN, MEMORY -> SandboxMode.MEMORY
        HARDCORE -> SandboxMode.HARDCORE
    }

    companion object {
        fun fromSandboxMode(mode: SandboxMode): SkirmishFogMode = when (mode) {
            SandboxMode.NO_FOG -> NONE
            SandboxMode.MEMORY -> MEMORY
            SandboxMode.HARDCORE -> HARDCORE
        }
    }
}

/**
 * Starting credits presets for Skirmish (`$1,000 .. $50,000`) and Free Sandbox (`无限`).
 */
enum class SkirmishStartingCredits(
    val amount: Long,
    private val fixedDisplay: String?,
    private val labelKey: String? = null,
) {
    INFINITE(-1L, null, "lobby.funds.infinite"),
    CREDITS_1000(1_000L, "$1,000"),
    CREDITS_2000(2_000L, "$2,000"),
    CREDITS_4000(4_000L, "$4,000"),
    CREDITS_10000(10_000L, "$10,000"),
    CREDITS_50000(50_000L, "$50,000"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        fixedDisplay ?: UiStrings.get(requireNotNull(labelKey), language)

    companion object {
        val SKIRMISH_OPTIONS: List<SkirmishStartingCredits> = listOf(
            CREDITS_1000,
            CREDITS_2000,
            CREDITS_4000,
            CREDITS_10000,
            CREDITS_50000,
        )

        val SANDBOX_OPTIONS: List<SkirmishStartingCredits> = listOf(
            INFINITE,
            CREDITS_1000,
            CREDITS_2000,
            CREDITS_4000,
            CREDITS_10000,
            CREDITS_50000,
        )
    }
}

/**
 * Passive income rate multiplier in `更多设置` (UI Supplement §4.2).
 */
enum class SkirmishIncomeMultiplier(val factor: Float, val displayText: String) {
    X0_5(0.5f, "0.5×"),
    X1_0(1.0f, "1×"),
    X1_5(1.5f, "1.5×"),
    X2_0(2.0f, "2×"),
    X3_0(3.0f, "3×"),
}

/**
 * Spawn assignment mode in `更多设置` (UI Supplement §4.2).
 */
enum class SkirmishSpawnAssignment(val labelKey: String) {
    FIXED("lobby.spawn.fixed"),
    RANDOM("lobby.spawn.random"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)
}

/**
 * Starting unit composition mode in `更多设置` (UI Supplement §4.2).
 */
enum class SkirmishStartingUnits(val labelKey: String) {
    MAP_DEFAULT("lobby.units.mapDefault"),
    STANDARD_BASE("lobby.units.standardBase"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)
}

/**
 * Low-frequency match rules grouped into the centered `更多设置` modal (UI Supplement §4.2).
 */
data class MoreMatchRules(
    val incomeMultiplier: SkirmishIncomeMultiplier = SkirmishIncomeMultiplier.X1_0,
    val sharedVision: Boolean = true,
    val spawnAssignment: SkirmishSpawnAssignment = SkirmishSpawnAssignment.FIXED,
    val startingUnits: SkirmishStartingUnits = SkirmishStartingUnits.MAP_DEFAULT,
    val noNukes: Boolean = false,
) {
    val hasNonDefault: Boolean
        get() = this != DEFAULT

    companion object {
        val DEFAULT = MoreMatchRules()
    }
}

enum class MatchSeatKind {
    LOCAL_HUMAN,
    REMOTE_HUMAN,
    AI,
}

/**
 * AI difficulty placeholder slot (Phase VIII Spec §1.3 & UI Supplement §4.1; AI behavior arrives in Phase IX).
 */
enum class SkirmishAiDifficulty(val labelKey: String) {
    IDLE("lobby.ai.idle"),
    EASY("lobby.ai.easy"),
    NORMAL("lobby.ai.normal"),
    HARD("lobby.ai.hard"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)

    companion object {
        val SKIRMISH_OPTIONS: List<SkirmishAiDifficulty> = listOf(EASY, NORMAL, HARD)
        val SANDBOX_OPTIONS: List<SkirmishAiDifficulty> = listOf(IDLE, EASY, NORMAL, HARD)
    }
}

/**
 * Single participant seat configuration in the 1–10 seat table (`MatchSlotTableView`).
 */
data class MatchSeatConfig(
    val seatId: Int,
    val kind: MatchSeatKind,
    val colorIndex: Int,
    val ordinalInKind: Int = 1,
    val customName: String? = null,
    val aiDifficulty: SkirmishAiDifficulty = SkirmishAiDifficulty.NORMAL,
    val teamIndex: Int = 0,
    val spawnPoint: Int = 1,
    val sandboxDummyName: Boolean = false,
) {
    init {
        require(colorIndex in 0 until MAX_PALETTE_COLORS) { "colorIndex out of 0..9: $colorIndex" }
        require(teamIndex in 0 until MAX_TEAMS) { "teamIndex out of 0..9: $teamIndex" }
        require(spawnPoint in 1..MAX_SEATS) { "spawnPoint out of 1..10: $spawnPoint" }
    }

    val colorSlotLabel: String
        get() = "P${colorIndex + 1}"

    val teamLabel: String
        get() = ('A' + teamIndex).toString()

    fun displayName(language: UiLanguage = UiStrings.currentLanguage): String {
        customName?.takeIf { it.isNotBlank() }?.let { return it }
        return when (kind) {
            MatchSeatKind.LOCAL_HUMAN -> UiStrings.get("lobby.seat.you", language)
            MatchSeatKind.REMOTE_HUMAN -> UiStrings.format("lobby.seat.playerN", "N" to ordinalInKind, language = language)
            MatchSeatKind.AI -> if (sandboxDummyName) {
                if (ordinalInKind <= 1) {
                    UiStrings.get("lobby.seat.dummy", language)
                } else {
                    UiStrings.format("lobby.seat.dummyN", "N" to ordinalInKind, language = language)
                }
            } else {
                UiStrings.format("lobby.seat.aiN", "N" to ordinalInKind, language = language)
            }
        }
    }

    companion object {
        const val MAX_SEATS: Int = 10
        const val MAX_PALETTE_COLORS: Int = 10
        const val MAX_TEAMS: Int = 10
    }
}

/**
 * Immutable Phase VIII pt1 -> pt2 skirmish / free-sandbox launch contract (`UI-P004-003`).
 *
 * All UI rule & 1–10 seat configurations are frozen into this value object when clicking
 * `开始对局` or `进入自由沙盒`. Fields already supported by `Engine/core` take immediate effect
 * via [applyToSandboxOptions]; pt2 rules remain cleanly preserved for core consumption.
 */
class SkirmishLaunchConfig private constructor(
    val mapId: String,
    val mapSlug: String,
    val mapDisplayName: String,
    val tmxPath: Path?,
    val catalogSceneId: String?,
    val maxSpawns: Int,
    val winMode: SkirmishWinMode,
    val winConditionFallbackNoticeKey: String?,
    val fogMode: SkirmishFogMode,
    val startingCredits: SkirmishStartingCredits,
    val moreRules: MoreMatchRules,
    val fastBuild: Boolean,
    val isFreeSandbox: Boolean,
    val seats: List<MatchSeatConfig>,
) {
    val immutableSeats: List<MatchSeatConfig>
        get() = seats

    init {
        require(seats.size in 1..MatchSeatConfig.MAX_SEATS) {
            "Expected 1..10 seats, got ${seats.size}"
        }
        require(maxSpawns in 2..MatchSeatConfig.MAX_SEATS) {
            "Expected maxSpawns in 2..10, got $maxSpawns"
        }
        require(seats.all { it.spawnPoint in 1..maxSpawns }) {
            "Every seat spawnPoint must fall within 1..$maxSpawns: ${seats.map { it.spawnPoint }}"
        }
    }

    fun applyToSandboxOptions(base: SandboxOptions, allowSandboxDeployment: Boolean = false): SandboxOptions =
        base.copy(
            mode = fogMode.toCurrentSandboxMode(),
            shareAlliedVision = moreRules.sharedVision,
            nukesEnabled = !moreRules.noNukes,
            deploymentEnabled = allowSandboxDeployment,
        )

    fun copy(
        mapId: String = this.mapId,
        mapSlug: String = this.mapSlug,
        mapDisplayName: String = this.mapDisplayName,
        tmxPath: Path? = this.tmxPath,
        catalogSceneId: String? = this.catalogSceneId,
        maxSpawns: Int = this.maxSpawns,
        winMode: SkirmishWinMode = this.winMode,
        winConditionFallbackNoticeKey: String? = this.winConditionFallbackNoticeKey,
        fogMode: SkirmishFogMode = this.fogMode,
        startingCredits: SkirmishStartingCredits = this.startingCredits,
        moreRules: MoreMatchRules = this.moreRules,
        fastBuild: Boolean = this.fastBuild,
        isFreeSandbox: Boolean = this.isFreeSandbox,
        seats: List<MatchSeatConfig> = this.seats,
    ): SkirmishLaunchConfig = invoke(
        mapId = mapId,
        mapSlug = mapSlug,
        mapDisplayName = mapDisplayName,
        tmxPath = tmxPath,
        catalogSceneId = catalogSceneId,
        maxSpawns = maxSpawns,
        winMode = winMode,
        winConditionFallbackNoticeKey = winConditionFallbackNoticeKey,
        fogMode = fogMode,
        startingCredits = startingCredits,
        moreRules = moreRules,
        fastBuild = fastBuild,
        isFreeSandbox = isFreeSandbox,
        seats = seats,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkirmishLaunchConfig) return false
        return mapId == other.mapId &&
            mapSlug == other.mapSlug &&
            mapDisplayName == other.mapDisplayName &&
            tmxPath == other.tmxPath &&
            catalogSceneId == other.catalogSceneId &&
            maxSpawns == other.maxSpawns &&
            winMode == other.winMode &&
            winConditionFallbackNoticeKey == other.winConditionFallbackNoticeKey &&
            fogMode == other.fogMode &&
            startingCredits == other.startingCredits &&
            moreRules == other.moreRules &&
            fastBuild == other.fastBuild &&
            isFreeSandbox == other.isFreeSandbox &&
            seats == other.seats
    }

    override fun hashCode(): Int {
        var result = mapId.hashCode()
        result = 31 * result + mapSlug.hashCode()
        result = 31 * result + mapDisplayName.hashCode()
        result = 31 * result + (tmxPath?.hashCode() ?: 0)
        result = 31 * result + (catalogSceneId?.hashCode() ?: 0)
        result = 31 * result + maxSpawns
        result = 31 * result + winMode.hashCode()
        result = 31 * result + (winConditionFallbackNoticeKey?.hashCode() ?: 0)
        result = 31 * result + fogMode.hashCode()
        result = 31 * result + startingCredits.hashCode()
        result = 31 * result + moreRules.hashCode()
        result = 31 * result + fastBuild.hashCode()
        result = 31 * result + isFreeSandbox.hashCode()
        result = 31 * result + seats.hashCode()
        return result
    }

    override fun toString(): String =
        "SkirmishLaunchConfig(mapId=$mapId, winMode=$winMode, fogMode=$fogMode, credits=$startingCredits, seats=${seats.size})"

    companion object {
        operator fun invoke(
            mapId: String,
            mapSlug: String,
            mapDisplayName: String,
            tmxPath: Path?,
            catalogSceneId: String? = null,
            maxSpawns: Int,
            winMode: SkirmishWinMode,
            winConditionFallbackNoticeKey: String? = null,
            fogMode: SkirmishFogMode,
            startingCredits: SkirmishStartingCredits,
            moreRules: MoreMatchRules,
            fastBuild: Boolean = false,
            isFreeSandbox: Boolean = false,
            seats: List<MatchSeatConfig>,
        ): SkirmishLaunchConfig = SkirmishLaunchConfig(
            mapId = mapId,
            mapSlug = mapSlug,
            mapDisplayName = mapDisplayName,
            tmxPath = tmxPath,
            catalogSceneId = catalogSceneId,
            maxSpawns = maxSpawns,
            winMode = winMode,
            winConditionFallbackNoticeKey = winConditionFallbackNoticeKey,
            fogMode = fogMode,
            startingCredits = startingCredits,
            moreRules = moreRules,
            fastBuild = fastBuild,
            isFreeSandbox = isFreeSandbox,
            seats = java.util.List.copyOf(seats),
        )
    }
}

/**
 * Pure JVM state machine backing [SkirmishLobbyScreen], [MoreMatchSettingsModal], and `004` Free Sandbox.
 *
 * Enforces:
 * - 1–10 dynamic seats (host seat `LOCAL_HUMAN` cannot be removed);
 * - Automatic 10-color swap when picking an already-occupied player color;
 * - Automatic spawn-point swap when picking an already-occupied spawn index;
 * - `混战` (FFA) and `双队平分` (2 Teams) quick team assignment;
 * - Automatic `winCondition` -> [SkirmishWinMode] initial mapping on map change while preserving seats & rules.
 */
class SkirmishLobbyState(
    initialMap: MapPickerEntry = OutGameMapCatalog.defaultEntry(),
    val freeSandboxMode: Boolean = false,
) {
    var selectedMap: MapPickerEntry = initialMap
        private set

    var winMode: SkirmishWinMode = if (freeSandboxMode) {
        SkirmishWinMode.SANDBOX
    } else {
        SkirmishWinMode.mapFromMapDefinition(initialMap.mapDefinition).mode
    }
        private set

    var winConditionFallbackNoticeKey: String? = if (freeSandboxMode) {
        null
    } else {
        SkirmishWinMode.mapFromMapDefinition(initialMap.mapDefinition).fallbackNoticeKey
    }
        private set

    var fogMode: SkirmishFogMode = if (freeSandboxMode) SkirmishFogMode.NONE else SkirmishFogMode.MEMORY
    var startingCredits: SkirmishStartingCredits = if (freeSandboxMode) {
        SkirmishStartingCredits.INFINITE
    } else {
        SkirmishStartingCredits.CREDITS_4000
    }
    var moreRules: MoreMatchRules = MoreMatchRules.DEFAULT
    var fastBuild: Boolean = false

    private var nextSeatId: Int = 1
    private var seatsCustomized: Boolean = false
    private val mutableSeats = ArrayList<MatchSeatConfig>()

    val seats: List<MatchSeatConfig>
        get() = mutableSeats.toList()

    val canAddSeat: Boolean
        get() = mutableSeats.size < MatchSeatConfig.MAX_SEATS

    /** Effective selectable spawn count (`2..10`), expanding smoothly if seats exceed map's base player count. */
    val availableSpawnCount: Int
        get() = maxOf(selectedMap.maxPlayers, mutableSeats.size).coerceIn(2, MatchSeatConfig.MAX_SEATS)

    init {
        resetDefaultSeatsForMap(initialMap)
    }

    private fun resetDefaultSeatsForMap(entry: MapPickerEntry) {
        mutableSeats.clear()
        nextSeatId = 1
        if (freeSandboxMode) {
            mutableSeats += MatchSeatConfig(
                seatId = nextSeatId++,
                kind = MatchSeatKind.LOCAL_HUMAN,
                colorIndex = 0,
                ordinalInKind = 1,
                teamIndex = 0,
                spawnPoint = 1,
            )
            mutableSeats += MatchSeatConfig(
                seatId = nextSeatId++,
                kind = MatchSeatKind.AI,
                colorIndex = 1,
                ordinalInKind = 1,
                aiDifficulty = SkirmishAiDifficulty.IDLE,
                teamIndex = 1,
                spawnPoint = 2,
                sandboxDummyName = true,
            )
            return
        }

        val initialTotal = entry.maxPlayers.coerceIn(2, 4)
        mutableSeats += MatchSeatConfig(
            seatId = nextSeatId++,
            kind = MatchSeatKind.LOCAL_HUMAN,
            colorIndex = 0,
            ordinalInKind = 1,
            teamIndex = 0,
            spawnPoint = 1,
        )
        for (i in 1 until initialTotal) {
            val team = if (initialTotal == 4) {
                if (i == 3) 0 else 1
            } else {
                i % 2
            }
            val diff = if (i == 3) SkirmishAiDifficulty.HARD else SkirmishAiDifficulty.NORMAL
            mutableSeats += MatchSeatConfig(
                seatId = nextSeatId++,
                kind = MatchSeatKind.AI,
                colorIndex = i % MatchSeatConfig.MAX_PALETTE_COLORS,
                ordinalInKind = i,
                aiDifficulty = diff,
                teamIndex = team,
                spawnPoint = i + 1,
            )
        }
    }

    /** Selects a new [MapPickerEntry] and updates `winMode` from map `winCondition` (when switching maps) while preserving custom seats & rules. */
    fun selectMapEntry(entry: MapPickerEntry) {
        val mapChanged = entry.id != selectedMap.id
        selectedMap = entry
        if (!freeSandboxMode && mapChanged) {
            val mapped = SkirmishWinMode.mapFromMapDefinition(entry.mapDefinition)
            winMode = mapped.mode
            winConditionFallbackNoticeKey = mapped.fallbackNoticeKey
            if (!seatsCustomized) {
                resetDefaultSeatsForMap(entry)
                return
            }
        }
        reconcileSeatSpawns()
    }

    private fun reconcileSeatSpawns() {
        val maxSpawn = availableSpawnCount
        val usedSpawns = HashSet<Int>()
        for (idx in mutableSeats.indices) {
            val cur = mutableSeats[idx]
            val validSpawn = if (cur.spawnPoint in 1..maxSpawn && usedSpawns.add(cur.spawnPoint)) {
                cur.spawnPoint
            } else {
                val fallback = (1..maxSpawn).firstOrNull { it !in usedSpawns } ?: (idx + 1).coerceAtMost(maxSpawn)
                usedSpawns.add(fallback)
                fallback
            }
            if (validSpawn != cur.spawnPoint) {
                mutableSeats[idx] = cur.copy(spawnPoint = validSpawn)
            }
        }
    }

    fun updateWinMode(mode: SkirmishWinMode) {
        if (!freeSandboxMode && winMode != mode) {
            winMode = mode
            winConditionFallbackNoticeKey = null
        }
    }

    fun restoreMoreRulesDefaults() {
        moreRules = MoreMatchRules.DEFAULT
    }

    /** Adds an AI seat (up to 10 seats total); returns newly added seat or null if already at 10 seats. */
    fun addAiSeat(
        difficulty: SkirmishAiDifficulty = if (freeSandboxMode) SkirmishAiDifficulty.IDLE else SkirmishAiDifficulty.NORMAL,
    ): MatchSeatConfig? {
        if (!canAddSeat) return null
        seatsCustomized = true
        val usedColors = mutableSeats.map { it.colorIndex }.toSet()
        val color = (0 until MatchSeatConfig.MAX_PALETTE_COLORS).firstOrNull { it !in usedColors }
            ?: (mutableSeats.size % MatchSeatConfig.MAX_PALETTE_COLORS)
        val maxSpawn = maxOf(selectedMap.maxPlayers, mutableSeats.size + 1).coerceIn(2, MatchSeatConfig.MAX_SEATS)
        val usedSpawns = mutableSeats.map { it.spawnPoint }.toSet()
        val spawn = (1..maxSpawn).firstOrNull { it !in usedSpawns } ?: (mutableSeats.size + 1)
        val nextAiOrd = (mutableSeats.filter { it.kind == MatchSeatKind.AI }.maxOfOrNull { it.ordinalInKind } ?: 0) + 1
        val nextTeam = if (mutableSeats.size % 2 == 0) 0 else 1
        val created = MatchSeatConfig(
            seatId = nextSeatId++,
            kind = MatchSeatKind.AI,
            colorIndex = color,
            ordinalInKind = nextAiOrd,
            aiDifficulty = difficulty,
            teamIndex = nextTeam,
            spawnPoint = spawn,
            sandboxDummyName = freeSandboxMode,
        )
        mutableSeats += created
        return created
    }

    /** Adds a non-host human seat (`玩家 N`, supporting the `踢出` action contract for `2.0` & headless verification). */
    fun addRemoteHumanSeat(customName: String? = null): MatchSeatConfig? {
        if (!canAddSeat) return null
        seatsCustomized = true
        val usedColors = mutableSeats.map { it.colorIndex }.toSet()
        val color = (0 until MatchSeatConfig.MAX_PALETTE_COLORS).firstOrNull { it !in usedColors }
            ?: (mutableSeats.size % MatchSeatConfig.MAX_PALETTE_COLORS)
        val maxSpawn = maxOf(selectedMap.maxPlayers, mutableSeats.size + 1).coerceIn(2, MatchSeatConfig.MAX_SEATS)
        val usedSpawns = mutableSeats.map { it.spawnPoint }.toSet()
        val spawn = (1..maxSpawn).firstOrNull { it !in usedSpawns } ?: (mutableSeats.size + 1)
        val humanCount = mutableSeats.count { it.kind != MatchSeatKind.AI }
        val created = MatchSeatConfig(
            seatId = nextSeatId++,
            kind = MatchSeatKind.REMOTE_HUMAN,
            colorIndex = color,
            ordinalInKind = humanCount + 1,
            customName = customName,
            teamIndex = if (mutableSeats.size % 2 == 0) 0 else 1,
            spawnPoint = spawn,
        )
        mutableSeats += created
        return created
    }

    /** Removes an AI seat or kicks a remote human seat; local host (`LOCAL_HUMAN`) is protected. */
    fun removeOrKickSeat(seatIndex: Int): Boolean {
        if (seatIndex !in mutableSeats.indices) return false
        val target = mutableSeats[seatIndex]
        if (target.kind == MatchSeatKind.LOCAL_HUMAN) return false
        seatsCustomized = true
        mutableSeats.removeAt(seatIndex)
        renumberAiOrdinals()
        reconcileSeatSpawns()
        return true
    }

    private fun renumberAiOrdinals() {
        var aiOrd = 1
        var remoteOrd = 2
        for (i in mutableSeats.indices) {
            val s = mutableSeats[i]
            when (s.kind) {
                MatchSeatKind.LOCAL_HUMAN -> Unit
                MatchSeatKind.AI -> {
                    if (s.ordinalInKind != aiOrd) mutableSeats[i] = s.copy(ordinalInKind = aiOrd)
                    aiOrd++
                }
                MatchSeatKind.REMOTE_HUMAN -> {
                    if (s.ordinalInKind != remoteOrd) mutableSeats[i] = s.copy(ordinalInKind = remoteOrd)
                    remoteOrd++
                }
            }
        }
    }

    /** Cycles seat [seatIndex] to the next color in the 10-color palette, swapping on collision. */
    fun cycleSeatColor(seatIndex: Int) {
        if (seatIndex !in mutableSeats.indices) return
        val nextColor = (mutableSeats[seatIndex].colorIndex + 1) % MatchSeatConfig.MAX_PALETTE_COLORS
        assignSeatColor(seatIndex, nextColor)
    }

    /** Assigns [targetColorIndex] (`0..9`) to [seatIndex], automatically swapping with any current holder. */
    fun assignSeatColor(seatIndex: Int, targetColorIndex: Int) {
        if (seatIndex !in mutableSeats.indices) return
        val normColor = Math.floorMod(targetColorIndex, MatchSeatConfig.MAX_PALETTE_COLORS)
        val current = mutableSeats[seatIndex]
        if (current.colorIndex == normColor) return
        seatsCustomized = true
        val holderIdx = mutableSeats.indexOfFirst { it.colorIndex == normColor }
        mutableSeats[seatIndex] = current.copy(colorIndex = normColor)
        if (holderIdx >= 0 && holderIdx != seatIndex) {
            mutableSeats[holderIdx] = mutableSeats[holderIdx].copy(colorIndex = current.colorIndex)
        }
    }

    /** Assigns [targetSpawnPoint] (`1..availableSpawnCount`) to [seatIndex], swapping with any current holder. */
    fun assignSeatSpawnPoint(seatIndex: Int, targetSpawnPoint: Int) {
        if (seatIndex !in mutableSeats.indices) return
        val clamped = targetSpawnPoint.coerceIn(1, availableSpawnCount)
        val current = mutableSeats[seatIndex]
        if (current.spawnPoint == clamped) return
        seatsCustomized = true
        val holderIdx = mutableSeats.indexOfFirst { it.spawnPoint == clamped }
        mutableSeats[seatIndex] = current.copy(spawnPoint = clamped)
        if (holderIdx >= 0 && holderIdx != seatIndex) {
            mutableSeats[holderIdx] = mutableSeats[holderIdx].copy(spawnPoint = current.spawnPoint)
        }
    }

    fun assignSeatTeam(seatIndex: Int, teamIndex: Int) {
        if (seatIndex !in mutableSeats.indices) return
        seatsCustomized = true
        val clamped = Math.floorMod(teamIndex, MatchSeatConfig.MAX_TEAMS)
        mutableSeats[seatIndex] = mutableSeats[seatIndex].copy(teamIndex = clamped)
    }

    fun assignSeatAiDifficulty(seatIndex: Int, difficulty: SkirmishAiDifficulty) {
        if (seatIndex !in mutableSeats.indices) return
        val cur = mutableSeats[seatIndex]
        if (cur.kind == MatchSeatKind.AI) {
            seatsCustomized = true
            mutableSeats[seatIndex] = cur.copy(aiDifficulty = difficulty)
        }
    }

    /** `混战` (FFA): assigns each active seat its own distinct team (`A, B, C...`). */
    fun applyFreeForAllTeams() {
        seatsCustomized = true
        for (i in mutableSeats.indices) {
            mutableSeats[i] = mutableSeats[i].copy(teamIndex = i % MatchSeatConfig.MAX_TEAMS)
        }
    }

    /** `双队平分` (2 Teams): splits active seats evenly between Team `A` (`0`) and Team `B` (`1`). */
    fun applyTwoTeamsSplit() {
        seatsCustomized = true
        val firstHalfCount = (mutableSeats.size + 1) / 2
        for (i in mutableSeats.indices) {
            val team = if (i < firstHalfCount) 0 else 1
            mutableSeats[i] = mutableSeats[i].copy(teamIndex = team)
        }
    }

    /** Freezes current lobby state into an immutable [SkirmishLaunchConfig] snapshot. */
    fun buildLaunchConfig(): SkirmishLaunchConfig = SkirmishLaunchConfig(
        mapId = selectedMap.id,
        mapSlug = selectedMap.slug,
        mapDisplayName = selectedMap.localizedTitle(),
        tmxPath = selectedMap.tmxPath,
        catalogSceneId = selectedMap.catalogSceneId,
        maxSpawns = availableSpawnCount,
        winMode = if (freeSandboxMode) SkirmishWinMode.SANDBOX else winMode,
        winConditionFallbackNoticeKey = if (freeSandboxMode) null else winConditionFallbackNoticeKey,
        fogMode = fogMode,
        startingCredits = startingCredits,
        moreRules = moreRules,
        fastBuild = fastBuild,
        isFreeSandbox = freeSandboxMode,
        seats = mutableSeats.toList(),
    )
}
