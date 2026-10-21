package com.rustedwarfare.reforge.android

import android.content.res.AssetManager
import java.io.File

internal object SandboxAssets {
    fun copy(assets: AssetManager, destination: File) {
        val expected = SandboxAssetFiles.paths()
        val payload = linkedMapOf<String, ByteArray>()
        fun collect(relative: String) {
            val source = if (relative.isEmpty()) "p001" else "p001/$relative"
            val children = checkNotNull(assets.list(source))
            if (children.isEmpty()) {
                check(relative in expected) { "Unknown sandbox asset: $relative" }
                payload[relative] = assets.open(source).use { it.readBytes() }
            } else {
                check(relative.isEmpty() || expected.any { it.startsWith("$relative/") }) {
                    "Unknown sandbox asset directory: $relative"
                }
                children.forEach { collect(if (relative.isEmpty()) it else "$relative/$it") }
            }
        }
        collect("")
        check(payload.keys == expected) { "Expected the exact twelve sandbox assets" }
        SandboxAssetFiles.install(destination.toPath(), payload)
    }
}
