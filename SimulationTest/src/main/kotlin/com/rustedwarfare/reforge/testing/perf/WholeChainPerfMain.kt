package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlinx.serialization.json.*
import java.nio.file.*
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.UUID
import kotlin.system.exitProcess

/** S08 functional qualification by default; final measurement is an explicit maintainer command. */
object WholeChainPerfMain {
    fun protocol() = buildJsonObject {
        put("schema", "s08.perf.v2"); put("first", 1); put("warmup", 2); put("measured", 5)
        put("mapWidthTiles", 50); put("mapHeightTiles", 40); put("fixtureOffsetWorld", 200.0)
        put("maxSteps", 2500); put("terminalTail", 8); put("batchDeadlineSeconds", 120); put("sessionDeadlineSeconds", 3600)
        put("counts", JsonArray(listOf(1, 10, 50).map(::JsonPrimitive)))
        put("cases", JsonArray(WholeChainPerf.KINDS.map(::JsonPrimitive)))
        put("retentions", JsonArray(TerrainRetention.entries.map { JsonPrimitive(it.name) }))
        put("costModes", JsonArray(listOf("light", "full-trace").map(::JsonPrimitive)))
    }
    @JvmStatic fun main(args: Array<String>) {
        var output: Path? = null
        try {
            require(args.size == 10 && args.toList().chunked(2).map { it[0] }.toSet() ==
                setOf("--repo-root", "--protocol", "--protocol-sha", "--output", "--mode")) { "Expected --repo-root ABS --protocol ABS --protocol-sha SHA256 --output NEW_ABS --mode qualify|measure" }
            val options = args.toList().chunked(2).associate { it[0] to it[1] }
            val repo = Path.of(options.getValue("--repo-root")).toRealPath()
            val protocolPath = Path.of(options.getValue("--protocol")).toRealPath()
            require(sha(protocolPath) == options.getValue("--protocol-sha")) { "Protocol SHA mismatch" }
            require(Json.parseToJsonElement(Files.readString(protocolPath)) == protocol()) { "Protocol differs from frozen S08 v2" }
            val mode = options.getValue("--mode"); require(mode in setOf("qualify", "measure"))
            val target = Path.of(options.getValue("--output")); require(target.isAbsolute && !Files.exists(target))
            val parent = target.parent.toRealPath()
            require(listOf(".git", "Rusted Warfare", "测试文件", "参考项目", "TestLab/local").none { parent.startsWith(repo.resolve(it)) }) { "Protected output" }
            require(parent.startsWith(repo.resolve(".scratch/开发票据/测试系统/P002-原版参考与规则性能/evidence/TEST-P002-023")) ||
                parent.startsWith(repo.resolve("SimulationTest/build"))) { "Use S08 evidence or SimulationTest/build output" }
            output = Files.createDirectory(parent.resolve(target.fileName))
            fun write(name: String, json: JsonElement) = Files.writeString(output.resolve(name), "$json\n", StandardOpenOption.CREATE_NEW)
            val sources = listOf(repo.resolve("Engine/core/src/main"), repo.resolve("SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/perf")).flatMap { root ->
                Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
            } + listOf("CoreAdapter.kt", "JsonIO.kt").map { repo.resolve("SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/$it") }
            val jars = System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() }
            require(jars.isNotEmpty() && jars.all { Files.isRegularFile(it) && it.toString().endsWith(".jar") }) { "Use packaged installDist jars" }
            val pinned = (sources + jars + listOf(protocolPath)).distinct()
            val before = JsonArray(pinned.map(::identity)); write("identity-before.json", before)
            write("environment.json", buildJsonObject {
                put("mode", mode); put("runId", UUID.randomUUID().toString()); put("java", System.getProperty("java.version"))
                put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch")); put("processors", Runtime.getRuntime().availableProcessors())
                put("jvmArgs", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
                put("arguments", JsonArray(args.map(::JsonPrimitive))); put("purpose", if (mode == "qualify") "functional-only; timings are probe data, no final performance verdict" else "maintainer final measurement; no numeric acceptance threshold")
                put("batchBoundary", "submit+advance+light sampling+raw serialization/write+inline geometry/knowledge oracle; excludes init/close and final second-pass qualification")
                put("lightBoundary", "drain diagnostics+knowledge snapshot+heap sample; trace/output separately timed")
                put("fullTraceBoundary", "same movement/search raw plus all 2000 knowledge/visibility cells each step; costs separately timed")
                put("unavailable", "GPU/native/process memory, individual GC pause distribution, async queue latency; frame/input sampled by separate platform collector")
            })
            write("protocol.json", protocol())
            val begun = System.nanoTime()
            val summaries = mutableListOf<JsonObject>()
            for (kind in WholeChainPerf.KINDS) for (count in listOf(1, 10, 50)) for (retention in TerrainRetention.entries) {
                val fixture = WholeChainPerf.fixture(kind, count, retention)
                val costs = if (mode == "measure") listOf(false, true) else listOf(false)
                for (full in costs) for (round in if (mode == "measure") 0..7 else 0..0) {
                    check(System.nanoTime() - begun < 3_600_000_000_000L) { "Session 3600s deadline; preserve partial batch" }
                    val phase = if (mode == "qualify") "qualification" else when (round) { 0 -> "first"; 1, 2 -> "warmup"; else -> "measured" }
                    val name = "$kind-$count-${retention.name}-${if (full) "full" else "light"}-$round"
                    Files.newBufferedWriter(output.resolve("$name.jsonl"), StandardOpenOption.CREATE_NEW).use { writer ->
                        val run = WholeChainPerf.capture(fixture, full) { row -> writer.write(row.toString()); writer.newLine(); writer.flush() }
                        summaries += JsonObject(WholeChainPerf.summary(run) + mapOf("phase" to JsonPrimitive(phase), "raw" to JsonPrimitive("$name.jsonl")))
                    }
                }
            }
            // One bounded same-input full trace control qualifies its independent cost path.
            if (mode == "qualify") {
                val fixture = WholeChainPerf.fixture("OPEN", 1, TerrainRetention.MEMORY)
                Files.newBufferedWriter(output.resolve("full-control.jsonl"), StandardOpenOption.CREATE_NEW).use { writer ->
                    val run = WholeChainPerf.capture(fixture, true) { writer.write(it.toString()); writer.newLine(); writer.flush() }
                    val summary = WholeChainPerf.summary(run)
                    check(summary["gameplaySha256"] == summaries.single { it["case"] == JsonPrimitive("OPEN") && it["count"] == JsonPrimitive(1) && it["retention"] == JsonPrimitive("MEMORY") }["gameplaySha256"]) { "Trace cost path changed gameplay" }
                    summaries += JsonObject(summary + mapOf("phase" to JsonPrimitive("cost-control"), "raw" to JsonPrimitive("full-control.jsonl")))
                }
            }
            for (group in summaries.groupBy { listOf(it["case"], it["count"], it["retention"]) }.values)
                check(group.map { it["gameplaySha256"] }.distinct().size == 1) { "Repeated/cost-control gameplay differs" }
            val after = JsonArray(pinned.map(::identity)); write("identity-after.json", after)
            check(before == after) { "Source/input/artifact drift" }
            write("summary.json", JsonArray(summaries))
            write("aggregate.json", aggregate(summaries))
            write("completion.json", buildJsonObject { put("complete", true); put("mode", mode); put("batches", summaries.size); put("finalPerformanceVerdict", "not-evaluated") })
            println(output)
        } catch (failure: Throwable) {
            preserveFailure(failure) { output?.let { Files.writeString(it.resolve("failure.txt"), failure.stackTraceToString(), StandardOpenOption.CREATE_NEW) } }
            System.err.println(failure.stackTraceToString()); exitProcess(if (failure is IllegalArgumentException) 64 else 4)
        }
    }
    fun aggregate(rows: List<JsonObject>) = JsonArray(rows.groupBy { row -> listOf("case", "count", "retention", "fullTrace", "phase").map { row.getValue(it) } }.map { (key, values) ->
        buildJsonObject {
            for ((i, field) in listOf("case", "count", "retention", "fullTrace", "phase").withIndex()) put(field, key[i])
            put("batchNanos", WholeChainPerf.stats(values.map { it.getValue("batchNanos").jsonPrimitive.long }))
            put("batches", values.size)
        }
    })
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun identity(path: Path) = buildJsonObject { put("path", path.toString()); put("bytes", Files.size(path)); put("sha256", sha(path)) }
}
