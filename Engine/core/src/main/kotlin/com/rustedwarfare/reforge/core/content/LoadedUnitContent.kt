package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.UnitSourceReader.Origin
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException

/** Identity of bytes actually parsed, independent of a later read of the input directory. */
data class ContentSourceIdentity(val path: Path, val bytes: Long, val sha256: String) {
    fun verifyStable(): ContentResult<Unit> {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Content load cancelled")
        return try {
            val current = contentSourceIdentity(path, Files.readAllBytes(path))
            if (current == this) ContentResult.Success(Unit)
            else ContentResult.Failure(ContentError(ContentErrorCode.BAD_REFERENCE, path.toString(),
                reason = "Content source bytes changed during loading; expected=$sha256; actual=${current.sha256}"))
        } catch (failure: IOException) {
            ContentResult.Failure(ContentError(ContentErrorCode.BAD_REFERENCE, path.toString(),
                reason = "Cannot verify loaded source: ${failure.message}"))
        } catch (failure: SecurityException) {
            ContentResult.Failure(ContentError(ContentErrorCode.BAD_REFERENCE, path.toString(),
                reason = "Cannot access loaded source: ${failure.message}"))
        }
    }
}

class LoadedUnitContent internal constructor(
    val definition: UnitDefinition, sources: List<ContentSourceIdentity>,
    fieldOrigins: Map<Pair<String, String>, Origin> = emptyMap(),
) {
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(ArrayList(sources))
    internal val fieldOrigins: Map<Pair<String, String>, Origin> = Collections.unmodifiableMap(LinkedHashMap(fieldOrigins))

    fun verifyStable(): ContentResult<Unit> {
        for (source in sources) when (val result = source.verifyStable()) {
            is ContentResult.Failure -> return result
            is ContentResult.Success -> Unit
        }
        return ContentResult.Success(Unit)
    }
}

internal fun contentSourceIdentity(path: Path, bytes: ByteArray): ContentSourceIdentity = ContentSourceIdentity(
    path.toAbsolutePath().normalize(), bytes.size.toLong(),
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
)
