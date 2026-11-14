package com.rustedwarfare.reforge.core.content

/** A Blink destination resolves against HOVER terrain for either a unit or an attachment. */
data class BlinkProfile(val range: Double, val chargeTicks: Int, val cooldownTicks: Int,
    val unlockCredits: Long = 0L, val unlockTicks: Int = 0, val locked: Boolean = false, val queueSpeed: Float = 1f) {
    internal fun isValid(): Boolean = range.toFloat().let { it.isFinite() && it > 0f && (it * it).isFinite() } &&
        chargeTicks in 1..36000 && cooldownTicks in 1..36000 && queueSpeed in .01f..100f &&
        (unlockCredits == 0L && unlockTicks == 0 || unlockCredits > 0L && unlockTicks in 1..36000)

    internal fun actions(): CustomActionsProfile {
        val actions = buildList {
            if (unlockCredits > 0L) add(CustomActionDefinition("addBlink", "addBlink", null,
                1f / unlockTicks, null, canPlayerCancel = true, blinkWork = BlinkWork.Unlock(unlockTicks, unlockCredits)))
            add(CustomActionDefinition("blink", "blink", null, 1f / chargeTicks, null,
                canPlayerCancel = true, cannotMove = true,
                highPriority = true, blinkWork = BlinkWork.Teleport(chargeTicks)))
        }
        return CustomActionsProfile(actions)
    }
}

sealed interface BlinkWork {
    val ticks: Int

    data class Unlock(override val ticks: Int, val credits: Long) : BlinkWork
    data class Teleport(override val ticks: Int) : BlinkWork
}
