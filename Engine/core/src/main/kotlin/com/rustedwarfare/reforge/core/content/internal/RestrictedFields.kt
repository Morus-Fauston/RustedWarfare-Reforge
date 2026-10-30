package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.reject
import java.nio.file.Files
import java.nio.file.Path

/** Strict native fixtures use '='; sectioned original subsets use their own scanner. */
internal fun readNativeFields(path: Path, allowed: Set<String>, family: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    Files.readAllLines(path).forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
        val separator = line.indexOf('=')
        if (separator <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
            reason = "Expected $family field=value at line ${index + 1}")
        val key = line.substring(0, separator).trim()
        if (key !in allowed) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key,
            reason = "Unsupported $family field")
        if (values.putIfAbsent(key, line.substring(separator + 1).trim()) != null)
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Duplicate $family field")
    }
    return values
}

internal fun requiredContentField(value: String?, path: Path, section: String?, key: String, reason: String): String =
    value?.takeIf { it.isNotBlank() }
        ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = reason)

/** The caller owns whitelist and inheritance; these flags retain the two existing INI dialects. */
internal fun readSectionFields(
    path: Path,
    allowed: Map<String, Set<String>>,
    family: String,
    trimSectionName: Boolean,
    reportCurrentSection: Boolean,
): Map<String, Map<String, String>> {
    val values = linkedMapOf<String, MutableMap<String, String>>()
    var section: String? = null
    Files.readAllLines(path).forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
        if (line.startsWith('[')) {
            if (!line.endsWith(']') || line.count { it == '[' } != 1 || line.count { it == ']' } != 1)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, section.takeIf { reportCurrentSection },
                    reason = "Invalid section at line ${index + 1}")
            val rawName = line.substring(1, line.length - 1)
            val name = if (trimSectionName) rawName.trim() else rawName
            if (name !in allowed) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, name,
                reason = "Unsupported $family section")
            if (values.putIfAbsent(name, linkedMapOf()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, name, reason = "Duplicate section")
            section = name
        } else {
            val current = section
            val colon = line.indexOf(':')
            if (current == null || colon <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                section.takeIf { reportCurrentSection }, reason = "Invalid field at line ${index + 1}")
            val key = line.substring(0, colon).trim()
            if (key !in allowed.getValue(current)) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, current, key,
                reason = "Unsupported $family field")
            if (values.getValue(current).putIfAbsent(key, line.substring(colon + 1).trim()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, key, reason = "Duplicate field")
        }
    }
    return values
}
