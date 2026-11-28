package com.rustedwarfare.reforge.testing

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class NavalProductionArchiveTest {
    @Test fun normal_ini_production_archive_preserves_and_compares_every_intermediate_gameplay_field() {
        verifyNavalProductionFormatArchive("ini", javaClass)
    }
}

internal fun verifyNavalProductionFormatArchive(format: String, ownerClass: Class<*>) {
    val repo = Path.of(System.getProperty("repo.root"))
    val public = repo.resolve("SimulationTest/public")
    val root = TestArchives.directory("RWR_NAVAL_ARCHIVE_ROOT", ownerClass, "production-core-$format").resolve("archives")
    Files.createDirectory(root)
    val scenario = public.resolve("scenarios/naval-production-$format-chain.json")
    val contract = public.resolve("contracts/naval-production-$format-chain.json")
    val document = readObject(scenario)
    val seal = Files.createDirectory(root.resolve("sealed-$format"))
    val content = document.objectAt("content")
    val files = listOf(scenario, contract) + (listOf(content.objectAt("map")) +
        content.arrayAt("unitVariants").map { it.jsonObject } + content.arrayAt("dependencies").map { it.jsonObject })
        .map { scenario.parent.resolve(it.text("path")).normalize() }
    val manifest = files.distinct().mapIndexed { i, source ->
        val destination = seal.resolve("$i-${source.fileName}")
        Files.copy(source, destination)
        obj("source" to s(source.toString()), "snapshot" to s(destination.fileName.toString()), "sha256" to s(sha256(source)))
    }
    Files.writeString(seal.resolve("manifest.json"), array(manifest).toString())
    val output = root.resolve(format)
    val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/${ownerClass.simpleName}"), timeoutMillis = 600_000))
    assertEquals(0, result.exitCode, "$format: $result; $output")
    val compared = TraceFiles.compare(scenario, contract, output)
    Files.writeString(root.resolve("$format-readonly-consumption.json"), compared.document.toString())
    assertEquals(0, compared.exitCode, compared.document.toString())

    val raw = output.resolve("reforge/raw.jsonl")
    val ship = "produced:p1:${"b".repeat(32)}"
    val fired = mutableListOf<JsonObject>()
    var accepted = 0
    var count = 0
    val normalizedDigest = MessageDigest.getInstance("SHA-256")
    Files.newBufferedReader(raw).useLines { lines -> lines.forEach { line ->
        val row = json.parseToJsonElement(line).jsonObject
        val normalizedLine = TraceComparator.normalize(row).toString() + "\n"
        normalizedDigest.update(normalizedLine.toByteArray(StandardCharsets.UTF_8))
        count++
        if (row["kind"] == s("action-result")) {
            assertEquals(s("accepted"), row.objectAt("result")["status"]); accepted++
        }
        if (row["kind"] == s("observation")) fired += row.objectAt("observation").arrayAt("projectileEvents")
            .map { it.jsonObject }.filter { it["kind"] == s("born") }.map { it.objectAt("projectile") }
            .filter { it["sourceUnitId"] == s(ship) }
    } }
    assertTrue(count > 0, "$format archive must be non-empty")
    val normalizedSha256 = HexFormat.of().formatHex(normalizedDigest.digest())
    Files.writeString(root.resolve("$format-full-exact-comparison.json"), obj(
        "passed" to b(true),
        "recordCount" to n(count),
        "actualSha256" to s(sha256(raw)),
        "normalizedStreamSha256" to s(normalizedSha256),
        "policy" to s("Whole JSON values after unchanged TraceComparator.normalize; raw start/end separately validated by TraceFiles; cross-format normalized stream verified across sibling formats; no gameplay fields dropped or tolerances added"),
    ).toString())

    // Cross-format exact normalized stream rendezvous across parallel JVMs within the same test run
    val archiveRootProp = System.getProperty("test.archive.root")
    val archiveRunProp = System.getProperty("test.archive.run")
    if (!archiveRootProp.isNullOrBlank() && !archiveRunProp.isNullOrBlank()) {
        val rendezvousDir = Path.of(archiveRootProp).resolve("naval-cross-format-rendezvous-$archiveRunProp")
        Files.createDirectories(rendezvousDir)
        val tmpFile = rendezvousDir.resolve(".tmp-$format-${UUID.randomUUID()}.json")
        val finalFile = rendezvousDir.resolve("$format.json")
        Files.writeString(tmpFile, obj("format" to s(format), "recordCount" to n(count), "normalizedSha256" to s(normalizedSha256)).toString())
        Files.move(tmpFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Files.list(rendezvousDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") && !it.fileName.toString().startsWith(".tmp-") }
                .forEach { sibling ->
                    val peer = readObject(sibling)
                    val peerFormat = peer.text("format")
                    assertEquals(count.toLong(), peer.integer("recordCount"), "$format vs $peerFormat record count")
                    assertEquals(normalizedSha256, peer.text("normalizedSha256"), "$format vs $peerFormat normalized stream SHA-256")
                }
        }
    }

    assertEquals(setOf("1", "2", "3", "4", "5"), fired.map { it.text("sourceWeaponName") }.toSet(), "$format all five stock weapons")
    assertEquals(setOf("c", "d"), fired.filter { it["targetUnitId"] != JsonNull }.map { it.text("targetUnitId") }.toSet())
    for (shot in fired.filter { it["sourceWeaponName"] in setOf(s("1"), s("2")) }) {
        assertEquals(JsonNull, shot["targetUnitId"])
        assertEquals(obj("x" to n(330.0), "y" to n(550.0)), shot.objectAt("ground").required("targetPosition"))
    }
    assertEquals(document.arrayAt("actions").size, accepted)
}
