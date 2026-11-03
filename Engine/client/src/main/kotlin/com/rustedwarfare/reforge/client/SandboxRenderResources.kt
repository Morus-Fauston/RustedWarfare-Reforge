package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Scenario
import com.badlogic.gdx.graphics.Color
import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.content.WeaponRecoil
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitObservation
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.VisibleUnitWeapon
import java.nio.file.Path
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

data class WeaponAppearance(val imagePath: Path?, val imageScale: Double)
internal data class TurretDrawing(val weaponName: String?, val imagePath: Path, val imageScale: Double,
    val pivot: Position, val heading: Double)

data class UnitAppearance(val displayRadius: Double, val imagePath: Path, val imageScale: Double, val totalFrames: Int,
    val turretImagePath: Path? = null, val deadImagePath: Path? = null, val turretImageScale: Double = 1.0,
    val backImagePath: Path? = null, val turretMount: TurretMount = TurretMount(),
    val defenseMuzzleOffset: Position? = null, val recoil: WeaponRecoil? = null, val shootDelay: Double = 0.0,
    val economicBuilding: Boolean = false, val logicalImages: UnitImageLogicalSizes? = null,
    val weaponImages: Map<String, WeaponAppearance> = emptyMap(),
    val legImagePath: Path? = null, val footImagePath: Path? = null, val rotorImagePath: Path? = null) {
    internal fun turretDrawings(unit: UnitObservation, weapons: List<VisibleUnitWeapon>): List<TurretDrawing> {
        if (!unit.alive) return emptyList()
        if (weapons.isNotEmpty()) {
            val byName = weapons.associateBy { it.weaponName }
            check(weapons.all { it.unitId == unit.unitId } && byName.size == weapons.size && byName.keys == weaponImages.keys) {
                "Visible weapon poses must match the prepared unit catalog"
            }
            return weaponImages.mapNotNull { (name, image) -> image.imagePath?.let { path ->
                val pose = byName.getValue(name)
                TurretDrawing(name, path, image.imageScale, pose.pivot, pose.turretHeading)
            } }
        }
        check(weaponImages.size <= 1) { "A multi-weapon unit requires authorized weapon poses" }
        return turretImagePath?.let { listOf(TurretDrawing(null, it, turretImageScale,
            turretPosition(unit), unit.turretHeading)) } ?: emptyList()
    }
    fun drawSize(slot: UnitImageSlot, pixelWidth: Int, pixelHeight: Int,
                 turretScale: Double = turretImageScale): ImageDrawSize {
        val logical = logicalImages?.forSlot(slot)
        val scale = if (slot == UnitImageSlot.TURRET) turretScale else imageScale
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
        val mappedUnits = units.mapValues { (id, before) ->
            val image = requireNotNull(visuals.units[id]) { "VISUAL_MAPPING_UNSUPPORTED: unit $id" }
            fun slot(beforePath: Path?, replacement: Path?, name: String): Path? = if (beforePath == null) null
                else requireNotNull(replacement) { "VISUAL_MAPPING_UNSUPPORTED: unit $id $name" }
            val turret = slot(before.turretImagePath, image.turret, "turret")
            before.copy(imagePath = image.body, totalFrames = image.totalFrames,
                backImagePath = slot(before.backImagePath, image.back, "back"),
                turretImagePath = turret,
                legImagePath = slot(before.legImagePath, image.leg, "leg"),
                footImagePath = slot(before.footImagePath, image.foot, "foot"),
                rotorImagePath = slot(before.rotorImagePath, image.rotor, "rotor"),
                deadImagePath = slot(before.deadImagePath, image.dead, "dead"), logicalImages = image.logicalImages,
                weaponImages = Collections.unmodifiableMap(before.weaponImages.mapValues { (_, weapon) ->
                    if (weapon.imagePath != null && weapon.imagePath == before.turretImagePath) weapon.copy(imagePath = turret)
                    else weapon
                }))
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
                UnitAppearance(unit.displayRadius, unit.imagePath, unit.imageScale, unit.totalFrames,
                    unit.combat?.turretImagePath, unit.durability?.deadImagePath ?: unit.combat?.deadImagePath,
                    unit.combat?.turretImageScale ?: 1.0, unit.building?.backImagePath,
                    unit.combat?.mount ?: TurretMount(),
                    unit.pointDefense?.let { Position(it.muzzleOffsetX, it.muzzleOffsetY) },
                    unit.combat?.recoil, unit.combat?.shootDelay ?: 0.0,
                    economicBuilding = unit.income != null && unit.construction != null,
                    weaponImages = Collections.unmodifiableMap(unit.combat?.namedWeapons?.ordered.orEmpty()
                        .associate { it.name to WeaponAppearance(it.imagePath, it.imageScale) }),
                    legImagePath = unit.mech?.legs?.first()?.legImage,
                    footImagePath = unit.mech?.legs?.first()?.footImage,
                    rotorImagePath = unit.rotor?.imagePath)
            }), Collections.unmodifiableMap(scenario.players.associate { it.playerId to it.teamId }))
    }
}
