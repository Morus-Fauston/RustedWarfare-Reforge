package com.rustedwarfare.reforge.client

import org.hjson.JsonArray
import org.hjson.JsonObject
import org.hjson.JsonValue

/**
 * Declarative presentation variant option types supported in `pack.json` (ADR-014 & UI Supplement §7).
 *
 * Strictly limited to finite discrete choices ([ENUM] and [BOOLEAN]) so the importer can statically
 * validate and cryptographically hash every possible variant asset at import time (ADR-008) with zero scripts.
 */
enum class ResourcePackOptionType(val wireName: String) {
    ENUM("enum"),
    BOOLEAN("boolean"),
    ;

    companion object {
        fun fromWireOrNull(raw: String): ResourcePackOptionType? =
            entries.firstOrNull { it.wireName == raw }
    }
}

/**
 * Relative intra-pack overlay manifest pointers activated when a particular option value is selected.
 */
data class ResourcePackVariantSpec(
    val uiTheme: String? = null,
    val images: String? = null,
    val sounds: String? = null,
) {
    val isEmpty: Boolean
        get() = uiTheme == null && images == null && sounds == null

    /** True when toggling this variant swaps image or audio media rather than pure [UiTheme] style tokens. */
    val affectsMediaAssets: Boolean
        get() = images != null || sounds != null

    fun toJsonObject(): JsonObject = JsonObject().apply {
        uiTheme?.let { add("uiTheme", it) }
        images?.let { add("images", it) }
        sounds?.let { add("sounds", it) }
    }
}

data class ResourcePackOptionChoice(
    val value: String,
    val name: String,
    val displayTextZh: String? = null,
    val displayTextEn: String? = null,
    val overlay: ResourcePackVariantSpec = ResourcePackVariantSpec(),
) {
    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String = when (language) {
        UiLanguage.ZH -> displayTextZh?.takeIf { it.isNotBlank() } ?: name
        UiLanguage.EN -> displayTextEn?.takeIf { it.isNotBlank() } ?: name
    }

    fun toJsonObject(): JsonObject = JsonObject().apply {
        add("value", value)
        add("name", name)
        displayTextZh?.let { add("displayText_zh", it) }
        displayTextEn?.let { add("displayText_en", it) }
        overlay.uiTheme?.let { add("uiTheme", it) }
        overlay.images?.let { add("images", it) }
        overlay.sounds?.let { add("sounds", it) }
    }
}

/**
 * Single declarative option declared by a resource pack (`options` array in `pack.json`).
 */
