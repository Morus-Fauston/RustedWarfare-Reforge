package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ResourcePackMenuRow(val id: String, val metadata: ResourcePackMetadata?, val icon: PreparedResourceImage?,
    val manifest: Path?, val enabled: Boolean, val canEnable: Boolean, val error: String?)

/** UI-thread state. Only the current operation may publish; importing never enables or saves. */
class VisualAssetSettingsSession(private val backend: VisualAssetSettings) : AutoCloseable {
    private enum class Operation { APPLY, IMPORT, EXTRACT, EXTRACT_RESOURCES, CATALOG, RESTORE, PREVIEW }
    private data class Checked(val entry: ResourcePackCatalogEntry, val pack: PreparedImagePack?, val error: String?)
    private sealed interface Completed {
        data class Prepared(val assets: PreparedVisualAssets, val save: Boolean, val checks: List<Checked>? = null,
            val content: ContentPackChoice? = null) : Completed
        data class Imported(val pack: PreparedImagePack) : Completed
        data class Catalog(val checks: List<Checked>) : Completed
    }
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "visual-assets-settings").apply { isDaemon = true } }
    private var pending: Future<Result<Completed>>? = null
    private var operation: Operation? = null
    private var importPath: Path? = null
    private val known = linkedMapOf<String, Checked>()
    private var lastPrepared: PreparedVisualAssets? = null
    private var lastPreparedContent: ContentPackChoice? = null
    private var previewsRequested = false
    private var dirty = false
    var selection: VisualAssetSelection = backend.initialSelection
        private set
    var error: String? = backend.initialError
        private set
    var diagnostic: String? = null
        private set
    var selectedPackId: String = selection.packs.enabled.firstOrNull() ?: VisualAssetSource.PLACEHOLDER.resourcePackId
        private set
    var revision: Long = 0
        private set
    var hasUnsavedCatalog: Boolean = false
        private set
    val busy: Boolean get() = pending != null
    val preparationStatus: String? get() = backend.preparationStatus
    val supportsOriginalImageExtraction: Boolean get() = backend.supportsOriginalImageExtraction
    val supportsOriginalResourceExtraction: Boolean get() = backend.supportsOriginalResourceExtraction
    val canStart: Boolean get() = !dirty && error == null &&
        (!busy || operation == Operation.PREVIEW || operation == Operation.CATALOG)
    val hasUnappliedChanges: Boolean get() = dirty
    val operationLabel: String? get() = (if (busy) preparationStatus else null) ?: when (operation) {
        Operation.APPLY -> "Validating and preparing selected resources..."
        Operation.IMPORT -> "Checking local image pack..."
        Operation.EXTRACT -> "Extracting verified PC images..."
        Operation.EXTRACT_RESOURCES -> "Extracting verified PC pictures and sounds..."
        Operation.CATALOG -> "Reading saved resource list..."
        Operation.RESTORE -> "Validating the saved configuration..."
        Operation.PREVIEW -> "Preparing resource previews..."
        null -> null
    }
    val availableResourcePacks: List<ResourcePackMetadata> get() = backend.availableResourcePacks + rows.filter { it.manifest != null }.mapNotNull { it.metadata }
    val selectedResourcePackMetadata: ResourcePackMetadata get() = backend.availableResourcePacks.single { it.id.value == selection.resourcePackId }
    val rows: List<ResourcePackMenuRow> get() {
        val base = VisualAssetSource.PLACEHOLDER.resourcePackId
        val original = VisualAssetSource.ORIGINAL.resourcePackId
        val builtins = backend.availableResourcePacks.associateBy { it.id.value }
        val order = (selection.packs.enabled + selection.packs.catalog.map { it.id } + original).distinct().filterNot { it == base } + base
        return Collections.unmodifiableList(order.map { id ->
            val entry = selection.packs.catalog.singleOrNull { it.id == id }
            val checked = known[id]?.takeIf { it.entry == entry }
            val frozen = lastPrepared?.resolvedImages?.layers?.singleOrNull { it.metadata.id.value == id }?.takeIf { layer ->
                if (entry != null) layer.identity == entry.identity && lastPrepared?.selection?.packs?.catalog?.contains(entry) == true
                else id != original || lastPrepared?.selection?.originalDirectory == selection.originalDirectory
            }
            val metadata = builtins[id] ?: checked?.pack?.metadata ?: frozen?.metadata
            val icon = metadata?.let { checked?.pack?.images?.get(it.icon) ?: frozen?.images?.get(it.icon) }
            val problem = if (entry != null && checked == null && frozen == null) "Check this saved pack before enabling it" else checked?.error
            ResourcePackMenuRow(id, metadata, icon, entry?.manifest, id == base || id in selection.packs.enabled,
                id != base && (entry == null || problem == null && (checked?.pack != null || frozen != null)), problem)
        })
    }

    init {
        val initial = selection
        if (initial.packs.catalog.isNotEmpty()) begin(Operation.CATALOG) { Completed.Catalog(checkCatalog(initial.packs.catalog)) }
    }

    fun edit(value: VisualAssetSelection) {
        check(!busy) { "Cancel preparation before editing" }
        hasUnsavedCatalog = hasUnsavedCatalog || value.packs.catalog != selection.packs.catalog
        selection = value; dirty = true; error = null; diagnostic = null; revision++
    }

    fun selectPack(id: String) {
        check(!busy) { "Cancel the current resource operation first" }
        require(rows.any { it.id == id }) { "Unknown resource pack: $id" }
        selectedPackId = id; revision++
    }

    fun setEnabled(id: String, value: Boolean) {
        check(!busy) { "Cancel the current resource operation first" }
        require(id != VisualAssetSource.PLACEHOLDER.resourcePackId) { "The base pack is always enabled and last" }
        val row = requireNotNull(rows.singleOrNull { it.id == id }) { "Unknown resource pack: $id" }
        if (row.enabled == value) return
        if (value) check(row.canEnable) { row.error ?: "Check this pack before enabling it" }
        val order = when {
            !value -> selection.packs.enabled.filterNot { it == id }
            id == VisualAssetSource.ORIGINAL.resourcePackId -> selection.packs.enabled + id
            else -> listOf(id) + selection.packs.enabled
        }
        edit(selection.copy(packs = ResourcePackSelection(selection.packs.catalog, order)))
    }

    fun movePack(id: String, direction: Int) {
        check(!busy) { "Cancel the current resource operation first" }
        require(direction == -1 || direction == 1) { "Move one priority position at a time" }
        val order = selection.packs.enabled.toMutableList()
        val index = order.indexOf(id)
        require(index >= 0) { "Only enabled non-base packs can be moved" }
        val target = index + direction
        if (target !in order.indices) return
        Collections.swap(order, index, target)
        edit(selection.copy(packs = ResourcePackSelection(selection.packs.catalog, order)))
    }

    fun fail(message: String) { dirty = true; error = message; revision++ }

    fun reportImportError(message: String) { diagnostic = message; revision++ }

    /** Initialize icons through the same worker-side resolver, without applying edits or saving. */
    fun ensurePreviews(content: ContentPackChoice? = null): Boolean {
        if (busy || dirty || lastPrepared != null || previewsRequested || error != null) return false
        previewsRequested = true
        val selected = selection
        begin(Operation.PREVIEW) { Completed.Prepared(backend.prepareForContent(selected, content), save = false, content = content) }
        return true
    }

    /** Freeze on the UI thread; run the returned action only on the loading worker.
     * The loader must still verify the returned frozen descriptor before publication. */
    fun prepareForLoad(content: ContentPackChoice? = null): () -> PreparedVisualAssets {
        check(canStart) { error ?: "Apply the selected resources before starting" }
        val selected = selection
        fun sameContent(previous: ContentPackChoice?) = previous?.unitId == content?.unitId &&
            previous?.content?.sources == content?.content?.sources
        val ready = lastPrepared?.takeIf { it.selection == selected && sameContent(lastPreparedContent) }
        val shared = pending // Passive catalog/preview only: canStart excludes explicit edits.
        return {
            if (ready != null) ready else {
                val prepared = shared?.get()?.getOrThrow() as? Completed.Prepared
                if (prepared != null && prepared.assets.selection == selected && sameContent(prepared.content)) prepared.assets
                else backend.prepareForContent(selected, content)
            }
        }
    }

    /** UI-thread receipt for a load accepted by the launch state after final publication checks.
     * Retains only the non-GL descriptor, never the world, textures, or an unapplied selection. */
    internal fun rememberPublished(assets: PreparedVisualAssets, content: ContentPackChoice? = null) {
        if (assets.selection != selection) return
        backend.onPublished(assets)
        lastPrepared = assets
        lastPreparedContent = content
    }

    fun apply(content: ContentPackChoice? = null) {
        val selected = selection
        check(!busy) { "Visual preparation is already running" }
        error = null
        begin(Operation.APPLY) { Completed.Prepared(backend.prepareForContent(selected, content), save = true, content = content) }
    }

    fun importImagePack(path: Path) {
        check(!busy) { "Cancel the current resource operation first" }
        importPath = path.toAbsolutePath().normalize()
        val selected = requireNotNull(importPath)
        begin(Operation.IMPORT) { Completed.Imported(backend.checkImagePack(selected)) }
    }

    fun extractOriginalImages(path: Path) {
        check(!busy) { "Cancel the current resource operation first" }
        if (!supportsOriginalImageExtraction) throw UnsupportedOperationException("PC image extraction is unavailable on this platform")
        val source = path.toAbsolutePath().normalize()
        begin(Operation.EXTRACT) { Completed.Imported(backend.extractOriginalImagePack(source)) }
    }

    fun extractOriginalResources(path: Path) {
        check(!busy) { "Cancel the current resource operation first" }
        if (!supportsOriginalResourceExtraction) throw UnsupportedOperationException("Complete PC resource extraction is unavailable on this platform")
        val source = path.toAbsolutePath().normalize()
        begin(Operation.EXTRACT_RESOURCES) { Completed.Imported(backend.extractOriginalResourcePack(source)) }
    }

    fun restoreSaved(content: ContentPackChoice? = null) {
        begin(Operation.RESTORE) {
            val saved = backend.loadSavedSelection()
            val checks = checkCatalog(saved.packs.catalog)
            Completed.Prepared(backend.prepareForContent(saved, content), save = false, checks = checks, content = content)
        }
    }

    private fun begin(kind: Operation, work: () -> Completed) {
        check(!busy) { "Cancel the current resource operation first" }
        operation = kind; diagnostic = null
        pending = executor.submit<Result<Completed>> { runCatching(work) }
        revision++
    }

    private fun checkCatalog(entries: List<ResourcePackCatalogEntry>): List<Checked> = entries.map { entry ->
        if (Thread.currentThread().isInterrupted) throw CancellationException("Catalog reading cancelled")
        runCatching {
            backend.checkImagePack(entry.manifest).also { pack ->
                require(pack.catalogEntry == entry) { "RESOURCE_PACK_SOURCE_CHANGED: ${entry.id} / ${entry.manifest}: Saved source changed; import it again" }
            }
        }.fold({ Checked(entry, it, null) }, { Checked(entry, null, "${entry.id}: ${it.message ?: it}") })
    }

    private fun install(checks: List<Checked>) { known.clear(); checks.forEach { known[it.entry.id] = it } }

    private fun acceptImport(pack: PreparedImagePack, extracted: Boolean = false) {
        val entry = pack.catalogEntry
        val previous = selection.packs.catalog.singleOrNull {
            it.manifest == entry.manifest || pack.clientHeld && it.id == entry.id
        }
        require(selection.packs.catalog.none { it.id == entry.id && it != previous }) { "Duplicate resource pack ID ${entry.id}: ${entry.manifest}" }
        if (extracted && previous == entry) {
            known[entry.id] = Checked(entry, pack, null)
            selectedPackId = entry.id
            diagnostic = "Existing extracted package checked. Your selection is unchanged."
            return
        }
        val catalog = selection.packs.catalog.toMutableList()
        if (previous == null) catalog += entry else catalog[catalog.indexOf(previous)] = entry
        val order = selection.packs.enabled.filterNot { it == previous?.id }
        val changedEnabled = order != selection.packs.enabled
        val updated = selection.copy(packs = ResourcePackSelection(catalog, order))
        selection = updated
        previous?.let { known.remove(it.id) }
        known[entry.id] = Checked(entry, pack, null)
        selectedPackId = entry.id; hasUnsavedCatalog = true
        if (changedEnabled) { dirty = true; error = null }
        diagnostic = (if (extracted) "Extracted and checked. " else "Imported and checked. ") +
            "This pack is disabled until you enable it. Save and apply to remember the list."
    }

    fun poll(): Boolean {
        val job = pending ?: return false
        if (!job.isDone) return false
        val kind = operation; val attemptedPath = importPath
        pending = null; operation = null; importPath = null
        try {
            when (val result = job.get().getOrThrow()) {
                is Completed.Imported -> acceptImport(result.pack, extracted = kind == Operation.EXTRACT || kind == Operation.EXTRACT_RESOURCES)
                is Completed.Catalog -> {
                    install(result.checks)
                    error = result.checks.firstOrNull { it.entry.id in selection.packs.enabled && it.error != null }?.error
                    if (result.checks.any { it.error != null }) diagnostic = "Some saved packs are unavailable. Disabled entries are not used."
                }
                is Completed.Prepared -> {
                    if (result.save) backend.applyPrepared(result.assets)
                    selection = result.assets.selection
                    lastPrepared = result.assets
                    lastPreparedContent = result.content
                    result.checks?.let(::install)
                    dirty = false; error = null
                    if (result.save || kind == Operation.RESTORE) hasUnsavedCatalog = false
                    if (rows.none { it.id == selectedPackId }) selectedPackId = selection.packs.enabled.firstOrNull() ?: VisualAssetSource.PLACEHOLDER.resourcePackId
                }
            }
        } catch (failure: Exception) {
            val message = failure.message ?: failure.toString()
            when (kind) {
                Operation.EXTRACT, Operation.EXTRACT_RESOURCES -> diagnostic = message
                Operation.IMPORT -> {
                    diagnostic = message
                    val affected = selection.packs.catalog.filter { it.manifest == attemptedPath || it.manifest.parent == attemptedPath }
                    for (entry in affected) known[entry.id] = Checked(entry, known[entry.id]?.takeIf { it.entry == entry }?.pack, message)
                    if (affected.any { it.id in selection.packs.enabled }) fail(message)
                }
                Operation.CATALOG -> {
                    diagnostic = message
                    if (selection.packs.enabled.any { it != VisualAssetSource.ORIGINAL.resourcePackId }) fail(message)
                }
                else -> fail(message)
            }
        }
        revision++
        return true
    }

    fun cancel(message: String? = null) {
        val kind = operation
        val notice = message ?: when (kind) {
            Operation.EXTRACT -> "Image extraction cancelled. Your selection is unchanged."
            Operation.EXTRACT_RESOURCES -> "Resource extraction cancelled. Your selection is unchanged."
            Operation.PREVIEW -> "Optional previews cancelled. The saved selection will be checked when starting."
            Operation.CATALOG -> "Resource list reading cancelled. The saved selection will be checked when starting."
            else -> "Preparation cancelled. Apply again or choose public placeholders."
        }
        pending?.cancel(true); pending = null; operation = null; importPath = null
        if (kind == Operation.IMPORT || kind == Operation.EXTRACT || kind == Operation.EXTRACT_RESOURCES ||
            kind == Operation.RESTORE || kind == Operation.PREVIEW || kind == Operation.CATALOG) diagnostic = notice
        else fail(notice)
        revision++
    }

    override fun close() { pending?.cancel(true); pending = null; operation = null; executor.shutdownNow() }
}
