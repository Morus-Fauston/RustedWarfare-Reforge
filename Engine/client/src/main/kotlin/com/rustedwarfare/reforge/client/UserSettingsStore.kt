package com.rustedwarfare.reforge.client

import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.Stringify
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Built-in UI style themes shipped in Phase VIII pt1 (UI-P004-001 & UI Supplement §0, §2, §7).
 *
 * Note: The NASA-Punk (`nasa`) concept theme is intentionally excluded per maintainer directive.
 */
enum class BuiltInUiThemeId(
    val code: String,
    val themeId: String,
    val classpathResource: String,
) {
    DARK("dark", "reforge:constructivist-default", "ui/theme.json"),
    WHITE("white", "reforge:polar-white", "ui/themes/white.json"),
    BONE("bone", "reforge:constructivist-bone", "ui/themes/bone.json"),
    ;

    companion object {
        fun fromCodeOrDefault(raw: String?, default: BuiltInUiThemeId = DARK): BuiltInUiThemeId {
            val normalized = raw?.trim()?.lowercase() ?: return default
            return entries.firstOrNull {
                it.code == normalized || it.themeId.lowercase() == normalized
            } ?: default
        }
    }
}

/**
 * Immutable cross-platform user preferences persisted to platform-private `settings.json`
 * (Phase VIII Spec §7.2 & UI Supplement §7–§8).
 */
data class UserSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** First-launch default is Simplified Chinese, never system locale (UI Supplement §8.1). */
    val language: UiLanguage = UiLanguage.ZH,
    val masterVolume: Float = 1.0f,
    val sfxVolume: Float = 1.0f,
    val muted: Boolean = false,
    /** Manual UI scale multiplier layered on top of [UiScale] nonlinear baseline (`0.75..1.50`). */
    val uiScaleMultiplier: Float = 1.0f,
    val touchCameraJoystick: Boolean = true,
    val cameraPanSensitivity: Float = 1.0f,
    val cameraZoomSensitivity: Float = 1.0f,
    val windowEdgeScroll: Boolean = true,
    val themeId: BuiltInUiThemeId = BuiltInUiThemeId.DARK,
    /** High-to-low resource pack IDs; `reforge:base` is always pinned at the bottom. */
    val enabledResourcePacks: List<String> = listOf(BASE_RESOURCE_PACK_ID),
    /** Known external resource pack manifest paths keyed by pack ID. */
    val knownResourcePackPaths: Map<String, String> = emptyMap(),
    /** High-to-low content pack IDs; base content pack is pinned at the bottom when configured. */
    val enabledContentPacks: List<String> = listOf(DEFAULT_BASE_CONTENT_PACK_ID),
    /** Known external content pack directory/manifest paths keyed by pack ID. */
    val knownContentPackPaths: Map<String, String> = emptyMap(),
) {
    fun normalized(): UserSettings {
        val cleanRes = normalizePackStack(enabledResourcePacks, BASE_RESOURCE_PACK_ID)
        val cleanMod = normalizePackStack(enabledContentPacks, DEFAULT_BASE_CONTENT_PACK_ID)
        return copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            masterVolume = masterVolume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1.0f,
            sfxVolume = sfxVolume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1.0f,
            uiScaleMultiplier = uiScaleMultiplier.takeIf(Float::isFinite)?.coerceIn(MIN_UI_SCALE_MULTIPLIER, MAX_UI_SCALE_MULTIPLIER) ?: 1.0f,
            cameraPanSensitivity = cameraPanSensitivity.takeIf(Float::isFinite)?.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) ?: 1.0f,
            cameraZoomSensitivity = cameraZoomSensitivity.takeIf(Float::isFinite)?.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) ?: 1.0f,
            enabledResourcePacks = cleanRes,
            knownResourcePackPaths = knownResourcePackPaths.filter { (k, v) -> k.isNotBlank() && v.isNotBlank() },
            enabledContentPacks = cleanMod,
            knownContentPackPaths = knownContentPackPaths.filter { (k, v) -> k.isNotBlank() && v.isNotBlank() },
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val BASE_RESOURCE_PACK_ID: String = "reforge:base"
        const val DEFAULT_BASE_CONTENT_PACK_ID: String = "phase-seven.json"
        const val MIN_UI_SCALE_MULTIPLIER: Float = 0.75f
        const val MAX_UI_SCALE_MULTIPLIER: Float = 1.50f
        const val MIN_SENSITIVITY: Float = 0.25f
        const val MAX_SENSITIVITY: Float = 3.00f
        val STANDARD_UI_SCALE_PRESETS: List<Float> = listOf(0.85f, 1.00f, 1.10f, 1.25f)

        /** Ensures [pinnedBottomId] is always present exactly once as the last (lowest-priority) element. */
        fun normalizePackStack(rawHighToLow: List<String>, pinnedBottomId: String): List<String> {
            val result = ArrayList<String>(rawHighToLow.size + 1)
            val seen = HashSet<String>()
            for (raw in rawHighToLow) {
                val id = raw.trim()
                if (id.isNotEmpty() && id != pinnedBottomId && seen.add(id)) {
                    result.add(id)
                }
            }
            result.add(pinnedBottomId)
            return result
        }
    }
}

