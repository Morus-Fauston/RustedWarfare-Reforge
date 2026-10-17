package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.XmlReader
import com.rustedwarfare.reforge.core.sim.Observation
import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Path

class SandboxGraphics private constructor(private val scenario: Scenario) : AutoCloseable {
    val projection = MapProjection((scenario.map.widthTiles * scenario.map.tileWidth).toDouble(),
        (scenario.map.heightTiles * scenario.map.tileHeight).toDouble())
    private val owned = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val units = mutableMapOf<String, TextureRegion>()
    private val ground = scenario.map.groundGids

    companion object {
        fun load(scenario: Scenario, root: Path): SandboxGraphics {
            val graphics = SandboxGraphics(scenario)
            try { graphics.loadImages(root); return graphics }
            catch (failure: Exception) {
                try { graphics.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private fun texture(path: Path, transparency: String? = null): Texture {
        val pixmap = Pixmap(Gdx.files.absolute(path.toString()))
        try {
            if (transparency != null) {
                val rgb = transparency.removePrefix("#").toInt(16)
                pixmap.blending = Pixmap.Blending.None
                for (y in 0 until pixmap.height) for (x in 0 until pixmap.width) {
                    if (pixmap.getPixel(x, y) ushr 8 == rgb) pixmap.drawPixel(x, y, 0)
                }
            }
            return Texture(pixmap).also { owned += it; it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        } finally { pixmap.dispose() }
    }

    private fun loadImages(root: Path) {
        val mapFile = root.resolve("maps/p001-controlled.tmx")
        val mapXml = XmlReader().parse(Gdx.files.absolute(mapFile.toString()))
        for (reference in mapXml.getChildrenByName("tileset")) {
            val firstGid = reference.getIntAttribute("firstgid")
            val definition = scenario.map.tileSets.single { it.firstGid == firstGid }
            val xml = reference.getAttribute("source", null)?.let {
                XmlReader().parse(Gdx.files.absolute(mapFile.parent.resolve(it).normalize().toString()))
            } ?: reference
            val image = xml.getChildByName("image") ?: error("Missing tileset image: $mapFile gid=$firstGid")
            val texture = texture(definition.imagePath, image.getAttribute("trans", null))
            val w = definition.tileWidth
            val h = definition.tileHeight
            val margin = xml.getIntAttribute("margin", 0)
            val spacing = xml.getIntAttribute("spacing", 0)
            val imageWidth = image.getIntAttribute("width", texture.width)
            val imageHeight = image.getIntAttribute("height", texture.height)
            val columns = xml.getIntAttribute("columns", (imageWidth - 2 * margin + spacing) / (w + spacing))
            val count = xml.getIntAttribute("tilecount", columns * ((imageHeight - 2 * margin + spacing) / (h + spacing)))
            require(columns > 0 && count > 0) { "Invalid image layout: ${definition.imagePath}" }
            for (index in 0 until count) tiles[firstGid + index] = TextureRegion(texture,
                margin + index % columns * (w + spacing), margin + index / columns * (h + spacing), w, h)
        }
        for (gid in ground) require(gid == 0 || tiles.containsKey(gid)) { "Missing tile image for gid=$gid in $mapFile" }
        for ((id, definition) in scenario.unitDefinitions) {
            val texture = texture(definition.imagePath)
            require(texture.width % definition.totalFrames == 0) { "Invalid unit image frames: ${definition.imagePath}" }
            units[id] = TextureRegion(texture, 0, 0, texture.width / definition.totalFrames, texture.height)
        }
    }

    fun draw(batch: SpriteBatch, observation: Observation) {
        val map = scenario.map
        batch.color = Color.WHITE
        for (index in ground.indices) {
            if (ground[index] == 0) continue
            batch.draw(tiles.getValue(ground[index]),
            projection.x((index % map.widthTiles * map.tileWidth).toDouble()),
            projection.y(((index / map.widthTiles + 1) * map.tileHeight).toDouble()),
            (map.tileWidth * projection.scale).toFloat(), (map.tileHeight * projection.scale).toFloat())
        }
        for (unit in observation.units) {
            val definition = scenario.unitDefinitions.getValue(unit.typeId)
            val region = units.getValue(unit.typeId)
            val w = (region.regionWidth * definition.imageScale * projection.scale).toFloat()
            val h = (region.regionHeight * definition.imageScale * projection.scale).toFloat()
            batch.draw(region, projection.x(unit.position.x) - w / 2, projection.y(unit.position.y) - h / 2,
                w / 2, h / 2, w, h, 1f, 1f, -unit.heading.toFloat())
        }
    }

    fun drawOverlay(shapes: ShapeRenderer, observation: Observation, selectedId: String?) {
        shapes.color = Color(0.45f, 0.5f, 0.47f, 1f)
        shapes.rect(projection.left.toFloat(), projection.bottom.toFloat(), projection.width.toFloat(), projection.height.toFloat())
        for (unit in observation.units) {
            shapes.color = if (unit.ownerPlayerId == "p1") Color(0.45f, 0.91f, 0.65f, 1f) else Color(1f, 0.72f, 0.3f, 1f)
            val radius = (scenario.unitDefinitions.getValue(unit.typeId).displayRadius * projection.scale).toFloat()
            val x = projection.x(unit.position.x)
            val y = projection.y(unit.position.y)
            shapes.circle(x, y, radius + if (unit.unitId == selectedId) 4f else 1f, 32)
            if (unit.unitId == selectedId) {
                shapes.circle(x, y, radius + 6f, 32)
                var previous = unit.position
                for (point in unit.pathPoints) {
                    shapes.line(projection.x(previous.x), projection.y(previous.y), projection.x(point.x), projection.y(point.y))
                    previous = point
                }
                unit.moveTarget?.let { target ->
                    val tx = projection.x(target.x); val ty = projection.y(target.y)
                    shapes.line(tx - 5, ty, tx + 5, ty); shapes.line(tx, ty - 5, tx, ty + 5)
                }
            }
        }
    }

    override fun close() {
        var failure: Exception? = null
        owned.forEach { texture ->
            try { texture.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        owned.clear(); tiles.clear(); units.clear()
        failure?.let { throw it }
    }
}
