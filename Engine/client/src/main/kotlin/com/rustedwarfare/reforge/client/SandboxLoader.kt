package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The render thread alone consumes results. Cancelled jobs never publish a world or own GL resources. */
class SandboxLoader internal constructor(private val load: (Path) -> Scenario) : AutoCloseable {
    constructor() : this(ControlledScenario::load)
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "p001-content").apply { isDaemon = true } }
    private var pending: Future<Result<Scenario>>? = null

    fun request(root: Path) {
        cancel()
        pending = executor.submit<Result<Scenario>> { runCatching { load(root) } }
    }
    fun poll(): Result<Scenario>? {
        val job = pending ?: return null
        if (!job.isDone) return null
        pending = null
        return job.get()
    }
    fun cancel() { pending?.cancel(true); pending = null }
    override fun close() { cancel(); executor.shutdownNow() }
}
