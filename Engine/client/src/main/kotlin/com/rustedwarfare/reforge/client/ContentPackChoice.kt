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

    init {
        require(unitId in content.units) { "Unknown package unit: $unitId" }
        require(content.units.getValue(unitId).fixedUnderwaterTarget == null) { "A fixed evidence target cannot be a player start choice" }
    }
    /** The selected unit's effective author; all validation uses the complete set. */
    val pack: LoadedContentPack get() = content.packages.single { it.id == content.provenance.getValue(unitId).packageId }

    /** Worker-only: recheck the final content before platform image preparation. */
    fun revalidate(): ContentPackChoice {
        when (val stable = content.verifyStable()) {
            is ContentResult.Failure -> error(stable.error.describe())
            is ContentResult.Success -> Unit
        }
        // The in-process content set owns immutable checked definitions and the complete source
        // closure. Recheck every ordinary path and byte identity, then retain those definitions.
        // A new process must still load/parse normally; final publication verifies the sources again.
        return this
    }
}

/** Worker-only final content baseline, also used by platforms before their first layer composition. */
fun contentPackVisuals(visuals: PreparedVisualAssets, choice: ContentPackChoice): PreparedVisualAssets {
    // A retained descriptor may already contain this exact checked content. Do not nest its
    // publication validators or recompose the same bindings on every reopen.
    if (visuals.isBoundTo(choice)) return visuals
    if (visuals.selection.source != VisualAssetSource.PLACEHOLDER && visuals.resolvedImages == null) return visuals
    val images = choice.content.units.mapValues { (_, unit) -> unit.imageBindings().visualImages() }
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
    val prior = visuals.resolvedImages ?: return rebound.withContentBinding(choice)
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
    return rebound.withResourceImages(ResourceImageComposer.compose(currentBase, prior.layers.dropLast(1))).withContentBinding(choice)
}

