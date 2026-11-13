package com.rustedwarfare.reforge.client

enum class UnitImageSlot { BODY, BACK, TURRET, DEAD, LEG, FOOT, ROTOR, SHADOW, SHIELD, LEFT_WING, RIGHT_WING, BEAM, BEAM_START, BEAM_END, AFTERBURN_TRAIL, ARM1, ARM2, SLOT_OVERLAY }

data class ImageLogicalSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) { "Expected positive logical image dimensions" } }
}

data class ImageDrawSize(val width: Double, val height: Double)

data class UnitImageLogicalSizes(val body: ImageLogicalSize, val back: ImageLogicalSize? = null,
    val turret: ImageLogicalSize? = null, val dead: ImageLogicalSize? = null,
    val leg: ImageLogicalSize? = null, val foot: ImageLogicalSize? = null, val rotor: ImageLogicalSize? = null,
    val weaponTurrets: Map<String, ImageLogicalSize> = emptyMap(), val shadow: ImageLogicalSize? = null,
    val shield: ImageLogicalSize? = null, val leftWing: ImageLogicalSize? = null, val rightWing: ImageLogicalSize? = null,
    val beam: ImageLogicalSize? = null, val beamStart: ImageLogicalSize? = null, val beamEnd: ImageLogicalSize? = null,
    val afterburnTrail: ImageLogicalSize? = null, val arm1: ImageLogicalSize? = null, val arm2: ImageLogicalSize? = null,
    val slotOverlay: ImageLogicalSize? = null) {
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
        UnitImageSlot.LEFT_WING -> leftWing
        UnitImageSlot.RIGHT_WING -> rightWing
        UnitImageSlot.BEAM -> beam
        UnitImageSlot.BEAM_START -> beamStart
        UnitImageSlot.BEAM_END -> beamEnd
        UnitImageSlot.AFTERBURN_TRAIL -> afterburnTrail
        UnitImageSlot.ARM1 -> arm1
        UnitImageSlot.ARM2 -> arm2
        UnitImageSlot.SLOT_OVERLAY -> slotOverlay
    }
}
