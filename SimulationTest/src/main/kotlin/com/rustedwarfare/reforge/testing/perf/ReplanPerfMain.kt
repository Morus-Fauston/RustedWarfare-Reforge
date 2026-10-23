package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.sim.SimulationContext
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.system.exitProcess

/** Development replanning protocol; historical RP contracts do not select this checker. */
object ReplanPerfMain {
    internal fun requireDevelopmentProtocol(protocol: Path, expectedSha: String) {
        require(expectedSha == ReplanPerf.DEVELOPMENT_CONTRACT_SHA256 && sha(protocol) == expectedSha) {
            "Explicit development replanning contract identity required"
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        var output: Path? = null
        try {
            val keys = setOf("--repo-root", "--unit", "--unit-sha", "--protocol", "--protocol-sha", "--output")
            require(args.size == 12)
            val pairs = args.toList().chunked(2)
            require(pairs.map { it[0] }.toSet() == keys)
            val options = pairs.associate { it[0] to it[1] }
            val repo = Path.of(options.getValue("--repo-root")).toRealPath()
            val unitPath = Path.of(options.getValue("--unit")).toRealPath()
            val protocol = Path.of(options.getValue("--protocol")).toRealPath()
            require(sha(unitPath) == options.getValue("--unit-sha")) { "Unit identity mismatch" }
            requireDevelopmentProtocol(protocol,options.getValue("--protocol-sha"))
            val root = Path.of(options.getValue("--output")).toRealPath()
            require(Files.isDirectory(root))
            require(listOf(".git", "Rusted Warfare", "测试文件", "参考项目", "TestLab/local").none { root.startsWith(repo.resolve(it)) }) { "Protected output root" }
            val unit = (ContentLoader.loadUnitVariant(unitPath) as? ContentResult.Success)?.value ?: error("Unit load failed")
            val sourceFiles = Files.walk(repo.resolve("Engine/core/src/main")).use { stream -> stream.filter { Files.isRegularFile(it) }.sorted().toList() } +
                listOf("perf/ReplanPerf.kt", "perf/ReplanPerfMain.kt", "perf/PathPerfBatch.kt", "MotionCenterGeometry.kt", "CoreAdapter.kt", "JsonIO.kt").map {
                    repo.resolve("SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/$it")
                }
            val code = listOf(ReplanPerf::class.java, SimulationContext::class.java, Json::class.java,
                kotlinx.serialization.KSerializer::class.java, Unit::class.java).map {
                Path.of(it.protectionDomain.codeSource.location.toURI())
            }.distinct()
            require(code.all { Files.isRegularFile(it) }) { "Use the packaged CLI with jar code sources" }
            val pinned = (sourceFiles + code + listOf(unitPath, unit.imagePath, protocol)).distinct()
            val before = pinned.map(::identity)
            val runOutput = Files.createDirectory(root.resolve("run-rp-development-" + UUID.randomUUID()))
            output = runOutput
            fun write(name: String, value: JsonElement) = Files.writeString(runOutput.resolve(name), value.toString() + "\n", StandardOpenOption.CREATE_NEW)
            write("identity-before.json", JsonArray(before))
            write("environment.json", buildJsonObject {
                ReplanPerf.identityFields().forEach { (key,value)->put(key,value) }
                put("kind", "rp-development-center-v2-environment"); put("cwd", Path.of("").toAbsolutePath().toString())
                put("javaVersion", System.getProperty("java.version")); put("javaVm", System.getProperty("java.vm.name"))
                put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch")); put("processors", Runtime.getRuntime().availableProcessors())
                put("jvmArgs", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
                put("arguments", JsonArray(args.map(::JsonPrimitive)))
                put("boundary", "External trusted projection; no automatic vision, rendering, device FPS or performance threshold")
                put("batchWallBoundary", "projection+submit+advance+drain+gameplay JSON construction; excludes initialization, file output and later validation")
                put("unavailable", "allocation bytes, GC individual pause distribution, queue wait (no queue), frame timings")
            })
            val summary = mutableListOf<JsonObject>()
            var batches = 0
            var sampleCount = 0
            var writeNanos = 0L
            for (mode in TerrainRetention.entries) for (count in listOf(1, 10, 50)) {
                val on = ReplanPerf.measure(unit, count, mode, true)
                val off = ReplanPerf.measure(unit, count, mode, false)
                write("control-${mode.name}-$count.json", buildJsonObject {
                    ReplanPerf.identityFields().forEach { (key,value)->put(key,value) }
                    put("on", ReplanPerf.batchJson(on)); put("off", ReplanPerf.batchJson(off))
                    put("sameGameplay", ReplanPerf.sameGameplay(on, off))
                })
                ReplanPerf.validate(on); ReplanPerf.validate(off)
                check(ReplanPerf.sameGameplay(on, off)) { "Diagnostics altered gameplay" }
                val rounds = mutableListOf<Pair<Int, ReplanBatch>>()
                for (round in 0..7) {
                    val batch = ReplanPerf.measure(unit, count, mode, true)
                    val writeStart = System.nanoTime()
                    write("batch-${mode.name}-$count-$round.json", ReplanPerf.batchJson(batch))
                    writeNanos += System.nanoTime() - writeStart
                    ReplanPerf.validate(batch)
                    rounds += round to batch
                    batches++
                    sampleCount += batch.steps.sumOf { it.samples.size }
                }
                for ((phase, range) in listOf("first" to 0..0, "warmup" to 1..2, "measured" to 3..7)) {
                    val selected = rounds.filter { it.first in range }.map { it.second }
                    val searches = selected.flatMap { it.steps }.flatMap { it.samples }
                    summary += buildJsonObject {
                        ReplanPerf.identityFields().forEach { (key,value)->put(key,value) }
                        put("retention", mode.name); put("count", count); put("phase", phase)
                        put("batchWallNanos", stats(selected.map { it.wallNanos }))
                        put("advanceNanos", stats(selected.flatMap { it.steps }.map { it.advanceNanos }))
                        put("searchNanos", stats(searches.map { it.searchNanos }))
                        put("inputPreparationNanos", stats(searches.map { it.inputPreparationNanos }))
                        put("actualSearchCalls", searches.sumOf { it.actualSearchCalls })
                        put("replans", searches.count { it.reason.name == "KNOWLEDGE_REPLAN" })
                        put("expanded", searches.sumOf { it.expanded }); put("neighborsChecked", searches.sumOf { it.neighborsChecked })
                    }
                }
            }
            check(batches == 48 && sampleCount == 3904) { "Missing batches/samples: $batches/$sampleCount" }
            val after = pinned.map(::identity)
            write("identity-after.json", JsonArray(after))
            check(before == after) { "Input/source/artifact drift" }
            write("summary.json", JsonArray(summary))
            write("completion.json", buildJsonObject {
                ReplanPerf.identityFields().forEach { (key,value)->put(key,value) }
                put("complete", true); put("exitCode", 0); put("batches", batches); put("searchSamples", sampleCount)
                put("controlPairs", 6); put("timedBatchWriteNanos", writeNanos); put("originalCompatibility", false)
            })
            println(output)
        } catch (error: Throwable) {
            output?.let { Files.writeString(it.resolve("failure.txt"), error.stackTraceToString(), StandardOpenOption.CREATE_NEW) }
            System.err.println(error.stackTraceToString())
            exitProcess(if (error is IllegalArgumentException) 64 else 4)
        }
    }

    private fun stats(values: List<Long>) = buildJsonObject {
        put("n", values.size); put("p50", PathPerfBatch.nearestRank(values, .5)); put("p95", PathPerfBatch.nearestRank(values, .95)); put("max", values.max())
    }
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun identity(path: Path) = buildJsonObject { put("path", path.toString()); put("bytes", Files.size(path)); put("sha256", sha(path)) }
}
