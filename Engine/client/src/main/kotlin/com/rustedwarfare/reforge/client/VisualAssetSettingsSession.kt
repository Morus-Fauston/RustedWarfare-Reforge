package com.rustedwarfare.reforge.client

import java.util.concurrent.Executors
import java.util.concurrent.Future

/** UI-thread state. A cancelled or replaced worker result has no authority to save settings. */
class VisualAssetSettingsSession(private val backend: VisualAssetSettings) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "visual-assets-settings").apply { isDaemon = true } }
    private var pending: Future<Result<PreparedVisualAssets>>? = null
    var selection: VisualAssetSelection = backend.initialSelection
        private set
    var error: String? = backend.initialError
        private set
    private var dirty = false
    val busy: Boolean get() = pending != null
    val canStart: Boolean get() = !busy && !dirty && error == null

    fun edit(value: VisualAssetSelection) {
        check(!busy) { "Cancel preparation before editing" }
        selection = value
        dirty = true
        error = null
    }

    fun fail(message: String) { dirty = true; error = message }

    fun apply() {
        check(!busy) { "Visual preparation is already running" }
        val selected = selection
        error = null
        pending = executor.submit<Result<PreparedVisualAssets>> { runCatching { backend.prepare(selected) } }
    }

    fun poll(): Boolean {
        val job = pending ?: return false
        if (!job.isDone) return false
        pending = null
        try {
            val prepared = job.get().getOrThrow()
            backend.applyPrepared(prepared)
            selection = prepared.selection
            dirty = false
            error = null
        } catch (failure: Exception) { fail(failure.message ?: failure.toString()) }
        return true
    }

    fun cancel() {
        pending?.cancel(true)
        pending = null
        dirty = true
        error = "Preparation cancelled. Apply again or choose public placeholders."
    }

    override fun close() { pending?.cancel(true); pending = null; executor.shutdownNow() }
}
