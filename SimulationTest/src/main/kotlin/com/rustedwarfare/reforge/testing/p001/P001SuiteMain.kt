package com.rustedwarfare.reforge.testing.p001

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out),true,Charsets.UTF_8))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err),true,Charsets.UTF_8))
    try {
        if(args.size==2 && args[0]=="--verify-archive") { exitProcess(P001Suite.verifyArchive(Path.of(args[1]).toAbsolutePath().normalize())) }
        if(args.size!=6) throw IllegalArgumentException("p001Suite --repo-root <absolute-root> --suite <root-relative-file> --output <root-relative-topic>")
        val pairs=args.toList().chunked(2)
        require(pairs.map { it[0] }.toSet()==setOf("--repo-root","--suite","--output")) { "Expected unique repo-root, suite and output options" }
        val opts=pairs.associate { it[0] to it[1] }
        val root=Path.of(opts.getValue("--repo-root"));require(root.isAbsolute) { "repo-root must be explicit and absolute" }
        val result=P001Suite.run(root,opts.getValue("--suite"),opts.getValue("--output"),listOf("p001Suite")+args)
        println("p001Suite exit=${result.exitCode} output=${result.output}")
        exitProcess(result.exitCode)
    } catch(failure:IllegalArgumentException) { System.err.println(failure.message);exitProcess(64) }
      catch(failure:Exception) { System.err.println("${failure.javaClass.simpleName}: ${failure.message}");exitProcess(4) }
}