/** Reuses the existing controlled combat layout, not a new map or spawn-file interpreter. */
internal fun contentPackScenario(root: Path, choice: ContentPackChoice): Scenario {
    val bunker = choice.unitId in setOf("commandCenter", "builder", "mechFactory", "mechFactoryT2", "mechBunker", "mechBunkerDeployed") &&
        choice.content.units["mechBunker"]?.groundHeight != null &&
        choice.content.units["mechBunkerDeployed"]?.building != null &&
        choice.content.units["commandCenter"]?.production?.unitTypeIds == listOf("builder") &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("mechFactory") &&
        choice.content.units["mechFactory"]?.production?.unitTypeIds?.contains("mechBunker") == true &&
        choice.content.units["mechFactoryT2"]?.production?.unitTypeIds?.contains("mechBunker") == true
    val aaBeam = choice.unitId in setOf("commandCenter", "builder", "airFactory", "airFactoryT2", "helicopter", "amphibiousJet", "dropship", "aaBeamGunship") &&
        choice.content.units["aaBeamGunship"]?.energy != null &&
        choice.content.units["commandCenter"]?.production?.unitTypeIds == listOf("builder") &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("airFactory") &&
        choice.content.units["airFactory"]?.upgrades?.actions?.singleOrNull()?.targetTypeId == "airFactoryT2" &&
        choice.content.units["airFactoryT2"]?.production?.unitTypeIds?.contains("aaBeamGunship") == true
    val airTransport = choice.unitId in setOf("commandCenter", "builder", "airFactory", "airFactoryT2", "helicopter", "amphibiousJet", "dropship") &&
        choice.content.units["dropship"]?.transport == TransportProfile.NATIVE_DROPSHIP &&
        choice.content.units["commandCenter"]?.production?.unitTypeIds == listOf("builder") &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("airFactory") &&
        choice.content.units["airFactory"]?.upgrades?.actions?.singleOrNull()?.targetTypeId == "airFactoryT2" &&
        choice.content.units["airFactoryT2"]?.production?.unitTypeIds?.contains("dropship") == true &&
        choice.content.units["c_tank"]?.combat != null
    val transport = choice.unitId in setOf("commandCenter", "builder", "seaFactory", "seaFactoryT2", "hovercraft") &&
        choice.content.units["hovercraft"]?.transport == TransportProfile.NATIVE_HOVERCRAFT &&
        choice.content.units["commandCenter"]?.production?.unitTypeIds == listOf("builder") &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("seaFactory") &&
        choice.content.units["seaFactory"]?.production?.unitTypeIds?.contains("hovercraft") == true &&
        choice.content.units["seaFactoryT2"]?.production?.unitTypeIds?.contains("hovercraft") == true
    val amphibious = choice.unitId in setOf("commandCenter", "builder", "airFactory", "airFactoryT2", "amphibiousJet", "helicopter") &&
        choice.content.units["amphibiousJet"]?.amphibiousJet != null &&
        choice.content.units["commandCenter"]?.production?.unitTypeIds == listOf("builder") &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("airFactory") &&
        choice.content.units["airFactory"]?.upgrades?.actions?.singleOrNull()?.targetTypeId == "airFactoryT2" &&
        choice.content.units["airFactoryT2"]?.production?.unitTypeIds == listOf("helicopter", "amphibiousJet") &&
        choice.content.units["c_tank"]?.combat != null && choice.content.units["helicopter"]?.rotor != null &&
        choice.content.units["navalUnderwaterTarget"]?.fixedUnderwaterTarget == FixedUnderwaterTargetProfile(-8.0)
    val naval = choice.unitId in setOf("builder", "seaFactory", "seaFactoryT2", "heavyBattleship", "attackSubmarine", "c_tank", "helicopter") &&
        choice.content.units["heavyBattleship"]?.movement?.type == MovementType.WATER &&
        choice.content.units["seaFactory"]?.production?.exitProfile == FactoryExitProfile.NATIVE_SEA &&
        choice.content.units["seaFactoryT2"]?.production?.unitTypeIds == listOf("heavyBattleship", "attackSubmarine") &&
        choice.content.units["attackSubmarine"]?.submarine == SubmarineProfile.NATIVE &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("seaFactory") &&
        choice.content.units["c_tank"]?.movement?.type == MovementType.LAND &&
        choice.content.units["helicopter"]?.rotor != null &&
        choice.content.units["navalUnderwaterTarget"]?.fixedUnderwaterTarget == FixedUnderwaterTargetProfile(-8.0)
    val authorWater = choice.content.units.getValue(choice.unitId).authoredWaterCombat
    val map = when (val result = ContentLoader.loadMap(root.resolve(if (aaBeam || airTransport || transport || amphibious || naval || authorWater) "maps/naval-shore.tmx" else "maps/p001-controlled.tmx"))) {
        is ContentResult.Failure -> error(result.error.describe())
        is ContentResult.Success -> result.value
    }
    require(map.widthTiles * map.tileWidth >= 600 && map.heightTiles * map.tileHeight >= 400) {
        "The existing controlled combat layout requires a map at least 600 by 400 world units"
    }
    if (bunker) return Scenario("p009-bunker-production", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", "commandCenter", Position(130.0, 130.0), 0.0),
            UnitSpawn("b", "p2", "mechGun", Position(500.0, 310.0), 270.0),
            UnitSpawn("c", "p2", "mechGun", Position(330.0, 386.0), 0.0),
            UnitSpawn("d", "p2", "mechGun", Position(250.0, 386.0), 0.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L, "d" to 11L), 12L),
        economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
    if (aaBeam) return Scenario("p009-aa-beam-production", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", "commandCenter", Position(130.0, 130.0), 0.0),
            UnitSpawn("b", "p2", "aaBeamGunship", Position(580.0, 300.0), 270.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L),
        economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
    if (transport || airTransport) return Scenario(if (airTransport) "p009-air-transport-production" else "p009-transport-production", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", "commandCenter", Position(130.0, 130.0), 0.0),
            UnitSpawn("b", "p2", "c_tank", Position(330.0, 550.0), 270.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L),
        economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
    if (amphibious) return Scenario("p009-amphibious-production", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", "commandCenter", Position(130.0, 130.0), 0.0),
            UnitSpawn("b", "p2", "c_tank", Position(330.0, 550.0), 270.0),
            UnitSpawn("c", "p2", "helicopter", Position(650.0, 150.0), 270.0),
            UnitSpawn("d", "p2", "navalUnderwaterTarget", Position(600.0, 300.0), 0.0, -8.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L, "d" to 11L), 12L),
        economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
    if (choice.unitId == "commandCenter" &&
        choice.content.units["hoverTank"]?.movement?.type == MovementType.HOVER &&
        choice.content.units["landFactory"]?.production?.unitTypeIds?.contains("hoverTank") == true &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("landFactory") &&
        choice.content.units["commandCenter"]?.production?.unitTypeIds == listOf("builder") &&
        choice.content.units["c_tank"]?.combat != null) return Scenario("p009-hover-production", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", "commandCenter", Position(130.0, 130.0), 0.0),
            UnitSpawn("b", "p2", "c_tank", Position(650.0, 350.0), 270.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L),
        economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)))
    if (naval) return Scenario(if (choice.unitId == "attackSubmarine") "p009-submarine-production" else "p008-naval-production", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", "builder", Position(390.0, 150.0), 90.0),
            UnitSpawn("b", "p2", "c_tank", Position(330.0, 550.0), 270.0),
            UnitSpawn("c", "p2", "helicopter", Position(650.0, 150.0), 270.0),
            UnitSpawn("d", "p2", "navalUnderwaterTarget", Position(600.0, 300.0), 0.0, -8.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L, "d" to 11L), 12L),
        economyContract = EconomyContract(mapOf("p1" to 12000L, "p2" to 0L)))
    if (authorWater) return Scenario("p008-content-pack", map, choice.content.units,
        listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
        listOf(UnitSpawn("a", "p1", choice.unitId, Position(500.0, 300.0), 90.0),
            UnitSpawn("b", "p2", choice.unitId, Position(650.0, 300.0), 270.0)),
        CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L))
    if (choice.unitId in setOf("builder", "mechFactory", "mechFactoryT2", "mechHeavyMissile") &&
        choice.content.units["mechHeavyMissile"]?.mech?.kind == MechKind.FOUR_LEGGED &&
        choice.content.units["mechFactoryT2"]?.production?.exitProfile == FactoryExitProfile.CUSTOM_MECH &&
        choice.content.units["builder"]?.builder?.buildingTypeIds == listOf("mechFactory") &&
        choice.content.units["helicopter"]?.rotor != null) {
        return Scenario("p008-heavy-mech-production", map, choice.content.units,
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", "builder", Position(190.0, 150.0), 90.0),
                UnitSpawn("b", "p2", "mechGun", Position(500.0, 310.0), 270.0),
                UnitSpawn("c", "p2", "helicopter", Position(500.0, 110.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
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
