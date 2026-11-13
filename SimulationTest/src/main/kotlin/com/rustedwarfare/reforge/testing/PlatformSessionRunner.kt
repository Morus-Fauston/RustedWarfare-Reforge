package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.client.SandboxSession
import com.rustedwarfare.reforge.client.SandboxLoader
import com.rustedwarfare.reforge.client.ContentPackChoice
import com.rustedwarfare.reforge.client.LoadedSandbox
import com.rustedwarfare.reforge.client.SandboxRenderResources
import com.rustedwarfare.reforge.client.MapProjection
import com.rustedwarfare.reforge.client.SoundPlayback
import com.rustedwarfare.reforge.client.PlaybackSound
import com.rustedwarfare.reforge.client.PlaybackSoundFactory
import com.rustedwarfare.reforge.client.soundListener
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.pc.PcResourceImagePreparation
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    require(args.size in 3..4) { "PlatformSessionRunnerKt INPUT_ROOT PLAN_JSON NEW_OUTPUT_DIRECTORY [LOAD_TIMEOUT_SECONDS]" }
    val timeout = if (args.size == 4) requireNotNull(args[3].toIntOrNull()) { "Expected integer load timeout" } else 5
    require(timeout in 1..600) { "Load timeout must be bounded to 1..600 seconds" }
    PlatformSessionRunner.run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), timeout)
}

