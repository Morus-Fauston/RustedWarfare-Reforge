package com.rustedwarfare.reforge.testing

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.exitProcess

private const val usage = "simulation-test run --scenario <file> --contract <file> --output <topic-directory> --mode self [--timeout-ms <positive-integer>] [--cancel-file <file>]"

fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
    if (args.contentEquals(arrayOf("--help"))) { println(usage); return }
    if (args.contentEquals(arrayOf("--version"))) { println("simulation-test schemaVersion=1 ticket=TEST-P001-002"); return }
    val options = try {
        if (args.firstOrNull() != "run" || args.size % 2 != 1) throw InputError("Expected run and option/value pairs")
        val entries = args.drop(1).chunked(2)
        val allowed = setOf("--scenario", "--contract", "--output", "--mode", "--timeout-ms", "--cancel-file")
        if (entries.any { it[0] !in allowed || it[1].isBlank() } || entries.map { it[0] }.distinct().size != entries.size) {
            throw InputError("Unknown or duplicate option")
        }
        entries.associate { it[0] to it[1] }.also {
            listOf("--scenario", "--contract", "--output", "--mode").forEach { key -> if (key !in it) throw InputError("Missing $key") }
            if (it["--mode"] != "self") throw InputError("Only --mode self is supported")
        }
    } catch (failure: InputError) { System.err.println("${failure.message}\n$usage"); exitProcess(64) }
    val timeout = options["--timeout-ms"]?.toLongOrNull() ?: if ("--timeout-ms" in options) {
        System.err.println("Invalid --timeout-ms"); exitProcess(64)
    } else 120_000L
    if (timeout <= 0) { System.err.println("Invalid --timeout-ms"); exitProcess(64) }
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
    try {
        val output = Path.of(options.getValue("--output")).resolve("run-$stamp-${UUID.randomUUID().toString().take(8)}")
        val cancel = options["--cancel-file"]?.let(Path::of)
        val result = Runner.run(RunRequest(Path.of(options.getValue("--scenario")), Path.of(options.getValue("--contract")),
            output, listOf("simulation-test") + args.toList(), timeout,
            { Thread.currentThread().isInterrupted || (cancel != null && Files.exists(cancel)) }))
        println("${result.status}: ${result.output}")
        exitProcess(result.exitCode)
    } catch (failure: Exception) {
        System.err.println("Cannot complete archive: ${failure.javaClass.simpleName}: ${failure.message}")
        exitProcess(4)
    }
}
