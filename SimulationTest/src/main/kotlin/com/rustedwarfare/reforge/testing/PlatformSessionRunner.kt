package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.client.SandboxSession
import com.rustedwarfare.reforge.core.sim.Position
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    require(args.size == 3) { "PlatformSessionRunnerKt INPUT_ROOT PLAN_JSON NEW_OUTPUT_DIRECTORY" }
    PlatformSessionRunner.run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))
}

/** Executes client intentions; it never edits simulation state or expected values. */
object PlatformSessionRunner {
    fun run(inputs: Path, plan: Path, output: Path) {
        val document = readObject(plan)
        require(document.integer("schemaVersion") == 1L) { "Unsupported platform plan version" }
        val intents = document.arrayAt("intents").map { it.jsonObject }
        require(intents.isNotEmpty() && intents.map { it.id("intentId") }.distinct().size == intents.size) { "Empty or duplicate intent IDs" }
        Files.createDirectory(output)
        var sequence = 0L
        Files.newBufferedWriter(output.resolve("client-diagnostic.jsonl"), CREATE_NEW).use { writer ->
            val evidence = ClientEvidence { kind, values ->
                val row = obj("sequence" to n(sequence++), "kind" to s(kind))
                writer.write(JsonObject(row + values.mapValues { encode(it.value) }).toString())
                writer.newLine()
                writer.flush()
            }
            SandboxSession(ControlledScenario.load(inputs), evidence).use { session ->
                for (intent in intents) {
                    evidence.record("intent-start", mapOf("intentId" to intent.id("intentId")))
                    when (intent.text("kind")) {
                        "select" -> {
                            val position = intent.objectAt("position")
                            session.select(Position(position.number("x"), position.number("y")))
                        }
                        "produce" -> session.produce(intent.id("typeId"))
                        "cancelProduction" -> session.cancelProduction(intent.id("typeId"))
                        "move" -> session.move(intent.objectAt("position").let { Position(it.number("x"), it.number("y")) })
                        "stop" -> session.stop()
                        "attack" -> session.command(intent.objectAt("position").let { Position(it.number("x"), it.number("y")) })
                        "build" -> session.build(intent.id("typeId"),
                            intent.objectAt("position").let { Position(it.number("x"), it.number("y")) })
                        "upgrade" -> session.upgrade(intent.id("upgradeId"))
                        "cancelUpgrade" -> session.cancelUpgrade(intent.id("upgradeId"))
                        else -> throw InputError("Unsupported platform intent: ${intent.text("kind")}")
                    }
                    val waitSteps = if ("waitSteps" in intent) intent.integer("waitSteps") else 0L
                    require(waitSteps in 0..600) { "Client intent wait must be bounded to 600 steps" }
                    repeat(waitSteps.toInt()) { session.tick() }
                    evidence.record("intent-end", mapOf("intentId" to intent.id("intentId")))
                }
            }
        }
    }

    private fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> s(value)
        is Boolean -> b(value)
        is Number -> n(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> (key as String) to encode(item) })
        is Iterable<*> -> array(value.map(::encode))
        else -> throw CaptureError("Unsupported client evidence value: ${value.javaClass.name}")
    }
}
