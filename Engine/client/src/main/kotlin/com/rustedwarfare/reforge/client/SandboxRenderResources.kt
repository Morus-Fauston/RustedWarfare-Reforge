package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Scenario
import com.badlogic.gdx.graphics.Color
import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.content.WeaponRecoil
import com.rustedwarfare.reforge.core.content.NuclearSiloKind
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitObservation
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.VisibleUnitWeapon
import java.nio.file.Path
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

data class WeaponAppearance(val imagePath: Path?, val imageScale: Double,
    val invisible: Boolean = false, val offsetX: Double = 0.0, val offsetY: Double = 0.0,
    val logicalSize: ImageLogicalSize? = null)
internal data class TurretDrawing(val weaponName: String?, val imagePath: Path, val imageScale: Double,
    val pivot: Position, val heading: Double, val logicalSize: ImageLogicalSize? = null) {
    fun drawSize(pixelWidth: Int, pixelHeight: Int) = ImageDrawSize(
        (logicalSize?.width ?: pixelWidth) * imageScale, (logicalSize?.height ?: pixelHeight) * imageScale)
}

internal data class WingDrawing(val slot: UnitImageSlot, val imagePath: Path, val center: Position, val heading: Double)

data class UnitAppearance(val displayRadius: Double, val imagePath: Path, val imageScale: Double, val totalFrames: Int,
    val turretImagePath: Path? = null, val deadImagePath: Path? = null, val turretImageScale: Double = 1.0,
    val backImagePath: Path? = null, val turretMount: TurretMount = TurretMount(),
    val defenseMuzzleOffset: Position? = null, val recoil: WeaponRecoil? = null, val shootDelay: Double = 0.0,
    val economicBuilding: Boolean = false, val logicalImages: UnitImageLogicalSizes? = null,
    val weaponImages: Map<String, WeaponAppearance> = emptyMap(),
    val legImagePath: Path? = null, val footImagePath: Path? = null, val rotorImagePath: Path? = null,
    val mechKind: com.rustedwarfare.reforge.core.content.MechKind? = null, val shadowImagePath: Path? = null,
    val shieldImagePath: Path? = null, val shieldRadius: Double = 0.0,
    val leftWingImagePath: Path? = null, val rightWingImagePath: Path? = null,
    val beamImagePath: Path? = null, val beamStartImagePath: Path? = null, val beamEndImagePath: Path? = null,
    val afterburnTrailImagePath: Path? = null, val repairRange: Double? = null,
    val iconWeaponName: String? = null, val nanoWeaponName: String? = null,
    val nativeLegSize: ImageLogicalSize? = null, val arm1ImagePath: Path? = null, val arm2ImagePath: Path? = null,
    val nuclearSiloKind: NuclearSiloKind? = null, val shadowOffset: Position? = null,
    val slotOverlayImagePath: Path? = null) {
    internal fun nuclearDoors(unit: UnitObservation): List<WingDrawing> {
        val amount = unit.nuclearDoors ?: return emptyList()
        if (!unit.alive) return emptyList()
        val forward = Math.toRadians(unit.heading); val side = Math.toRadians(unit.heading + 90.0)
        val offsets = if (nuclearSiloKind == NuclearSiloKind.INTERCEPTOR)
            listOf(Position(1.0 + 5f * amount, 12.0), Position(-2.0 - 5f * amount, 11.0))
        else listOf(Position(3.0 + 10f * amount, -1.0 - 8f * amount), Position(-5.0 - 9f * amount, 9.0 + 5f * amount))
        return listOf(Triple(UnitImageSlot.ARM1, arm1ImagePath, offsets[0]),
            Triple(UnitImageSlot.ARM2, arm2ImagePath, offsets[1])).map { (slot, path, offset) ->
            WingDrawing(slot, requireNotNull(path), Position(
                unit.position.x + sin(forward) * offset.y + sin(side) * offset.x,
                unit.position.y - cos(forward) * offset.y - cos(side) * offset.x), unit.heading)
        }
    }
    internal fun amphibiousWings(unit: UnitObservation, weapons: List<VisibleUnitWeapon>): List<WingDrawing> {
        if (!unit.alive || unit.amphibiousJet == null) return emptyList()
        val poses = weapons.filter { it.unitId == unit.unitId }.associateBy { it.weaponName }
        val heading = requireNotNull(poses["body"]) { "Amphibious body requires an authorized pose" }.turretHeading
        return listOf(Triple(UnitImageSlot.LEFT_WING, leftWingImagePath, "left"),
            Triple(UnitImageSlot.RIGHT_WING, rightWingImagePath, "right")).map { (slot, path, name) ->
            val pivot = requireNotNull(poses[name]).pivot
            WingDrawing(slot, requireNotNull(path), Position(pivot.x, pivot.y - unit.height), heading)
        }
    }
    internal fun turretDrawings(unit: UnitObservation, weapons: List<VisibleUnitWeapon>): List<TurretDrawing> {
        if (!unit.alive) return emptyList()
        if (weapons.isNotEmpty()) {
            val byName = weapons.associateBy { it.weaponName }
            check(weapons.all { it.unitId == unit.unitId } && byName.size == weapons.size && byName.keys == weaponImages.keys) {
                "Visible weapon poses must match the prepared unit catalog"
            }
            return weaponImages.mapNotNull { (name, image) -> if (image.invisible) null else image.imagePath?.let { path ->
                val pose = byName.getValue(name)
                val forward = Math.toRadians(pose.turretHeading)
                val side = Math.toRadians(pose.turretHeading + 90.0)
                val pivot = Position(pose.pivot.x + sin(forward) * image.offsetY + sin(side) * image.offsetX,
                    pose.pivot.y - cos(forward) * image.offsetY - cos(side) * image.offsetX -
                        if (unit.energy != null || unit.mechPose != null || unit.attachment != null) unit.height else 0.0)
                TurretDrawing(name, path, image.imageScale, pivot, pose.turretHeading,
                    image.logicalSize ?: logicalImages?.turret?.takeIf { path == turretImagePath })
            } }
        }
        check(weaponImages.size <= 1) { "A multi-weapon unit requires authorized weapon poses" }
        return turretImagePath?.let { listOf(TurretDrawing(null, it, turretImageScale,
            turretPosition(unit), unit.turretHeading, logicalImages?.turret)) } ?: emptyList()
    }
    fun drawSize(slot: UnitImageSlot, pixelWidth: Int, pixelHeight: Int,
                 turretScale: Double = turretImageScale): ImageDrawSize {
        val logical = logicalImages?.forSlot(slot)
        val scale = when (slot) {
            UnitImageSlot.TURRET -> turretScale
            UnitImageSlot.SHIELD -> shieldRadius * 2.0 / 87.0 * 1.25
            else -> imageScale
        }
        return ImageDrawSize((logical?.width ?: pixelWidth) * scale, (logical?.height ?: pixelHeight) * scale)
    }
    fun isDrawn(unit: UnitObservation): Boolean = unit.alive || (!economicBuilding && unit.mechPose == null) ||
        unit.constructionProgress >= 1f && deadImagePath != null
    fun turretPosition(unit: UnitObservation): Position {
        val forward = Math.toRadians(unit.heading)
        val side = Math.toRadians(unit.heading + 90.0)
        val cooldown = unit.cooldown ?: 0.0
        val elapsed = shootDelay - cooldown
        val distance = recoil?.let { if (cooldown <= 0.0) 0.0 else when {
            elapsed < it.outTime -> elapsed / it.outTime * it.offset
            elapsed < it.outTime + it.returnTime -> it.offset - (elapsed - it.outTime) / it.returnTime * it.offset
            else -> 0.0
        } } ?: 0.0
        val turret = Math.toRadians(unit.turretHeading)
        return Position(unit.position.x + sin(forward) * turretMount.localY + sin(side) * turretMount.localX + sin(turret) * distance,
            unit.position.y - cos(forward) * turretMount.localY - cos(side) * turretMount.localX - cos(turret) * distance)
    }
}

