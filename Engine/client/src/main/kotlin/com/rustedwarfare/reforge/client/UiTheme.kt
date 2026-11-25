package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.rustedwarfare.reforge.core.content.StructuredNumberLexemes
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.ParseException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Lightweight, immutable in-game HUD visual style theme (ADR-014).
 *
 * Strictly governs visual presentation (colors, decorative metrics, HUD icon brightness tuning,
 * and optional command icon/font/nine-patch resource paths). Never alters HUD section organization,
 * information set, interaction state machines, or touch hit-target safety floors.
 */
data class UiThemeColors(
    val rail: Color,
    val solid: Color,
    val cell: Color,
    val cellHover: Color,
    val cellFlash: Color,
    val consoleCellHover: Color,
    val well: Color,
    val iconWell: Color,
    val line: Color,
    val popupBorder: Color,
    val hair: Color,
    val text: Color,
    val dim: Color,
    val value: Color,
    val bone: Color,
    val boneHi: Color,
    val ink: Color,
    val noteInk: Color,
    val disabledText: Color,
    val disabledIcon: Color,
    val disabledUnitTint: Color,
    val descText: Color,
    val rust: Color,
    val amber: Color,
    val foe: Color,
    val scrim: Color,
    val keyShadow: Color,
    val scrollTrack: Color,
    val queueProgress: Color,
    val queueCountBadgeBg: Color,
    val queueCountBadgeText: Color,
    val queueCountBadgeStroke: Color,
    val groupEmptyBg: Color,
    val groupHoverBg: Color,
    val debugBg: Color,
    val debugText: Color,
    val debugLegendHard: Color,
    val debugLegendStalled: Color,
    val debugLegendDetail: Color,
)

data class UiThemeMetrics(
    val keyRadius: Float,
    val keyShadowHeight: Float,
    val commonKeyShadowHeight: Float,
    val queueBadgeOffsetX: Float,
    val queueBadgeOffsetY: Float,
    val queueBadgeStrokeWidth: Float,
)

data class UiUnitIconTuning(
    val gamma: Float,
    val brightnessLift: Float,
    val saturationScale: Float,
    val outlineLumaThreshold: Float,
) {
    val isIdentity: Boolean
        get() = kotlin.math.abs(gamma - 1f) < 1e-4f &&
            kotlin.math.abs(brightnessLift) < 1e-4f &&
            kotlin.math.abs(saturationScale - 1f) < 1e-4f

    companion object {
        val IDENTITY = UiUnitIconTuning(
            gamma = 1f,
            brightnessLift = 0f,
            saturationScale = 1f,
            outlineLumaThreshold = 0.14f,
        )
    }
}

data class UiThemeAssets(
    val commandIconsJson: String,
    val commandIconsPng: String,
    val fontRegular: String,
    val fontBold: String,
    val fontMono: String,
    val panelNinePatch: String? = null,
    val keyNinePatch: String? = null,
)

data class UiTheme(
    val id: String,
    val colors: UiThemeColors,
    val metrics: UiThemeMetrics,
    val unitIconTuning: UiUnitIconTuning,
    val assets: UiThemeAssets,
    val appliedLayers: List<String> = listOf(id),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val DEFAULT_RESOURCE_PATH: String = "ui/theme.json"

        /** Built-in constructivist default loaded from `reforge:base` classpath resource `ui/theme.json`. */
        val DEFAULT: UiTheme by lazy { loadBuiltInDefault() }

        fun loadBuiltInDefault(): UiTheme {
            val stream = requireNotNull(UiTheme::class.java.getResourceAsStream("/$DEFAULT_RESOURCE_PATH")) {
                "UI_THEME_INVALID: /$DEFAULT_RESOURCE_PATH: Missing built-in default theme"
            }
            val bytes = stream.use { it.readBytes() }
            val patch = UiThemePatch.read(bytes, DEFAULT_RESOURCE_PATH, requireFull = true)
            return UiThemeComposer.compose(basePatch = patch, overridesHighToLow = emptyList())
        }
    }
}