data class ResourcePackOption(
    val id: String,
    val type: ResourcePackOptionType,
    val name: String,
    val displayTextZh: String? = null,
    val displayTextEn: String? = null,
    val defaultValue: String,
    val choices: List<ResourcePackOptionChoice> = emptyList(),
    val whenTrue: ResourcePackVariantSpec = ResourcePackVariantSpec(),
    val whenFalse: ResourcePackVariantSpec = ResourcePackVariantSpec(),
) {
    val affectsMediaAssets: Boolean
        get() = when (type) {
            ResourcePackOptionType.ENUM -> choices.any { it.overlay.affectsMediaAssets }
            ResourcePackOptionType.BOOLEAN -> whenTrue.affectsMediaAssets || whenFalse.affectsMediaAssets
        }

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String = when (language) {
        UiLanguage.ZH -> displayTextZh?.takeIf { it.isNotBlank() } ?: name
        UiLanguage.EN -> displayTextEn?.takeIf { it.isNotBlank() } ?: name
    }

    /**
     * Resolves [raw] against this option's declared choices, silently falling back to [defaultValue]
     * if [raw] is absent or obsolete after a pack version update (UI Supplement §7 / Q7).
     */
    fun resolveValueOrDefault(raw: String?): String {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return defaultValue
        return when (type) {
            ResourcePackOptionType.ENUM ->
                if (choices.any { it.value == candidate }) candidate else defaultValue
            ResourcePackOptionType.BOOLEAN ->
                if (candidate == "true" || candidate == "false") candidate else defaultValue
        }
    }

    fun localizedValueLabel(raw: String?, language: UiLanguage = UiStrings.currentLanguage): String {
        val resolved = resolveValueOrDefault(raw)
        return when (type) {
            ResourcePackOptionType.ENUM ->
                choices.firstOrNull { it.value == resolved }?.localizedLabel(language) ?: resolved
            ResourcePackOptionType.BOOLEAN ->
                UiStrings.get(if (resolved == "true") "common.on" else "common.off", language)
        }
    }

    fun activeOverlay(raw: String?): ResourcePackVariantSpec {
        val resolved = resolveValueOrDefault(raw)
        return when (type) {
            ResourcePackOptionType.ENUM ->
                choices.firstOrNull { it.value == resolved }?.overlay ?: ResourcePackVariantSpec()
            ResourcePackOptionType.BOOLEAN ->
                if (resolved == "true") whenTrue else whenFalse
        }
    }

    fun nonEmptyOverlays(): List<Pair<String, ResourcePackVariantSpec>> = when (type) {
        ResourcePackOptionType.ENUM -> choices.mapNotNull { ch ->
            ch.overlay.takeUnless { it.isEmpty }?.let { ch.value to it }
        }
        ResourcePackOptionType.BOOLEAN -> buildList {
            if (!whenTrue.isEmpty) add("true" to whenTrue)
            if (!whenFalse.isEmpty) add("false" to whenFalse)
        }
    }

    fun toJsonObject(): JsonObject = JsonObject().apply {
        add("id", id)
        add("type", this@ResourcePackOption.type.wireName)
        add("name", name)
        displayTextZh?.let { add("displayText_zh", it) }
        displayTextEn?.let { add("displayText_en", it) }
        when (this@ResourcePackOption.type) {
            ResourcePackOptionType.ENUM -> {
                add("default", defaultValue)
                val arr = JsonArray()
                choices.forEach { arr.add(it.toJsonObject()) }
                add("choices", arr)
            }
            ResourcePackOptionType.BOOLEAN -> {
                add("default", defaultValue == "true")
                if (!whenTrue.isEmpty) add("whenTrue", whenTrue.toJsonObject())
                if (!whenFalse.isEmpty) add("whenFalse", whenFalse.toJsonObject())
            }
        }
    }

    fun toPlainJsonString(): String = toJsonObject().toString(org.hjson.Stringify.PLAIN)

    companion object {
        private val IDENT_REGEX = Regex("^[a-zA-Z0-9_-]{1,48}$")
        private const val MAX_OPTIONS_PER_PACK = 16
        private const val MAX_CHOICES_PER_ENUM = 16

        private val OPTION_KEYS = setOf(
            "id", "type", "name", "displayText_zh", "displayText_en",
            "default", "choices", "whenTrue", "whenFalse",
        )
        private val CHOICE_KEYS = setOf(
            "value", "name", "displayText_zh", "displayText_en",
            "uiTheme", "images", "sounds",
        )
        private val OVERLAY_KEYS = setOf("uiTheme", "images", "sounds")

        fun parseList(node: JsonValue?, source: String): List<ResourcePackOption> {
            if (node == null) return emptyList()
            fun bad(field: String, reason: String): Nothing =
                throw IllegalArgumentException("RESOURCE_PACK_METADATA_INVALID: $source / $field: $reason")
            if (!node.isArray) bad("options", "Expected an array of option declarations")
            val arr = node.asArray()
            if (arr.size() > MAX_OPTIONS_PER_PACK) {
                bad("options", "Exceeds maximum $MAX_OPTIONS_PER_PACK options per pack")
            }

            fun validateSafeRelativePath(path: String, field: String) {
                if (path.isBlank() ||
                    path.startsWith("/") ||
                    path.any { it == ':' || it == '\\' || it.isISOControl() } ||
                    path.split('/').any { it.isBlank() || it == "." || it == ".." }
                ) {
                    bad(field, "Expected safe relative intra-pack path, got '$path'")
                }
            }

            fun reqString(obj: JsonObject, key: String, pathPrefix: String): String {
                val v = obj.get(key) ?: bad("$pathPrefix.$key", "Required string field")
                if (!v.isString || v.asString().isBlank() || v.asString().any(Char::isISOControl)) {
                    bad("$pathPrefix.$key", "Expected nonblank text without control characters")
                }
                return v.asString()
            }

            fun optString(obj: JsonObject, key: String, pathPrefix: String): String? {
                val v = obj.get(key) ?: return null
                if (!v.isString || v.asString().isBlank() || v.asString().any(Char::isISOControl)) {
                    bad("$pathPrefix.$key", "Expected nonblank text without control characters")
                }
                return v.asString()
            }

            fun parseOverlayFromObject(obj: JsonObject, pathPrefix: String, allowedKeys: Set<String>): ResourcePackVariantSpec {
                val seen = HashSet<String>()
                for (m in obj) {
                    if (!seen.add(m.name)) bad("$pathPrefix.${m.name}", "Duplicate member")
                    if (m.name !in allowedKeys) bad("$pathPrefix.${m.name}", "Unknown member")
                }
                val uiTheme = optString(obj, "uiTheme", pathPrefix)?.also { validateSafeRelativePath(it, "$pathPrefix.uiTheme") }
                val images = optString(obj, "images", pathPrefix)?.also { validateSafeRelativePath(it, "$pathPrefix.images") }
                val sounds = optString(obj, "sounds", pathPrefix)?.also { validateSafeRelativePath(it, "$pathPrefix.sounds") }
                return ResourcePackVariantSpec(uiTheme = uiTheme, images = images, sounds = sounds)
            }

            val seenOptionIds = HashSet<String>()
            val result = ArrayList<ResourcePackOption>(arr.size())

            arr.forEachIndexed { idx, item ->
                val prefix = "options[$idx]"
                if (!item.isObject) bad(prefix, "Expected an option object")
                val obj = item.asObject()
                val seenKeys = HashSet<String>()
                for (m in obj) {
                    if (!seenKeys.add(m.name)) bad("$prefix.${m.name}", "Duplicate option member")
                    if (m.name !in OPTION_KEYS) bad("$prefix.${m.name}", "Unknown option member")
                }

                val id = reqString(obj, "id", prefix)
                if (!IDENT_REGEX.matches(id)) {
                    bad("$prefix.id", "Option id '$id' must match ${IDENT_REGEX.pattern}")
                }
                if (!seenOptionIds.add(id)) {
                    bad("$prefix.id", "Duplicate option id '$id'")
                }

                val typeWire = reqString(obj, "type", prefix)
                val type = ResourcePackOptionType.fromWireOrNull(typeWire)
                    ?: bad("$prefix.type", "Expected 'enum' or 'boolean', got '$typeWire'")
                val name = reqString(obj, "name", prefix)
                val zh = optString(obj, "displayText_zh", prefix)
                val en = optString(obj, "displayText_en", prefix)

                when (type) {
                    ResourcePackOptionType.ENUM -> {
                        if (obj.get("whenTrue") != null || obj.get("whenFalse") != null) {
                            bad(prefix, "whenTrue/whenFalse are only valid on boolean options")
                        }
                        val defaultVal = reqString(obj, "default", prefix)
                        val choicesNode = obj.get("choices") ?: bad("$prefix.choices", "Required choices array for enum option")
                        if (!choicesNode.isArray) bad("$prefix.choices", "Expected an array")
                        val cArr = choicesNode.asArray()
                        if (cArr.size() !in 2..MAX_CHOICES_PER_ENUM) {
                            bad("$prefix.choices", "Enum option must declare 2..$MAX_CHOICES_PER_ENUM choices")
                        }
                        val seenValues = HashSet<String>()
                        val parsedChoices = cArr.mapIndexed { cIdx, cItem ->
                            val cPrefix = "$prefix.choices[$cIdx]"
                            if (!cItem.isObject) bad(cPrefix, "Expected choice object")
                            val cObj = cItem.asObject()
                            val overlay = parseOverlayFromObject(cObj, cPrefix, CHOICE_KEYS)
                            val value = reqString(cObj, "value", cPrefix)
                            if (!IDENT_REGEX.matches(value)) {
                                bad("$cPrefix.value", "Choice value '$value' must match ${IDENT_REGEX.pattern}")
                            }
                            if (id == "theme" && value.equals("nasa", ignoreCase = true)) {
                                bad("$cPrefix.value", "Unsupported reserved theme value '$value'")
                            }
                            if (!seenValues.add(value)) {
                                bad("$cPrefix.value", "Duplicate choice value '$value'")
                            }
                            ResourcePackOptionChoice(
                                value = value,
                                name = reqString(cObj, "name", cPrefix),
                                displayTextZh = optString(cObj, "displayText_zh", cPrefix),
                                displayTextEn = optString(cObj, "displayText_en", cPrefix),
                                overlay = overlay,
                            )
                        }
                        if (defaultVal !in seenValues) {
                            bad("$prefix.default", "Default '$defaultVal' is not among declared choices")
                        }
                        result += ResourcePackOption(
                            id = id,
                            type = ResourcePackOptionType.ENUM,
                            name = name,
                            displayTextZh = zh,
                            displayTextEn = en,
                            defaultValue = defaultVal,
                            choices = parsedChoices,
                        )
                    }
                    ResourcePackOptionType.BOOLEAN -> {
                        if (obj.get("choices") != null) {
                            bad("$prefix.choices", "choices is only valid on enum options")
                        }
                        val defNode = obj.get("default") ?: bad("$prefix.default", "Required boolean default")
                        if (!defNode.isBoolean) bad("$prefix.default", "Expected boolean true or false")
                        val whenTrue = obj.get("whenTrue")?.let { wt ->
                            if (!wt.isObject) bad("$prefix.whenTrue", "Expected an overlay object")
                            parseOverlayFromObject(wt.asObject(), "$prefix.whenTrue", OVERLAY_KEYS)
                        } ?: ResourcePackVariantSpec()
                        val whenFalse = obj.get("whenFalse")?.let { wf ->
                            if (!wf.isObject) bad("$prefix.whenFalse", "Expected an overlay object")
                            parseOverlayFromObject(wf.asObject(), "$prefix.whenFalse", OVERLAY_KEYS)
                        } ?: ResourcePackVariantSpec()
                        result += ResourcePackOption(
                            id = id,
                            type = ResourcePackOptionType.BOOLEAN,
                            name = name,
                            displayTextZh = zh,
                            displayTextEn = en,
                            defaultValue = if (defNode.asBoolean()) "true" else "false",
                            whenTrue = whenTrue,
                            whenFalse = whenFalse,
                        )
                    }
                }
            }
            return result
        }
    }
}

