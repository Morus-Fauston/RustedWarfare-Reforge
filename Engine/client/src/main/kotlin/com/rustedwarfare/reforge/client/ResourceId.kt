package com.rustedwarfare.reforge.client

/** Logical address only. It never resolves a path against the host filesystem. */
@JvmInline
value class ResourceId private constructor(val value: String) {
    val namespace: String get() = value.substringBefore(':')
    val path: String get() = value.substringAfter(':')
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ResourceId {
            val parts = value.split(':')
            require(parts.size == 2 && parts[0].matches(Regex("[a-z0-9_.-]+")) &&
                parts[1].split('/').all { segment -> segment != "." && segment != ".." &&
                    segment.matches(Regex("[a-z0-9_.-]+")) }) {
                "RESOURCE_ID_INVALID: $value: Expected lowercase namespace:path without empty or relative segments"
            }
            return ResourceId(value)
        }
    }
}
