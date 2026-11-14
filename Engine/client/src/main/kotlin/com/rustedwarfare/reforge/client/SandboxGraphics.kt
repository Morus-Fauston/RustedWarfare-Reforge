package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.XmlReader
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.UnitObservation
import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ProjectileAppearance
import kotlin.math.abs

class SandboxGraphics private constructor(private val resources: SandboxRenderResources, fullBattlefield: Boolean) : AutoCloseable {
    val projection = MapProjection(resources.widthTiles.toDouble() * resources.tileWidth,
        resources.heightTiles.toDouble() * resources.tileHeight,
        if (fullBattlefield) MapArea(0.0, 0.0, 960.0, 640.0) else MapArea(16.0, 64.0, 704.0, 512.0),
        fillViewport = fullBattlefield)
    val minimap = if (fullBattlefield) Minimap(projection.worldWidth, projection.worldHeight,
        DESKTOP_MINIMAP_AREA) else Minimap(projection.worldWidth, projection.worldHeight)
    private val owned = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val units = mutableMapOf<String, TextureRegion>()
    private val turrets = mutableMapOf<String, TextureRegion>()
    private val namedTurrets = mutableMapOf<Pair<String, String>, TextureRegion>()
    private data class Impact(val position: Position, val height: Double, val started: Long, val large: Boolean)
    private val impacts = mutableMapOf<String, Impact>()
    private data class FlameParticle(val position: Position, val height: Double, val started: Long, val smoke: Boolean)
    private val flameParticles = mutableMapOf<String, FlameParticle>()
    private var impactSample: String? = null
    private val corpses = mutableMapOf<String, TextureRegion>()
    private val backs = mutableMapOf<String, TextureRegion>()
    private val legs = mutableMapOf<String, TextureRegion>()
    private val feet = mutableMapOf<String, TextureRegion>()
    private val rotors = mutableMapOf<String, TextureRegion>()
    private val shadows = mutableMapOf<String, TextureRegion>()
    private val shields = mutableMapOf<String, TextureRegion>()
    private val wings = mutableMapOf<Pair<String, UnitImageSlot>, TextureRegion>()
    private val beamImages = mutableMapOf<Path, TextureRegion>()
    private val trailImages = mutableMapOf<Path, TextureRegion>()
    private val afterburnTrails = AfterburnTrailPresentation()
    private val nuclearPresentation = NuclearPresentation()
    private data class BlinkFlash(val from: Position, val to: Position, val step: Long)
    private val blinkOrigins = mutableMapOf<String, Position>()
    private val blinkFlashes = mutableMapOf<String, BlinkFlash>()
    private var blinkSample: String? = null
    fun unitIcon(typeId: String): TextureRegion? = resources.units[typeId]?.iconWeaponName?.let {
        namedTurrets[typeId to it]
    } ?: units[typeId]

