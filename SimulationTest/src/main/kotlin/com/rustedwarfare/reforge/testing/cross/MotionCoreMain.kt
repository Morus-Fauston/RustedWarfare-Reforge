package com.rustedwarfare.reforge.testing.cross

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        require(args.size==4 && args[0]=="--request" && args[2]=="--output") { "MotionCoreMainKt --request <file> --output <new-file>" }
        val document=MotionCoreCapture.capture(Path.of(args[1]),Path.of(args[3]))
        println("Captured ${document["recordCount"]} full-frequency core rows: ${args[3]}")
    } catch(error: Exception){System.err.println("Core motion capture failed: ${error.javaClass.simpleName}: ${error.message}");exitProcess(4)}
}