/** Provider interface passed into HUD components so active theme swaps cleanly without static hardcoding. */
fun interface UiThemeProvider {
    fun currentTheme(): UiTheme
}

/**
 * Resolves theme-declared asset paths (`ui/icons/...`, `ui/fonts/...`, etc.) across active
 * resource pack roots (high-to-low) before falling back to bundled classpath resources.
 */
class UiThemeAssetResolver(
    packRootsHighToLow: List<Path> = emptyList(),
    private val classpathResolver: (String) -> FileHandle = { path -> Gdx.files.classpath(path) },
) {
    val packRoots: List<Path> = packRootsHighToLow.map { it.toAbsolutePath().normalize() }

    fun resolve(relativePath: String): FileHandle {
        val hit = resolveOverridePath(relativePath)
        return if (hit != null) FileHandle(hit.toFile()) else classpathResolver(relativePath)
    }

    /** Pure JVM inspection helper (no GL / Gdx.files required) for testing override resolution order. */
    fun resolveOverridePath(relativePath: String): Path? {
        validateRelativeAssetPath(relativePath, "asset")
        for (root in packRoots) {
            val hit = resolveSafeRegularFile(root, relativePath, strictError = false)
            if (hit != null) return hit
        }
        return null
    }

    override fun equals(other: Any?): Boolean =
        other is UiThemeAssetResolver && packRoots == other.packRoots

    override fun hashCode(): Int = packRoots.hashCode()
}

