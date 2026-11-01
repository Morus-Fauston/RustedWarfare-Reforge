package com.rustedwarfare.reforge.pc

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Owned construction content kept alive for the render thread and subsequent matches. */
internal object PhaseFourInputs {
    private val resources = listOf(
        "assets/terrain.png",
        "construction.properties",
        "maps/economy-chain.tmx",
        "maps/open.tmx",
        "maps/operating-chain.tmx",
        "maps/p001-controlled.tmx",
        "maps/p005-sealed.tmx",
        "maps/production-chain.tmx",
        "units/base.png",
        "units/base_back.png",
        "units/base_dead.png",
        "units/builder.png",
        "units/builder.properties",
        "units/builder_dead.png",
        "units/commandCenter.properties",
        "units/extractor.ini",
        "units/extractor_back.png",
        "units/extractor_common.ini",
        "units/extractor_t1.png",
        "units/extractor_t2.png",
        "units/extractor_t3.png",
        "units/extractorT2.ini",
        "units/extractorT3.ini",
        "units/fabricator_dead.png",
        "units/fabricatorT1.ini",
        "units/fabricatorT1.png",
        "units/fabricatorT2.ini",
        "units/fabricatorT2.png",
        "units/fabricatorT3.ini",
        "units/fabricatorT3.png",
        "units/factory.png",
        "units/factory_back.png",
        "units/factory_dead.png",
        "units/landFactory.properties",
        "units/laser_defence.png",
        "units/laser_defence_dead.png",
        "units/laserDefence.properties",
        "units/tank.ini",
        "units/tank.png",
        "units/tank_dead.png",
        "units/tank_turret.png",
        "units/turret.png",
        "units/turret_base2.png",
        "units/turret_common_land.ini",
        "units/turret_dead.png",
        "units/turret_t1.ini",
        "units/turret_t1_artillery.ini",
        "units/turret_t2_gun.ini",
        "units/turret_top.png",
        "units/turret_top_artillery.png",
        "units/turret_top_l2.png"
    )

    val root: Path by lazy {
        extract({ name -> PhaseFourInputs::class.java.classLoader.getResourceAsStream(name) })
    }

    internal fun extract(open: (String) -> InputStream?,
                         temporaryParent: Path = Path.of(System.getProperty("java.io.tmpdir"))): Path {
        val root = Files.createTempDirectory(temporaryParent, "reforge-phase4-").toRealPath()
        val owned = arrayListOf(root)
        try {
            for (relative in resources) {
                val target = root.resolve(relative).normalize()
                check(target.startsWith(root)) { "Packaged construction resource escapes its directory: $relative" }
                val parents = generateSequence(target.parent) { it.parent }
                    .takeWhile { it != root }.toList().asReversed()
                for (parent in parents) {
                    if (Files.notExists(parent)) {
                        Files.createDirectory(parent)
                        owned.add(parent)
                    }
                }
                owned.add(target)
                val input = checkNotNull(open("phase4/$relative")) {
                    "Missing packaged construction resource: phase4/$relative"
                }
                input.use { source ->
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output -> source.copyTo(output) }
                }
            }
            owned.forEach { it.toFile().deleteOnExit() }
            return root
        } catch (failure: Throwable) {
            for (path in owned.asReversed()) {
                try { Files.deleteIfExists(path) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}
