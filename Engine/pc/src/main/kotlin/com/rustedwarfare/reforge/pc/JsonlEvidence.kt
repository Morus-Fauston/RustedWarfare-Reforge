package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.client.ControlledScenario
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Window diagnostics, deliberately separate from the test line's formal trajectory schema. */
class JsonlEvidence internal constructor(private val directory: Path, private val runId: String, inputs: Path,
                                        createWriter: (Path) -> java.io.BufferedWriter) : ClientEvidence, AutoCloseable {
    constructor(directory: Path, runId: String, inputs: Path) : this(directory, runId, inputs,
        { Files.newBufferedWriter(it, StandardOpenOption.CREATE_NEW) })
    private val writer: java.io.BufferedWriter
    private var sequence = 0L
    private var contentRoot = inputs

    init {
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        Files.createDirectories(directory.resolve("reforge"))
        Files.createDirectories(directory.resolve("logs"))
        Files.createDirectories(directory.resolve("review"))
        writer = createWriter(directory.resolve("reforge/client-diagnostic.jsonl"))
        try {
            val ticket = when {
                Files.exists(inputs.resolve("construction.properties")) -> when (
                    Files.readString(inputs.resolve("construction.properties")).lineSequence().map { it.trim() }
                        .firstOrNull { it.startsWith("variant=") }) {
                    "variant=economy-construction", "variant=economy-death" -> "ENG-P006-003"
                    "variant=economy-upgrades" -> "ENG-P006-004"
                    "variant=factory-construction" -> "ENG-P006-002"
                    else -> "ENG-P006-001"
                }
                Files.exists(inputs.resolve("patrol.properties")) -> "ENG-P005-007"
                Files.exists(inputs.resolve("upgrade.properties")) -> "ENG-P005-004"
                Files.exists(inputs.resolve("point-defense.properties")) -> "ENG-P005-005"
                Files.exists(inputs.resolve("turret.properties")) -> "ENG-P005-003"
                Files.exists(inputs.resolve("production.properties")) -> "ENG-P005-002"
                Files.exists(inputs.resolve("combat.properties")) -> "ENG-P005-001"
                else -> "ENG-P002-009"
            }
            Files.writeString(directory.resolve("manifest.json"), json(mapOf(
                "format" to "p001.windows-diagnostic.v1", "runId" to runId,
                "phase" to ticket.substringAfter("ENG-").substringBeforeLast('-'),
                "topic" to when (ticket) {
                    "ENG-P006-001" -> "construction-windows-client"
                    "ENG-P006-002" -> "factory-construction-windows-client"
                    "ENG-P006-003" -> "economy-construction-windows-client"
                    "ENG-P006-004" -> "economy-upgrades-windows-client"
                    "ENG-P005-007" -> "patrol-windows-client"
                    "ENG-P005-004" -> "upgrade-windows-client"
                    "ENG-P005-005" -> "point-defense-windows-client"
                    "ENG-P005-003" -> "turret-windows-client"
                    "ENG-P005-002" -> "production-windows-client"
                    "ENG-P005-001" -> "combat-windows-client"
                    else -> "03-windows-client"
                },
                "ticket" to ticket,
                "startedAt" to Instant.now().toString(), "inputs" to inputs.toString(),
                "javaVersion" to System.getProperty("java.version"), "javaHome" to System.getProperty("java.home"),
                "osName" to System.getProperty("os.name"), "osVersion" to System.getProperty("os.version"),
                "osArch" to System.getProperty("os.arch"), "workingDirectory" to Path.of("").toAbsolutePath().toString(),
                "runtimeJars" to System.getProperty("java.class.path").split(java.io.File.pathSeparator)
                    .map { identity(Path.of(it)) },
                "originalGameExecuted" to false, "compatibilityVerdict" to "not-evaluated",
            )), StandardOpenOption.CREATE_NEW)
        } catch (failure: Throwable) {
            preserveFailure(failure) { writer.close() }
            throw failure
        }
    }

    override fun record(kind: String, values: Map<String, Any?>) {
        writer.write(json(mapOf("format" to "p001.windows-diagnostic.v1", "runId" to runId,
            "sequence" to sequence++, "wallTime" to Instant.now().toString(), "kind" to kind) + values))
        writer.newLine()
        if (kind != "observation" || values["phase"] != "step") writer.flush()
        if (kind == "load-request") contentRoot = Path.of(values.getValue("root") as String).toAbsolutePath().normalize()
        if (kind == "load-ready") {
            val scenario = ControlledScenario.load(contentRoot)
            val combat = scenario.combatContract != null
            val construction = scenario.scenarioId == "p006-construction"
            val production = scenario.unitDefinitions.values.any { it.production != null }
            val turret = scenario.unitDefinitions.containsKey("c_turret_t1")
            val defense = scenario.unitDefinitions.values.any { it.pointDefense != null }
            val upgrade = scenario.unitDefinitions.values.any { it.upgrades != null }
            val fixedManifest = contentRoot.resolve(when {
                construction -> "construction.properties"
                scenario.scenarioId == "p005-patrol" -> "patrol.properties"
                upgrade -> "upgrade.properties"
                defense -> "point-defense.properties"
                turret -> "turret.properties"
                production -> "production.properties"
                else -> "combat.properties"
            })
            val files = if (combat) mutableListOf(contentRoot.resolve("maps/p001-controlled.tmx"),
                contentRoot.resolve("tilesets/p005-terrain.tsx"), contentRoot.resolve("units/tank.ini"),
                fixedManifest) else mutableListOf(contentRoot.resolve("maps/p001-controlled.tmx"),
                contentRoot.resolve("tilesets/p001-ground.tsx"), contentRoot.resolve("tilesets/p001-rock.tsx"),
                contentRoot.resolve("units/weaponless_tank.ini"))
            if (construction) {
                files.add(contentRoot.resolve("units/builder.properties"))
                files.add(contentRoot.resolve("units/commandCenter.properties"))
                if (scenario.unitDefinitions.containsKey("landFactory")) files.add(contentRoot.resolve("units/landFactory.properties"))
                if (scenario.unitDefinitions.containsKey("extractorT1")) files.addAll(listOf(
                    contentRoot.resolve("units/extractor.ini"), contentRoot.resolve("units/extractor_common.ini")))
                if (scenario.unitDefinitions.containsKey("fabricatorT1")) files.add(contentRoot.resolve("units/fabricatorT1.ini"))
                for (type in listOf("extractorT2", "extractorT3", "fabricatorT2", "fabricatorT3")) {
                    if (scenario.unitDefinitions.containsKey(type)) files.add(contentRoot.resolve("units/$type.ini"))
                }
            } else if (production) {
                files.add(contentRoot.resolve("production.properties"))
                files.add(contentRoot.resolve("units/landFactory.properties"))
                for (name in listOf("p005-corridor.tmx", "p005-sealed.tmx")) {
                    contentRoot.resolve("maps/$name").takeIf { Files.isRegularFile(it) }?.let { files.add(it) }
                }
            }
            if (turret) files.addAll(listOf(contentRoot.resolve("units/turret_t1.ini"),
                contentRoot.resolve("units/turret_common_land.ini")))
            if (scenario.unitDefinitions["c_turret_t1"]?.upgrades != null) files.addAll(listOf(contentRoot.resolve("units/turret_t2_gun.ini"),
                contentRoot.resolve("units/turret_t1_artillery.ini")))
            if (defense) files.add(contentRoot.resolve("units/laserDefence.properties"))
            files += scenario.map.tileSets.map { it.imagePath }
            files += scenario.unitDefinitions.values.map { it.imagePath }
            files += scenario.unitDefinitions.values.mapNotNull { it.combat?.turretImagePath }
            files += scenario.unitDefinitions.values.mapNotNull { it.combat?.deadImagePath }
            files += scenario.unitDefinitions.values.mapNotNull { it.durability?.deadImagePath }
            files += scenario.unitDefinitions.values.mapNotNull { it.building?.backImagePath }
            val manifestFile = contentRoot.resolve("sandbox.properties")
            val manifest = manifestFile.takeIf { Files.exists(it) }?.let(::identity)
            if (manifest != null) files.add(manifestFile)
            record("input-identities", mapOf("generation" to values["generation"],
                "root" to contentRoot.toString(), "scenarioId" to scenario.scenarioId,
                "sandboxManifest" to if (combat) identity(fixedManifest) else (manifest ?: identity(manifestFile)),
                "files" to files.distinct().map(::identity)))
        }
    }

    override fun close() { writer.close() }

    private fun identity(path: Path): Map<String, Any?> {
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute)) return mapOf("path" to absolute.toString(), "exists" to false)
        return mapOf("path" to absolute.toString(), "bytes" to Files.size(absolute),
            "sha256" to evidenceSha256(absolute))
    }

    private fun json(value: Any?): String = evidenceJson(value)
}