internal data class DefenseBeam(val start: Position, val end: Position)

class SandboxRenderResources private constructor(
    val widthTiles: Int, val heightTiles: Int, val tileWidth: Int, val tileHeight: Int,
    val tileSets: List<VisualTileAtlas>, val units: Map<String, UnitAppearance>,
    private val playerTeams: Map<String, String?>,
) {
    fun withVisuals(visuals: PreparedVisualAssets): SandboxRenderResources {
        visuals.verify()
        return bindVisuals(visuals)
    }

    /** Pure worker binding. The loader verifies the complete descriptor after binding, before publishing it. */
    internal fun bindVisuals(visuals: PreparedVisualAssets): SandboxRenderResources {
        val mappedUnits = units.mapValues { (id, before) ->
            val image = requireNotNull(visuals.units[id]) { "VISUAL_MAPPING_UNSUPPORTED: unit $id" }
            val replacements = image.bindings()
            before.withImages(before.imageBindings().map { part, _ ->
                requireNotNull(replacements.images[part]) { "VISUAL_MAPPING_UNSUPPORTED: unit $id ${part.suffix}" }
            })
        }
        val mappedTiles = tileSets.map { atlas ->
            val layout = atlas.supportedLayout()
            val resolved = visuals.resolvedImages?.images?.get(ResourceId.parse("reforge:terrain/$layout"))
            val rows = (atlas.tileCount + atlas.columns - 1) / atlas.columns
            if (resolved != null) require(resolved.pixelWidth % atlas.columns == 0 && resolved.pixelHeight % rows == 0 &&
                resolved.declaration.totalFrames == 1 && resolved.declaration.frameWidth == atlas.columns * atlas.tileWidth &&
                resolved.declaration.frameHeight == rows * atlas.tileHeight) { "RESOURCE_PACK_BINDING_INVALID: reforge:terrain/$layout: Invalid atlas sampling layout" }
            atlas.copy(imagePath = requireNotNull(visuals.terrain[layout]) { "VISUAL_MAPPING_UNSUPPORTED: $layout" }, transparentColor = null,
                pixelTileWidth = resolved?.let { it.pixelWidth / atlas.columns } ?: atlas.tileWidth,
                pixelTileHeight = resolved?.let { it.pixelHeight / rows } ?: atlas.tileHeight)
        }
        return SandboxRenderResources(widthTiles, heightTiles, tileWidth, tileHeight,
            Collections.unmodifiableList(mappedTiles), Collections.unmodifiableMap(mappedUnits), playerTeams)
    }
    /** Both endpoints come from the authorized player view and immutable appearance metadata. */
    internal fun defenseBeams(observation: PlayerObservation): List<DefenseBeam> = observation.projectileEvents.mapNotNull { event ->
        if (event.kind != "deflected") return@mapNotNull null
        val tower = observation.units.find { it.unitId == event.interceptorUnitId } ?: return@mapNotNull null
        val offset = units[tower.typeId]?.defenseMuzzleOffset ?: return@mapNotNull null
        DefenseBeam(Position(tower.position.x + offset.x, tower.position.y + offset.y), event.position)
    }

    /** Work state and both visible endpoints come from the authorized player observation. */
    internal fun nanoBeams(observation: PlayerObservation): List<DefenseBeam> = observation.units.mapNotNull { worker ->
        if (!worker.alive || worker.carriedBy != null || worker.constructionProgress < 1f) return@mapNotNull null
        val name = units[worker.typeId]?.nanoWeaponName ?: return@mapNotNull null
        val target = observation.units.singleOrNull { it.unitId == worker.constructionTargetId && it.alive && it.carriedBy == null }
            ?: return@mapNotNull null
        val weapon = observation.weapons.singleOrNull { it.unitId == worker.unitId && it.weaponName == name }
            ?: return@mapNotNull null
        DefenseBeam(Position(weapon.muzzle.x, weapon.muzzle.y - worker.height),
            Position(target.position.x, target.position.y - target.height))
    }

    fun unitCircleColor(viewerPlayerId: String, ownerPlayerId: String): Color {
        require(viewerPlayerId in playerTeams && ownerPlayerId in playerTeams)
        val viewerTeam = playerTeams[viewerPlayerId]
        return when {
            ownerPlayerId == viewerPlayerId -> Color(0.45f, 0.91f, 0.65f, 1f)
            viewerTeam != null && viewerTeam == playerTeams[ownerPlayerId] -> Color(1f, 0.72f, 0.3f, 1f)
            else -> Color(1f, 0f, 0f, 1f)
        }
    }
    companion object {
        fun from(scenario: Scenario): SandboxRenderResources = SandboxRenderResources(
            scenario.map.widthTiles, scenario.map.heightTiles, scenario.map.tileWidth, scenario.map.tileHeight,
            Collections.unmodifiableList(scenario.map.tileSets.map(VisualTileAtlas::from)),
            Collections.unmodifiableMap(scenario.unitDefinitions.mapValues { (_, unit) ->
                val images = unit.imageBindings().visualImages()
                UnitAppearance(unit.displayRadius, images.body, unit.imageScale, images.totalFrames,
                    images.turret, images.dead, unit.combat?.turretImageScale ?: 1.0, images.back,
                    unit.combat?.mount ?: TurretMount(),
                    unit.pointDefense?.let { Position(it.muzzleOffsetX, it.muzzleOffsetY) },
                    unit.combat?.recoil, unit.combat?.shootDelay ?: 0.0,
                    economicBuilding = unit.income != null && unit.construction != null,
                    weaponImages = Collections.unmodifiableMap(unit.combat?.namedWeapons?.ordered.orEmpty()
                        .associate { it.name to WeaponAppearance(it.imagePath, it.imageScale, it.invisible, it.imageOffsetX, it.imageOffsetY) }),
                    legImagePath = images.leg, footImagePath = images.foot,
                    rotorImagePath = images.rotor, mechKind = unit.mech?.kind, shadowImagePath = images.shadow,
                    shieldImagePath = images.shield, shieldRadius = unit.radius,
                    leftWingImagePath = images.leftWing, rightWingImagePath = images.rightWing,
                    beamImagePath = images.beam, beamStartImagePath = images.beamStart, beamEndImagePath = images.beamEnd,
                    afterburnTrailImagePath = images.afterburnTrail,
                    arm1ImagePath = images.arm1, arm2ImagePath = images.arm2, nuclearSiloKind = unit.nuclearSilo?.kind,
                    slotOverlayImagePath = images.slotOverlay,
                    repairRange = if (unit.maintenance != null) unit.builder?.range?.toDouble() ?: 230.0 else null,
                    iconWeaponName = when {
                        unit.maintenance == com.rustedwarfare.reforge.core.content.MaintenanceProfile.MECH_ENGINEER -> "body"
                        unit.attachmentModule?.empty == false -> unit.combat?.namedWeapons?.ordered?.firstOrNull()?.name
                        else -> null
                    },
                    nanoWeaponName = unit.builder?.mainNanoTurret,
                    shadowOffset = if (unit.id == "mechFlame") Position(2.0, 2.0) else null,
                    nativeLegSize = when {
                        unit.attachmentLayout != null -> ImageLogicalSize(10, 84)
                        unit.maintenance == com.rustedwarfare.reforge.core.content.MaintenanceProfile.SCOUT_BOT -> ImageLogicalSize(24, 102)
                        unit.maintenance == com.rustedwarfare.reforge.core.content.MaintenanceProfile.MECH_ENGINEER -> ImageLogicalSize(19, 82)
                        unit.id == "mechFlame" || unit.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED -> ImageLogicalSize(16, 68)
                        else -> null
                    })
            }), Collections.unmodifiableMap(scenario.players.associate { it.playerId to it.teamId }))
    }
}
