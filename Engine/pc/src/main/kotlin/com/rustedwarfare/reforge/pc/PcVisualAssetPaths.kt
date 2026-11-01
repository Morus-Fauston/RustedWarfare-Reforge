package com.rustedwarfare.reforge.pc

import java.nio.file.Path

internal object PcVisualAssetPaths {
    fun userBase(localAppData: String?, userHome: String): Path =
        (localAppData?.takeUnless { it.isBlank() }?.let(Path::of) ?: Path.of(userHome).resolve("AppData/Local"))
            .resolve("RustedWarfare-Reforge/visual-assets/v1").toAbsolutePath().normalize()

    fun applicationRoot(codeLocation: Path, workingDirectory: Path): Path =
        (if (codeLocation.fileName.toString().endsWith(".jar") && codeLocation.parent?.fileName?.toString() == "lib")
            codeLocation.parent.parent else workingDirectory).toAbsolutePath().normalize()
}
