package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.client.LoadedSandbox
import com.rustedwarfare.reforge.client.DiagnosticOutput
import com.rustedwarfare.reforge.client.DiagnosticWriter
import com.rustedwarfare.reforge.client.FixedDiagnosticRecord
import com.rustedwarfare.reforge.core.sim.PlayerObservation
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
    private val diagnostics: DiagnosticWriter

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
                    "variant=building-maintenance" -> "ENG-P006-005"
                    "variant=construction-queue" -> "ENG-P006-006"
                    "variant=defense-construction", "variant=defense-construction-siege" -> "ENG-P006-007"
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
                    "ENG-P006-005" -> "building-maintenance-windows-client"
                    "ENG-P006-006" -> "construction-queue-windows-client"
                    "ENG-P006-007" -> "defense-construction-windows-client"
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
        diagnostics = BoundedDiagnosticWriter(diagnosticOutput(runId, writer))
    }

    override fun recordObservation(phase: String, observation: PlayerObservation, generation: Long?) {
        diagnostics.checkFailure()
        diagnostics.record(FixedDiagnosticRecord.observation(phase, observation, generation,
            mapOf("wallTime" to Instant.now().toString())), phase != "step")
    }

    override fun record(kind: String, values: Map<String, Any?>) {
        diagnostics.checkFailure()
        val fixed = FixedDiagnosticRecord.capture(kind, values,
            mapOf("runId" to runId, "wallTime" to Instant.now().toString()))
        diagnostics.record(fixed, kind != "observation" || fixed.values["phase"] != "step")
    }

    override fun recordLoadReady(generation: Long, loaded: LoadedSandbox) {
        val contentRoot = requireNotNull(loaded.inputRoot) { "Loaded input root is required for identity evidence" }
        val scenario = loaded.scenario
        val contentPack = loaded.contentPack
        val parsedSources = loaded.contentSources.associateBy { it.path }
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
        val files = if (contentPack != null) mutableListOf(contentRoot.resolve("maps/p001-controlled.tmx")).apply {
            addAll(loaded.contentSources.map { it.path })
        } else if (combat) mutableListOf(contentRoot.resolve("maps/p001-controlled.tmx")).apply {
            contentRoot.resolve("tilesets/p005-terrain.tsx").takeIf { Files.isRegularFile(it) }?.let { add(it) }
            addAll(loaded.contentSources.map { it.path })
            add(fixedManifest)
        } else mutableListOf(contentRoot.resolve("maps/p001-controlled.tmx"),
            contentRoot.resolve("tilesets/p001-ground.tsx"), contentRoot.resolve("tilesets/p001-rock.tsx"),
            contentRoot.resolve("units/weaponless_tank.ini"))
        fun legacyUnit(relative: String) {
            val legacy = contentRoot.resolve(relative).toAbsolutePath().normalize()
            val stem = legacy.fileName.toString().substringBeforeLast('.')
            val alreadyParsed = parsedSources.keys.any {
                it.parent == legacy.parent && it.fileName.toString().substringBeforeLast('.') == stem
            }
            if (!alreadyParsed) files.add(legacy)
        }
        if (construction) {
            legacyUnit("units/builder.properties")
            legacyUnit("units/commandCenter.properties")
            if (scenario.unitDefinitions.containsKey("landFactory")) legacyUnit("units/landFactory.properties")
            if (scenario.unitDefinitions.containsKey("extractorT1")) {
                legacyUnit("units/extractor.ini")
                legacyUnit("units/extractor_common.ini")
            }
            if (scenario.unitDefinitions.containsKey("fabricatorT1")) legacyUnit("units/fabricatorT1.ini")
            for (type in listOf("extractorT2", "extractorT3", "fabricatorT2", "fabricatorT3")) {
                if (scenario.unitDefinitions.containsKey(type)) legacyUnit("units/$type.ini")
            }
        } else if (production) {
            files.add(contentRoot.resolve("production.properties"))
            legacyUnit("units/landFactory.properties")
            for (name in listOf("p005-corridor.tmx", "p005-sealed.tmx")) {
                contentRoot.resolve("maps/$name").takeIf { Files.isRegularFile(it) }?.let { files.add(it) }
            }
        }
        if (turret) listOf("units/turret_t1.ini", "units/turret_common_land.ini").forEach(::legacyUnit)
        if (scenario.unitDefinitions["c_turret_t1"]?.upgrades != null)
            listOf("units/turret_t2_gun.ini", "units/turret_t1_artillery.ini").forEach(::legacyUnit)
        if (defense) legacyUnit("units/laserDefence.properties")
        files += scenario.map.tileSets.map { it.imagePath }
        files += scenario.unitDefinitions.values.map { it.imagePath }
        files += scenario.unitDefinitions.values.mapNotNull { it.combat?.turretImagePath }
        files += scenario.unitDefinitions.values.mapNotNull { it.combat?.deadImagePath }
        files += scenario.unitDefinitions.values.mapNotNull { it.durability?.deadImagePath }
        files += scenario.unitDefinitions.values.mapNotNull { it.building?.backImagePath }
        val manifestFile = contentRoot.resolve("sandbox.properties")
        val manifest = manifestFile.takeIf { contentPack == null && Files.exists(it) }?.let(::identity)
        if (manifest != null) files.add(manifestFile)
        val fileIdentities = files.distinct().map { file ->
            val parsed = parsedSources[file.toAbsolutePath().normalize()]
            if (parsed == null) identity(file) else mapOf("path" to parsed.path.toString(), "bytes" to parsed.bytes, "sha256" to parsed.sha256)
        }
        super<ClientEvidence>.recordLoadReady(generation, loaded)
        val packageManifest = contentPack?.let { selected ->
            val source = parsedSources.getValue(selected.pack.manifest)
            mapOf("path" to source.path.toString(), "bytes" to source.bytes, "sha256" to source.sha256)
        }
        val contentSet = contentPack?.content?.let { content ->
            val packages = content.packages.associateBy { it.id }
            fun loadedIdentity(path: Path): Map<String, Any?> {
                val source = parsedSources.getValue(path)
                return mapOf("path" to source.path.toString(), "bytes" to source.bytes, "sha256" to source.sha256)
            }
            mapOf("packages" to content.packages.map { pack -> mapOf("id" to pack.id, "name" to pack.name,
                "root" to pack.root.toString(), "manifest" to loadedIdentity(pack.manifest), "dependencies" to pack.dependencies) },
                "units" to content.provenance.mapValues { (_, origin) -> mapOf("packageId" to origin.packageId,
                    "manifest" to loadedIdentity(packages.getValue(origin.packageId).manifest),
                    "entryPath" to origin.entryPath.toString(), "replacedPackageIds" to origin.replacedPackageIds) })
        }
        record("input-identities", mapOf("generation" to generation,
            "root" to contentRoot.toString(), "scenarioId" to scenario.scenarioId,
            "sandboxManifest" to (packageManifest ?: if (combat) identity(fixedManifest) else (manifest ?: identity(manifestFile))),
            "files" to fileIdentities) + (contentPack?.let { selected -> mapOf("contentPack" to mapOf(
                "id" to selected.pack.id, "name" to selected.pack.name, "unitId" to selected.unitId,
                "root" to selected.pack.root.toString(), "manifest" to packageManifest), "contentSet" to contentSet) } ?: emptyMap()))
    }

    override fun close() { diagnostics.close() }

    private fun identity(path: Path): Map<String, Any?> {
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute)) return mapOf("path" to absolute.toString(), "exists" to false)
        return mapOf("path" to absolute.toString(), "bytes" to Files.size(absolute),
            "sha256" to evidenceSha256(absolute))
    }

    private fun json(value: Any?): String = evidenceJson(value)
}

/** The worker owns the output and immutable run identity, never its host or GL objects. */
private fun diagnosticOutput(runId: String, writer: java.io.BufferedWriter): DiagnosticOutput =
    object : DiagnosticOutput {
        override fun write(sequence: Long, record: FixedDiagnosticRecord) {
            writeEvidenceJson(mapOf("format" to "p001.windows-diagnostic.v1", "runId" to runId,
                "sequence" to sequence, "wallTime" to record.metadata["wallTime"], "kind" to record.kind) + record.values, writer)
            writer.newLine()
        }
        override fun flush() = writer.flush()
        override fun close() = writer.close()
    }
