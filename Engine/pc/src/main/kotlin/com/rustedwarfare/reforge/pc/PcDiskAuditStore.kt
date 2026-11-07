package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.ContentPackChoice
import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.VisualAssetSelection
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CancellationException

internal data class PcDiskAuditPack(val id: String, val manifest: Path, val identity: String,
    val profile: String, val basis: String, val source: String, val generation: Path, val manifestSha256: String)

/** An untrusted locator for fixed pack generations, not an authority for qualification. */
internal class PcDiskAuditStore(private val cache: Path) {
    private fun root(roots: List<Path>): Path {
        val requested = cache.toAbsolutePath().normalize()
        var existing = requested
        while (Files.notExists(existing, NOFOLLOW_LINKS)) existing = requireNotNull(existing.parent)
        val canonical = existing.toRealPath().resolve(existing.relativize(requested))
        require(roots.none { canonical.startsWith(it) || it.startsWith(canonical) }) {
            "RESOURCE_PACK_IMAGE_INVALID: Package source and private cache must not overlap"
        }
        return canonical
    }

    fun read(roots: List<Path>, outputBasis: String): List<PcDiskAuditPack>? = try {
        checkVisualCancellation()
        val file = root(roots).resolve("checked-audit.json")
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || file.toRealPath() != file || Files.size(file) !in 1..1024L * 1024) null
        else {
            fun fields(row: JsonValue, names: Set<String>) { require(row.isObject && row.size == names.size && row.map { it.name }.toSet() == names) }
            fun text(row: JsonValue, key: String) = requireNotNull(row.get(key)).also { require(it.isString) }.asString()
            fun hash(row: JsonValue, key: String) = text(row, key).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            fun path(row: JsonValue, key: String) = Path.of(text(row, key)).also { require(it.isAbsolute && it == it.normalize()) }
            val bytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024 + 1) }; require(bytes.size <= 1024 * 1024)
            val json = JsonReader().parse(bytes.toString(Charsets.UTF_8))
            fields(json, setOf("schemaVersion", "configuration", "outputBasis", "packs"))
            require(json.get("schemaVersion")?.isLong == true && json.getLong("schemaVersion") == 1L)
            hash(json, "configuration"); require(hash(json, "outputBasis") == outputBasis)
            val packs = requireNotNull(json.get("packs")); require(packs.isArray && packs.size in 1..1024)
            val result = packs.map { row ->
                checkVisualCancellation()
                fields(row, setOf("id", "manifest", "identity", "profile", "basis", "source", "generation", "manifestSha256"))
                val id = text(row, "id"); ResourceId.parse(id)
                PcDiskAuditPack(id, path(row, "manifest"), hash(row, "identity"), text(row, "profile"), hash(row, "basis"),
                    hash(row, "source"), path(row, "generation"), hash(row, "manifestSha256"))
            }
            require(result.map { it.id }.distinct().size == result.size && result.map { it.manifest }.distinct().size == result.size)
            result
        }
    } catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { null }

    fun write(roots: List<Path>, configuration: String, outputBasis: String, packs: List<PcDiskAuditPack>) {
        require(packs.isNotEmpty())
        checkVisualCancellation()
        val root = root(roots); Files.createDirectories(root)
        val lockFile = root.resolve("checked-audit.lock")
        if (Files.exists(lockFile, NOFOLLOW_LINKS)) require(Files.isRegularFile(lockFile, NOFOLLOW_LINKS) && lockFile.toRealPath() == lockFile)
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            require(lock != null) { "CACHE_BUSY: resource audit publication is already owned" }
            lock.use {
                val document = JsonValue(JsonValue.ValueType.`object`)
                document.addChild("schemaVersion", JsonValue(1L)); document.addChild("configuration", JsonValue(configuration))
                document.addChild("outputBasis", JsonValue(outputBasis))
                val rows = JsonValue(JsonValue.ValueType.array); document.addChild("packs", rows)
                for (pack in packs) {
                    val row = JsonValue(JsonValue.ValueType.`object`)
                    for ((name, value) in linkedMapOf("id" to pack.id, "manifest" to pack.manifest.toString(), "identity" to pack.identity,
                        "profile" to pack.profile, "basis" to pack.basis, "source" to pack.source, "generation" to pack.generation.toString(),
                        "manifestSha256" to pack.manifestSha256)) row.addChild(name, JsonValue(value))
                    rows.addChild(row)
                }
                val bytes = document.toJson(OutputType.json).toByteArray(Charsets.UTF_8); require(bytes.size <= 1024 * 1024)
                val target = root.resolve("checked-audit.json")
                if (Files.exists(target, NOFOLLOW_LINKS)) require(Files.isRegularFile(target, NOFOLLOW_LINKS) && target.toRealPath() == target)
                val temporary = Files.createTempFile(root, "checked-audit-", ".tmp")
                Files.write(temporary, bytes); checkVisualCancellation(); moveVisualAtomically(temporary, target, true)
            }
        }
    }

    companion object {
        /** Binds the current combination; restored layers are always rebound to current content/options. */
        fun configuration(selection: VisualAssetSelection, content: ContentPackChoice?): String {
            val fields = mutableListOf(selection.source.name, selection.originalDirectory?.toString().orEmpty(), selection.temporary.toString())
            selection.packs.catalog.forEach { fields += listOf(it.id, it.manifest.toString(), it.identity) }
            fields += "enabled"; fields += selection.packs.enabled
            fields += "content"; fields += content?.unitId.orEmpty()
            content?.content?.sources?.forEach { fields += listOf(it.path.toString(), it.bytes.toString(), it.sha256) }
            return visualHash(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        }
    }
}