    companion object {
        internal val DESKTOP_MINIMAP_AREA = MapArea(752.0, 512.0, 184.0, 112.0)

        fun load(resources: SandboxRenderResources, root: Path, fullBattlefield: Boolean = false): SandboxGraphics =
            loadPrepared(resources, root, intArrayOf(), fullBattlefield)

        /** Consume worker-bound metadata; GL owns texture upload, completeness checks and release. */
        fun load(loaded: LoadedSandbox, root: Path, fullBattlefield: Boolean = false): SandboxGraphics =
            loadPrepared(loaded.renderResources, root, loaded.scenario.map.groundGids, fullBattlefield)

        fun load(scenario: Scenario, root: Path, visuals: PreparedVisualAssets? = null): SandboxGraphics {
            val resources = SandboxRenderResources.from(scenario)
            return loadPrepared(visuals?.let { resources.withVisuals(it) } ?: resources, root, scenario.map.groundGids, false)
        }

        private fun loadPrepared(resources: SandboxRenderResources, root: Path, ground: IntArray,
                                 fullBattlefield: Boolean): SandboxGraphics {
            val graphics = SandboxGraphics(resources, fullBattlefield)
            try {
                graphics.loadImages(root)
                for (gid in ground) require(gid == 0 || graphics.tiles.containsKey(gid)) { "Missing tile image for gid=$gid" }
                return graphics
            } catch (failure: Exception) {
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
                definition.margin + index % definition.columns * (definition.pixelTileWidth + definition.spacing),
                definition.margin + index / definition.columns * (definition.pixelTileHeight + definition.spacing),
                definition.pixelTileWidth, definition.pixelTileHeight)
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
        val turretImages = mutableMapOf<Path, TextureRegion>()
        fun turretRegion(path: Path) = turretImages.getOrPut(path) { TextureRegion(texture(path)) }
        for ((id, definition) in resources.units) {
            val texture = texture(definition.imagePath)
            require(texture.width % definition.totalFrames == 0) { "Invalid unit image frames: ${definition.imagePath}" }
            units[id] = TextureRegion(texture, 0, 0, texture.width / definition.totalFrames, texture.height)
            definition.turretImagePath?.let { turrets[id] = turretRegion(it) }
            for ((name, weapon) in definition.weaponImages)
                if (!weapon.invisible) weapon.imagePath?.let { namedTurrets[id to name] = turretRegion(it) }
            definition.deadImagePath?.let { corpses[id] = TextureRegion(texture(it)) }
            definition.backImagePath?.let { backs[id] = TextureRegion(texture(it)) }
            definition.legImagePath?.let { legs[id] = TextureRegion(texture(it)) }
            definition.footImagePath?.let { feet[id] = TextureRegion(texture(it)) }
            definition.rotorImagePath?.let { rotors[id] = TextureRegion(texture(it)) }
            definition.shadowImagePath?.let { shadows[id] = TextureRegion(texture(it)) }
            definition.shieldImagePath?.let { shields[id] = TextureRegion(texture(it)) }
            definition.leftWingImagePath?.let { wings[id to UnitImageSlot.LEFT_WING] = TextureRegion(texture(it)) }
            definition.rightWingImagePath?.let { wings[id to UnitImageSlot.RIGHT_WING] = TextureRegion(texture(it)) }
            definition.arm1ImagePath?.let { wings[id to UnitImageSlot.ARM1] = TextureRegion(texture(it)) }
            definition.arm2ImagePath?.let { wings[id to UnitImageSlot.ARM2] = TextureRegion(texture(it)) }
            definition.slotOverlayImagePath?.let { wings[id to UnitImageSlot.SLOT_OVERLAY] = TextureRegion(texture(it)) }
            for (path in listOfNotNull(definition.beamImagePath, definition.beamStartImagePath, definition.beamEndImagePath)) {
                beamImages[path] = TextureRegion(texture(path))
            }
            definition.beamImagePath?.let { beamImages.getValue(it).texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.Repeat) }
            definition.afterburnTrailImagePath?.let { trailImages.getOrPut(it) { TextureRegion(texture(it)) } }
        }
    }

