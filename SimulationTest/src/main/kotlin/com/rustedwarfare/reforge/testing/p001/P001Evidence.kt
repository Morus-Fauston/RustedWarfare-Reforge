package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.testing.*
import com.rustedwarfare.reforge.core.sim.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.time.Instant
import java.util.UUID

internal class P001Evidence(val directory: Path) {
    init { Files.createDirectories(directory.parent); Files.createDirectory(directory) }
    fun document(name: String, value: JsonElement) {
        val target = directory.resolve(name); Files.createDirectories(target.parent)
        Files.writeString(target, value.toString()+"\n", StandardOpenOption.CREATE_NEW)
    }
    fun lines(name: String, rows: List<JsonObject>) {
        val target=directory.resolve(name); Files.createDirectories(target.parent)
        Files.writeString(target,rows.joinToString("\n",postfix="\n"),StandardOpenOption.CREATE_NEW)
    }
    fun snapshot(name: String, source: Path) {
        val target=directory.resolve("inputs/$name"); Files.createDirectories(target.parent); Files.copy(source,target)
    }
    fun inventory(): JsonArray = Files.walk(directory).use { paths -> array(paths.filter { Files.isRegularFile(it) }.sorted().map {
        obj("path" to s(directory.relativize(it).toString().replace('\\','/')), "sha256" to s(sha256(it)))
    }.toList()) }
}

internal fun newRun(topic: Path) = topic.resolve("run-${Instant.now().toString().replace(":", "").replace("-", "")}-${UUID.randomUUID().toString().take(8)}")
internal fun nullable(value: String?) = value?.let(::s) ?: JsonNull
internal fun failureJson(failure: RelationFailure?) = failure?.let {
    obj("ruleId" to s(it.ruleId), "sampleIndex" to n(it.sample), "expected" to s(it.expected), "actual" to s(it.actual))
} ?: JsonNull
internal fun observationRow(phase: String, observation: Observation) = record("p001-observation", "phase" to s(phase), "observation" to observation.json())
internal fun identityArtifacts(): JsonArray {
    val classes=listOf(P001Relations::class.java,SimulationContext::class.java,Json::class.java,kotlin.KotlinVersion::class.java)
    return array(classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().map { path ->
        val hash = if(Files.isDirectory(path)) Files.walk(path).use { files ->
            digest(files.filter { Files.isRegularFile(it) }.sorted().map { "${path.relativize(it)}:${sha256(it)}" }.toList().joinToString("\n").toByteArray(Charsets.UTF_8))
        } else sha256(path)
        obj("path" to s(path.toString()),"sha256" to s(hash))
    })
}
internal fun rootPath(root: Path, relative: String): Path {
    val path=Path.of(relative)
    if(path.isAbsolute) throw InputError("Expected repo-root relative path: $relative")
    val result=root.resolve(path).normalize()
    if(!result.startsWith(root)) throw InputError("Path escapes repo-root: $relative")
    return result
}
internal fun verifyIdentity(root: Path, ref: JsonObject): Path {
    val path=rootPath(root,ref.text("path"))
    val hash=ref.text("sha256")
    if(!Regex("[a-f0-9]{64}").matches(hash) || !Files.isRegularFile(path) || sha256(path)!=hash) throw InputError("Input identity mismatch: ${ref.text("path")}")
    return path
}