private fun resolveSafeRegularFile(root: Path, relativePath: String, strictError: Boolean): Path? {
    val normRoot = root.toAbsolutePath().normalize()
    if (!Files.isDirectory(normRoot, NOFOLLOW_LINKS) || Files.isSymbolicLink(normRoot)) {
        if (strictError) throw IllegalArgumentException("UI_THEME_INVALID: $normRoot: Expected ordinary pack root directory")
        return null
    }
    val realRoot = normRoot.toRealPath()
    val candidate = realRoot.resolve(relativePath).normalize()
    if (!candidate.startsWith(realRoot) || candidate == realRoot) {
        if (strictError) throw IllegalArgumentException("UI_THEME_INVALID: $candidate: Escapes pack root")
        return null
    }
    var cursor = realRoot
    for (part in realRoot.relativize(candidate)) {
        cursor = cursor.resolve(part)
        if (Files.notExists(cursor, NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(cursor) || cursor.toRealPath() != cursor) {
            if (strictError) throw IllegalArgumentException("UI_THEME_INVALID: $cursor: Linked theme file or directory is not allowed")
            return null
        }
    }
    if (!Files.isRegularFile(candidate, NOFOLLOW_LINKS)) {
        if (strictError) throw IllegalArgumentException("UI_THEME_INVALID: $candidate: Expected ordinary regular file")
        return null
    }
    return candidate
}

/**
 * Partial declarative style override parsed from a pack's `ui/theme.json`.
 * Every field is optional when acting as an overlay; unknown keys, bad hex colors,
 * out-of-range numbers, or unsafe paths are rejected with `UI_THEME_INVALID`.
 */
data class UiThemePatch(
    val id: String?,
    val colors: Map<String, Color>,
    val metrics: Map<String, Float>,
    val unitIconTuning: Map<String, Float>,
    val assets: Map<String, String?>,
) {
    companion object {
        private val TOP_KEYS = setOf("schemaVersion", "id", "colors", "metrics", "unitIconTuning", "assets")

        internal val COLOR_KEYS = setOf(
            "rail", "solid", "cell", "cellHover", "cellFlash", "consoleCellHover",
            "well", "iconWell", "line", "popupBorder", "hair",
            "text", "dim", "value", "bone", "boneHi", "ink", "noteInk",
            "disabledText", "disabledIcon", "disabledUnitTint", "descText",
            "rust", "amber", "foe", "scrim", "keyShadow", "scrollTrack",
            "queueProgress", "queueCountBadgeBg", "queueCountBadgeText", "queueCountBadgeStroke",
            "groupEmptyBg", "groupHoverBg",
            "debugBg", "debugText", "debugLegendHard", "debugLegendStalled", "debugLegendDetail",
        )

        internal val METRIC_BOUNDS: Map<String, ClosedFloatingPointRange<Float>> = mapOf(
            "keyRadius" to 0f..12f,
            "keyShadowHeight" to 0f..6f,
            "commonKeyShadowHeight" to 0f..6f,
            "queueBadgeOffsetX" to -4f..8f,
            "queueBadgeOffsetY" to -4f..8f,
            "queueBadgeStrokeWidth" to 0f..3f,
        )

        internal val TUNING_BOUNDS: Map<String, ClosedFloatingPointRange<Float>> = mapOf(
            "gamma" to 0.5f..1.5f,
            "brightnessLift" to -0.25f..0.40f,
            "saturationScale" to 0.5f..1.5f,
            "outlineLumaThreshold" to 0.0f..0.35f,
        )

        internal val REQUIRED_ASSET_KEYS = setOf(
            "commandIconsJson", "commandIconsPng", "fontRegular", "fontBold", "fontMono",
        )
        internal val OPTIONAL_ASSET_KEYS = setOf(
            "panelNinePatch", "keyNinePatch",
        )
        internal val ALL_ASSET_KEYS = REQUIRED_ASSET_KEYS + OPTIONAL_ASSET_KEYS

        fun read(bytes: ByteArray, source: String, requireFull: Boolean = false): UiThemePatch {
            fun bad(field: String, reason: String): Nothing =
                throw IllegalArgumentException("UI_THEME_INVALID: $source / $field: $reason")

            val text = try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                bad("encoding", "Expected UTF-8 JSON")
            }
            val parsed = try {
                JsonValue.readJSON(text)
            } catch (failure: ParseException) {
                bad("syntax", "${failure.line}:${failure.column}: ${failure.message}")
            }
            if (!parsed.isObject) bad("root", "Expected a JSON object")
            val root = parsed.asObject()

            val seen = mutableSetOf<String>()
            for (member in root) {
                if (!seen.add(member.name)) bad(member.name, "Duplicate member")
                if (member.name !in TOP_KEYS) bad(member.name, "Unknown theme member")
            }
            val numbers = try {
                StructuredNumberLexemes.read(text, parsed)
            } catch (failure: IllegalArgumentException) {
                bad("syntax", failure.message ?: "Invalid numeric token")
            }

            val verVal = root.get("schemaVersion") ?: bad("schemaVersion", "Required integer 1")
            val verToken = numbers[verVal]
            if (!verVal.isNumber || verToken != UiTheme.SCHEMA_VERSION.toString()) {
                bad("schemaVersion", "Expected supported integer ${UiTheme.SCHEMA_VERSION}")
            }

            val idVal = root.get("id")
            val id = if (idVal != null) {
                if (!idVal.isString || idVal.asString().isBlank() || idVal.asString().any(Char::isISOControl)) {
                    bad("id", "Expected non-blank text without control characters")
                }
                idVal.asString()
            } else {
                if (requireFull) bad("id", "Required theme id")
                null
            }

            val colors = LinkedHashMap<String, Color>()
            root.get("colors")?.let { cVal ->
                if (!cVal.isObject) bad("colors", "Expected an object")
                val cObj = cVal.asObject()
                val cSeen = mutableSetOf<String>()
                for (m in cObj) {
                    if (!cSeen.add(m.name)) bad("colors.${m.name}", "Duplicate color key")
                    if (m.name !in COLOR_KEYS) bad("colors.${m.name}", "Unknown color token")
                    if (!m.value.isString) bad("colors.${m.name}", "Expected #RRGGBB or #RRGGBBAA hex string")
                    colors[m.name] = parseHexColor(m.value.asString()) { reason -> bad("colors.${m.name}", reason) }
                }
            }
            if (requireFull) {
                for (k in COLOR_KEYS) if (k !in colors) bad("colors.$k", "Missing required base color token")
            }

            fun parseBoundedNumbers(
                sectionName: String,
                obj: JsonObject,
                bounds: Map<String, ClosedFloatingPointRange<Float>>,
            ): Map<String, Float> {
                val out = LinkedHashMap<String, Float>()
                val mSeen = mutableSetOf<String>()
                for (m in obj) {
                    if (!mSeen.add(m.name)) bad("$sectionName.${m.name}", "Duplicate key")
                    val range = bounds[m.name] ?: bad("$sectionName.${m.name}", "Unknown key")
                    if (!m.value.isNumber) bad("$sectionName.${m.name}", "Expected a finite number")
                    val f = m.value.asDouble().toFloat()
                    if (!f.isFinite() || f !in range) {
                        bad("$sectionName.${m.name}", "Value $f out of allowed range ${range.start}..${range.endInclusive}")
                    }
                    out[m.name] = f
                }
                if (requireFull) {
                    for (k in bounds.keys) if (k !in out) bad("$sectionName.$k", "Missing required base key")
                }
                return out
            }

            val metrics = root.get("metrics")?.let { mVal ->
                if (!mVal.isObject) bad("metrics", "Expected an object")
                parseBoundedNumbers("metrics", mVal.asObject(), METRIC_BOUNDS)
            } ?: run {
                if (requireFull) bad("metrics", "Missing required metrics section")
                emptyMap()
            }

            val tuning = root.get("unitIconTuning")?.let { tVal ->
                if (!tVal.isObject) bad("unitIconTuning", "Expected an object")
                parseBoundedNumbers("unitIconTuning", tVal.asObject(), TUNING_BOUNDS)
            } ?: run {
                if (requireFull) bad("unitIconTuning", "Missing required unitIconTuning section")
                emptyMap()
            }

            val assets = LinkedHashMap<String, String?>()
            root.get("assets")?.let { aVal ->
                if (!aVal.isObject) bad("assets", "Expected an object")
                val aObj = aVal.asObject()
                val aSeen = mutableSetOf<String>()
                for (m in aObj) {
                    if (!aSeen.add(m.name)) bad("assets.${m.name}", "Duplicate asset key")
                    if (m.name !in ALL_ASSET_KEYS) bad("assets.${m.name}", "Unknown asset key")
                    if (m.value.isNull) {
                        if (m.name in REQUIRED_ASSET_KEYS) bad("assets.${m.name}", "Required asset path cannot be null")
                        assets[m.name] = null
                    } else if (m.value.isString) {
                        val rel = m.value.asString()
                        validateRelativeAssetPath(rel, "assets.${m.name}", source)
                        assets[m.name] = rel
                    } else {
                        bad("assets.${m.name}", "Expected relative asset path string")
                    }
                }
            }
            if (requireFull) {
                for (k in REQUIRED_ASSET_KEYS) if (k !in assets) bad("assets.$k", "Missing required base asset key")
            }

            return UiThemePatch(id = id, colors = colors, metrics = metrics, unitIconTuning = tuning, assets = assets)
        }

        internal fun parseHexColor(raw: String, onError: (String) -> Nothing): Color {
            val atIdx = raw.indexOf('@')
            val hexPart = if (atIdx >= 0) raw.substring(0, atIdx) else raw
            val alphaPart = if (atIdx >= 0) raw.substring(atIdx + 1) else null
            if (!hexPart.startsWith("#") || (hexPart.length != 7 && (alphaPart != null || hexPart.length != 9))) {
                onError("Expected #RRGGBB, #RRGGBBAA, or #RRGGBB@alpha, got '$raw'")
            }
            val hex = hexPart.substring(1)
            if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                onError("Invalid hex digits in '$raw'")
            }
            val r = hex.substring(0, 2).toInt(16) / 255f
            val g = hex.substring(2, 4).toInt(16) / 255f
            val b = hex.substring(4, 6).toInt(16) / 255f
            val a = when {
                alphaPart != null -> {
                    val parsed = alphaPart.toFloatOrNull()
                    if (parsed == null || !parsed.isFinite() || parsed !in 0f..1f) {
                        onError("Invalid alpha '$alphaPart' in '$raw'")
                    }
                    parsed
                }
                hex.length == 8 -> hex.substring(6, 8).toInt(16) / 255f
                else -> 1f
            }
            return Color(r, g, b, a)
        }
    }
}

