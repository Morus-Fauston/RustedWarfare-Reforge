package com.rustedwarfare.reforge.client

enum class UnitImageSlot { BODY, BACK, TURRET, DEAD, LEG, FOOT, ROTOR, SHADOW, SHIELD }

data class ImageLogicalSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) { "Expected positive logical image dimensions" } }
}

data class ImageDrawSize(val width: Double, val height: Double)

data class UnitImageLogicalSizes(val body: ImageLogicalSize, val back: ImageLogicalSize? = null,
    val turret: ImageLogicalSize? = null, val dead: ImageLogicalSize? = null,
    val leg: ImageLogicalSize? = null, val foot: ImageLogicalSize? = null, val rotor: ImageLogicalSize? = null,
    val weaponTurrets: Map<String, ImageLogicalSize> = emptyMap(), val shadow: ImageLogicalSize? = null,
    val shield: ImageLogicalSize? = null) {
    fun forSlot(slot: UnitImageSlot): ImageLogicalSize? = when (slot) {
        UnitImageSlot.BODY -> body
        UnitImageSlot.BACK -> back
        UnitImageSlot.TURRET -> turret
        UnitImageSlot.DEAD -> dead
        UnitImageSlot.LEG -> leg
        UnitImageSlot.FOOT -> foot
        UnitImageSlot.ROTOR -> rotor
        UnitImageSlot.SHADOW -> shadow
        UnitImageSlot.SHIELD -> shield
    }
}