/**
 * Key identifying one prepared intra-pack variant overlay (`optionId` + `choiceValue`).
 */
data class PackVariantKey(
    val optionId: String,
    val choiceValue: String,
)

/**
 * Statically validated and cached intra-pack variant overlay assets owned by a [PreparedImagePack].
 */
class PreparedPackVariantOverlay(
    val uiThemePatch: UiThemePatch? = null,
    val uiThemeRawBytes: ByteArray? = null,
    val uiThemeSourceRelPath: String? = null,
    images: Map<ResourceId, PreparedResourceImage> = emptyMap(),
    sounds: Map<ResourceId, PreparedResourceSound> = emptyMap(),
    soundEvents: Map<ResourceId, ResourceSoundEvent> = emptyMap(),
) {
    val images: Map<ResourceId, PreparedResourceImage> = java.util.Collections.unmodifiableMap(LinkedHashMap(images))
    val sounds: Map<ResourceId, PreparedResourceSound> = java.util.Collections.unmodifiableMap(LinkedHashMap(sounds))
    val soundEvents: Map<ResourceId, ResourceSoundEvent> = java.util.Collections.unmodifiableMap(LinkedHashMap(soundEvents))
}

/**
 * Output of resolving a single [PreparedImagePack]'s base assets + active [ResourcePackOption] overlays
 * in declaration order (`options[0]` -> `options[1]` -> ...).
 */
data class ResolvedPackVariant(
    val images: Map<ResourceId, PreparedResourceImage>,
    val sounds: Map<ResourceId, PreparedResourceSound>,
    val soundEvents: Map<ResourceId, ResourceSoundEvent>,
    /** Ordered low-to-high intra-pack theme patches (base `ui/theme.json` first, followed by active option patches). */
    val uiThemePatchesLowToHigh: List<UiThemePatch>,
)
