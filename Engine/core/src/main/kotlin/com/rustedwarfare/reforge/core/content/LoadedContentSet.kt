package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

class ContentProvenance internal constructor(val packageId: String, val entryPath: Path, replacedPackageIds: List<String>) {
    val replacedPackageIds: List<String> = Collections.unmodifiableList(ArrayList(replacedPackageIds))
}

/** A unique final gameplay table; presentation ordering cannot select a winner. */
class LoadedContentSet internal constructor(packages: List<LoadedContentPack>, units: Map<String, UnitDefinition>,
    provenance: Map<String, ContentProvenance>, sources: List<ContentSourceIdentity>) {
    val packages: List<LoadedContentPack> = Collections.unmodifiableList(ArrayList(packages))
    val units: Map<String, UnitDefinition> = Collections.unmodifiableMap(LinkedHashMap(units))
    val provenance: Map<String, ContentProvenance> = Collections.unmodifiableMap(LinkedHashMap(provenance))
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(ArrayList(sources))

    fun verifyStable(): ContentResult<Unit> {
        for (pack in packages) when (val result = pack.verifyStable()) {
            is ContentResult.Failure -> return result
            is ContentResult.Success -> Unit
        }
        return ContentResult.Success(Unit)
    }
}
