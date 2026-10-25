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
    var projection = MapProjection(resources.widthTiles.toDouble() * resources.tileWidth,
        resources.heightTiles.toDouble() * resources.tileHeight)
        private set
    fun useMapArea(area: MapArea) {
        projection = MapProjection(projection.worldWidth, projection.worldHeight, area)
    }
    private val owned = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val units = mutableMapOf<String, TextureRegion>()
    private val turrets = mutableMapOf<String, TextureRegion>()
    private val corpses = mutableMapOf<String, TextureRegion>()
    private val backs = mutableMapOf<String, TextureRegion>()

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

    private fun loadParsedTiles() {
        for (definition in resources.tileSets) {
            val texture = texture(definition.imagePath, definition.transparentColor)
            for (index in 0 until definition.tileCount) tiles[definition.firstGid + index] = TextureRegion(texture,
                definition.margin + index % definition.columns * (definition.tileWidth + definition.spacing),
                definition.margin + index / definition.columns * (definition.tileHeight + definition.spacing),
                definition.tileWidth, definition.tileHeight)
        }
    }

    private fun loadLegacyTiles(root: Path) {
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
    }

    private fun loadImages(root: Path) {
        // Parsed content carries its immutable atlas layout across initial load and GL recovery.
        // Retain the legacy constructor's unspecified-layout behavior for existing callers.
        if (resources.tileSets.all { it.columns > 0 && it.tileCount != Int.MAX_VALUE }) loadParsedTiles()
        else loadLegacyTiles(root)
        for ((id, definition) in resources.units) {
            val texture = texture(definition.imagePath)
            require(texture.width % definition.totalFrames == 0) { "Invalid unit image frames: ${definition.imagePath}" }
            units[id] = TextureRegion(texture, 0, 0, texture.width / definition.totalFrames, texture.height)
            definition.turretImagePath?.let { turrets[id] = TextureRegion(texture(it)) }
            definition.deadImagePath?.let { corpses[id] = TextureRegion(texture(it)) }
            definition.backImagePath?.let { backs[id] = TextureRegion(texture(it)) }
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
            if (unit.alive) backs[unit.typeId]?.let { back ->
                val bw = (back.regionWidth * definition.imageScale * projection.scale).toFloat()
                val bh = (back.regionHeight * definition.imageScale * projection.scale).toFloat()
                batch.draw(back, projection.x(unit.position.x) - bw / 2, projection.y(unit.position.y) - bh / 2,
                    bw / 2, bh / 2, bw, bh, 1f, 1f, -unit.heading.toFloat())
            }
            val region = if (!unit.alive) corpses[unit.typeId] ?: units.getValue(unit.typeId) else units.getValue(unit.typeId)
            val w = (region.regionWidth * definition.imageScale * projection.scale).toFloat()
            val h = (region.regionHeight * definition.imageScale * projection.scale).toFloat()
            batch.draw(region, projection.x(unit.position.x) - w / 2, projection.y(unit.position.y) - h / 2,
                w / 2, h / 2, w, h, 1f, 1f, -unit.heading.toFloat())
            if (unit.alive) turrets[unit.typeId]?.let { turret ->
                val tw = (turret.regionWidth * definition.turretImageScale * projection.scale).toFloat()
                val th = (turret.regionHeight * definition.turretImageScale * projection.scale).toFloat()
                val heading = Math.toRadians(unit.heading)
                val mount = definition.turretMount
                val x = unit.position.x + kotlin.math.sin(heading) * mount.localY + kotlin.math.cos(heading) * mount.localX
                val y = unit.position.y - kotlin.math.cos(heading) * mount.localY + kotlin.math.sin(heading) * mount.localX
                batch.draw(turret, projection.x(x) - tw / 2, projection.y(y) - th / 2,
                    tw / 2, th / 2, tw, th, 1f, 1f, -unit.turretHeading.toFloat())
            }
        }
    }

    fun drawOverlay(shapes: ShapeRenderer, frame: SandboxFrame, selectedId: String?) {
        drawOverlay(shapes, frame, selectedId?.let { setOf(it) } ?: emptySet())
    }

    fun drawOverlay(shapes: ShapeRenderer, frame: SandboxFrame, selectedIds: Set<String>) {
        shapes.color = Color(0.45f, 0.5f, 0.47f, 1f)
        shapes.rect(projection.left.toFloat(), projection.bottom.toFloat(), projection.width.toFloat(), projection.height.toFloat())
        for (unit in frame.observation.units) {
            shapes.color = if (unit.alive) resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId) else Color.DARK_GRAY
            val radius = (resources.units.getValue(unit.typeId).displayRadius * projection.scale).toFloat()
            val x = projection.x(unit.position.x)
            val y = projection.y(unit.position.y)
            shapes.circle(x, y, radius + if (unit.unitId in selectedIds) 4f else 1f, 32)
            val health = unit.health
            val maximum = unit.maxHealth
            if (unit.alive && health != null && maximum != null) {
                shapes.line(x - radius, y + radius + 4f, x - radius + 2f * radius * (health / maximum).toFloat(), y + radius + 4f)
            }
            if (unit.unitId in selectedIds) {
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
        shapes.color = Color.YELLOW
        for (projectile in frame.observation.projectiles) {
            val x = projection.x(projectile.position.x)
            val y = projection.y(projectile.position.y)
            shapes.circle(x, y, 2f, 8)
        }
    }

    fun drawMinimap(shapes: ShapeRenderer, frame: SandboxFrame, area: MapArea) {
        val snapshot = MinimapSnapshot.from(frame)
        val cellWidth = (area.width / resources.widthTiles).toFloat()
        val cellHeight = (area.height / resources.heightTiles).toFloat()
        for (cell in snapshot.terrain) {
            shapes.color = when {
                !cell.known -> Color.BLACK
                cell.visible -> Color(0.3f, 0.38f, 0.28f, 1f)
                else -> Color(0.09f, 0.114f, 0.084f, 1f)
            }
            shapes.rect(area.left.toFloat() + cell.column * cellWidth,
                area.top.toFloat() - (cell.row + 1) * cellHeight, cellWidth, cellHeight)
        }
        for (unit in snapshot.units) {
            shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
            shapes.circle((area.left + unit.position.x / projection.worldWidth * area.width).toFloat(),
                (area.top - unit.position.y / projection.worldHeight * area.height).toFloat(), 2f, 8)
        }
    }

    fun drawMinimapCamera(shapes: ShapeRenderer, area: MapArea) {
        val (start, end) = projection.visibleWorld
        shapes.color = Color.LIGHT_GRAY
        shapes.rect((area.left + start.x / projection.worldWidth * area.width).toFloat(),
            (area.top - end.y / projection.worldHeight * area.height).toFloat(),
            ((end.x - start.x) / projection.worldWidth * area.width).toFloat(),
            ((end.y - start.y) / projection.worldHeight * area.height).toFloat())
    }

    override fun close() {
        var failure: Exception? = null
        owned.forEach { texture ->
            try { texture.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        owned.clear(); tiles.clear(); units.clear(); turrets.clear(); corpses.clear(); backs.clear()
        failure?.let { throw it }
    }
}