    fun draw(batch: SpriteBatch, frame: SandboxFrame, construction: ConstructionPreview? = null,
        selectedIds: Set<String> = emptySet()) {
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
        for (drawing in afterburnTrails.project(frame.observation, resources)) {
            val region = trailImages.getValue(requireNotNull(drawing.images.afterburnTrailImagePath))
            val size = drawing.images.drawSize(UnitImageSlot.AFTERBURN_TRAIL, region.regionWidth, region.regionHeight)
            val width = (size.width * projection.scale * drawing.scale).toFloat()
            val height = (size.height * projection.scale * drawing.scale).toFloat()
            batch.setColor(1f, 227f / 255f, 43f / 255f, drawing.alpha)
            batch.draw(region, projection.x(drawing.center.x) - width / 2, projection.y(drawing.center.y) - height / 2,
                width / 2, height / 2, width, height, 1f, 1f, -drawing.heading.toFloat())
        }
        batch.color = Color.WHITE
        val visibleWeapons = frame.observation.weapons.groupBy { it.unitId }
        val mechFrames = MechPresentation.project(frame.observation, projection, resources).associateBy { it.unitId }
        val flightFrames = FlightPresentation.project(frame.observation, projection).associateBy { it.unitId }
        val waterFrames = WaterPresentation.project(frame.observation, projection).associateBy { it.unitId }
        val observedUnits = frame.observation.units
        val drawOrder = if (observedUnits.any { it.attachment != null }) observedUnits.sortedBy { it.attachment != null }
            else observedUnits
        for (unit in drawOrder) {
            val definition = resources.units.getValue(unit.typeId)
            if (!definition.isDrawn(unit)) continue
            batch.setColor(1f, 1f, 1f, if (unit.alive && unit.constructionProgress < 1f) .45f else 1f)
            val mech = mechFrames[unit.unitId]
            val flight = flightFrames[unit.unitId]
            val water = waterFrames[unit.unitId]
            val hover = HoverPresentation.project(unit, projection)
            val heightAdjustedCenter = unit.takeIf { it.amphibiousJet != null || it.attachment != null }?.displayedBodyPosition()
            val jetHeading = unit.amphibiousJet?.let {
                visibleWeapons[unit.unitId]?.singleOrNull { it.weaponName == "body" }?.turretHeading ?: unit.heading
            }
            if (unit.alive && unit.flightPose != null && flight == null) continue
            if (unit.waterPose != null && water == null) continue
            if (unit.hoverPose != null && hover == null) continue
            if (flight?.drawShadow == true) shadows[unit.typeId]?.let { shadow ->
                val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                val sw = (size.width * projection.scale).toFloat()
                val sh = (size.height * projection.scale).toFloat()
                batch.draw(shadow, projection.x(unit.position.x) - sw / 2, projection.y(unit.position.y) - sh / 2,
                    sw / 2, sh / 2, sw, sh, 1f, 1f, -unit.heading.toFloat())
            }
            if (hover?.drawShadow == true) shadows[unit.typeId]?.let { shadow ->
                val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                val sw = (size.width * projection.scale).toFloat()
                val sh = (size.height * projection.scale).toFloat()
                batch.draw(shadow, hover.shadowX - sw / 2, hover.shadowY - sh / 2,
                    sw / 2, sh / 2, sw, sh, 1f, 1f, hover.rotation)
            }
            if (unit.amphibiousJet != null && unit.alive && unit.height > 0.0) shadows[unit.typeId]?.let { shadow ->
                val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                val sw = (size.width * projection.scale).toFloat()
                val sh = (size.height * projection.scale).toFloat()
                batch.draw(shadow, projection.x(unit.position.x) - sw / 2, projection.y(unit.position.y) - sh / 2,
                    sw / 2, sh / 2, sw, sh, 1f, 1f, -requireNotNull(jetHeading).toFloat())
            }
            if (mech?.drawShadow == true) shadows[unit.typeId]?.let { shadow ->
                val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                val sw = (size.width * projection.scale).toFloat()
                val sh = (size.height * projection.scale).toFloat()
                batch.draw(shadow, mech.shadowX - sw / 2, mech.shadowY - sh / 2,
                    sw / 2, sh / 2, sw, sh, 1f, 1f, mech.rotation)
            }
            mech?.legs?.forEach { leg ->
                val shaft = legs.getValue(unit.typeId)
                val foot = feet.getValue(unit.typeId)
                val vertical = leg.shaft
                if (vertical != null) {
                    if (vertical.sourceHeight > 0) {
                        val crop = TextureRegion(shaft)
                        val span = shaft.v2 - shaft.v
                        crop.setRegion(shaft.u, shaft.v + span * vertical.sourceY / vertical.sourceImageHeight,
                            shaft.u2, shaft.v + span * (vertical.sourceY + vertical.sourceHeight) / vertical.sourceImageHeight)
                        batch.draw(crop, vertical.x - vertical.width / 2, vertical.y - vertical.height / 2,
                            vertical.width / 2, vertical.height / 2, vertical.width, vertical.height, 1f, 1f, vertical.rotation)
                    }
                } else {
                    val shaftSize = definition.drawSize(UnitImageSlot.LEG, shaft.regionWidth, shaft.regionHeight)
                    val thickness = (shaftSize.height * projection.scale).toFloat()
                    batch.draw(shaft, leg.anchorX, leg.anchorY - thickness / 2, 0f, thickness / 2,
                        leg.length, thickness, 1f, 1f, leg.rotation)
                }
                val footSize = definition.drawSize(UnitImageSlot.FOOT, foot.regionWidth, foot.regionHeight)
                val fw = (footSize.width * projection.scale).toFloat()
                val fh = (footSize.height * projection.scale).toFloat()
                batch.draw(foot, leg.footX - fw / 2, leg.footY - fh / 2, fw / 2, fh / 2,
                    fw, fh, 1f, 1f, leg.footRotation)
            }
            if (unit.alive) backs[unit.typeId]?.let { back ->
                val size = definition.drawSize(UnitImageSlot.BACK, back.regionWidth, back.regionHeight)
                val bw = (size.width * projection.scale).toFloat()
                val bh = (size.height * projection.scale).toFloat()
                batch.draw(back, projection.x(unit.position.x) - bw / 2, projection.y(unit.position.y) - bh / 2,
                    bw / 2, bh / 2, bw, bh, 1f, 1f, -unit.heading.toFloat())
            }
            val corpse = if (!unit.alive) corpses[unit.typeId] else null
            val region = corpse ?: units.getValue(unit.typeId)
            val size = definition.drawSize(if (corpse != null) UnitImageSlot.DEAD else UnitImageSlot.BODY, region.regionWidth, region.regionHeight)
            val w = (size.width * projection.scale).toFloat()
            val h = (size.height * projection.scale).toFloat()
            batch.draw(region, (flight?.bodyX ?: mech?.bodyX ?: water?.bodyX ?: hover?.bodyX ?: projection.x(heightAdjustedCenter?.x ?: unit.position.x)) - w / 2,
                (flight?.bodyY ?: mech?.bodyY ?: water?.bodyY ?: hover?.bodyY ?: projection.y(heightAdjustedCenter?.y ?: unit.position.y)) - h / 2,
                w / 2, h / 2, w, h, 1f, 1f, flight?.bodyRotation ?: mech?.rotation ?: water?.bodyRotation ?: hover?.rotation ?: -(jetHeading ?: unit.heading).toFloat())
            if (unit.alive && unit.unitId in selectedIds) {
                wings[unit.typeId to UnitImageSlot.SLOT_OVERLAY]?.let { overlay ->
                    val size = definition.drawSize(UnitImageSlot.SLOT_OVERLAY, overlay.regionWidth, overlay.regionHeight)
                    val width = (size.width * projection.scale).toFloat()
                    val height = (size.height * projection.scale).toFloat()
                    val center = unit.displayedBodyPosition()
                    batch.setColor(1f, 1f, 1f, .55f)
                    batch.draw(overlay, projection.x(center.x) - width / 2, projection.y(center.y) - height / 2,
                        width / 2, height / 2, width, height, 1f, 1f, -unit.heading.toFloat())
                    batch.color = Color.WHITE
                }
            }
            for (drawing in definition.amphibiousWings(unit, visibleWeapons[unit.unitId].orEmpty()) + definition.nuclearDoors(unit)) {
                val wing = wings.getValue(unit.typeId to drawing.slot)
                val size = definition.drawSize(drawing.slot, wing.regionWidth, wing.regionHeight)
                val ww = (size.width * projection.scale).toFloat()
                val wh = (size.height * projection.scale).toFloat()
                batch.draw(wing, projection.x(drawing.center.x) - ww / 2, projection.y(drawing.center.y) - wh / 2,
                    ww / 2, wh / 2, ww, wh, 1f, 1f, -drawing.heading.toFloat())
            }
            flight?.let { pose ->
                rotors[unit.typeId]?.let { rotor ->
                    val rotorSize = definition.drawSize(UnitImageSlot.ROTOR, rotor.regionWidth, rotor.regionHeight)
                    val rw = (rotorSize.width * projection.scale).toFloat()
                    val rh = (rotorSize.height * projection.scale).toFloat()
                    batch.draw(rotor, pose.rotorX - rw / 2, pose.rotorY - rh / 2, rw / 2, rh / 2,
                        rw, rh, 1f, 1f, pose.rotorRotation)
                }
            }
            for (drawing in definition.turretDrawings(unit, visibleWeapons[unit.unitId].orEmpty())) {
                val turret = drawing.weaponName?.let { namedTurrets.getValue(unit.typeId to it) } ?: turrets.getValue(unit.typeId)
                val pivot = drawing.pivot
                val size = drawing.drawSize(turret.regionWidth, turret.regionHeight)
                val tw = (size.width * projection.scale).toFloat()
                val th = (size.height * projection.scale).toFloat()
                batch.draw(turret, projection.x(pivot.x) - tw / 2, projection.y(pivot.y) - th / 2,
                    tw / 2, th / 2, tw, th, 1f, 1f, -drawing.heading.toFloat())
            }
            ShieldPresentation.image(unit, shields)?.let { drawing ->
                val shield = drawing.resource
                val shieldSize = definition.drawSize(UnitImageSlot.SHIELD, shield.regionWidth, shield.regionHeight)
                val sw = (shieldSize.width * projection.scale).toFloat()
                val sh = (shieldSize.height * projection.scale).toFloat()
                val center = unit.displayedBodyPosition()
                batch.setColor(1f, 1f, 1f, drawing.opacity)
                batch.draw(shield, projection.x(center.x) - sw / 2, projection.y(center.y) - sh / 2, sw, sh)
                batch.color = Color.WHITE
            }
        }
        for (drawing in BeamPresentation.project(frame.observation, resources)) {
            val x = projection.x(drawing.start.x)
            val y = projection.y(drawing.start.y)
            val endX = projection.x(drawing.end.x)
            val endY = projection.y(drawing.end.y)
            val length = kotlin.math.hypot(endX - x, endY - y)
            if (!listOf(x, y, endX, endY, length).all { it.isFinite() } || length == 0f) continue
            val images = drawing.images
            val body = beamImages.getValue(requireNotNull(images.beamImagePath))
            val size = images.drawSize(UnitImageSlot.BEAM, body.regionWidth, body.regionHeight)
            val width = (size.width * projection.scale).toFloat()
            val tileHeight = (size.height * projection.scale).toFloat()
            val repeated = TextureRegion(body)
            val phase = drawing.textureOffset / size.height.toFloat()
            repeated.setRegion(body.u, body.v + phase, body.u2, body.v + phase + length / tileHeight)
            val angle = Math.toDegrees(kotlin.math.atan2((endY - y).toDouble(), (endX - x).toDouble())).toFloat() - 90f
            batch.draw(repeated, x - width / 2, y, width / 2, 0f, width, length, 1f, 1f, angle)
            for ((path, slot, point) in listOf(
                Triple(requireNotNull(images.beamStartImagePath), UnitImageSlot.BEAM_START, x to y),
                Triple(requireNotNull(images.beamEndImagePath), UnitImageSlot.BEAM_END, endX to endY))) {
                val cap = beamImages.getValue(path)
                val capSize = images.drawSize(slot, cap.regionWidth, cap.regionHeight)
                val cw = (capSize.width * projection.scale).toFloat()
                val ch = (capSize.height * projection.scale).toFloat()
                batch.draw(cap, point.first - cw / 2, point.second - ch / 2, cw, ch)
            }
        }
        construction?.let { preview ->
            val definition = resources.units.getValue(preview.typeId)
            when (preview.state) {
                PlacementState.CLEAR -> batch.setColor(.6f, 1f, .6f, .5f)
                PlacementState.BLOCKED -> batch.setColor(1f, .4f, .4f, .5f)
                PlacementState.UNEXPLORED -> batch.setColor(1f, .85f, .4f, .5f)
            }
            for ((slot, region) in listOf(UnitImageSlot.BACK to backs[preview.typeId], UnitImageSlot.BODY to units[preview.typeId])) {
                if (region == null) continue
                val size = definition.drawSize(slot, region.regionWidth, region.regionHeight)
                val w = (size.width * projection.scale).toFloat()
                val h = (size.height * projection.scale).toFloat()
                batch.draw(region, projection.x(preview.position.x) - w / 2f,
                    projection.y(preview.position.y) - h / 2f, w, h)
            }
        }
        batch.color = Color.WHITE
    }

