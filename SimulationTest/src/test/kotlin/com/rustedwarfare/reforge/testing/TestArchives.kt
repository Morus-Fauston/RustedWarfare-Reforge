package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID

/** Explicit archive roots are outer roots, never a shared mutable case directory. */
object TestArchives {
    private val run = System.getProperty("test.archive.run") ?: UUID.randomUUID().toString()
    private val fallback by lazy { Files.createTempDirectory("reforge-test-archives-") }

    fun directory(environment: String, owner: Class<*>, case: String): Path {
        fun segment(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9._-]+")) && value != "." && value != "..")
            return value
        }
        val outer = System.getenv(environment)?.let(Path::of)
            ?: System.getProperty("test.archive.root")?.let(Path::of) ?: fallback
        val worker = System.getProperty("org.gradle.test.worker") ?: "standalone"
        val parent = outer.toAbsolutePath().normalize().resolve(segment(run))
            .resolve(segment(System.getProperty("test.archive.module") ?: "tools"))
            .resolve("worker-${segment(worker)}").resolve(segment(owner.name))
        val leaf = parent.resolve(segment(case))
        Files.createDirectories(parent)
        Files.createDirectory(leaf)
        Files.writeString(leaf.resolve("worker.json"),
            """{"pid":${ProcessHandle.current().pid()},"worker":"$worker","startedMillis":${System.currentTimeMillis()}}""", CREATE_NEW)
        return leaf
    }
}
