package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.XmlReader
import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Path

class SandboxGraphics private constructor(private val resources: SandboxRenderResources) : AutoCloseable {
    val projection = MapProjection(resources.widthTiles.toDouble() * resources.tileWidth,
        resources.heightTiles.toDouble() * resources.tileHeight)
    private val owned = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val units = mutableMapOf<String, TextureRegion>()

    companion object {
        fun load(resources: SandboxRenderResources, root: Path): SandboxGraphics {
            val graphics = SandboxGraphics(resources)
            try { graphics.loadImages(root); return graphics }
            catch (failure: Exception) {
                try { graphics.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun load(scenario: Scenario, root: Path): SandboxGraphics {
            val graphics = load(SandboxRenderResources.from(scenario), root)
            try {
                for (gid in scenario.map.groundGids) require(gid == 0 || graphics.tiles.containsKey(gid)) { "Missing tile image for gid=$gid" }
                return graphics
            }
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
            val definition = resources.tileSets.single { it.firstGid == firstGid }
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
        for ((id, definition) in resources.units) {
            val texture = texture(definition.imagePath)
            require(texture.width % definition.totalFrames == 0) { "Invalid unit image frames: ${definition.imagePath}" }
            units[id] = TextureRegion(texture, 0, 0, texture.width / definition.totalFrames, texture.height)
        }
    }

    fun draw(batch: SpriteBatch, frame: SandboxFrame) {
        for (cell in frame.terrain) {
            val gid = cell.gid ?: continue
            if (gid == 0) continue
            batch.setColor(if (cell.visible) 1f else 0.3f, if (cell.visible) 1f else 0.3f,
                if (cell.visible) 1f else 0.3f, 1f)
            batch.draw(tiles.getValue(gid),
                projection.x(cell.column.toDouble() * resources.tileWidth),
                projection.y((cell.row + 1.0) * resources.tileHeight),
                (resources.tileWidth * projection.scale).toFloat(), (resources.tileHeight * projection.scale).toFloat())
        }
        batch.color = Color.WHITE
        for (unit in frame.observation.units) {
            val definition = resources.units.getValue(unit.typeId)
            val region = units.getValue(unit.typeId)
            val w = (region.regionWidth * definition.imageScale * projection.scale).toFloat()
            val h = (region.regionHeight * definition.imageScale * projection.scale).toFloat()
            batch.draw(region, projection.x(unit.position.x) - w / 2, projection.y(unit.position.y) - h / 2,
                w / 2, h / 2, w, h, 1f, 1f, -unit.heading.toFloat())
        }
    }

    fun drawOverlay(shapes: ShapeRenderer, frame: SandboxFrame, selectedId: String?) {
        shapes.color = Color(0.45f, 0.5f, 0.47f, 1f)
        shapes.rect(projection.left.toFloat(), projection.bottom.toFloat(), projection.width.toFloat(), projection.height.toFloat())
        for (unit in frame.observation.units) {
            shapes.color = if (unit.ownerPlayerId == "p1") Color(0.45f, 0.91f, 0.65f, 1f) else Color(1f, 0.72f, 0.3f, 1f)
            val radius = (resources.units.getValue(unit.typeId).displayRadius * projection.scale).toFloat()
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
