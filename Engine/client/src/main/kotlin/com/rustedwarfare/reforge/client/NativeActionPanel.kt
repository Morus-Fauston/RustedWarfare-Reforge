package com.rustedwarfare.reforge.client

internal data class NativeCardBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** Fixed native panel geometry shared by actors and ordinary press/release validation. */
internal object NativeActionPanel {
    private val spiderSlotActions = (1..6).associate { number ->
        val index = number - 1
        "selectSlot$number" to NativeCardBounds(752f + index % 3 * 60f, 278f - index / 3 * 44f, 56f, 40f)
    }
    private val spiderModuleAction = NativeCardBounds(812f, 190f, 116f, 40f)

    val customActions = listOf("afterBurn", "cancelBurn", "deploy", "undeploy").mapIndexed { index, name ->
        name to NativeCardBounds(752f + index % 2 * 92f, 188f, 84f, 40f)
    }.toMap() + mapOf("surface" to NativeCardBounds(844f, 188f, 84f, 40f),
        "dive" to NativeCardBounds(844f, 188f, 84f, 40f),
        "launchScoutBot" to NativeCardBounds(844f, 256f, 84f, 52f),
        "buildNuke" to NativeCardBounds(752f, 256f, 84f, 52f),
        "launchNuke" to NativeCardBounds(844f, 256f, 84f, 52f),
        "buildAntiNuke" to NativeCardBounds(752f, 256f, 176f, 52f)) + spiderSlotActions +
        ("selectSlotBuild" to NativeCardBounds(752f, 190f, 56f, 40f)) +
        listOf("buildGun", "cancel", "reclaim").associateWith { spiderModuleAction }
    val navalProduction = NativeCardBounds(752f, 270f, 84f, 50f)
    val navalUnload = NativeCardBounds(752f, 188f, 84f, 34f)
    private val navalActions = mapOf(
        "surface" to NativeCardBounds(844f, 188f, 84f, 34f),
        "dive" to NativeCardBounds(844f, 188f, 84f, 34f),
        "launchScoutBot" to NativeCardBounds(844f, 270f, 84f, 50f),
        "buildAntiNuke" to NativeCardBounds(752f, 224f, 176f, 44f))
    fun customBounds(name: String, naval: Boolean): NativeCardBounds =
        if (naval) navalActions[name] ?: customActions.getValue(name) else customActions.getValue(name)
    val transportWithCustomAction = NativeCardBounds(752f, 188f, 84f, 40f)
    fun supports(scenarioId: String?) = scenarioId in setOf("p009-nuclear-production", "p006-construction", "p008-mech-production", "p008-helicopter-production", "p008-heavy-mech-production", "p008-naval-production", "p009-hover-production", "p009-submarine-production", "p009-amphibious-production", "p009-transport-production", "p009-air-transport-production", "p009-aa-beam-production", "p009-bomber-production", "p009-bunker-production", "p009-repairbay-production", "p009-engineer-production", "p009-nautilus-production", "p009-modular-spider-start")
    val production = listOf(NativeCardBounds(752f, 256f, 84f, 64f), NativeCardBounds(752f, 188f, 84f, 64f), NativeCardBounds(844f, 256f, 84f, 64f))
    val upgrades = listOf("upgrade_gun" to 264f, "upgrade_artillery" to 216f).associate { (key, y) ->
        key to (NativeCardBounds(752f, y, 84f, 40f) to NativeCardBounds(844f, y, 84f, 40f))
    } + ("upgrade_t2" to (NativeCardBounds(844f, 264f, 84f, 40f) to NativeCardBounds(844f, 216f, 84f, 40f)))
}
