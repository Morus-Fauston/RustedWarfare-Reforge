package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import com.rustedwarfare.reforge.core.content.ContentResult
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class SandboxPreparationTiming(val startedNanos: Long, val scenarioPreparedNanos: Long,
    val visualsPreparedNanos: Long, val completedNanos: Long)

data class LoadedSandbox(val scenario: Scenario, val visuals: PreparedVisualAssets?, val renderResources: SandboxRenderResources,
                         val contentSources: List<ContentSourceIdentity> = emptyList(), val inputRoot: Path? = null,
                         val contentPack: ContentPackChoice? = null, val preparationTiming: SandboxPreparationTiming? = null)

/** The render thread alone consumes results. Cancelled jobs never publish a world or own GL resources. */
class SandboxLoader internal constructor(private val load: ((Path) -> Scenario)?) : AutoCloseable {
    constructor() : this(null)
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "p001-content").apply { isDaemon = true } }
    private var pending: Future<Result<LoadedSandbox>>? = null

    fun request(root: Path, prepareVisuals: (() -> PreparedVisualAssets)? = null) {
        begin(root, null, prepareVisuals)
    }

    fun requestPack(root: Path, contentPack: ContentPackChoice, prepareVisuals: (() -> PreparedVisualAssets)? = null) {
        begin(root, contentPack, prepareVisuals)
    }

    private fun begin(root: Path, contentPack: ContentPackChoice?, prepareVisuals: (() -> PreparedVisualAssets)?) {
        cancel()
        pending = executor.submit<Result<LoadedSandbox>> { runCatching {
            val startedNanos = System.nanoTime()
            val checked = contentPack?.revalidate()
            val prepared = if (checked != null) PreparedControlledScenario(contentPackScenario(root, checked), emptyList())
                else load?.let { PreparedControlledScenario(it(root), emptyList()) } ?: ControlledScenario.prepare(root)
            val scene = prepared.scenario
            val scenarioPreparedNanos = System.nanoTime()
            val visuals = prepareVisuals?.invoke()?.let { before -> checked?.let { contentPackVisuals(before, it) } ?: before }
            val visualsPreparedNanos = System.nanoTime()
            val resources = SandboxRenderResources.from(scene)
            val renderResources = visuals?.let { resources.bindVisuals(it) } ?: resources
            prepared.verifyStable()
            checked?.content?.verifyStable()?.let { stable ->
                if (stable is ContentResult.Failure) error(stable.error.describe())
            }
            visuals?.verifyForPublication()
            LoadedSandbox(scene, visuals, renderResources, checked?.content?.sources ?: prepared.sources,
                root.toAbsolutePath().normalize(), checked,
                SandboxPreparationTiming(startedNanos, scenarioPreparedNanos, visualsPreparedNanos, System.nanoTime()))
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
