package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.security.MessageDigest

/** Immutable pre-match choice. A later file edit requires another explicit check. */
data class ContentPackChoice(val content: LoadedContentSet, val unitId: String) {
    constructor(pack: LoadedContentPack, unitId: String) : this(when (val result = ContentLoader.composeContentPacks(listOf(pack))) {
        is ContentResult.Failure -> error(result.error.describe())
        is ContentResult.Success -> result.value
    }, unitId)

    init { require(unitId in content.units) { "Unknown package unit: $unitId" } }
    /** The selected unit's effective author; all validation uses the complete set. */
    val pack: LoadedContentPack get() = content.packages.single { it.id == content.provenance.getValue(unitId).packageId }

    /** Worker-only: recheck the final content before platform image preparation. */
    fun revalidate(): ContentPackChoice {
        when (val stable = content.verifyStable()) {
            is ContentResult.Failure -> error(stable.error.describe())
            is ContentResult.Success -> Unit
        }
        val current = when (val result = ContentLoader.loadContentPacks(content.packages.map { it.manifest })) {
            is ContentResult.Failure -> error(result.error.describe())
            is ContentResult.Success -> result.value
        }
        check(current.sources == content.sources) { "Package sources changed after checking; check the packages again" }
        return ContentPackChoice(current, unitId)
    }
}

/** Worker-only final content baseline, also used by platforms before their first layer composition. */
fun contentPackVisuals(visuals: PreparedVisualAssets, choice: ContentPackChoice): PreparedVisualAssets {
    if (visuals.selection.source != VisualAssetSource.PLACEHOLDER && visuals.resolvedImages == null) return visuals
    val images = choice.content.units.mapValues { (_, unit) ->
        VisualUnitImages(unit.imagePath, unit.building?.backImagePath, unit.combat?.turretImagePath,
            unit.durability?.deadImagePath ?: unit.combat?.deadImagePath, unit.totalFrames,
            leg = unit.mech?.legs?.first()?.legImage, foot = unit.mech?.legs?.first()?.footImage,
            rotor = unit.rotor?.imagePath)
    }
    val sourceIdentity = MessageDigest.getInstance("SHA-256")
        .digest(choice.content.sources.joinToString("\n") { "${it.path}:${it.sha256}" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun verifyContent() {
        when (val stable = choice.content.verifyStable()) {
            is ContentResult.Failure -> error(stable.error.describe())
            is ContentResult.Success -> Unit
        }
    }
    val identity = "${visuals.identity}/content-pack:${choice.pack.id}:$sourceIdentity"
    val reboundImages = PreparedVisualAssets(visuals.selection, visuals.profileId, identity, visuals.units + images, visuals.terrain) {
        visuals.verify(); verifyContent()
    }
    val rebound = visuals.resolvedSounds?.let(reboundImages::withResourceSounds) ?: reboundImages
    val prior = visuals.resolvedImages ?: return rebound
    val base = prior.layers.last()
    val contentOnly = PreparedVisualAssets(VisualAssetSelection(), "content-image-base", sourceIdentity, images, emptyMap(), ::verifyContent)
    val additions = ResourceImagePreparation.baseLayer(contentOnly, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
    val sources = linkedMapOf<Path, ContentSourceIdentity>()
    for (source in base.sources + choice.content.sources) {
        val previous = sources.putIfAbsent(source.path, source)
        require(previous == null || previous == source) { "RESOURCE_PACK_SOURCE_CHANGED: ${source.path}: Content changed after resource preparation" }
    }
    val currentBase = ResourceImageLayer(base.metadata, base.images + additions.images, identity, sources.values.toList(), ::verifyContent)
        .withSounds(base.sounds, base.soundEvents)
    return rebound.withResourceImages(ResourceImageComposer.compose(currentBase, prior.layers.dropLast(1)))
}

/** Reuses the existing controlled combat layout, not a new map or spawn-file interpreter. */
internal fun contentPackScenario(root: Path, choice: ContentPackChoice): Scenario {
    val map = when (val result = ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))) {
        is ContentResult.Failure -> error(result.error.describe())
        is ContentResult.Success -> result.value
    }
    require(map.widthTiles * map.tileWidth >= 600 && map.heightTiles * map.tileHeight >= 400) {
        "The existing controlled combat layout requires a map at least 600 by 400 world units"
    }
    val productionScene = when {
        choice.unitId in setOf("builder", "airFactory") &&
            choice.content.units["airFactory"]?.production?.exitProfile == FactoryExitProfile.NATIVE_AIR ->
            "p008-helicopter-production" to "helicopter"
        choice.unitId in setOf("builder", "mechFactory") &&
            choice.content.units["mechFactory"]?.production?.exitProfile == FactoryExitProfile.CUSTOM_MECH ->
            "p008-mech-production" to "mechGun"
        else -> null
    }
    if (productionScene != null) {
        return Scenario(productionScene.first, map, choice.content.units,
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", "builder", Position(190.0, 150.0), 90.0),
                UnitSpawn("b", "p2", productionScene.second, Position(500.0, 310.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
    }
    return Scenario("p008-content-pack", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", choice.unitId, Position(110.0, 210.0), 90.0),
            UnitSpawn("b", "p2", choice.unitId, Position(350.0, 210.0), 270.0),
            UnitSpawn("c", "p2", choice.unitId, Position(510.0, 330.0), 270.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L),
        economyContract = if (choice.content.units.values.any { it.production != null || it.builder != null || it.construction != null })
            EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)) else null)
}
