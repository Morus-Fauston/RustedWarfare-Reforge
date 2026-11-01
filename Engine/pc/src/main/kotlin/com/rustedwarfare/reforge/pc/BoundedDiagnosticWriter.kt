package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.DiagnosticOutput
import com.rustedwarfare.reforge.client.DiagnosticWriter
import com.rustedwarfare.reforge.client.FixedDiagnosticRecord
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class DiagnosticCloseTimeoutException : IOException("Diagnostic output did not finish before the close deadline")

/** One worker owns the codec and output. Admission includes queued and in-flight work. */
internal class BoundedDiagnosticWriter(
    private val output: DiagnosticOutput,
    private val maxRecords: Int = 120,
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val closeTimeoutMillis: Long = 5000,
) : DiagnosticWriter {
    private class Work(val sequence: Long, val record: FixedDiagnosticRecord?, val forceFlush: Boolean) {
        val bytes = record?.byteUpperBound ?: 0L
        var done = false
    }
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private val queue = ArrayBlockingQueue<Work>(maxRecords)
    private var nextSequence = 0L
    private var arrival = 0L
    private var admission = 0L
    private var pending = 0
    private var bytes = 0L
    @Volatile private var failure: Throwable? = null
    private var closing = false
    private var terminated = false
    private var deadline = 0L
    private var timeoutRecorded = false
    private val worker = Thread({ runWorker() }, "reforge-diagnostics-${ids.incrementAndGet()}").apply { isDaemon = true }

    init {
        require(maxRecords > 0 && maxBytes > 0 && closeTimeoutMillis > 0)
        try { worker.start() }
        catch (error: Throwable) { preserveFailure(error) { output.close() }; throw error }
    }

    private fun remember(error: Throwable) {
        val first = failure
        if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        changed.signalAll()
    }
    private fun failed() { failure?.let { throw it } }
    override fun checkFailure() { failed() }

    private fun awaitChange() {
        try { changed.await() }
        catch (error: InterruptedException) {
            remember(error); Thread.currentThread().interrupt(); throw failure ?: error
        }
    }

    private fun enqueue(record: FixedDiagnosticRecord?, forceFlush: Boolean): Work = lock.withLock {
        failed(); check(!closing) { "Diagnostic writer is closed" }
        val ticket = arrival++
        val weight = record?.byteUpperBound ?: 0L
        while (true) {
            failed(); check(!closing) { "Diagnostic writer is closed" }
            val capacity = pending < maxRecords && when {
                weight > maxBytes -> pending == 0
                bytes > maxBytes -> false // an oversized predecessor still owns the output
                else -> weight <= maxBytes - bytes
            }
            if (ticket == admission && capacity) break
            awaitChange()
        }
        val sequence = if (record == null) -1L else nextSequence++
        val work = Work(sequence, record, forceFlush)
        pending++; bytes += weight
        check(queue.offer(work)) { "Admitted diagnostic work must fit its bounded queue" }
        admission++; changed.signalAll()
        work
    }

    private fun completed(work: Work) = lock.withLock {
        while (!work.done) { failed(); awaitChange() }
        failed()
    }

    override fun record(record: FixedDiagnosticRecord, forceFlush: Boolean): Long {
        val work = enqueue(record, forceFlush)
        if (forceFlush) completed(work)
        return work.sequence
    }

    override fun flush() { completed(enqueue(null, true)) }

    private fun runWorker() {
        try {
            while (true) {
                val work = lock.withLock {
                    while (queue.isEmpty()) {
                        failed()
                        if (closing && pending == 0) return@withLock null
                        changed.await()
                    }
                    failed()
                    queue.poll()
                } ?: break
                work.record?.let { output.write(work.sequence, it) }
                if (work.forceFlush) output.flush()
                lock.withLock {
                    work.done = true; pending--; bytes -= work.bytes; changed.signalAll()
                }
            }
        } catch (error: Throwable) {
            lock.withLock { remember(error) }
        } finally {
            if (lock.withLock { failure == null }) try { output.flush() }
            catch (error: Throwable) { lock.withLock { remember(error) } }
            try { output.close() } catch (error: Throwable) { lock.withLock { remember(error) } }
            lock.withLock { queue.clear(); pending = 0; bytes = 0; terminated = true; changed.signalAll() }
        }
    }

    private fun timedOut() {
        if (!timeoutRecorded) { timeoutRecorded = true; remember(DiagnosticCloseTimeoutException()) }
        worker.interrupt()
        failed()
    }

    override fun close() {
        check(Thread.currentThread() !== worker) { "Diagnostic output cannot close its own worker" }
        lock.withLock {
            if (!closing) {
                closing = true
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
                changed.signalAll()
            }
            while (!terminated) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) timedOut()
                try { changed.awaitNanos(remaining) }
                catch (error: InterruptedException) {
                    remember(error); worker.interrupt(); Thread.currentThread().interrupt(); throw failure ?: error
                }
            }
        }
        // The termination acknowledgement is the worker's last action; join confirms JVM exit.
        if (worker.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) try {
                worker.join(TimeUnit.NANOSECONDS.toMillis(remaining), (remaining % 1_000_000).toInt())
            } catch (error: InterruptedException) {
                val first = lock.withLock { remember(error); checkNotNull(failure) }
                Thread.currentThread().interrupt(); throw first
            }
            if (worker.isAlive) lock.withLock { timedOut() }
        }
        lock.withLock { failed() }
    }

    private companion object { val ids = AtomicLong() }
}
