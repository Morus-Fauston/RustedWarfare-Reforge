package com.rustedwarfare.reforge.client

import org.hjson.JsonValue
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Localized display text resolver for resource packs and content packs (UI Supplement §8.4).
 *
 * Rules:
 * - In Simplified Chinese (`UiLanguage.ZH`), prefers `displayText_zh` / `title_zh` / `name_zh`
 *   and `description_zh`, falling back cleanly to original `displayText` / `title` / `name` / `description`.
 * - In English (`UiLanguage.EN`), prefers `displayText_en` / `description_en` if present, otherwise original.
 * - Supports `ResourcePackMetadata`, Rusted Warfare `mod-info.txt` (`[mod]` INI), optional `pack-i18n.json`,
 *   and repository built-in sandbox content packs without requiring `Engine/core` schema changes.
 */
object LocalizedMetadata {
    private data class PackTextOverride(
        val zhName: String,
        val enName: String,
        val zhDescription: String,
        val enDescription: String,
    )

    private val builtInContentPackTexts: Map<String, PackTextOverride> = mapOf(
        "phase-seven.json" to PackTextOverride(
            zhName = "现行综合试玩包",
            enName = "Phase VII Sandbox Pack",
            zhDescription = "基础玩法内容包 · 底层必选",
            enDescription = "Base gameplay content pack · Required",
        ),
        "phase-six-a.json" to PackTextOverride(
            zhName = "六期 A 综合沙盒包",
            enName = "Phase VI-A Sandbox Pack",
            zhDescription = "六期 A 代表单位验证内容包",
            enDescription = "Phase VI-A representative unit content pack",
        ),
    )

    fun resourcePackName(metadata: ResourcePackMetadata, language: UiLanguage = UiStrings.currentLanguage): String =
        when (language) {
            UiLanguage.ZH -> metadata.displayTextZh?.takeIf(String::isNotBlank) ?: metadata.name
            UiLanguage.EN -> metadata.displayTextEn?.takeIf(String::isNotBlank) ?: metadata.name
        }

    fun resourcePackDescription(metadata: ResourcePackMetadata, language: UiLanguage = UiStrings.currentLanguage): String =
        when (language) {
            UiLanguage.ZH -> metadata.descriptionZh?.takeIf(String::isNotBlank) ?: metadata.description
            UiLanguage.EN -> metadata.descriptionEn?.takeIf(String::isNotBlank) ?: metadata.description
        }

    /**
     * Resolves display text from a generic key-value map (e.g. parsed INI `[mod]` / `[core]` section or JSON map).
     */
    fun resolveTextFromMap(
        fields: Map<String, String?>,
        primaryBaseKeys: List<String> = listOf("displayText", "title", "name"),
        language: UiLanguage = UiStrings.currentLanguage,
    ): String? {
        val normalized = HashMap<String, String>(fields.size)
        for ((k, v) in fields) {
            val trimmed = v?.trim()?.takeIf(String::isNotEmpty) ?: continue
            normalized[k.trim().lowercase()] = trimmed
        }
        val langSuffixes = when (language) {
            UiLanguage.ZH -> listOf("_zh", "_zh_cn")
            UiLanguage.EN -> listOf("_en")
        }
        for (base in primaryBaseKeys) {
            val lowerBase = base.lowercase()
            for (suffix in langSuffixes) {
                normalized[lowerBase + suffix]?.let { return it }
            }
        }
        for (base in primaryBaseKeys) {
            normalized[base.lowercase()]?.let { return it }
        }
        return null
    }

    fun resolveDescriptionFromMap(
        fields: Map<String, String?>,
        language: UiLanguage = UiStrings.currentLanguage,
    ): String? = resolveTextFromMap(fields, listOf("description", "desc"), language)

    /**
     * Resolves localized title & description for a content pack directory (`mod-info.txt` or `pack-i18n.json`),
     * falling back to built-in repository pack translations and finally [fallbackName] / [fallbackDescription].
     */
    fun contentPackName(
        packId: String,
        fallbackName: String,
        packRoot: Path? = null,
        language: UiLanguage = UiStrings.currentLanguage,
    ): String {
        val sidecar = packRoot?.let(::readDirectorySidecarFields)
        if (!sidecar.isNullOrEmpty()) {
            resolveTextFromMap(sidecar, listOf("displayText", "title", "name"), language)?.let { return it }
        }
        builtInContentPackTexts[packId]?.let { builtin ->
            return if (language == UiLanguage.ZH) builtin.zhName else builtin.enName
        }
        return fallbackName
    }

    fun contentPackDescription(
        packId: String,
        fallbackDescription: String = "",
        packRoot: Path? = null,
        language: UiLanguage = UiStrings.currentLanguage,
    ): String {
        val sidecar = packRoot?.let(::readDirectorySidecarFields)
        if (!sidecar.isNullOrEmpty()) {
            resolveDescriptionFromMap(sidecar, language)?.let { return it }
        }
        builtInContentPackTexts[packId]?.let { builtin ->
            return if (language == UiLanguage.ZH) builtin.zhDescription else builtin.enDescription
        }
        return fallbackDescription
    }

    /** Parses Rusted Warfare `mod-info.txt` INI text (`[mod]` section or top-level keys) safely in memory. */
    fun parseModInfoIni(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var currentSection = ""
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            if (currentSection.isNotEmpty() && currentSection != "mod") continue
            val sep = line.indexOf(':').let { colon ->
                val eq = line.indexOf('=')
                when {
                    colon < 0 -> eq
                    eq < 0 -> colon
                    else -> minOf(colon, eq)
                }
            }
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim().replace("\\n", " ")
            if (key.isNotEmpty() && value.isNotEmpty()) {
                out[key] = value
            }
        }
        return out
    }

    private fun readDirectorySidecarFields(packRoot: Path): Map<String, String> {
        val dir = runCatching {
            val norm = packRoot.toAbsolutePath().normalize()
            if (Files.isRegularFile(norm, NOFOLLOW_LINKS)) norm.parent else norm
        }.getOrNull() ?: return emptyMap()
        if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) return emptyMap()

        val i18nJson = dir.resolve("pack-i18n.json")
        if (Files.isRegularFile(i18nJson, NOFOLLOW_LINKS)) {
            readBoundedUtf8(i18nJson)?.let { jsonText ->
                runCatching {
                    val parsed = JsonValue.readJSON(jsonText)
                    if (parsed.isObject) {
                        val map = LinkedHashMap<String, String>()
                        for (m in parsed.asObject()) {
                            if (m.value.isString) map[m.name] = m.value.asString()
                        }
                        if (map.isNotEmpty()) return map
                    }
                }
            }
        }

        val modInfo = dir.resolve("mod-info.txt")
        if (Files.isRegularFile(modInfo, NOFOLLOW_LINKS)) {
            readBoundedUtf8(modInfo)?.let { iniText ->
                val parsed = parseModInfoIni(iniText)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return emptyMap()
    }

    private fun readBoundedUtf8(file: Path, maxBytes: Int = 64 * 1024): String? = runCatching {
        val bytes = Files.newInputStream(file).use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) return null
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }.recoverCatching { err ->
        if (err is CharacterCodingException) null else null
    }.getOrNull()
}
