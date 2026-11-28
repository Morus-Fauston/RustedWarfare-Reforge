package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.PreparedVisualAssets
import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import com.rustedwarfare.reforge.client.VisualUnitImages
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Only packaged, repository-authored images. This path never examines the remembered original directory. */
internal class PcPlaceholderVisualAssets {
    fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
        require(selection.source == VisualAssetSource.PLACEHOLDER)
        checkVisualCancellation()
        val paths = verifiedShared()
        val units = manifest.get("unitBindings").associate { binding ->
            fun slot(name: String): Path? = binding.getString(name, null)?.let { paths.outputs.getValue(it) }
            val body = binding.getString("body")
            val frames = manifest.get("files").first { it.getString("id") == body }.getInt("frames")
            binding.name to VisualUnitImages(requireNotNull(slot("body")), slot("back"), slot("turret"), slot("dead"), frames,
                leg = slot("leg"), foot = slot("foot"), rotor = slot("rotor"),
                weaponTurrets = binding.get("weaponTurrets")?.associate { it.name to paths.outputs.getValue(it.asString()) }.orEmpty(),
                shadow = slot("shadow"), shield = slot("shield"), leftWing = slot("leftWing"), rightWing = slot("rightWing"),
                beam = slot("beam"), beamStart = slot("beamStart"), beamEnd = slot("beamEnd"), afterburnTrail = slot("afterburnTrail"),
                arm1 = slot("arm1"), arm2 = slot("arm2"), slotOverlay = slot("slotOverlay"))
        }
        val terrain = manifest.get("terrain").associate { it.name to paths.outputs.getValue(it.asString()) }
        return PreparedVisualAssets(selection, manifest.getString("profileId"), visualHash(bytes) + "/" + paths.generation.fileName,
            units, terrain, paths::verify)
    }

    companion object {
        private val bytes by lazy { resource("manifest.json") }
        private val manifest by lazy {
            JsonReader().parse(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("schemaVersion") == 1) }
        }
        @Volatile private var cached: PcPreparedVisualAssets? = null

        @Synchronized
        private fun verifiedShared(): PcPreparedVisualAssets {
            cached?.let { existing ->
                try {
                    existing.verify()
                    return existing
                } catch (_: Exception) {
                    cached = null
                }
            }
            return extract().also { cached = it }
        }

        private fun resource(path: String) = requireNotNull(PcPlaceholderVisualAssets::class.java.getResourceAsStream("/visuals/placeholder/$path")) {
            "PREPARE_FAILED: missing packaged placeholder $path"
        }.use { it.readBytes() }

        private fun extract(): PcPreparedVisualAssets {
            val root = Files.createTempDirectory("reforge-placeholder-").toAbsolutePath().normalize()
            val owned = mutableListOf(root)
            val outputs = linkedMapOf<String, Path>()
            val identities = linkedMapOf<String, CachedVisualImage>()
            try {
                for (file in manifest.get("files")) {
                    checkVisualCancellation()
                    val relative = file.getString("resource")
                    val target = root.resolve(relative).normalize()
                    require(target.startsWith(root) && target.parent.parent == root) { "PREPARE_FAILED: invalid placeholder path" }
                    if (Files.notExists(target.parent)) { Files.createDirectory(target.parent); owned.add(target.parent) }
                    val data = resource(relative)
                    require(data.size == file.getInt("bytes") && visualHash(data) == file.getString("sha256")) { "PREPARE_FAILED: placeholder $relative" }
                    Files.write(target, data, CREATE_NEW); owned.add(target)
                    val id = file.getString("id")
                    outputs[id] = target
                    identities[id] = CachedVisualImage(data.size, file.getInt("width"), file.getInt("height"), file.getString("sha256"))
                }
                return PcPreparedVisualAssets(root, outputs, identities).also {
                    it.verify(); owned.forEach { path -> path.toFile().deleteOnExit() }
                }
            } catch (failure: Exception) {
                for (path in owned.asReversed()) try { Files.deleteIfExists(path) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
