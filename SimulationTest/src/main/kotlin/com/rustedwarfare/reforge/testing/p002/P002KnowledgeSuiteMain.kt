package com.rustedwarfare.reforge.testing.p002

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        if (args.size == 2 && args[0] == "--verify-archive") {
            val verdict = P002KnowledgeSuite.verify(Path.of(args[1]))
            println("p002Knowledge verify exit=${verdict.exitCode} reason=${verdict.reason}")
            exitProcess(verdict.exitCode)
        }
        require(args.size == 6) { "--repo-root <absolute> --suite <relative> --output <absolute>" }
        val pairs = args.toList().chunked(2)
        require(pairs.map { it[0] }.toSet() == setOf("--repo-root", "--suite", "--output")) { "Expected unique root, suite and output options" }
        val options = pairs.associate { it[0] to it[1] }
        val root = Path.of(options.getValue("--repo-root")); require(root.isAbsolute)
        val output = Path.of(options.getValue("--output")); require(output.isAbsolute)
        val result = P002KnowledgeSuite.run(root, options.getValue("--suite"), output, listOf("p002KnowledgeSuite") + args)
        println("p002Knowledge exit=${result.exitCode} cases=${result.caseCount} output=${result.output}")
        exitProcess(result.exitCode)
    } catch (error: IllegalArgumentException) { System.err.println(error.message); exitProcess(64) }
      catch (error: Exception) { System.err.println("${error.javaClass.simpleName}: ${error.message}"); exitProcess(4) }
}
