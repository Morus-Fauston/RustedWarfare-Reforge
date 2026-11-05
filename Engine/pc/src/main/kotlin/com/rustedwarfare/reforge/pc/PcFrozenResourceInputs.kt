package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** One audit's checked source bytes. Consumers must not mutate returned internal buffers.
 * Publication still checks the current complete tree; closing releases only the transient bytes. */
internal class PcFrozenResourceInputs private constructor(
    private val roots: List<Path>, private val initial: Map<Path, String>, private val retainedBytesLimit: Long,
) : AutoCloseable {
    private val bytes = linkedMapOf<Path, ByteArray>()
    private var closed = false
    private var retainedBytes = 0L

    fun read(input: Path, maximum: Int): ByteArray {
        check(!closed) { "Frozen resource input request is closed" }
        require(maximum in 1 until Int.MAX_VALUE)
        checkVisualCancellation()
        val file = input.toAbsolutePath().normalize()
        val expected = initial[file]
        require(expected != null && expected != "directory") { "RESOURCE_PACK_SOURCE_CHANGED: Unknown frozen member $file" }
        bytes[file]?.let { cached ->
            require(cached.size <= maximum) { "RESOURCE_PACK_SOURCE_CHANGED: Member exceeds read bound $file" }
            return cached
        }
        require(!Files.isSymbolicLink(file) && file.toRealPath() == file) {
            "RESOURCE_PACK_SOURCE_CHANGED: Linked frozen member $file"
        }
        fun attributes() = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val before = attributes()
        require(before.isRegularFile && before.size() <= maximum.toLong()) {
            "RESOURCE_PACK_SOURCE_CHANGED: Invalid or oversized frozen member $file"
        }
        val captured = Files.newInputStream(file).use { it.readNBytes(maximum + 1) }
        val after = attributes()
        require(captured.size.toLong() == before.size() && captured.size <= maximum &&
            before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.fileKey() == after.fileKey() && visualHash(captured) == expected) {
            "RESOURCE_PACK_SOURCE_CHANGED: Frozen member changed while reading $file"
        }
        checkVisualCancellation()
        // Retention is an optimization, never a new package acceptance limit.
        if (captured.size.toLong() <= retainedBytesLimit - retainedBytes) {
            bytes[file] = captured
            retainedBytes += captured.size
        }
        return captured
    }

    fun sha256(input: Path): String {
        checkVisualCancellation()
        val file = input.toAbsolutePath().normalize()
        return requireNotNull(initial[file]?.takeUnless { it == "directory" }) {
            "RESOURCE_PACK_SOURCE_CHANGED: Unknown frozen member $file"
        }
    }

    /** A separate publication boundary, never a cached successful check. Valid after close(). */
    fun verifyCurrent() {
        require(snapshot(roots) == initial) { "RESOURCE_PACK_SOURCE_CHANGED: Audited inputs changed before publication" }
    }

    override fun close() { bytes.clear(); retainedBytes = 0; closed = true }

    companion object {
        fun capture(inputs: List<Path>, retainedBytesLimit: Long = 64L * 1024 * 1024): PcFrozenResourceInputs {
            require(retainedBytesLimit >= 0)
            val roots = inputs.map { it.toAbsolutePath().normalize() }.distinct()
            return PcFrozenResourceInputs(roots, snapshot(roots), retainedBytesLimit)
        }

        private fun snapshot(roots: List<Path>): Map<Path, String> {
            val result = linkedMapOf<Path, String>()
            val buffer = ByteArray(64 * 1024)
            for (root in roots) {
                require(root.toRealPath() == root && Files.isDirectory(root, NOFOLLOW_LINKS)) {
                    "RESOURCE_PACK_SOURCE_CHANGED: Non-canonical package root $root"
                }
                fun members(): List<Path> = Files.walk(root).use { paths -> paths.peek { checkVisualCancellation() }.sorted().toList() }
                val paths = members()
                for (path in paths) {
                    checkVisualCancellation()
                    require(!Files.isSymbolicLink(path) && path.toRealPath() == path) {
                        "RESOURCE_PACK_SOURCE_CHANGED: Linked package member $path"
                    }
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) { result[path] = "directory"; continue }
                    fun attributes() = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    val before = attributes()
                    require(before.isRegularFile) { "RESOURCE_PACK_SOURCE_CHANGED: Invalid package member $path" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    Files.newInputStream(path).use { stream ->
                        while (true) {
                            checkVisualCancellation()
                            val size = stream.read(buffer)
                            if (size < 0) break
                            count += size
                            require(count <= before.size()) { "RESOURCE_PACK_SOURCE_CHANGED: Package member grew while reading $path" }
                            digest.update(buffer, 0, size)
                        }
                    }
                    val after = attributes()
                    require(count == before.size() && before.size() == after.size() &&
                        before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) {
                        "RESOURCE_PACK_SOURCE_CHANGED: Package member changed while reading $path"
                    }
                    result[path] = HexFormat.of().formatHex(digest.digest())
                }
                require(members() == paths) { "RESOURCE_PACK_SOURCE_CHANGED: Package membership changed $root" }
            }
            checkVisualCancellation()
            return result
        }
    }
}
