package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class BoundedDiagnosticWriterTest {
    private class Gate(val ignoreInterrupts: Boolean = false) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            while (true) try { check(release.await(5, TimeUnit.SECONDS)); return }
            catch (error: InterruptedException) { if (!ignoreInterrupts) throw error }
        }
    }
    private class Output(private val gates: Map<Long, Gate> = emptyMap(), private val closeGate: Gate? = null) : DiagnosticOutput {
        val rows = CopyOnWriteArrayList<Pair<Long, String>>()
        val threads = ConcurrentHashMap.newKeySet<String>()
        var writeError: Throwable? = null; var flushError: Throwable? = null; var closeError: Throwable? = null
        @Volatile var flushes = 0
        @Volatile var closes = 0
        override fun write(sequence: Long, record: FixedDiagnosticRecord) {
            threads.add(Thread.currentThread().name); gates[sequence]?.hold()
            writeError?.let { throw it }; rows.add(sequence to record.values["label"].toString())
        }
        override fun flush() { threads.add(Thread.currentThread().name); flushError?.let { throw it }; flushes++ }
        override fun close() { threads.add(Thread.currentThread().name); closes++; closeGate?.hold(); closeError?.let { throw it } }
    }
    private fun record(label: String = "small") = FixedDiagnosticRecord.capture("sample", mapOf("label" to label))
    private fun pending(pool: ExecutorService, action: () -> Long): Future<Long> {
        val entered = CountDownLatch(1)
        val future = pool.submit<Long> { entered.countDown(); action() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFailsWith<TimeoutException> { future.get(100, TimeUnit.MILLISECONDS) }
        return future
    }

    @Test fun count_limit_includes_inflight_and_preserves_single_worker_order() {
        val gate = Gate(); val out = Output(mapOf(0L to gate)); val sink = BoundedDiagnosticWriter(out, 1, 1_000_000, 2000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertEquals(0L, sink.record(record("a"), false)); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val second = pending(pool) { sink.record(record("b"), false) }
            assertTrue(out.rows.isEmpty()); gate.release.countDown()
            assertEquals(1L, second.get(2, TimeUnit.SECONDS)); sink.close(); sink.close()
            assertEquals(listOf(0L to "a", 1L to "b"), out.rows.toList())
            assertEquals(1, out.closes); assertEquals(1, out.threads.size)
            assertFalse(Thread.currentThread().name in out.threads)
        } finally { gate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun independent_byte_limit_blocks_even_when_count_slots_remain() {
        val item = record(); val gate = Gate(); val out = Output(mapOf(0L to gate))
        val sink = BoundedDiagnosticWriter(out, 5, item.byteUpperBound, 2000); val pool = Executors.newSingleThreadExecutor()
        try {
            sink.record(item, false); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val second = pending(pool) { sink.record(item, false) }
            gate.release.countDown(); assertEquals(1L, second.get(2, TimeUnit.SECONDS)); sink.close()
            assertEquals(listOf(0L, 1L), out.rows.map { it.first })
        } finally { gate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun oversized_record_waits_for_predecessor_and_excludes_following_records() {
        val small = record(); val big = record("x".repeat(10000)); val first = Gate(); val large = Gate()
        val out = Output(mapOf(0L to first, 1L to large)); val sink = BoundedDiagnosticWriter(out, 5, small.byteUpperBound * 2, 2000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            assertTrue(big.byteUpperBound > small.byteUpperBound * 2)
            sink.record(small, false); assertTrue(first.entered.await(2, TimeUnit.SECONDS))
            val oversized = pending(pool) { sink.record(big, false) }
            first.release.countDown(); assertEquals(1L, oversized.get(2, TimeUnit.SECONDS))
            assertTrue(large.entered.await(2, TimeUnit.SECONDS))
            val tail = pending(pool) { sink.record(small, false) }
            large.release.countDown(); assertEquals(2L, tail.get(2, TimeUnit.SECONDS)); sink.flush(); sink.close()
            assertEquals(listOf(0L, 1L, 2L), out.rows.map { it.first })
        } finally { first.release.countDown(); large.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun strong_flush_record_waits_for_its_write_and_prior_records() {
        val gate = Gate(); val out = Output(mapOf(0L to gate)); val sink = BoundedDiagnosticWriter(out, 5, 1_000_000, 2000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            sink.record(record("a"), false); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val barrier = pending(pool) { sink.record(record("b"), true) }
            assertEquals(0, out.flushes); gate.release.countDown()
            assertEquals(1L, barrier.get(2, TimeUnit.SECONDS)); assertEquals(1, out.flushes)
            assertEquals(listOf("a", "b"), out.rows.map { it.second }); sink.close()
        } finally { gate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun worker_failure_wakes_resource_waiters_and_retains_first_error_through_close() {
        val first = IOException("write"); val cleanup = IOException("close"); val gate = Gate()
        val out = Output(mapOf(0L to gate)).apply { writeError = first; closeError = cleanup }
        val sink = BoundedDiagnosticWriter(out, 1, 1_000_000, 2000); val pool = Executors.newSingleThreadExecutor()
        try {
            sink.record(record(), false); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val second = pending(pool) { sink.record(record(), false) }; gate.release.countDown()
            assertSame(first, assertFailsWith<ExecutionException> { second.get(2, TimeUnit.SECONDS) }.cause)
            assertSame(first, assertFailsWith<IOException> { sink.flush() })
            assertSame(first, assertFailsWith<IOException> { sink.close() })
            assertSame(first, assertFailsWith<IOException> { sink.close() })
            assertSame(cleanup, first.suppressed.single()); assertEquals(1, out.closes)
        } finally { gate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun flush_and_close_failures_cannot_be_reported_as_success() {
        for (onFlush in listOf(false, true)) {
            val first = IOException(if (onFlush) "flush" else "close")
            val out = Output().apply { if (onFlush) flushError = first else closeError = first }
            val sink = BoundedDiagnosticWriter(out, 5, 1_000_000, 2000)
            if (onFlush) assertSame(first, assertFailsWith<IOException> { sink.record(record(), true) })
            else sink.record(record(), false)
            assertSame(first, assertFailsWith<IOException> { sink.close() })
            assertSame(first, assertFailsWith<IOException> { sink.record(record(), false) })
            assertEquals(1, out.closes)
        }
    }

    @Test fun close_timeout_reports_incomplete_without_claiming_uninterruptible_io_has_stopped() {
        val gate = Gate(ignoreInterrupts = true); val out = Output(mapOf(0L to gate))
        val sink = BoundedDiagnosticWriter(out, 5, 1_000_000, 30)
        try {
            sink.record(record(), false); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val failure = assertFailsWith<DiagnosticCloseTimeoutException> { sink.close() }
            assertSame(failure, assertFailsWith<DiagnosticCloseTimeoutException> { sink.close() })
            assertEquals(0, out.closes)
            assertSame(failure, assertFailsWith<DiagnosticCloseTimeoutException> { sink.record(record(), false) })
        } finally { gate.release.countDown() }
    }

    @Test fun interrupted_admission_wakes_waiters_preserves_flag_and_fails_later_calls() {
        val gate = Gate(); val out = Output(mapOf(0L to gate))
        val sink = BoundedDiagnosticWriter(out, 1, 1_000_000, 2000)
        val pool = Executors.newSingleThreadExecutor(); val thread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        try {
            sink.record(record(), false); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val waiting = pool.submit<Pair<Throwable, Boolean>> {
                thread.set(Thread.currentThread()); entered.countDown()
                try { sink.record(record("blocked"), false); error("unexpected acceptance") }
                catch (error: Throwable) { error to Thread.currentThread().isInterrupted }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { waiting.get(100, TimeUnit.MILLISECONDS) }
            thread.get().interrupt()
            val (first, interrupted) = waiting.get(2, TimeUnit.SECONDS)
            assertIs<InterruptedException>(first); assertTrue(interrupted)
            assertSame(first, assertFailsWith<InterruptedException> { sink.record(record(), false) })
            gate.release.countDown()
            assertSame(first, assertFailsWith<InterruptedException> { sink.close() })
        } finally { gate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun concurrent_close_drains_once_and_rejects_unaccepted_record() {
        val gate = Gate(); val out = Output(mapOf(0L to gate))
        val sink = BoundedDiagnosticWriter(out, 1, 1_000_000, 2000)
        val pool = Executors.newFixedThreadPool(3); val entered = CountDownLatch(2)
        try {
            sink.record(record("accepted"), false); assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val closers = List(2) { pool.submit { entered.countDown(); sink.close() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            closers.forEach { assertFailsWith<TimeoutException> { it.get(100, TimeUnit.MILLISECONDS) } }
            val rejected = pool.submit<Throwable> {
                try { sink.record(record("late"), false); error("unexpected acceptance") }
                catch (error: Throwable) { error }
            }
            assertIs<IllegalStateException>(rejected.get(2, TimeUnit.SECONDS))
            gate.release.countDown(); closers.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(listOf(0L to "accepted"), out.rows.toList()); assertEquals(1, out.closes)
        } finally { gate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun interrupted_close_retains_preexisting_write_failure_and_interrupt_flag() {
        val first = IOException("write"); val closeGate = Gate(ignoreInterrupts = true)
        val out = Output(closeGate = closeGate).apply { writeError = first }
        val sink = BoundedDiagnosticWriter(out, 1, 1_000_000, 2000)
        val pool = Executors.newSingleThreadExecutor(); val thread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        try {
            assertSame(first, assertFailsWith<IOException> { sink.record(record(), true) })
            assertTrue(closeGate.entered.await(2, TimeUnit.SECONDS))
            val closing = pool.submit<Pair<Throwable, Boolean>> {
                thread.set(Thread.currentThread()); entered.countDown()
                try { sink.close(); error("unexpected completion") }
                catch (error: Throwable) { error to Thread.currentThread().isInterrupted }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { closing.get(100, TimeUnit.MILLISECONDS) }
            thread.get().interrupt()
            val (failure, interrupted) = closing.get(2, TimeUnit.SECONDS)
            assertSame(first, failure); assertTrue(interrupted)
            closeGate.release.countDown()
            assertSame(first, assertFailsWith<IOException> { sink.close() })
        } finally { closeGate.release.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }

    @Test fun concurrent_producers_with_small_capacity_wake_without_losing_or_reordering_work() {
        val out = Output(); val sink = BoundedDiagnosticWriter(out, 2, 1_000_000, 2000)
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try {
            val accepted = List(8) { producer -> pool.submit<List<Pair<Long, String>>> {
                check(start.await(2, TimeUnit.SECONDS))
                List(20) { index ->
                    val label = "$producer-$index"; sink.record(record(label), false) to label
                }
            } }
            start.countDown()
            val expected = accepted.flatMap { it.get(5, TimeUnit.SECONDS) }.sortedBy { it.first }
            sink.flush(); sink.close()
            assertEquals((0L until 160L).toList(), expected.map { it.first })
            assertEquals(expected, out.rows.toList()); assertEquals(1, out.closes)
        } finally { start.countDown(); pool.shutdownNow(); runCatching { sink.close() } }
    }
}
