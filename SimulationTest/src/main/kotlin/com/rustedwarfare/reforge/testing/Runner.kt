package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

data class RunRequest(
    val scenario: Path,
    val contract: Path,
    val output: Path,
    val command: List<String>,
    val timeoutMillis: Long = 120_000,
    val cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
)
data class RunResult(val exitCode: Int, val status: String, val output: Path)

object Runner {
    fun run(request: RunRequest): RunResult {
        require(request.timeoutMillis > 0) { "timeoutMillis must be positive" }
        val started = Instant.now()
        val startNanos = System.nanoTime()
        var loaded: LoadedScenario? = null
        var observed = 0
        var initialStateChecked = false
        var required = 0
        var collectionStatus = "incomplete"
        var status = "execution-failed"
        var exitCode = 4
        var error: String? = null
        var judgment: Judgment? = null
        var contract: JsonObject? = null
        var scenario: JsonObject? = null
        val output = request.output.toAbsolutePath().normalize()
        RunArchive(output).use { archive ->
            archive.write(record("run-start", "runId" to s(output.fileName.toString()), "startedAt" to s(started.toString())))
            try {
                archive.snapshot(request.scenario, "scenario.json")
                archive.snapshot(request.contract, "contract.json")
                loaded = ScenarioReader.load(request.scenario, request.contract,
                    output.resolve("inputs/scenario.json"), output.resolve("inputs/contract.json"))
                val input = loaded
                contract = input.contractDocument
                scenario = input.scenarioDocument
                TraceComparator.validateContract(scenario, contract)
                required = input.steps.size
                if (sha256(output.resolve("inputs/scenario.json")) != input.scenarioIdentity.sha256 ||
                    sha256(output.resolve("inputs/contract.json")) != input.contractIdentity.sha256) {
                    throw InputError("Input changed during snapshot")
                }
                fun checkExecution() {
                    if (request.cancelled()) throw RunCancelled()
                    if ((System.nanoTime() - startNanos) / 1_000_000 >= request.timeoutMillis) throw RunTimedOut()
                }
                checkExecution()
                CoreAdapter(input).use { core ->
                    val initial = core.initial()
                    initialStateChecked = true
                    archive.observation(initial, "initial", input)
                    for (step in input.steps) {
                        checkExecution()
                        input.actions.filter { it.integer("atStep") == step.stepIndex }.forEach { action ->
                            checkExecution()
                            archive.write(record("action-result", "atStep" to n(step.stepIndex), "action" to action,
                                "result" to core.submit(action).json()), input)
                        }
                        val observation = core.advance(step)
                        if (observation.stepIndex != step.stepIndex || observation.scenarioId != input.core.scenarioId ||
                            observation.simulationTime != (observed + 1).toDouble()) {
                            throw CaptureError("Observation does not match the requested simulation boundary")
                        }
                        archive.observation(observation, "after-advance", input)
                        observed++
                    }
                }
                checkExecution()
                if (input.identities.any { sha256(it.path) != it.sha256 }) throw InputError("Content changed during execution")
                if (sha256(output.resolve("inputs/scenario.json")) != input.scenarioIdentity.sha256 ||
                    sha256(output.resolve("inputs/contract.json")) != input.contractIdentity.sha256 ||
                    sha256(request.scenario) != input.scenarioIdentity.sha256 || sha256(request.contract) != input.contractIdentity.sha256) {
                    throw InputError("Scenario/contract input identity changed during execution")
                }
                collectionStatus = "collected"
                // Validate the completed capture in memory before writing its final judgment/footer.
                val completion = record("run-end", "status" to s("completed"), "exitCode" to n(0),
                    "observedSteps" to n(observed), "requiredSteps" to n(required), "complete" to b(true))
                judgment = TraceComparator.evaluate(scenario, contract,
                    archive.records + completion)
                status = judgment.status
                exitCode = judgment.exitCode
                error = (judgment.document["reason"] as? JsonPrimitive)?.content
            } catch (failure: RunCancelled) {
                Thread.interrupted() // Clear the flag so evidence IO can finish after cooperative cancellation.
                status = "cancelled"; exitCode = 3; error = "Cancellation requested at an explicit boundary"
            } catch (failure: RunTimedOut) {
                status = "execution-failed"; exitCode = 4; error = "Cooperative execution timeout"
            } catch (failure: InputError) {
                status = "execution-failed"; exitCode = 64; error = failure.message
            } catch (failure: CaptureError) {
                status = "evidence-insufficient"; exitCode = 3; error = failure.message
            } catch (failure: Exception) {
                status = "execution-failed"; exitCode = 4; error = "${failure.javaClass.simpleName}: ${failure.message}"
            }
            val complete = collectionStatus == "collected" && observed == required && required > 0
            archive.write(record("run-end", "status" to s(status), "exitCode" to n(exitCode),
                "observedSteps" to n(observed), "requiredSteps" to n(required), "complete" to b(complete),
                "collectionStatus" to s(collectionStatus), "error" to error?.let(::s)), loaded)
            archive.document("comparison/result.json", (judgment ?: TraceComparator.judgment(exitCode, status,
                contract, if (exitCode == 64) "input-error" else status, reason = error)).document)
            archive.document("logs/execution.json", record("execution-log", "command" to array(request.command.map(::s)),
                "workingDirectory" to s(Path.of("").toAbsolutePath().toString()),
                "exitCode" to n(exitCode), "error" to error?.let(::s)))
            val tool = toolIdentity()
            archive.document("manifest.json", record("run-manifest", "runId" to s(output.fileName.toString()),
                "ticket" to s("TEST-P001-002"), "period" to s("P001-自身行为验证"), "topic" to s(output.parent.fileName.toString()),
                "startedAt" to s(started.toString()), "finishedAt" to s(Instant.now().toString()),
                "wallElapsedMillis" to n((System.nanoTime() - startNanos) / 1_000_000),
                "status" to s(status), "exitCode" to n(exitCode), "complete" to b(complete),
                "collectionStatus" to s(collectionStatus),
                "environment" to obj("os" to s(System.getProperty("os.name")), "osVersion" to s(System.getProperty("os.version")),
                    "arch" to s(System.getProperty("os.arch")), "java" to s(System.getProperty("java.runtime.version"))),
                "tool" to tool,
                "scenario" to (loaded?.scenarioIdentity?.json() ?: fileIdentity(request.scenario)),
                "contract" to (loaded?.contractIdentity?.json() ?: fileIdentity(request.contract)),
                "inputs" to array(loaded?.identities?.map { it.json() } ?: emptyList()),
                "inputSnapshots" to array(listOf("scenario.json", "contract.json").mapNotNull {
                    val snapshot = output.resolve("inputs/$it")
                    if (Files.isRegularFile(snapshot)) obj("path" to s("inputs/$it"), "sha256" to s(sha256(snapshot))) else null
                }),
                "commands" to array(listOf(array(request.command.map(::s)))),
                "coverage" to obj("initialStateChecked" to b(initialStateChecked), "observedSteps" to n(observed),
                    "requiredSteps" to n(required), "ruleEvaluation" to b(judgment != null), "originalCompatibility" to b(false)),
                "traceFiles" to array(listOf("reforge/raw.jsonl", "reforge/normalized.jsonl").map {
                    obj("path" to s(it), "sha256" to s(sha256(output.resolve(it))))
                }),
                "error" to error?.let(::s),
            ))
        }
        return RunResult(exitCode, status, output)
    }

    private fun fileIdentity(path: Path): JsonObject = obj("path" to s(path.toAbsolutePath().normalize().toString()),
        "sha256" to if (Files.isRegularFile(path)) s(sha256(path)) else JsonNull)

    private fun toolIdentity(): JsonObject {
        val artifacts = listOf(Runner::class.java, com.rustedwarfare.reforge.core.sim.SimulationContext::class.java,
            Json::class.java, kotlinx.serialization.KSerializer::class.java, kotlin.KotlinVersion::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().map { path ->
            val hash = if (Files.isDirectory(path)) Files.walk(path).use { files ->
                digest(files.filter { Files.isRegularFile(it) }.sorted().map {
                    "${path.relativize(it)}:${sha256(it)}"
                }.toList().joinToString("\n").toByteArray(Charsets.UTF_8))
            } else sha256(path)
            obj("path" to s(path.toString()), "sha256" to s(hash))
        }
        return obj("id" to s("simulation-test.p001.record-chain"),
            "sha256" to s(digest(artifacts.joinToString("\n") { it.text("sha256") }.toByteArray(Charsets.UTF_8))),
            "artifacts" to array(artifacts))
    }
}

private class RunCancelled : RuntimeException()
private class RunTimedOut : RuntimeException()