private fun validateRelativeAssetPath(path: String, field: String, source: String = "theme") {
    if (path.isBlank() ||
        path.startsWith("/") ||
        path.any { it == ':' || it == '\\' || it.isISOControl() } ||
        path.split('/').any { it.isBlank() || it == "." || it == ".." }
    ) {
        throw IllegalArgumentException("UI_THEME_INVALID: $source / $field: Expected safe relative path, got '$path'")
    }
}

/**
 * Pure Kotlin layered partial theme composer (ADR-014).
 *
 * Follows the exact same high-to-low resource pack precedence as [ResourceImageComposer]:
 * highest-priority user pack -> lower-priority user packs -> built-in `reforge:base`.
 * Any omitted field automatically falls back layer-by-layer to the built-in constructivist default.
 */
object UiThemeComposer {
    const val PACK_THEME_FILE: String = "ui/theme.json"
    const val MAX_THEME_BYTES: Int = 512 * 1024

    /**
     * Strictly resolves a pack-relative file without following any symbolic link along any path segment.
     * Returns null only when the relative path does not exist inside [root]; throws `UI_THEME_INVALID`
     * if [root] or any existing segment is a symlink, escapes [root], or is not an ordinary regular file.
     */
    fun resolveStrictPackFile(root: Path, relativePath: String): Path? {
        validateRelativeAssetPath(relativePath, "asset", root.toString())
        return resolveSafeRegularFile(root, relativePath, strictError = true)
    }

