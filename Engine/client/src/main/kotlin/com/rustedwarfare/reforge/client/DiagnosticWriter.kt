package com.rustedwarfare.reforge.client

import java.io.BufferedWriter

/** Platform codecs retain their own fields; lifecycle and sequence belong to the recorder. */
interface DiagnosticOutput : AutoCloseable {
    fun write(sequence: Long, record: FixedDiagnosticRecord)
    fun flush()
}

class DiagnosticLineOutput(private val writer: BufferedWriter,
    private val encode: (Long, FixedDiagnosticRecord) -> String) : DiagnosticOutput {
    override fun write(sequence: Long, record: FixedDiagnosticRecord) {
        writer.write(encode(sequence, record)); writer.newLine()
    }
    override fun flush() = writer.flush()
    override fun close() = writer.close()
}

interface DiagnosticWriter : AutoCloseable {
    fun record(record: FixedDiagnosticRecord, forceFlush: Boolean): Long
    fun flush()
    fun checkFailure()
}

/** Android and immediate-flush consumers retain synchronous execution. */
class SynchronousDiagnosticWriter(private val output: DiagnosticOutput) : DiagnosticWriter {
    private var sequence = 0L
    private var failure: Throwable? = null
    private var closed = false

    private fun remember(error: Throwable) {
        val first = failure
        if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
    }
    @Synchronized override fun checkFailure() { failure?.let { throw it } }
    private fun open() { checkFailure(); check(!closed) { "Diagnostic writer is closed" } }

    @Synchronized override fun record(record: FixedDiagnosticRecord, forceFlush: Boolean): Long {
        open()
        val current = sequence++
        try { output.write(current, record); if (forceFlush) output.flush() }
        catch (error: Throwable) { remember(error); throw error }
        return current
    }

    @Synchronized override fun flush() {
        open()
        try { output.flush() } catch (error: Throwable) { remember(error); throw error }
    }

    @Synchronized override fun close() {
        if (closed) { checkFailure(); return }
        closed = true
        if (failure == null) try { output.flush() } catch (error: Throwable) { remember(error) }
        try { output.close() } catch (error: Throwable) { remember(error) }
        checkFailure()
    }
}
