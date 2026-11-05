package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedVisualAssets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** One current selection, held only in this process. No GL objects or disk-provided trust. */
internal class PcAuditedVisualReuse {
    private data class Audit(val key: Any, val prepared: PreparedVisualAssets)
    private var current: Audit? = null

    fun prepare(key: Any?, roots: List<Path>, audit: (() -> Unit) -> PreparedVisualAssets): PreparedVisualAssets {
        val previous = current
        current = null // Failure/cancellation cannot leave a reusable partial request.
        checkVisualCancellation()
        if (key == null) return audit(::checkVisualCancellation)
        if (previous != null && previous.key == key) {
            previous.prepared.verifyForPublication()
            checkVisualCancellation()
            current = previous
            return previous.prepared
        }
        val before = snapshot(roots)
        val prepared = audit {
            require(snapshot(roots) == before) { "RESOURCE_PACK_SOURCE_CHANGED: Audited inputs changed before publication" }
        }
        require(snapshot(roots) == before) { "RESOURCE_PACK_SOURCE_CHANGED: Inputs changed during audit" }
        checkVisualCancellation()
        current = Audit(key, prepared)
        return prepared
    }

    /** Hash every member, including qualification certificates and original bytes not imported as PNG. */
    private fun snapshot(roots: List<Path>): Map<Path, String> {
        val result = linkedMapOf<Path, String>()
        val buffer = ByteArray(64 * 1024)
        for (input in roots.distinct()) {
            val root = input.toAbsolutePath().normalize()
            require(root.toRealPath() == root && Files.isDirectory(root, NOFOLLOW_LINKS)) {
                "RESOURCE_PACK_SOURCE_CHANGED: Non-canonical package root $root"
            }
            fun members(): List<Path> = Files.walk(root).use { paths -> paths.sorted().toList() }
            val paths = members()
            for (path in paths) {
                checkVisualCancellation()
                require(!Files.isSymbolicLink(path) && path.toRealPath() == path) {
                    "RESOURCE_PACK_SOURCE_CHANGED: Linked package member $path"
                }
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    result[path] = "directory"
                    continue
                }
                fun attributes() = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val before = attributes()
                require(before.isRegularFile) {
                    "RESOURCE_PACK_SOURCE_CHANGED: Invalid package member $path"
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
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
                result[path] = java.util.HexFormat.of().formatHex(digest.digest())
            }
            require(members() == paths) { "RESOURCE_PACK_SOURCE_CHANGED: Package membership changed $root" }
        }
        checkVisualCancellation()
        return result
    }
}