/**
 * Ephemeral command-line overrides applied on top of [UserSettings] for the current process only.
 * Never persisted back to `settings.json` (Phase VIII Spec §7.2).
 */
data class UserSettingsCliOverrides(
    val language: UiLanguage? = null,
    val muted: Boolean? = null,
    val uiScaleMultiplier: Float? = null,
    val touchCameraJoystick: Boolean? = null,
    val windowEdgeScroll: Boolean? = null,
    val themeId: BuiltInUiThemeId? = null,
    val enabledResourcePacks: List<String>? = null,
    val enabledContentPacks: List<String>? = null,
) {
    val isEmpty: Boolean
        get() = language == null &&
            muted == null &&
            uiScaleMultiplier == null &&
            touchCameraJoystick == null &&
            windowEdgeScroll == null &&
            themeId == null &&
            enabledResourcePacks == null &&
            enabledContentPacks == null

    fun applyTo(base: UserSettings): UserSettings = base.copy(
        language = language ?: base.language,
        muted = muted ?: base.muted,
        uiScaleMultiplier = uiScaleMultiplier ?: base.uiScaleMultiplier,
        touchCameraJoystick = touchCameraJoystick ?: base.touchCameraJoystick,
        windowEdgeScroll = windowEdgeScroll ?: base.windowEdgeScroll,
        themeId = themeId ?: base.themeId,
        enabledResourcePacks = enabledResourcePacks ?: base.enabledResourcePacks,
        enabledContentPacks = enabledContentPacks ?: base.enabledContentPacks,
    ).normalized()
}

/**
 * Atomic JSON store for [UserSettings] in platform-private user directories.
 *
 * Guarantees:
 * - Never writes inside the repository unless an explicit isolated temp path is injected in tests.
 * - Corrupted, truncated, or schema-incompatible `settings.json` safely falls back to defaults
 *   (`language = ZH`, `themeId = DARK`) and records [lastLoadWarning].
 * - [cliOverrides] take precedence in [effective] without ever polluting persisted [saved] settings on disk.
 */