    fun compose(
        overridesHighToLow: List<UiThemePatch>,
        base: UiTheme = UiTheme.DEFAULT,
    ): UiTheme {
        val basePatch = toPatch(base)
        return compose(basePatch = basePatch, overridesHighToLow = overridesHighToLow)
    }

    fun compose(
        basePatch: UiThemePatch,
        overridesHighToLow: List<UiThemePatch>,
    ): UiTheme {
        val mergedColors = LinkedHashMap<String, Color>(basePatch.colors)
        val mergedMetrics = LinkedHashMap<String, Float>(basePatch.metrics)
        val mergedTuning = LinkedHashMap<String, Float>(basePatch.unitIconTuning)
        val mergedAssets = LinkedHashMap<String, String?>(basePatch.assets)
        val layers = mutableListOf(basePatch.id ?: "reforge:base")

        // Apply low-to-high so higher priority overrides win cleanly.
        for (patch in overridesHighToLow.asReversed()) {
            mergedColors.putAll(patch.colors)
            mergedMetrics.putAll(patch.metrics)
            mergedTuning.putAll(patch.unitIconTuning)
            mergedAssets.putAll(patch.assets)
            patch.id?.let { layers += it }
        }

        fun c(k: String): Color = requireNotNull(mergedColors[k]) { "UI_THEME_INVALID: missing color '$k'" }.cpy()
        fun m(k: String): Float = requireNotNull(mergedMetrics[k]) { "UI_THEME_INVALID: missing metric '$k'" }
        fun t(k: String): Float = requireNotNull(mergedTuning[k]) { "UI_THEME_INVALID: missing tuning '$k'" }
        fun a(k: String): String = requireNotNull(mergedAssets[k]) { "UI_THEME_INVALID: missing asset '$k'" }

        val effectiveId = overridesHighToLow.firstNotNullOfOrNull { it.id } ?: (basePatch.id ?: "reforge:base")
        return UiTheme(
            id = effectiveId,
            colors = UiThemeColors(
                rail = c("rail"),
                solid = c("solid"),
                cell = c("cell"),
                cellHover = c("cellHover"),
                cellFlash = c("cellFlash"),
                consoleCellHover = c("consoleCellHover"),
                well = c("well"),
                iconWell = c("iconWell"),
                line = c("line"),
                popupBorder = c("popupBorder"),
                hair = c("hair"),
                text = c("text"),
                dim = c("dim"),
                value = c("value"),
                bone = c("bone"),
                boneHi = c("boneHi"),
                ink = c("ink"),
                noteInk = c("noteInk"),
                disabledText = c("disabledText"),
                disabledIcon = c("disabledIcon"),
                disabledUnitTint = c("disabledUnitTint"),
                descText = c("descText"),
                rust = c("rust"),
                amber = c("amber"),
                foe = c("foe"),
                scrim = c("scrim"),
                keyShadow = c("keyShadow"),
                scrollTrack = c("scrollTrack"),
                queueProgress = c("queueProgress"),
                queueCountBadgeBg = c("queueCountBadgeBg"),
                queueCountBadgeText = c("queueCountBadgeText"),
                queueCountBadgeStroke = c("queueCountBadgeStroke"),
                groupEmptyBg = c("groupEmptyBg"),
                groupHoverBg = c("groupHoverBg"),
                debugBg = c("debugBg"),
                debugText = c("debugText"),
                debugLegendHard = c("debugLegendHard"),
                debugLegendStalled = c("debugLegendStalled"),
                debugLegendDetail = c("debugLegendDetail"),
            ),
            metrics = UiThemeMetrics(
                keyRadius = m("keyRadius"),
                keyShadowHeight = m("keyShadowHeight"),
                commonKeyShadowHeight = m("commonKeyShadowHeight"),
                queueBadgeOffsetX = m("queueBadgeOffsetX"),
                queueBadgeOffsetY = m("queueBadgeOffsetY"),
                queueBadgeStrokeWidth = m("queueBadgeStrokeWidth"),
            ),
            unitIconTuning = UiUnitIconTuning(
                gamma = t("gamma"),
                brightnessLift = t("brightnessLift"),
                saturationScale = t("saturationScale"),
                outlineLumaThreshold = t("outlineLumaThreshold"),
            ),
            assets = UiThemeAssets(
                commandIconsJson = a("commandIconsJson"),
                commandIconsPng = a("commandIconsPng"),
                fontRegular = a("fontRegular"),
                fontBold = a("fontBold"),
                fontMono = a("fontMono"),
                panelNinePatch = mergedAssets["panelNinePatch"],
                keyNinePatch = mergedAssets["keyNinePatch"],
            ),
            appliedLayers = layers.asReversed(),
        )
    }