/** Executes client intentions; it never edits simulation state or expected values. */
object PlatformSessionRunner {
    fun run(inputs: Path, plan: Path, output: Path, loadTimeoutSeconds: Int = 5) {
        require(loadTimeoutSeconds in 1..600) { "Load timeout must be bounded to 1..600 seconds" }
        val document = readObject(plan)
        require(document.integer("schemaVersion") == 1L) { "Unsupported platform plan version" }
        val intents = document.arrayAt("intents").map { it.jsonObject }
        require(intents.isNotEmpty() && intents.map { it.id("intentId") }.distinct().size == intents.size) { "Empty or duplicate intent IDs" }
        Files.createDirectory(output)
        var sequence = 0L
        Files.newBufferedWriter(output.resolve("client-diagnostic.jsonl"), CREATE_NEW).use { writer ->
            val evidence = ClientEvidence { kind, values ->
                // This adapter executes one freshly loaded world per invocation.
                // Label every record so readers can reject stale rows even if worldId text is reused.
                val row = obj("sequence" to n(sequence++), "kind" to s(kind), "generation" to n(1))
                writer.write(JsonObject(row + values.mapValues { encode(it.value) }).toString())
                writer.newLine()
                writer.flush()
            }
            val loaded = loadScenario(inputs, document, evidence, output, loadTimeoutSeconds, plan)
            SandboxSession(loaded.scenario, evidence).use { session ->
                val projection = MapProjection(loaded.scenario.map.widthTiles.toDouble() * loaded.scenario.map.tileWidth,
                    loaded.scenario.map.heightTiles.toDouble() * loaded.scenario.map.tileHeight)
                val listener = projection.soundListener()
                val player = loaded.visuals?.resolvedSounds?.let { sounds ->
                    evidence.record("sound-backend-ready", mapOf("backend" to "device-free"))
                    SoundPlayback(session.consumeSounds(listener), sounds, PlaybackSoundFactory {
                        object : PlaybackSound {
                            private var voice = 0L
                            override fun play(volume: Float, pitch: Float): Long = ++voice
                            override fun stop() {}
                            override fun close() {}
                        }
                    }, evidence)
                }
                player.use {
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
                        "unit-action" -> {
                            val name = intent.id("actionName")
                            val nuclear = name in setOf("buildNuke", "launchNuke", "buildAntiNuke")
                            val targeted = name in setOf("launchNuke", "launchScoutBot", "blink")
                            val slotOrder = name in setOf("buildGun", "buildSmallLaser", "buildAntiair", "buildLightning",
                                "buildArtillery", "buildFabricator", "buildShieldGen", "buildSpeed", "upgradeT2", "upgradeFlak",
                                "buildLaserdefense", "buildAntinuke", "buildBlink", "reclaim")
                            val spider = slotOrder || name in setOf("selectSlotBuild", "cancel", "isEnabled", "isDisabled") ||
                                name.matches(Regex("selectSlot[1-6]"))
                            val cancel = intent["cancelQueued"]?.let { value ->
                                value.jsonPrimitive.booleanOrNull ?: throw InputError("Expected action cancellation boolean")
                            } ?: false
                            val keys = setOf("intentId", "kind", "actionName", "waitSteps") +
                                (if ("cancelQueued" in intent && (nuclear || targeted || slotOrder)) setOf("cancelQueued") else emptySet()) +
                                (if (targeted && !cancel) setOf("target") else emptySet())
                            if (intent.keys != keys ||
                                !nuclear && !targeted && !spider &&
                                    name !in setOf("afterBurn", "cancelBurn", "deploy", "undeploy", "surface", "dive"))
                                throw InputError("Expected a finite normal unit action and explicit waitSteps")
                            val point = intent["target"]?.jsonObject?.let {
                                if (it.keys != setOf("x", "y")) throw InputError("Expected finite ground point")
                                Position(it.number("x"), it.number("y"))
                            }
                            session.unitAction(name, cancel, point)
                        }
                        "load" -> session.command(intent.objectAt("position").let { Position(it.number("x"), it.number("y")) })
                        "unload" -> {
                            if (intent.required("unloading") !in setOf(b(true), b(false))) throw InputError("Expected unload boolean")
                            session.unload(intent.required("unloading").jsonPrimitive.boolean)
                        }
                        "submarine-depth" -> {
                            if (intent.required("surface") !in setOf(b(true), b(false))) throw InputError("Expected submarine surface boolean")
                            session.submarineDepth(intent.required("surface").jsonPrimitive.boolean)
                        }
                        "amphibious-flight" -> {
                            if (intent.required("flying") !in setOf(b(true), b(false))) throw InputError("Expected amphibious flying boolean")
                            session.amphibiousFlight(intent.required("flying").jsonPrimitive.boolean)
                        }
                        "attack" -> session.command(intent.objectAt("position").let { Position(it.number("x"), it.number("y")) })
                        "build" -> session.build(intent.id("typeId"),
                            intent.objectAt("position").let { Position(it.number("x"), it.number("y")) })
                        "upgrade" -> session.upgrade(intent.id("upgradeId"))
                        "cancelUpgrade" -> session.cancelUpgrade(intent.id("upgradeId"))
                        "reclaim", "repair" -> {
                            val repair = intent.text("kind") == "repair"
                            val target = if ("targetUnitId" in intent) intent.id("targetUnitId") else {
                                val point = intent.objectAt("position").let { Position(it.number("x"), it.number("y")) }
                                (if (repair) session.repairTargetAt(point) else session.reclaimTargetAt(point)) ?: ""
                            }
                            if (repair) session.repair(target) else session.reclaim(target)
                        }
                        else -> throw InputError("Unsupported platform intent: ${intent.text("kind")}")
                    }
                    val waitSteps = if ("waitSteps" in intent) intent.integer("waitSteps") else 0L
                    require(waitSteps in 0..600) { "Client intent wait must be bounded to 600 steps" }
                    repeat(waitSteps.toInt()) { tick ->
                        if (tick % 8 == 0) player?.beginFrame()
                        session.tick()
                        player?.consume(session.consumeSounds(listener))
                    }
                    evidence.record("intent-end", mapOf("intentId" to intent.id("intentId")))
                }
                }
            }
        }
    }

    private fun loadScenario(inputs: Path, document: JsonObject, evidence: ClientEvidence, output: Path, loadTimeoutSeconds: Int, seed: Path): LoadedSandbox {
        val imageDirectories = if ("resourcePacks" in document) {
            val images = document.objectAt("resourcePacks")
            if (images.keys != setOf("directories")) throw InputError("Expected resourcePacks directories only")
            val names = images.arrayAt("directories").map { item ->
                val name = item as? JsonPrimitive
                if (name == null || !name.isString || name.content.isBlank()) throw InputError("Expected local image package directory text")
                name.content
            }
            if (names.isEmpty()) throw InputError("Expected at least one image package directory")
            val root = inputs.toAbsolutePath().normalize()
            names.map { name ->
                val path = Path.of(name)
                if (path.isAbsolute || path.any { it.toString() == ".." }) throw InputError("Image package must stay inside input snapshot")
                val directory = root.resolve(path).normalize()
                if (!directory.startsWith(root) || !directory.toRealPath().startsWith(root.toRealPath()))
                    throw InputError("Image package escapes input snapshot")
                directory
            }
        } else null
        val testScenario = if ("testScene" in document) com.rustedwarfare.reforge.client.TestScenarioChoice.open(seed, inputs) else null
        if (testScenario == null && "contentPack" !in document && imageDirectories == null) {
            val scenario = ControlledScenario.load(inputs)
            return LoadedSandbox(scenario, null, SandboxRenderResources.from(scenario))
        }
        val contentChoice = testScenario?.content ?: if ("contentPack" in document) loadContentChoice(inputs, document) else null
        return SandboxLoader().use { loader ->
            val prepare = imageDirectories?.let { directories -> { PcResourceImagePreparation.prepare(directories, output.resolve("resource-cache"), contentChoice) } }
            if (testScenario != null) loader.requestTestScenario(testScenario, prepare)
            else if (contentChoice == null) loader.request(inputs, prepare) else loader.requestPack(inputs, contentChoice, prepare)
            val deadline = System.nanoTime() + loadTimeoutSeconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { result ->
                    val loaded = result.getOrThrow()
                    evidence.recordLoadReady(1L, loaded)
                    loaded.visuals?.let { evidence.recordVisualAssetsReady(it, 1L) }
                    loaded.contentPack?.let { current ->
                        evidence.record("content-pack-ready", mapOf("id" to current.pack.id, "unitId" to current.unitId,
                            "packageIds" to current.content.packages.map { it.id },
                            "effectiveSources" to current.content.provenance.mapValues { (_, origin) -> mapOf(
                                "packageId" to origin.packageId, "entryPath" to origin.entryPath.toString(),
                                "replacedPackageIds" to origin.replacedPackageIds) },
                            "sources" to loaded.contentSources.map { mapOf("path" to it.path.toString(), "bytes" to it.bytes, "sha256" to it.sha256) }))
                    }
                    return@use loaded
                }
                Thread.sleep(5)
            }
            throw InputError("Package sandbox did not load within $loadTimeoutSeconds seconds")
        }
    }

    private fun loadContentChoice(inputs: Path, document: JsonObject): ContentPackChoice {
        val selection = document.objectAt("contentPack")
        val names = when (selection.keys) {
            setOf("directory", "unitId") -> listOf(selection.text("directory"))
            setOf("directories", "unitId") -> selection.arrayAt("directories").map { value ->
                val text = value as? JsonPrimitive
                if (text == null || !text.isString || text.content.isBlank()) throw InputError("Expected local package directory text")
                text.content
            }
            else -> throw InputError("Expected contentPack directory or directories, and unitId")
        }
        val root = inputs.toAbsolutePath().normalize()
        val directories = names.map { name ->
            val relative = Path.of(name)
            if (relative.isAbsolute || relative.any { it.toString() == ".." }) throw InputError("Package directory must stay inside input snapshot")
            val directory = root.resolve(relative).normalize()
            if (!directory.startsWith(root)) throw InputError("Package directory escapes input snapshot")
            directory
        }
        val content = when (val result = ContentLoader.loadContentPacks(directories)) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> throw InputError(result.error.describe())
        }
        return ContentPackChoice(content, selection.text("unitId"))
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
