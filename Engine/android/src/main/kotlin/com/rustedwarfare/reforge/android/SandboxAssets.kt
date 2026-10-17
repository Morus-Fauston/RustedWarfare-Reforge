package com.rustedwarfare.reforge.android

import android.content.res.AssetManager
import java.io.File

internal object SandboxAssets {
    fun copy(assets: AssetManager, destination: File, source: String = "p001") {
        val children = checkNotNull(assets.list(source))
        if (children.isEmpty()) {
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            check(destination.mkdirs() || destination.isDirectory)
            children.forEach { copy(assets, File(destination, it), "$source/$it") }
        }
    }
}
