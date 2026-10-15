package com.rustedwarfare.reforge.core.content

data class ContentError(
    val code: ContentErrorCode,
    val file: String,
    val section: String? = null,
    val field: String? = null,
    val objectRef: String? = null,
    val reason: String,
) {
    fun describe(): String = "code=$code; file=$file; section=${section?.let { "[$it]" } ?: "-"}; " +
        "field=${field ?: "-"}; ref=${objectRef ?: "-"}; reason=$reason"
}

enum class ContentErrorCode {
    MISSING_REQUIRED_FIELD, UNSUPPORTED_FIELD, UNSUPPORTED_FEATURE,
    UNSUPPORTED_ENCODING, UNSUPPORTED_LAYER, UNSUPPORTED_PROPERTY,
    BAD_REFERENCE, MALFORMED_SYNTAX, IDENTITY_CONFLICT, WEAPON_SECTION_PRESENT,
}

internal class ContentReadException(val error: ContentError) : RuntimeException(error.describe())

internal fun reject(
    code: ContentErrorCode,
    file: java.nio.file.Path,
    section: String? = null,
    field: String? = null,
    ref: String? = null,
    reason: String,
): Nothing = throw ContentReadException(ContentError(code, file.toAbsolutePath().normalize().toString(), section, field, ref, reason))
