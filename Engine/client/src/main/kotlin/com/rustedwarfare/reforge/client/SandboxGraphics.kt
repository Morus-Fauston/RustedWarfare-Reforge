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
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.abs

class SandboxGraphics private constructor(private val resources: SandboxRenderResources) : AutoCloseable {
    val projection = MapProjection(resources.widthTiles.toDouble() * resources.tileWidth,
        resources.heightTiles.toDouble() * resources.tileHeight, MapArea(16.0, 64.0, 704.0, 512.0))
    val minimap = Minimap(projection.worldWidth, projection.worldHeight)
    private val owned = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val units = mutableMapOf<String, TextureRegion>()
    private val turrets = mutableMapOf<String, TextureRegion>()
    private val impacts = mutableMapOf<String, Pair<Position, Long>>()
    private var impactSample: String? = null
    private val corpses = mutableMapOf<String, TextureRegion>()
    private val backs = mutableMapOf<String, TextureRegion>()
    fun unitIcon(typeId: String): TextureRegion? = units[typeId]

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

    fun draw(batch: SpriteBatch, frame: SandboxFrame, construction: ConstructionPreview? = null) {
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
            if (!definition.isDrawn(unit)) continue
            batch.setColor(1f, 1f, 1f, if (unit.alive && unit.constructionProgress < 1f) .45f else 1f)
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
                val pivot = definition.turretPosition(unit)
                val tw = (turret.regionWidth * definition.turretImageScale * projection.scale).toFloat()
                val th = (turret.regionHeight * definition.turretImageScale * projection.scale).toFloat()
                batch.draw(turret, projection.x(pivot.x) - tw / 2, projection.y(pivot.y) - th / 2,
                    tw / 2, th / 2, tw, th, 1f, 1f, -unit.turretHeading.toFloat())
            }
        }
        construction?.let { preview ->
            val definition = resources.units.getValue(preview.typeId)
            when (preview.state) {
                PlacementState.CLEAR -> batch.setColor(.6f, 1f, .6f, .5f)
                PlacementState.BLOCKED -> batch.setColor(1f, .4f, .4f, .5f)
                PlacementState.UNEXPLORED -> batch.setColor(1f, .85f, .4f, .5f)
            }
            for (region in listOfNotNull(backs[preview.typeId], units[preview.typeId])) {
                val w = (region.regionWidth * definition.imageScale * projection.scale).toFloat()
                val h = (region.regionHeight * definition.imageScale * projection.scale).toFloat()
                batch.draw(region, projection.x(preview.position.x) - w / 2f,
                    projection.y(preview.position.y) - h / 2f, w, h)
            }
        }
        batch.color = Color.WHITE
    }

    fun drawOverlay(shapes: ShapeRenderer, frame: SandboxFrame, selectedIds: Set<String>, box: Pair<Position, Position>? = null,
                    construction: ConstructionPreview? = null) {
        shapes.color = Color(0.45f, 0.5f, 0.47f, 1f)
        shapes.rect(projection.left.toFloat(), projection.bottom.toFloat(), projection.width.toFloat(), projection.height.toFloat())
        for (unit in frame.observation.units) {
            if (!resources.units.getValue(unit.typeId).isDrawn(unit)) continue
            shapes.color = if (unit.alive) resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId) else Color.DARK_GRAY
            val radius = (resources.units.getValue(unit.typeId).displayRadius * projection.scale).toFloat()
            val x = projection.x(unit.position.x)
            val y = projection.y(unit.position.y)
            unit.constructionTargetId?.let { targetId ->
                frame.observation.units.firstOrNull { it.unitId == targetId && it.alive }?.let { target ->
                    shapes.color = if (unit.tasks.firstOrNull()?.kind ==
                        com.rustedwarfare.reforge.core.sim.UnitTaskKind.RECLAIM) Color.RED else Color.BLUE
                    shapes.line(x, y, projection.x(target.position.x), projection.y(target.position.y))
                    shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
                }
            }
            shapes.circle(x, y, radius + if (unit.unitId in selectedIds) 4f else 1f, 32)
            val health = unit.health
            val maximum = unit.maxHealth
            if (unit.alive && health != null && maximum != null) {
                shapes.line(x - radius, y + radius + 4f, x - radius + 2f * radius * (health / maximum).toFloat(), y + radius + 4f)
            }
            if (unit.alive && unit.constructionProgress < 1f) {
                shapes.color = Color.CYAN
                shapes.line(x - radius, y - radius - 9f, x - radius + 2f * radius * unit.constructionProgress, y - radius - 9f)
                shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
            }
            if (unit.alive) unit.pointDefense?.let { defense ->
                shapes.color = if (defense.exhausted) Color.ORANGE else Color.CYAN
                shapes.line(x - radius, y + radius + 8f, x - radius + 2f * radius * defense.energy, y + radius + 8f)
                shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
            }
            if (unit.alive && frame.observation.scenarioId == "p006-construction") {
                frame.observation.factoryQueues.firstOrNull { it.factoryId == unit.unitId }?.items?.firstOrNull()?.let { head ->
                    shapes.color = Color(.68f, .3f, .82f, 1f)
                    shapes.line(x - radius, y - radius - 5f, x - radius + 2f * radius * head.progress, y - radius - 5f)
                    shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
                }
            }
            if (unit.unitId in selectedIds) {
                shapes.circle(x, y, radius + 6f, 32)
                if (unit.ownerPlayerId == frame.observation.playerId) {
                    shapes.color = Color.CYAN
                    var stored: Position? = null
                    for (task in unit.tasks) if (task.kind == com.rustedwarfare.reforge.core.sim.UnitTaskKind.PATROL) {
                        val point = checkNotNull(task.target)
                        val px = projection.x(point.x); val py = projection.y(point.y)
                        shapes.circle(px, py, 4f, 16)
                        stored?.let { shapes.line(projection.x(it.x), projection.y(it.y), px, py) }
                        stored = point
                    }
                    shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
                }
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
        construction?.let { preview ->
            shapes.color = when (preview.state) {
                PlacementState.CLEAR -> Color.GREEN
                PlacementState.BLOCKED -> Color.RED
                PlacementState.UNEXPLORED -> Color.GRAY
            }
            for ((col, row) in preview.cells) shapes.rect(projection.x(col * 20.0), projection.y((row + 1) * 20.0),
                (20 * projection.scale).toFloat(), (20 * projection.scale).toFloat())
        }
        shapes.color = Color.YELLOW
        for (projectile in frame.observation.projectiles) {
            val x = projection.x(projectile.position.x)
            val y = projection.y(projectile.position.y) + (projectile.height * projection.scale).toFloat()
            val areaRadius = projectile.areaRadius
            if (areaRadius == null) shapes.circle(x, y, 2f, 8)
            else if (areaRadius > 0f) shapes.circle(x, y, (areaRadius * projection.scale).toFloat(), 48)
        }
        val now = System.nanoTime()
        if (impactSample != frame.observation.sampleId) {
            impactSample = frame.observation.sampleId
            for (event in frame.observation.projectileEvents) if (event.kind == "impact")
                impacts[event.token] = event.position to now
        }
        impacts.entries.removeAll { now - it.value.second > 180_000_000L }
        shapes.color = Color.ORANGE
        for ((position, started) in impacts.values) {
            val fraction = (now - started).toDouble() / 180_000_000.0
            shapes.circle(projection.x(position.x), projection.y(position.y), (3.0 + 9.0 * fraction).toFloat(), 16)
        }
        shapes.color = Color.CYAN
        for (beam in resources.defenseBeams(frame.observation)) {
            shapes.line(projection.x(beam.start.x), projection.y(beam.start.y), projection.x(beam.end.x), projection.y(beam.end.y))
        }
        box?.let { (start, end) ->
            val x1 = projection.x(start.x); val x2 = projection.x(end.x)
            val y1 = projection.y(start.y); val y2 = projection.y(end.y)
            shapes.color = Color(0.45f, 0.91f, 0.65f, 1f)
            shapes.rect(minOf(x1, x2), minOf(y1, y2), abs(x1 - x2), abs(y1 - y2))
        }
    }

    fun drawMinimap(shapes: ShapeRenderer, frame: SandboxFrame) {
        val view = minimap.frame(frame)
        val area = minimap.area
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color.BLACK
        shapes.rect(area.left.toFloat(), area.bottom.toFloat(), area.width.toFloat(), area.height.toFloat())
        val width = (area.width / resources.widthTiles).toFloat()
        val height = (area.height / resources.heightTiles).toFloat()
        for (cell in view.terrain) {
            if (!cell.known) continue
            shapes.color = if (cell.visible) Color(0.35f, 0.46f, 0.34f, 1f) else Color(0.1f, 0.14f, 0.1f, 1f)
            shapes.rect(minimap.x(cell.column.toDouble() * resources.tileWidth),
                minimap.y((cell.row + 1.0) * resources.tileHeight), width, height)
        }
        for (unit in view.units) {
            shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.owner)
            shapes.rect(minimap.x(unit.position.x) - 2f, minimap.y(unit.position.y) - 2f, 4f, 4f)
        }
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = Color.WHITE
        shapes.rect(area.left.toFloat(), area.bottom.toFloat(), area.width.toFloat(), area.height.toFloat())
        val topLeft = checkNotNull(projection.clippedWorld(projection.mapArea.left, projection.mapArea.bottom + projection.mapArea.height))
        val bottomRight = checkNotNull(projection.clippedWorld(projection.mapArea.left + projection.mapArea.width, projection.mapArea.bottom))
        shapes.rect(minimap.x(topLeft.x), minimap.y(bottomRight.y),
            minimap.x(bottomRight.x) - minimap.x(topLeft.x), minimap.y(topLeft.y) - minimap.y(bottomRight.y))
        shapes.end()
    }

    override fun close() {
        var failure: Exception? = null
        owned.forEach { texture ->
            try { texture.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        owned.clear(); tiles.clear(); units.clear(); turrets.clear(); corpses.clear(); backs.clear(); impacts.clear()
        failure?.let { throw it }
    }
}
