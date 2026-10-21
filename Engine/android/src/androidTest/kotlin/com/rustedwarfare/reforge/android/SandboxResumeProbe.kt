package com.rustedwarfare.reforge.android

import com.badlogic.gdx.Application
import com.badlogic.gdx.LifecycleListener
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Read-only TEST observer tied to one app/graphics/run, never a simulation driver. */
internal class SandboxResumeProbe(private val app: Application, private val file: File) : LifecycleListener, AutoCloseable {
    data class Fence(val offset: Long, val frame: Long, val delta: Double, val nanos: Long)
    private val graphics = app.graphics
    private val closing = AtomicBoolean()
    val registered = AtomicBoolean()
    val pauses = AtomicInteger()
    val resumes = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    val first = AtomicReference<Fence?>()
    val second = AtomicReference<Fence?>()
    val permission = SandboxResumePermission()

    private fun observe(body: () -> Unit) {
        if (closing.get()) return
        try { body() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
    }
    fun register() {
        app.postRunnable {
            observe {
                app.addLifecycleListener(this)
                if (closing.get()) {
                    app.removeLifecycleListener(this)
                    return@observe
                }
                registered.set(true)
            }
        }
    }
    private fun fence(): Fence {
        check(file.isFile && file.length() in 1..16L * 1024 * 1024) { "Invalid original diagnostic file" }
        return Fence(file.length(), graphics.frameId, graphics.deltaTime.toDouble(), System.nanoTime())
    }
    override fun pause() = observe {
        check(registered.get() && pauses.incrementAndGet() == 1 && resumes.get() == 0) { "Unexpected business pause" }
        permission.pause(System.nanoTime())
    }
    fun armResume() {
        check(!closing.get() && registered.get()) { "Inactive resume observer" }
        permission.arm(System.nanoTime())
    }
    override fun resume() = observe {
        check(registered.get() && pauses.get() == 1 && resumes.incrementAndGet() == 1) { "Unexpected business resume" }
        permission.resume(System.nanoTime())
        app.postRunnable {
            observe {
                check(first.compareAndSet(null, fence())) { "Duplicate first resume fence" }
                app.postRunnable {
                    observe { check(second.compareAndSet(null, fence())) { "Duplicate second resume fence" } }
                }
            }
        }
    }
    override fun dispose() = observe { error("Application disposed before business observation completed") }
    override fun close() {
        closing.set(true)
        // This backend synchronizes listener removal; do not await a paused GL queue.
        app.removeLifecycleListener(this)
    }
}