    fun drawOverlay(shapes: ShapeRenderer, frame: SandboxFrame, selectedIds: Set<String>, box: Pair<Position, Position>? = null,
                    construction: ConstructionPreview? = null, groundAction: GroundActionPreview? = null,
                    primaryId: String? = null) {
        shapes.color = Color(0.45f, 0.5f, 0.47f, 1f)
        shapes.rect(projection.left.toFloat(), projection.bottom.toFloat(), projection.width.toFloat(), projection.height.toFloat())
        val selectedSlots = frame.observation.units.asSequence().filter { it.unitId in selectedIds }
            .associate { it.unitId to it.attachmentOverview?.selectedSlot }
        for (unit in frame.observation.units) {
            if (!resources.units.getValue(unit.typeId).isDrawn(unit)) continue
            val attachment = unit.attachment
            val showSlot = attachment != null && attachment.parentUnitId in selectedSlots
            if (attachment?.empty == true && !showSlot) continue
            shapes.color = if (unit.alive) resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId) else Color.DARK_GRAY
            val radius = (resources.units.getValue(unit.typeId).displayRadius * projection.scale).toFloat()
            val center = unit.displayedBodyPosition()
            val x = projection.x(center.x)
            val y = projection.y(center.y)
            if (!x.isFinite() || !y.isFinite()) continue
            unit.constructionTargetId?.let { targetId ->
                frame.observation.units.firstOrNull { it.unitId == targetId && it.alive }?.let { target ->
                    shapes.color = if (unit.tasks.firstOrNull()?.kind ==
                        com.rustedwarfare.reforge.core.sim.UnitTaskKind.RECLAIM) Color.RED else Color.BLUE
                    shapes.line(x, y, projection.x(target.position.x), projection.y(target.position.y))
                    shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
                }
            }
            if (showSlot) {
                val active = selectedSlots[attachment?.parentUnitId] == attachment?.slot
                shapes.color = if (active) Color.CYAN else Color.WHITE
                shapes.circle(x, y, (if (active) 8f else 6f) * projection.scale.toFloat(), 16)
            } else if (attachment == null) {
                shapes.circle(x, y, radius + if (unit.unitId in selectedIds) 4f else 1f, 32)
                if (unit.unitId == primaryId && selectedIds.size > 1) {
                    val teamColor = shapes.color.cpy()
                    shapes.color = Color.WHITE
                    shapes.circle(x, y, radius + 7f, 32)
                    shapes.color = teamColor
                }
            }
            val health = unit.health
            val maximum = unit.maxHealth
            if (unit.alive && attachment?.empty != true && health != null && maximum != null) {
                shapes.line(x - radius, y + radius + 4f, x - radius + 2f * radius * (health / maximum).toFloat(), y + radius + 4f)
            }
            if (unit.alive) unit.shield?.let { shield ->
                shapes.color = Color.CYAN
                shapes.line(x - radius, y + radius + 8f,
                    x - radius + 2f * radius * (shield.current / shield.maximum), y + radius + 8f)
                shapes.color = resources.unitCircleColor(frame.observation.playerId, unit.ownerPlayerId)
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
            if (unit.alive) unit.energy?.let { energy ->
                shapes.color = if (energy.recharging) Color.ORANGE else Color.CYAN
                shapes.line(x - radius, y + radius + 8f,
                    x - radius + 2f * radius * (energy.current / energy.maximum), y + radius + 8f)
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
                if (unit.alive && unit.constructionProgress >= 1f && unit.ownerPlayerId == frame.observation.playerId)
                    resources.units.getValue(unit.typeId).repairRange?.let { range ->
                        shapes.color = Color(.25f, .65f, .45f, 1f)
                        shapes.circle(projection.x(unit.position.x), projection.y(unit.position.y),
                            (range * projection.scale).toFloat(), 96)
                    }
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
        groundAction?.let { preview ->
            shapes.color = if (preview.valid) Color.ORANGE else Color.RED
            val sourceX = projection.x(preview.source.x); val sourceY = projection.y(preview.source.y)
            if (preview.minimumRange > 0.0)
                shapes.circle(sourceX, sourceY, (preview.minimumRange * projection.scale).toFloat(), 96)
            shapes.circle(sourceX, sourceY, (preview.maximumRange * projection.scale).toFloat(), 192)
            val x = projection.x(preview.position.x); val y = projection.y(preview.position.y)
            if (preview.areaRadius > 0.0) {
                shapes.circle(x, y, (preview.areaRadius * projection.scale).toFloat(), 96)
                shapes.circle(x, y, ((preview.areaRadius - 30.0) * projection.scale).toFloat(), 96)
            }
            shapes.line(x - 7f, y, x + 7f, y); shapes.line(x, y - 7f, x, y + 7f)
        }
        drawBlink(shapes, frame.observation)
        for (effect in nuclearPresentation.project(frame.observation)) {
            val x=projection.x(effect.center.x)
            val y=projection.y(effect.center.y)+(effect.height*projection.scale).toFloat()
            val radius=(effect.radius*projection.scale).toFloat()
            when (effect.kind) {
                NuclearEffectKind.TRAIL -> {
                    shapes.setColor(.55f,.52f,.44f,effect.alpha)
                    shapes.circle(x,y,radius,12)
                }
                NuclearEffectKind.LAUNCH -> {
                    shapes.setColor(1f,.72f,.2f,effect.alpha)
                    shapes.circle(x,y,radius,32)
                    shapes.line(x-radius,y,x+radius,y);shapes.line(x,y-radius,x,y+radius)
                }
                NuclearEffectKind.INTERCEPTION -> {
                    shapes.setColor(1f, .94f, .7f, effect.alpha)
                    shapes.circle(x, y, radius, 24)
                    shapes.circle(x, y, radius * .5f, 16)
                }
                NuclearEffectKind.EXPLOSION -> {
                    shapes.setColor(1f,.88f,.48f,effect.alpha)
                    shapes.circle(x,y,radius,64)
                    shapes.circle(x,y,radius*.65f,48)
                    for (i in 0..7) {
                        val a=i*Math.PI/4
                        val dx=(kotlin.math.cos(a)*radius).toFloat();val dy=(kotlin.math.sin(a)*radius).toFloat()
                        shapes.line(x+dx*.25f,y+dy*.25f,x+dx,y+dy)
                    }
                }
            }
        }
        shapes.color = Color.YELLOW
        for (projectile in frame.observation.projectiles) {
            if (projectile.beam != null) continue
            val x = projection.x(projectile.position.x)
            val y = projection.y(projectile.position.y) + (projectile.height * projection.scale).toFloat()
            val areaRadius = projectile.areaRadius
            val appearance = projectile.appearance
            if (appearance == ProjectileAppearance.MECH_FLAME) continue
            if (appearance == ProjectileAppearance.NUCLEAR && areaRadius == null) {
                shapes.color = Color.DARK_GRAY
                shapes.ellipse(x - 5f, projection.y(projectile.position.y) - 2f, 10f, 4f, 16)
                shapes.color = Color.LIGHT_GRAY
                shapes.line(x, y - 4f, x, y + 5f)
            }
            shapes.setColor(appearance.red / 255f, appearance.green / 255f, appearance.blue / 255f, 1f)
            val lightning = projectile.lightning
            if (lightning != null) shapes.line(x, y, projection.x(lightning.end.x),
                projection.y(lightning.end.y) + (lightning.endHeight * projection.scale).toFloat())
            else if (areaRadius == null) shapes.circle(x, y, appearance.drawSize, 8)
            else if (areaRadius > 0f) shapes.circle(x, y, (areaRadius * projection.scale).toFloat(), 48)
        }
        val now = System.nanoTime()
        if (impactSample != frame.observation.sampleId) {
            impactSample = frame.observation.sampleId
            for (event in frame.observation.projectileEvents) {
                if (event.appearance == ProjectileAppearance.MECH_FLAME) {
                    val smoke = event.kind == "born" && (event.token.hashCode() and Int.MAX_VALUE) % 10 < 3
                    if (smoke || event.kind == "impact")
                        flameParticles[event.token + event.kind] = FlameParticle(event.position, event.height, now, smoke)
                    continue
                }
                if (event.kind != "impact" || event.beam != null) continue
                val large = event.appearance == ProjectileAppearance.BOMBER_BOMB
                val height = if (large || event.appearance == ProjectileAppearance.BOMBER_DEPTH_CHARGE) event.height else 0.0
                impacts[event.token] = Impact(event.position, height, now, large)
            }
        }
        fun duration(impact: Impact) = if (impact.large) 400_000_000L else 180_000_000L
        impacts.entries.removeAll { now - it.value.started > duration(it.value) }
        shapes.color = Color.ORANGE
        for (impact in impacts.values) {
            val fraction = (now - impact.started).toDouble() / duration(impact)
            val radius = (3.0 + 9.0 * fraction) * if (impact.large) 2.6 * projection.scale else 1.0
            shapes.circle(projection.x(impact.position.x), projection.y(impact.position.y) + (impact.height * projection.scale).toFloat(),
                radius.toFloat(), 16)
        }
        shapes.color = Color.CYAN
        for (beam in resources.defenseBeams(frame.observation)) {
            shapes.line(projection.x(beam.start.x), projection.y(beam.start.y), projection.x(beam.end.x), projection.y(beam.end.y))
        }
        shapes.color = Color.GREEN
        for (beam in resources.nanoBeams(frame.observation)) {
            shapes.line(projection.x(beam.start.x), projection.y(beam.start.y), projection.x(beam.end.x), projection.y(beam.end.y))
        }
        box?.let { (start, end) ->
            val x1 = projection.x(start.x); val x2 = projection.x(end.x)
            val y1 = projection.y(start.y); val y2 = projection.y(end.y)
            shapes.color = Color(0.45f, 0.91f, 0.65f, 1f)
            shapes.rect(minOf(x1, x2), minOf(y1, y2), abs(x1 - x2), abs(y1 - y2))
        }
    }

    fun drawFlameEffects(shapes: ShapeRenderer, frame: SandboxFrame) {
        val now = System.nanoTime()
        flameParticles.values.removeAll { now - it.started > 350_000_000L }
        val shots = frame.observation.projectiles.filter { it.appearance == ProjectileAppearance.MECH_FLAME }
        if (shots.isEmpty() && flameParticles.isEmpty()) return
        val scale = projection.scale.toFloat()
        val gl = Gdx.gl
        val wasBlending = gl.glIsEnabled(GL20.GL_BLEND)
        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        try {
            for (shot in shots) {
                val x = projection.x(shot.position.x)
                val y = projection.y(shot.position.y) + shot.height.toFloat() * scale
                shapes.setColor(.92f, .25f, .035f, .64f)
                shapes.circle(x, y, 4.1f * scale, 12)
                shapes.setColor(1f, .67f, .18f, .94f)
                shapes.circle(x, y, 2.5f * scale, 10)
                shapes.setColor(1f, .94f, .64f, 1f)
                shapes.circle(x, y, 1.1f * scale, 8)
            }
            for (particle in flameParticles.values) {
                val age = ((now - particle.started).toDouble() / 350_000_000L).toFloat()
                val x = projection.x(particle.position.x)
                val y = projection.y(particle.position.y) + (particle.height.toFloat() + 6f * age) * scale
                if (particle.smoke) shapes.setColor(.36f, .29f, .24f, .65f * (1f - age))
                else shapes.setColor(1f, .44f + .25f * (1f - age), .1f, 1f - age)
                shapes.circle(x, y, (3f + age * 6f) * scale, 12)
            }
        } finally {
            shapes.end()
            if (!wasBlending) gl.glDisable(GL20.GL_BLEND)
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

    private fun drawBlink(shapes: ShapeRenderer, observation: PlayerObservation) {
        val step = observation.stepIndex
        fun charge(unit: UnitObservation) = unit.unitActions?.queue?.firstOrNull { it.name == "blinkQueued" ||
            it.name == "blink" && unit.attachmentOverview == null }
        if (observation.sampleId != blinkSample) {
            blinkSample = observation.sampleId
            for (unit in observation.units) {
                if (charge(unit) != null) blinkOrigins[unit.unitId] = unit.position
                if (observation.events.any { it.kind == "teleported" && it.unitId == unit.unitId } &&
                    blinkFlashes[unit.unitId]?.step != step) {
                    val origin = blinkOrigins.remove(unit.unitId) ?: unit.position
                    blinkFlashes[unit.unitId] = BlinkFlash(origin, unit.position, step)
                }
            }
            val charging = observation.units.filter { it.alive && charge(it) != null }.mapTo(hashSetOf()) { it.unitId }
            blinkOrigins.keys.retainAll(charging)
            blinkFlashes.values.removeAll { step - it.step >= 36 }
        }
        shapes.setColor(.07f, .93f, .95f, 1f)
        for (unit in observation.units) {
            val charge = charge(unit) ?: continue
            val radius = (24 + charge.progress * 25) * projection.scale.toFloat()
            val x = projection.x(unit.position.x)
            val y = projection.y(unit.position.y)
            shapes.circle(x, y, radius, 40)
            shapes.circle(x, y, radius * .78f, 32)
        }
        for (flash in blinkFlashes.values) {
            val progress = (step - flash.step) / 36f
            shapes.setColor(.07f, .93f, .95f, 1f - progress)
            val radius = (20 + 38 * progress) * projection.scale.toFloat()
            for (point in listOf(flash.from, flash.to))
                shapes.circle(projection.x(point.x), projection.y(point.y), radius, 40)
        }
    }

    override fun close() {
        var failure: Exception? = null
        owned.forEach { texture ->
            try { texture.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        owned.clear(); tiles.clear(); units.clear(); turrets.clear(); namedTurrets.clear(); corpses.clear(); backs.clear()
        legs.clear(); feet.clear(); impacts.clear()
        failure?.let { throw it }
    }
}