    /**
     * Loads optional `ui/theme.json` overlays from enabled resource pack directories (high-to-low)
     * and composes them over [base]. Packs without `ui/theme.json` are skipped cleanly.
     */
    fun composeFromPackDirectories(
        packRootsHighToLow: List<Path>,
        base: UiTheme = UiTheme.DEFAULT,
    ): UiTheme {
        val patches = mutableListOf<UiThemePatch>()
        for (root in packRootsHighToLow) {
            val themeFile = resolveSafeRegularFile(root, PACK_THEME_FILE, strictError = true) ?: continue
            val bytes = Files.newInputStream(themeFile).use { it.readNBytes(MAX_THEME_BYTES + 1) }
            if (bytes.size > MAX_THEME_BYTES) {
                throw IllegalArgumentException("UI_THEME_INVALID: $themeFile: Theme exceeds 512 KiB limit")
            }
            patches += UiThemePatch.read(bytes, themeFile.toString(), requireFull = false)
        }
        return compose(overridesHighToLow = patches, base = base)
    }

    private fun toPatch(theme: UiTheme): UiThemePatch = UiThemePatch(
        id = theme.id,
        colors = linkedMapOf(
            "rail" to theme.colors.rail,
            "solid" to theme.colors.solid,
            "cell" to theme.colors.cell,
            "cellHover" to theme.colors.cellHover,
            "cellFlash" to theme.colors.cellFlash,
            "consoleCellHover" to theme.colors.consoleCellHover,
            "well" to theme.colors.well,
            "iconWell" to theme.colors.iconWell,
            "line" to theme.colors.line,
            "popupBorder" to theme.colors.popupBorder,
            "hair" to theme.colors.hair,
            "text" to theme.colors.text,
            "dim" to theme.colors.dim,
            "value" to theme.colors.value,
            "bone" to theme.colors.bone,
            "boneHi" to theme.colors.boneHi,
            "ink" to theme.colors.ink,
            "noteInk" to theme.colors.noteInk,
            "disabledText" to theme.colors.disabledText,
            "disabledIcon" to theme.colors.disabledIcon,
            "disabledUnitTint" to theme.colors.disabledUnitTint,
            "descText" to theme.colors.descText,
            "rust" to theme.colors.rust,
            "amber" to theme.colors.amber,
            "foe" to theme.colors.foe,
            "scrim" to theme.colors.scrim,
            "keyShadow" to theme.colors.keyShadow,
            "scrollTrack" to theme.colors.scrollTrack,
            "queueProgress" to theme.colors.queueProgress,
            "queueCountBadgeBg" to theme.colors.queueCountBadgeBg,
            "queueCountBadgeText" to theme.colors.queueCountBadgeText,
            "queueCountBadgeStroke" to theme.colors.queueCountBadgeStroke,
            "groupEmptyBg" to theme.colors.groupEmptyBg,
            "groupHoverBg" to theme.colors.groupHoverBg,
            "debugBg" to theme.colors.debugBg,
            "debugText" to theme.colors.debugText,
            "debugLegendHard" to theme.colors.debugLegendHard,
            "debugLegendStalled" to theme.colors.debugLegendStalled,
            "debugLegendDetail" to theme.colors.debugLegendDetail,
        ),
        metrics = linkedMapOf(
            "keyRadius" to theme.metrics.keyRadius,
            "keyShadowHeight" to theme.metrics.keyShadowHeight,
            "commonKeyShadowHeight" to theme.metrics.commonKeyShadowHeight,
            "queueBadgeOffsetX" to theme.metrics.queueBadgeOffsetX,
            "queueBadgeOffsetY" to theme.metrics.queueBadgeOffsetY,
            "queueBadgeStrokeWidth" to theme.metrics.queueBadgeStrokeWidth,
        ),
        unitIconTuning = linkedMapOf(
            "gamma" to theme.unitIconTuning.gamma,
            "brightnessLift" to theme.unitIconTuning.brightnessLift,
            "saturationScale" to theme.unitIconTuning.saturationScale,
            "outlineLumaThreshold" to theme.unitIconTuning.outlineLumaThreshold,
        ),
        assets = linkedMapOf(
            "commandIconsJson" to theme.assets.commandIconsJson,
            "commandIconsPng" to theme.assets.commandIconsPng,
            "fontRegular" to theme.assets.fontRegular,
            "fontBold" to theme.assets.fontBold,
            "fontMono" to theme.assets.fontMono,
            "panelNinePatch" to theme.assets.panelNinePatch,
            "keyNinePatch" to theme.assets.keyNinePatch,
        ),
    )
}
