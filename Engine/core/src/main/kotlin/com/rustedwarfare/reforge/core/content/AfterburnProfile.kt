package com.rustedwarfare.reforge.core.content

/** A qualified accelerated form and the normal form it returns to. */
data class AfterburnProfile(val normalForm: String, val boostedForm: String) {
    companion object {
        private val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        internal val ORIGINAL = AfterburnProfile("aaBeamGunship", "aaBeamGunship_afterburn")
    }

    internal fun validIds(): Boolean = normalForm != boostedForm &&
        ID.matches(normalForm) && ID.matches(boostedForm)
}