class UserSettingsStore(
    val file: Path,
    val cliOverrides: UserSettingsCliOverrides = UserSettingsCliOverrides(),
) {
    @Volatile
    var saved: UserSettings = UserSettings()
        private set

    @Volatile
    var lastLoadWarning: String? = null
        private set

    /** Read-only merged view where [cliOverrides] win over persisted [saved] settings. */
    val effective: UserSettings
        get() = cliOverrides.applyTo(saved)

    init {
        reload()
    }

    @Synchronized
    fun reload(): UserSettings {
        lastLoadWarning = null
        val target = file.toAbsolutePath().normalize()
        if (Files.notExists(target)) {
            saved = UserSettings().normalized()
            return effective
        }
        saved = try {
            val bytes = Files.newInputStream(target).use { it.readNBytes(MAX_SETTINGS_BYTES + 1) }
            require(bytes.size <= MAX_SETTINGS_BYTES) { "settings.json exceeds $MAX_SETTINGS_BYTES bytes" }
            parseJson(bytes)
        } catch (failure: Exception) {
            lastLoadWarning = "SETTINGS_FALLBACK_DEFAULT: $target: ${failure.message ?: failure.javaClass.simpleName}"
            UserSettings().normalized()
        }
        return effective
    }

    /**
     * Updates user settings and atomically persists them to [file], while preserving the original
     * persisted values of any fields currently overridden via [cliOverrides] so CLI flags never
     * leak into `settings.json`.
     */
    @Synchronized
    fun update(transform: (UserSettings) -> UserSettings): UserSettings {
        val currentSaved = saved
        val proposed = transform(effective).normalized()
        val toPersist = proposed.copy(
            language = if (cliOverrides.language != null) currentSaved.language else proposed.language,
            muted = if (cliOverrides.muted != null) currentSaved.muted else proposed.muted,
            uiScaleMultiplier = if (cliOverrides.uiScaleMultiplier != null) currentSaved.uiScaleMultiplier else proposed.uiScaleMultiplier,
            touchCameraJoystick = if (cliOverrides.touchCameraJoystick != null) currentSaved.touchCameraJoystick else proposed.touchCameraJoystick,
            windowEdgeScroll = if (cliOverrides.windowEdgeScroll != null) currentSaved.windowEdgeScroll else proposed.windowEdgeScroll,
            themeId = if (cliOverrides.themeId != null) currentSaved.themeId else proposed.themeId,
            enabledResourcePacks = if (cliOverrides.enabledResourcePacks != null) currentSaved.enabledResourcePacks else proposed.enabledResourcePacks,
            enabledContentPacks = if (cliOverrides.enabledContentPacks != null) currentSaved.enabledContentPacks else proposed.enabledContentPacks,
        ).normalized()
        writeAtomically(toPersist)
        saved = toPersist
        return effective
    }

    private fun writeAtomically(settings: UserSettings) {
        val target = file.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "settings.json must have a parent directory: $target" }
        Files.createDirectories(parent)
        val tmp = parent.resolve("settings-${UUID.randomUUID()}.tmp")
        try {
            val json = serializeJson(settings)
            Files.writeString(tmp, json, Charsets.UTF_8)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw IllegalStateException("USER_SETTINGS_WRITE_FAILED: $target: ${failure.message}", failure)
        }
    }

    companion object {
        const val MAX_SETTINGS_BYTES: Int = 256 * 1024
        const val SETTINGS_FILE_NAME: String = "settings.json"

        /** Resolves desktop user-private `settings.json` path (Windows `%LOCALAPPDATA%\RustedWarfare-Reforge\settings.json`). */
        fun desktopDefaultFile(
            localAppData: String? = System.getenv("LOCALAPPDATA"),
            userHome: String = System.getProperty("user.home"),
        ): Path {
            val base = if (!localAppData.isNullOrBlank()) {
                Path.of(localAppData)
            } else {
                Path.of(userHome).resolve("AppData/Local")
            }
            return base.resolve("RustedWarfare-Reforge").resolve(SETTINGS_FILE_NAME).toAbsolutePath().normalize()
        }

        internal fun serializeJson(settings: UserSettings): String {
            val s = settings.normalized()
            val obj = JsonObject()
                .add("schemaVersion", UserSettings.CURRENT_SCHEMA_VERSION)
                .add("language", s.language.code)
                .add("masterVolume", roundFloat(s.masterVolume))
                .add("sfxVolume", roundFloat(s.sfxVolume))
                .add("muted", s.muted)
                .add("uiScaleMultiplier", roundFloat(s.uiScaleMultiplier))
                .add("touchCameraJoystick", s.touchCameraJoystick)
                .add("cameraPanSensitivity", roundFloat(s.cameraPanSensitivity))
                .add("cameraZoomSensitivity", roundFloat(s.cameraZoomSensitivity))
                .add("windowEdgeScroll", s.windowEdgeScroll)
                .add("themeId", s.themeId.code)
                .add("enabledResourcePacks", toJsonArray(s.enabledResourcePacks))
                .add("knownResourcePackPaths", toJsonObject(s.knownResourcePackPaths))
                .add("enabledContentPacks", toJsonArray(s.enabledContentPacks))
                .add("knownContentPackPaths", toJsonObject(s.knownContentPackPaths))
            return obj.toString(Stringify.FORMATTED) + "\n"
        }

        internal fun parseJson(bytes: ByteArray): UserSettings {
            val text = try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("Invalid UTF-8 in settings.json", e)
            }
            val parsed = JsonValue.readJSON(text)
            require(parsed.isObject) { "Expected JSON object root" }
            val root = parsed.asObject()
            val verVal = root.get("schemaVersion") ?: throw IllegalArgumentException("Missing schemaVersion")
            require(verVal.isNumber && verVal.asInt() == UserSettings.CURRENT_SCHEMA_VERSION) {
                "Unsupported schemaVersion: $verVal"
            }

            fun optFloat(key: String, default: Float): Float {
                val v = root.get(key) ?: return default
                require(v.isNumber) { "Expected number for '$key'" }
                val f = v.asDouble().toFloat()
                require(f.isFinite()) { "Non-finite number for '$key'" }
                return f
            }

            fun optBoolean(key: String, default: Boolean): Boolean {
                val v = root.get(key) ?: return default
                require(v.isBoolean) { "Expected boolean for '$key'" }
                return v.asBoolean()
            }

            fun optStringList(key: String, default: List<String>): List<String> {
                val v = root.get(key) ?: return default
                require(v.isArray) { "Expected array for '$key'" }
                return v.asArray().map { item ->
                    require(item.isString) { "Expected string element in '$key'" }
                    item.asString()
                }
            }

            fun optStringMap(key: String): Map<String, String> {
                val v = root.get(key) ?: return emptyMap()
                require(v.isObject) { "Expected object for '$key'" }
                val out = LinkedHashMap<String, String>()
                for (member in v.asObject()) {
                    require(member.value.isString) { "Expected string value in '$key.${member.name}'" }
                    out[member.name] = member.value.asString()
                }
                return out
            }

            val language = root.get("language")?.let { lv ->
                require(lv.isString) { "Expected string for 'language'" }
                UiLanguage.fromCodeOrNull(lv.asString()) ?: throw IllegalArgumentException("Unknown language '${lv.asString()}'")
            } ?: UiLanguage.ZH

            val themeId = root.get("themeId")?.let { tv ->
                require(tv.isString) { "Expected string for 'themeId'" }
                val raw = tv.asString().trim().lowercase()
                require(raw != "nasa") { "Unsupported themeId '$raw'" }
                BuiltInUiThemeId.entries.firstOrNull { it.code == raw || it.themeId.lowercase() == raw }
                    ?: throw IllegalArgumentException("Unknown themeId '${tv.asString()}'")
            } ?: BuiltInUiThemeId.DARK

            return UserSettings(
                schemaVersion = UserSettings.CURRENT_SCHEMA_VERSION,
                language = language,
                masterVolume = optFloat("masterVolume", 1.0f),
                sfxVolume = optFloat("sfxVolume", 1.0f),
                muted = optBoolean("muted", false),
                uiScaleMultiplier = optFloat("uiScaleMultiplier", 1.0f),
                touchCameraJoystick = optBoolean("touchCameraJoystick", true),
                cameraPanSensitivity = optFloat("cameraPanSensitivity", 1.0f),
                cameraZoomSensitivity = optFloat("cameraZoomSensitivity", 1.0f),
                windowEdgeScroll = optBoolean("windowEdgeScroll", true),
                themeId = themeId,
                enabledResourcePacks = optStringList("enabledResourcePacks", listOf(UserSettings.BASE_RESOURCE_PACK_ID)),
                knownResourcePackPaths = optStringMap("knownResourcePackPaths"),
                enabledContentPacks = optStringList("enabledContentPacks", listOf(UserSettings.DEFAULT_BASE_CONTENT_PACK_ID)),
                knownContentPackPaths = optStringMap("knownContentPackPaths"),
            ).normalized()
        }

        private fun roundFloat(v: Float): Double = kotlin.math.round(v.toDouble() * 10000.0) / 10000.0

        private fun toJsonArray(items: List<String>): org.hjson.JsonArray = org.hjson.JsonArray().apply {
            items.forEach(::add)
        }

        private fun toJsonObject(map: Map<String, String>): JsonObject = JsonObject().apply {
            map.forEach { (k, v) -> add(k, v) }
        }
    }
}
