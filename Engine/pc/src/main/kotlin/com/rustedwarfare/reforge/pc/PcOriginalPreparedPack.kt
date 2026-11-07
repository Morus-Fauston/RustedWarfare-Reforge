package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** Runtime PNG/PCM/JSON and the complete original qualification are separate sibling trees.
 * External descriptors confer no trust: the consumer re-audits the qualification tree. */
internal class PcOriginalPreparedPack(private val cache: Path,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun export(legacy: PreparedImagePack): PreparedImagePack {
        val output = cache.toAbsolutePath().normalize().resolveSibling("prepared-original-packs")
        var ancestor = output
        while (Files.notExists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
        require(ancestor.toRealPath().resolve(ancestor.relativize(output)) == output && !output.startsWith(legacy.manifest.parent))
        Files.createDirectories(output)
        FileChannel.open(output.resolve("export.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            legacy.verify(); legacy.verifySources(); checkVisualCancellation()
            PcFrozenResourceInputs.capture(listOf(legacy.manifest.parent)).use { frozen ->
                val stage = Files.createDirectory(output.resolve("pending-${UUID.randomUUID()}"))
                val qualification = Files.createDirectory(stage.resolve("qualification"))
                Files.walk(legacy.manifest.parent).use { files -> files.forEach { source ->
                    checkVisualCancellation()
                    val target = qualification.resolve(legacy.manifest.parent.relativize(source))
                    require(target.normalize().startsWith(qualification))
                    if (Files.isDirectory(source, NOFOLLOW_LINKS)) Files.createDirectories(target)
                    else Files.write(target, frozen.read(source, 16 * 1024 * 1024), CREATE_NEW)
                } }
                val runtime = Files.createDirectory(stage.resolve("runtime"))
                val images = obj(); val sounds = obj(); val events = obj()
                val copied = linkedMapOf<Path, String>()
                fun media(path: Path, suffix: String): String = copied.getOrPut(path) {
                    checkVisualCancellation()
                    val name = "media-${copied.size}.$suffix"
                    Files.copy(path, runtime.resolve(name)); name
                }
                for ((id, image) in legacy.images) {
                    val layout = image.declaration
                    images.addChild(id.value, obj("file" to str(media(image.path, "png")),
                        "frameWidth" to num(layout.frameWidth), "frameHeight" to num(layout.frameHeight), "totalFrames" to num(layout.totalFrames)))
                }
                for ((id, sound) in legacy.sounds) sounds.addChild(id.value, obj("file" to str(media(sound.path, "wav"))))
                for ((id, event) in legacy.soundEvents) {
                    val candidates = JsonValue(JsonValue.ValueType.array)
                    for (candidate in event.candidates) candidates.addChild(obj("sound" to str(candidate.sound.value), "weight" to num(candidate.weight)))
                    events.addChild(id.value, obj("candidates" to candidates, "append" to JsonValue(event.append)))
                }
                write(runtime.resolve("images.json"), obj("formatVersion" to num(1), "images" to images))
                write(runtime.resolve("sounds.json"), obj("formatVersion" to num(2), "sounds" to sounds, "events" to events))
                val metadata = legacy.metadata
                write(runtime.resolve("pack.json"), obj("formatVersion" to num(1), "requiresResourceFormat" to num(4),
                    "id" to str(metadata.id.value), "name" to str(metadata.name), "description" to str(metadata.description), "icon" to str(metadata.icon.value)))
                frozen.verifyCurrent(); legacy.verify(); legacy.verifySources(); checkVisualCancellation()
                val target = output.resolve(UUID.randomUUID().toString())
                atomicMove(stage, target, false)
                frozen.verifyCurrent(); checkVisualCancellation()
                return PcResourcePackImporter(cache).load(target.resolve("runtime"))
            }
        } }
    }

    fun qualificationRoot(pack: PreparedImagePack): Path {
        val runtime = pack.manifest.parent
        require(runtime.fileName.toString() == "runtime" && UUID.fromString(runtime.parent.fileName.toString()).toString() == runtime.parent.fileName.toString()) {
            "SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID: Incomplete finished output"
        }
        val root = runtime.parent.resolve("qualification")
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath() == root) { "SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID: Missing separate qualification" }
        return root
    }

    fun verifyEquivalent(pack: PreparedImagePack, legacy: PreparedImagePack) {
        fun same(value: Boolean) = require(value) { "SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID: Finished media or mapping differs from qualified output" }
        same(pack.metadata.id == legacy.metadata.id && legacy.metadata.requiresResourceFormat == 2 && pack.metadata.requiresResourceFormat == 4)
        same(pack.images.keys == legacy.images.keys && pack.sounds.keys == legacy.sounds.keys && pack.soundEvents == legacy.soundEvents)
        for ((id, image) in pack.images) {
            checkVisualCancellation(); val expected = legacy.images.getValue(id)
            same(image.declaration.copy(file = expected.declaration.file) == expected.declaration &&
                image.pixelWidth == expected.pixelWidth && image.pixelHeight == expected.pixelHeight &&
                visualHash(Files.readAllBytes(image.path)) == visualHash(Files.readAllBytes(expected.path)))
        }
        for ((id, sound) in pack.sounds) same(sound.copy(path = legacy.sounds.getValue(id).path) == legacy.sounds.getValue(id))
        pack.verify(); pack.verifySources(); legacy.verify(); legacy.verifySources()
    }

    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue(JsonValue.ValueType.`object`).also { value -> fields.forEach { (key, field) -> value.addChild(key, field) } }
    private fun str(value: String) = JsonValue(value)
    private fun num(value: Int) = JsonValue(value.toLong())
    private fun write(path: Path, value: JsonValue) = Files.writeString(path, value.toJson(OutputType.json), CREATE_NEW)
}
