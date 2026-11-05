package com.rustedwarfare.reforge.pc

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import com.badlogic.gdx.utils.JsonValue
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import com.rustedwarfare.reforge.client.PreparedVisualAssets
import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import com.rustedwarfare.reforge.client.VisualUnitImages

/** Desktop-only preparation. Never imports original gameplay or touches the source directory. */
internal class PcVisualAssetService(private val cacheRoot: Path,
    private val profile: VisualAssetProfile = VisualAssetProfile.bundled(),
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun prepareVisuals(selection: VisualAssetSelection): PreparedVisualAssets {
        require(selection.source == VisualAssetSource.ORIGINAL)
        val root = requireNotNull(selection.originalDirectory) { "SOURCE_ROOT_INVALID: original directory required" }
            .toAbsolutePath().normalize()
        val prepared = prepare(root)
        val units = profile.json.get("unitBindings").associate { binding ->
            fun slot(name: String): Path? = binding.getString(name, null)?.let { prepared.outputs.getValue(it) }
            binding.name to VisualUnitImages(requireNotNull(slot("body")), slot("back"), slot("turret"), slot("dead"),
                profile.recipes.getValue(binding.getString("body")).getInt("frames", 1), leg = slot("leg"), foot = slot("foot"), rotor = slot("rotor"),
                weaponTurrets = binding.get("weaponTurrets")?.associate { it.name to prepared.outputs.getValue(it.asString()) }.orEmpty())
        }
        val terrain = profile.json.get("terrainOutputs").associate { it.name to prepared.outputs.getValue(it.name) }
        return PreparedVisualAssets(selection.copy(originalDirectory = root), profile.id,
            profile.key + "/" + prepared.generation.fileName, units, terrain, prepared::verify)
    }

    fun prepare(sourceRoot: Path): PcPreparedVisualAssets {
        checkVisualCancellation()
        val root = try { sourceRoot.toRealPath().also { require(Files.isDirectory(it)) } }
            catch (error: Exception) { throw IllegalArgumentException("SOURCE_ROOT_INVALID: $sourceRoot", error) }
        try {
            val absoluteCache = cacheRoot.toAbsolutePath().normalize()
            var existing = absoluteCache
            while (Files.notExists(existing, NOFOLLOW_LINKS)) existing = requireNotNull(existing.parent)
            val canonicalCache = existing.toRealPath().resolve(existing.relativize(absoluteCache))
            require(!canonicalCache.startsWith(root) && !root.startsWith(canonicalCache)) {
                "SOURCE_ROOT_INVALID: source and private cache must not overlap: $root"
            }
            val cache = VisualAssetCache(canonicalCache.resolve(profile.key), profile, atomicMove)
            return cache.locked { prepareLocked(root, cache) }
        }
        catch (error: java.io.IOException) { throw IllegalArgumentException("PREPARE_FAILED: ${error.message}", error) }
    }

    private fun prepareLocked(sourceRoot: Path, cache: VisualAssetCache): PcPreparedVisualAssets {
        val root = sourceRoot
        val identity = profile.json.get("identity")
        val errors = mutableListOf<String>()
        val observed = mutableMapOf<Path, Triple<Long, FileTime, Any?>>()
        fun member(path: String, size: Int, hash: String, version: Boolean = false): ByteArray? {
            checkVisualCancellation()
            try {
                val target = root.resolve(path).normalize()
                require(target.startsWith(root) && target != root)
                var cursor = root
                for (part in root.relativize(target)) {
                    cursor = cursor.resolve(part)
                    require(!Files.isSymbolicLink(cursor) && cursor.toRealPath() == cursor) { "linked member" }
                }
                require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "not a regular file" }
                fun attributes(): Triple<Long, FileTime, Any?> = Files.readAttributes(target, BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS).let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
                val before = attributes()
                val bytes = Files.newInputStream(target).use { it.readNBytes(size + 1) }
                require(bytes.size == size && visualHash(bytes) == hash) { "length or SHA-256 mismatch" }
                require(attributes() == before && (observed[target] == null || observed[target] == before)) { "source changed during preparation" }
                observed[target] = before
                return bytes
            } catch (error: Exception) {
                val code = when {
                    Files.notExists(root.resolve(path), NOFOLLOW_LINKS) -> "SOURCE_ASSET_MISSING"
                    version -> "SOURCE_VERSION_UNSUPPORTED"
                    else -> "SOURCE_ASSET_MISMATCH"
                }
                errors += "$code: $path: ${error.message}"
                return null
            }
        }
        member(identity.getString("manifestPath"), identity.getInt("manifestBytes"), identity.getString("manifestSha256"), true)
        val images = profile.json.get("sources").mapNotNull { source ->
            val path = source.getString("path")
            val bytes = member(path, source.getInt("bytes"), source.getString("sha256")) ?: return@mapNotNull null
            try {
            val image = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val reader = ImageIO.getImageReadersByFormatName("png").next()
                try {
                    reader.input = stream
                    require(reader.getWidth(0) == source.getInt("width") && reader.getHeight(0) == source.getInt("height")) { "dimensions" }
                    reader.read(0)
                } finally { reader.dispose() }
            }
            require(image.width == source.getInt("width") && image.height == source.getInt("height")) {
                "SOURCE_ASSET_MISMATCH: dimensions ${source.getString("path")}"
            }
            source.getString("id") to image
            } catch (error: Exception) { errors += "SOURCE_ASSET_MISMATCH: $path: ${error.message}"; null }
        }.toMap()
        require(errors.isEmpty()) { errors.joinToString("\n") }
        fun revalidate() {
            checkVisualCancellation()
            member(identity.getString("manifestPath"), identity.getInt("manifestBytes"), identity.getString("manifestSha256"), true)
            for (source in profile.json.get("sources")) member(source.getString("path"), source.getInt("bytes"), source.getString("sha256"))
            require(errors.isEmpty()) { errors.joinToString("\n") }
        }
        return cache.prepare(beforePublish = ::revalidate) { staging ->
        for ((_, recipe) in profile.recipes) {
            checkVisualCancellation()
            val target = recipe.get("target").asIntArray()
            val result = BufferedImage(target[0], target[1], BufferedImage.TYPE_INT_ARGB)
            when (recipe.getString("operation")) {
                "crop" -> paint(result, images, recipe, 0, false)
                "compose-strip" -> {
                    require(recipe.getString("blend") == "source-over")
                    var x = 0
                    for (cell in recipe.get("cells")) {
                        paint(result, images, recipe.get("background"), x, false)
                        paint(result, images, cell, x, true)
                        x += cell.get("target").asIntArray()[0]
                    }
                    require(x == target[0]) { "VISUAL_MAPPING_UNSUPPORTED: strip width" }
                }
                else -> error("VISUAL_MAPPING_UNSUPPORTED: recipe")
            }
            val output = staging.resolve(recipe.getString("file"))
            Files.createDirectories(output.parent)
            val bytes = ByteArrayOutputStream().also { buffer ->
                MemoryCacheImageOutputStream(buffer).use { require(ImageIO.write(result, "png", it)) }
            }.toByteArray()
            Files.write(output, bytes)
        }
        }
    }

    private fun paint(output: BufferedImage, images: Map<String, BufferedImage>, recipe: JsonValue, offset: Int, blend: Boolean) {
        val source = images.getValue(recipe.getString("source"))
        val rect = recipe.get("rect").asIntArray()
        val target = recipe.get("target").asIntArray()
        val rotation = recipe.getInt("rotate", 0)
        require(rotation == 0 || rotation == 90) { "VISUAL_MAPPING_UNSUPPORTED: crop rotation" }
        require(rect.size == 4 && target.size == 2 && rect[0] >= 0 && rect[1] >= 0 && rect[2] > 0 && rect[3] > 0 &&
            rect[0] + rect[2] <= source.width && rect[1] + rect[3] <= source.height &&
            target[0] > 0 && target[1] > 0 && offset + target[0] <= output.width && target[1] <= output.height) {
            "VISUAL_MAPPING_UNSUPPORTED: crop bounds"
        }
        for (y in 0 until target[1]) for (x in 0 until target[0]) {
            val sourceX = if (rotation == 90) y * rect[2] / target[1] else x * rect[2] / target[0]
            val sourceY = if (rotation == 90) rect[3] - 1 - x * rect[3] / target[0] else y * rect[3] / target[1]
            val color = source.getRGB(rect[0] + sourceX, rect[1] + sourceY)
            output.setRGB(offset + x, y, if (blend) over(color, output.getRGB(offset + x, y)) else color)
        }
    }

    private fun over(source: Int, destination: Int): Int {
        val a = source ushr 24
        val b = destination ushr 24
        val denominator = a * 255 + b * (255 - a)
        if (denominator == 0) return 0
        var result = ((denominator + 127) / 255) shl 24
        for (shift in listOf(0, 8, 16)) {
            val numerator = ((source ushr shift) and 255) * a * 255 +
                ((destination ushr shift) and 255) * b * (255 - a)
            result = result or (((numerator + denominator / 2) / denominator) shl shift)
        }
        return result
    }
}
