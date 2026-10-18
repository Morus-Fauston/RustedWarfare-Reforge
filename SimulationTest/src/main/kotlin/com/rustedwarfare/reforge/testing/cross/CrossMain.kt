package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

private const val usage = "CrossMainKt --contract <file> --actual <jsonl> --expected <jsonl> --output <new-file>"

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) { println(usage); return }
    val options = try {
        val keys = setOf("--contract", "--actual", "--expected", "--output")
        require(args.size == 8) { usage }
        val pairs = args.toList().chunked(2)
        require(pairs.map { it[0] }.toSet() == keys && pairs.all { it[1].isNotBlank() }) { usage }
        pairs.associate { it[0] to Path.of(it[1]) }
    } catch (failure: IllegalArgumentException) {
        System.err.println(failure.message)
        exitProcess(64)
    }
    try {
        val result = CrossComparison.compare(options.getValue("--contract"), options.getValue("--actual"), options.getValue("--expected"))
        Files.writeString(options.getValue("--output"), result.document.toString() + "\n", StandardOpenOption.CREATE_NEW)
        println("${result.status}: ${options.getValue("--output").toAbsolutePath().normalize()}")
        exitProcess(result.exitCode)
    } catch (failure: java.io.IOException) {
        System.err.println("Cannot write cross result: ${failure.javaClass.simpleName}: ${failure.message}")
        exitProcess(4)
    }
}
