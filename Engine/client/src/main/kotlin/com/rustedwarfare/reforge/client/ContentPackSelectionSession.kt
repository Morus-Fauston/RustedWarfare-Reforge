package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** UI-thread menu state. Checks run off the render thread and never mutate a match. */
class ContentPackSelectionSession(
    private val read: (Path) -> ContentResult<LoadedContentPack> = ContentLoader::loadContentPack,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "content-pack-check").apply { isDaemon = true } }
    private var pending: Future<Result<LoadedContentPack>>? = null
    private var path: Path? = null
    private var dirty = false
    private val imported = linkedMapOf<Path, LoadedContentPack>()
    private val enabledManifests = linkedSetOf<Path>()
    val packages: List<LoadedContentPack> get() = Collections.unmodifiableList(imported.values.toList())
    val enabledPackages: List<LoadedContentPack> get() = packages.filter { it.manifest in enabledManifests }
    var composition: LoadedContentSet? = null
        private set
    var compositionError: String? = null
        private set
    var checked: LoadedContentPack? = null
        private set
    var selectedUnitId: String? = null
        private set
    val enabled: Boolean get() = checked?.manifest in enabledManifests
    var error: String? = null
        private set
    val inputPath: Path? get() = path
    val busy: Boolean get() = pending != null
    val canEnable: Boolean get() = !busy && !dirty && error == null && checked != null
    val availableUnits: Set<String> get() = (if (enabledManifests.isEmpty()) checked?.units else composition?.units)
        ?.filterValues { it.fixedUnderwaterTarget == null }?.keys.orEmpty()
    val canSelect: Boolean get() = !busy && !dirty && error == null && availableUnits.isNotEmpty()
    val canStart: Boolean get() = !busy && (enabledManifests.isEmpty() ||
        !dirty && error == null && composition != null && selectedUnitId in availableUnits)

    fun edit(value: Path?) {
        check(!busy) { "Cancel the current package check before editing" }
        path = value
        dirty = true
        error = null
    }

    fun fail(message: String) { dirty = true; error = message }

    fun checkPackage() {
        check(!busy) { "A package check is already running" }
        val selected = requireNotNull(path) { "Enter a local content package directory or pack.json/pack.hjson" }
        error = null
        dirty = true
        pending = executor.submit<Result<LoadedContentPack>> { runCatching {
            when (val result = read(selected)) {
                is ContentResult.Success -> result.value
                is ContentResult.Failure -> error(result.error.describe())
            }
        } }
    }

    fun poll(): Boolean {
        val job = pending ?: return false
        if (!job.isDone) return false
        pending = null
        try {
            val result = job.get().getOrThrow()
            check(imported.values.none { it.id == result.id && it.manifest != result.manifest }) {
                "IDENTITY_CONFLICT: ${result.manifest} / id: Duplicate checked package identity ${result.id}"
            }
            imported[result.manifest] = result
            enabledManifests.remove(result.manifest)
            checked = result
            dirty = false
            error = null
            if (enabledManifests.isEmpty()) selectedUnitId = result.units.keys.first()
            compose()
        } catch (failure: Exception) { fail(failure.message ?: failure.toString()) }
        return true
    }

    fun enable(value: Boolean) {
        if (value) check(canEnable) { "Check the package before enabling it" }
        checked?.manifest?.let { if (value) enabledManifests.add(it) else enabledManifests.remove(it) }
        compose()
    }

    fun disableAll() { enabledManifests.clear(); compose() }

    fun selectPackage(packageId: String) {
        check(!busy) { "Cancel the current package check before changing packages" }
        checked = imported.values.single { it.id == packageId }
        path = checked!!.root
        dirty = false
        error = null
        compose()
    }

    private fun compose() {
        composition = null
        compositionError = null
        if (enabledManifests.isNotEmpty()) when (val result = ContentLoader.composeContentPacks(enabledPackages)) {
            is ContentResult.Success -> composition = result.value
            is ContentResult.Failure -> compositionError = result.error.describe()
        }
        if (selectedUnitId !in availableUnits) selectedUnitId = availableUnits.firstOrNull()
    }

    fun select(unitId: String) {
        check(canSelect) { "Check a valid package combination before selecting a unit" }
        require(unitId in availableUnits) { "Unknown package unit: $unitId" }
        selectedUnitId = unitId
    }

    fun choice(): ContentPackChoice? {
        check(canStart) { error ?: compositionError ?: "Finish checking the selected content packages" }
        if (enabledManifests.isEmpty()) return null
        return ContentPackChoice(requireNotNull(composition), requireNotNull(selectedUnitId))
    }

    fun cancel() {
        pending?.cancel(true)
        pending = null
        fail("Package check cancelled. Check again or disable the package.")
    }

    override fun close() { pending?.cancel(true); pending = null; executor.shutdownNow() }
}
