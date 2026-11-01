package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class LoadedSandbox(val scenario: Scenario, val visuals: PreparedVisualAssets?, val renderResources: SandboxRenderResources)

/** The render thread alone consumes results. Cancelled jobs never publish a world or own GL resources. */
class SandboxLoader internal constructor(private val load: (Path) -> Scenario) : AutoCloseable {
    constructor() : this(ControlledScenario::load)
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "p001-content").apply { isDaemon = true } }
    private var pending: Future<Result<LoadedSandbox>>? = null

    fun request(root: Path, prepareVisuals: (() -> PreparedVisualAssets)? = null) {
        cancel()
        pending = executor.submit<Result<LoadedSandbox>> { runCatching {
            val scene = load(root)
            val visuals = prepareVisuals?.invoke()
            val resources = SandboxRenderResources.from(scene)
            LoadedSandbox(scene, visuals, visuals?.let { resources.withVisuals(it) } ?: resources)
        } }
    }
    fun poll(): Result<Scenario>? = pollPrepared()?.map { it.scenario }
    fun pollPrepared(): Result<LoadedSandbox>? {
        val job = pending ?: return null
        if (!job.isDone) return null
        pending = null
        return job.get()
    }
    fun cancel() { pending?.cancel(true); pending = null }
    override fun close() { cancel(); executor.shutdownNow() }
}
