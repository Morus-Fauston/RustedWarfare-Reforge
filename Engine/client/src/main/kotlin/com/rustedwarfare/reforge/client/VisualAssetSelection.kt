package com.rustedwarfare.reforge.client

import java.nio.file.Path

enum class VisualAssetSource(val value: String) { PLACEHOLDER("placeholder"), ORIGINAL("original") }

/** Remembering a directory never enables it; only source selects original visuals. */
data class VisualAssetSelection(val source: VisualAssetSource = VisualAssetSource.PLACEHOLDER,
    val originalDirectory: Path? = null, val temporary: Boolean = false)
