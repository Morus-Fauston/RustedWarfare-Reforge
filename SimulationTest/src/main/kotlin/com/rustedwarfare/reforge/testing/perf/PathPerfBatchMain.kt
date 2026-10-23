package com.rustedwarfare.reforge.testing.perf

import java.nio.file.Path
import kotlin.system.exitProcess

/** Development-only CLI: explicit geometry version plus four unique input/output keys. */
object PathPerfBatchMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = try {
            parse(args)
        } catch (error: IllegalArgumentException) {
            System.err.println("invalid arguments: ${error.message}")
            exitProcess(64)
        }
        if (config == null) {
            System.err.println("usage: --repo-root ABS --unit ABS --unit-sha SHA256 --output ABS --geometry-version ${PathPerfBatch.DEVELOPMENT_GEOMETRY}")
            exitProcess(64)
        }
        val result = try {
            PathPerfBatch.run(config)
        } catch (error: Throwable) {
            System.err.println("run failed: ${error.message}")
            exitProcess(4)
        }
        exitProcess(result.exitCode)
    }

    fun parse(args: Array<String>): PathPerfConfig? {
        if (args.size != 10) return null
        val values = LinkedHashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index]
            if (key !in setOf("--repo-root", "--unit", "--unit-sha", "--output", "--geometry-version")) return null
            if (values.containsKey(key)) return null
            values[key] = args[index + 1]
            index += 2
        }
        val repo = values["--repo-root"] ?: return null
        val unit = values["--unit"] ?: return null
        val sha = values["--unit-sha"] ?: return null
        val output = values["--output"] ?: return null
        val geometry = values["--geometry-version"] ?: return null
        if (geometry != PathPerfBatch.DEVELOPMENT_GEOMETRY) return null
        return PathPerfConfig(Path.of(repo), Path.of(unit), sha, Path.of(output), geometry)
    }
}
