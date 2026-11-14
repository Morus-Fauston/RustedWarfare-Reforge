package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.ContentPackReader
import com.rustedwarfare.reforge.core.content.internal.UnitSourceReader.Origin
import java.io.IOException
import java.nio.file.Path
import java.util.Collections

/** A checked local declaration, never executable code or a live-world mutation. */
class LoadedContentPack internal constructor(
    val root: Path, val manifest: Path, val id: String, val name: String,
    units: Map<String, UnitDefinition>, sources: List<ContentSourceIdentity>,
    dependencies: List<String> = emptyList(), replacements: Map<String, ContentReplacement> = emptyMap(),
    unitFiles: Map<String, Path> = emptyMap(),
    unitFieldOrigins: Map<String, Map<Pair<String, String>, Origin>> = emptyMap(),
    val opening: ContentPackOpening? = null,
) {
    val units: Map<String, UnitDefinition> = Collections.unmodifiableMap(LinkedHashMap(units))
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(ArrayList(sources))
    val dependencies: List<String> = Collections.unmodifiableList(ArrayList(dependencies))
    val replacements: Map<String, ContentReplacement> = Collections.unmodifiableMap(LinkedHashMap(replacements))
    val unitFiles: Map<String, Path> = Collections.unmodifiableMap(LinkedHashMap(unitFiles))
    internal val unitFieldOrigins: Map<String, Map<Pair<String, String>, Origin>> =
        Collections.unmodifiableMap(LinkedHashMap(unitFieldOrigins))

    fun verifyStable(): ContentResult<Unit> {
        for (source in sources) {
            try { ContentPackReader.requireOrdinary(root, source.path) }
            catch (failure: ContentReadException) { return ContentResult.Failure(failure.error) }
            catch (failure: IOException) { return inaccessible(source.path, failure.message) }
            catch (failure: SecurityException) { return inaccessible(source.path, failure.message) }
            when (val result = source.verifyStable()) {
                is ContentResult.Failure -> return result
                is ContentResult.Success -> Unit
            }
        }
        return ContentResult.Success(Unit)
    }

    private fun inaccessible(path: Path, reason: String?) = ContentResult.Failure(
        ContentError(ContentErrorCode.BAD_REFERENCE, path.toString(), reason = reason ?: "Cannot verify package source"))
}

data class ContentReplacement(val targetPackId: String, val targetContentId: String)
