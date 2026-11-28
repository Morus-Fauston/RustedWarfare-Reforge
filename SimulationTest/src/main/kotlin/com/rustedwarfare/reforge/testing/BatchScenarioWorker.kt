package com.rustedwarfare.reforge.testing

import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal object BatchScenarioWorker {
    private val stampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

    fun serve(reader: BufferedReader, protocolOut: PrintStream, sessionOnly: Boolean = false) {
        val fallbackErr = PrintStream(FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8)
        System.setOut(fallbackErr)
        System.setErr(fallbackErr)
        while (true) {
            val rawLine = reader.readLine() ?: break
            if (rawLine.isBlank()) continue
            Thread.interrupted()
            val response = handleLine(rawLine, fallbackErr, sessionOnly)
            protocolOut.println(response.toString())
            protocolOut.flush()
            if (Runtime.getRuntime().totalMemory() > 300L * 1024 * 1024) {
                System.gc()
            }
        }
    }

    private fun JsonObject.optString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.reqString(key: String): String =
        optString(key)?.takeIf { it.isNotBlank() } ?: throw InputError("Missing $key")

    private fun JsonObject.optLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private inline fun <T> withTaskLogs(req: JsonObject, fallbackErr: PrintStream, block: () -> T): T {
        val stdoutPath = req.optString("stdoutLog")?.let(Path::of)
        val stderrPath = req.optString("stderrLog")?.let(Path::of)
        if (stdoutPath == null && stderrPath == null) {
            System.setOut(fallbackErr)
            System.setErr(fallbackErr)
            return block()
        }
        stdoutPath?.parent?.let(Files::createDirectories)
        stderrPath?.parent?.let(Files::createDirectories)
        val outStream = stdoutPath?.let { PrintStream(Files.newOutputStream(it, CREATE_NEW), true, Charsets.UTF_8) }
        val errStream = stderrPath?.let { PrintStream(Files.newOutputStream(it, CREATE_NEW), true, Charsets.UTF_8) }
        try {
            System.setOut(outStream ?: fallbackErr)
            System.setErr(errStream ?: fallbackErr)
            return block()
        } finally {
            outStream?.flush()
            errStream?.flush()
            System.setOut(fallbackErr)
            System.setErr(fallbackErr)
            outStream?.close()
            errStream?.close()
        }
    }

    private fun handleLine(rawLine: String, fallbackErr: PrintStream, sessionOnly: Boolean): JsonObject {
        val req = try {
            json.parseToJsonElement(rawLine).jsonObject
        } catch (parseErr: Exception) {
            return obj(
                "id" to s(""),
                "ok" to b(false),
                "exitCode" to n(64),
                "errorClass" to s(parseErr.javaClass.simpleName),
                "message" to s(parseErr.message ?: "Invalid JSON request"),
            )
        }
        val id = req.optString("id") ?: ""
        val cmd = req.optString("cmd") ?: if (sessionOnly) "session-run" else ""
        return withTaskLogs(req, fallbackErr) {
            try {
                if (sessionOnly && cmd != "session-run") {
                    throw InputError("PlatformSessionRunner batch worker supports only session-run")
                }
                when (cmd) {
                    "core-run" -> executeCoreRun(id, req)
                    "core-compare" -> executeCoreCompare(id, req)
                    "core-assertions" -> executeCoreAssertions(id, req)
                    "session-run" -> executeSessionRun(id, req)
                    else -> {
                        System.err.println("Unsupported batch worker command: $cmd")
                        obj("id" to s(id), "ok" to b(false), "exitCode" to n(64), "message" to s("Unsupported batch command: $cmd"))
                    }
                }
            } catch (oom: VirtualMachineError) {
                throw oom
            } catch (inputErr: InputError) {
                val code = if (sessionOnly || cmd == "session-run") 1 else 64
                inputErr.printStackTrace(System.err)
                obj(
                    "id" to s(id),
                    "ok" to b(false),
                    "exitCode" to n(code),
                    "errorClass" to s(inputErr.javaClass.simpleName),
                    "message" to s(inputErr.message ?: ""),
                )
            } catch (failure: Throwable) {
                val code = when (cmd) {
                    "core-run", "core-compare" -> 4
                    else -> 1
                }
                if (cmd == "core-run" || cmd == "core-compare") {
                    System.err.println("Cannot complete archive: ${failure.javaClass.simpleName}: ${failure.message}")
                } else {
                    failure.printStackTrace(System.err)
                }
                obj(
                    "id" to s(id),
                    "ok" to b(false),
                    "exitCode" to n(code),
                    "errorClass" to s(failure.javaClass.simpleName),
                    "message" to s(failure.message ?: ""),
                )
            }
        }
    }

    private fun executeCoreRun(id: String, req: JsonObject): JsonObject {
        val scenario = Path.of(req.reqString("scenario"))
        val contract = Path.of(req.reqString("contract"))
        val baseOutput = Path.of(req.reqString("output"))
        val timeoutMs = req.optLong("timeoutMs") ?: 120_000L
        if (timeoutMs <= 0L) throw InputError("Invalid timeoutMs")
        val cancel = req.optString("cancelFile")?.let(Path::of)
        val stamp = stampFormatter.format(Instant.now())
        val runDir = baseOutput.resolve("run-$stamp-${UUID.randomUUID().toString().take(8)}")
        val argv = listOf("simulation-test", "run", "--scenario", scenario.toString(), "--contract", contract.toString(),
            "--output", baseOutput.toString(), "--mode", "self") +
            (if ("timeoutMs" in req) listOf("--timeout-ms", timeoutMs.toString()) else emptyList()) +
            (if (cancel != null) listOf("--cancel-file", cancel.toString()) else emptyList())
        val result = Runner.run(RunRequest(
            scenario = scenario,
            contract = contract,
            output = runDir,
            command = argv,
            timeoutMillis = timeoutMs,
            cancelled = { Thread.currentThread().isInterrupted || (cancel != null && Files.exists(cancel)) },
        ))
        println("${result.status}: ${result.output}")
        val fields = mutableListOf<Pair<String, JsonElement>>(
            "id" to s(id),
            "ok" to b(result.exitCode == 0),
            "exitCode" to n(result.exitCode),
            "status" to s(result.status),
            "runOutput" to s(result.output.toString()),
        )
        return obj(*fields.toTypedArray())
    }

    private fun executeCoreCompare(id: String, req: JsonObject): JsonObject {
        val scenario = Path.of(req.reqString("scenario"))
        val contract = Path.of(req.reqString("contract"))
        val actual = Path.of(req.reqString("actual"))
        val expected = req.optString("expected")?.let(Path::of)
        val baseOutput = Path.of(req.reqString("output"))
        val stamp = stampFormatter.format(Instant.now())
        val runDir = baseOutput.resolve("run-$stamp-${UUID.randomUUID().toString().take(8)}")
        val argv = listOf("simulation-test", "compare", "--scenario", scenario.toString(), "--contract", contract.toString(),
            "--actual", actual.toString()) +
            (if (expected != null) listOf("--expected", expected.toString()) else emptyList()) +
            listOf("--output", baseOutput.toString(), "--mode", "self")
        val result = TraceFiles.compare(scenario, contract, actual, expected)
        Files.createDirectories(runDir.parent)
        Files.createDirectory(runDir)
        Files.createDirectory(runDir.resolve("comparison"))
        Files.writeString(runDir.resolve("comparison/result.json"), result.document.toString(), CREATE_NEW)
        Files.writeString(runDir.resolve("command.json"), record("comparison-command", "argv" to array(argv.map(::s))).toString(), CREATE_NEW)
        val normalizedOut = runDir.toAbsolutePath().normalize()
        println("${result.status}: $normalizedOut")
        return obj(
            "id" to s(id),
            "ok" to b(result.exitCode == 0),
            "exitCode" to n(result.exitCode),
            "status" to s(result.status),
            "runOutput" to s(normalizedOut.toString()),
        )
    }

    private fun executeCoreAssertions(id: String, req: JsonObject): JsonObject {
        val family = req.reqString("family")
        val scenario = req.reqString("scenario")
        val contract = req.reqString("contract")
        val archive = req.reqString("archive")
        val output = req.reqString("output")
        runPublicArchiveAssertions(arrayOf(family, scenario, contract, archive, output))
        return obj("id" to s(id), "ok" to b(true), "exitCode" to n(0))
    }

    private fun executeSessionRun(id: String, req: JsonObject): JsonObject {
        val inputs = Path.of(req.reqString("inputs"))
        val plan = Path.of(req.reqString("plan"))
        val output = Path.of(req.reqString("output"))
        val loadTimeoutSeconds = (req.optLong("loadTimeoutSeconds") ?: 30L).toInt()
        PlatformSessionRunner.run(inputs, plan, output, loadTimeoutSeconds)
        return obj("id" to s(id), "ok" to b(true), "exitCode" to n(0))
    }
}
