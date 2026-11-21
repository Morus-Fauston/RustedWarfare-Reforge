package com.rustedwarfare.reforge.client

enum class HudNoticeKind {
    INFO,
    WARNING,
}

data class HudNoticeItem(
    val id: Long,
    val text: String,
    val kind: HudNoticeKind,
    val durationSeconds: Float,
    val remainingSeconds: Float,
) {
    /** Older stacked items or fading items dim cleanly per HUD Spec §4. */
    val alpha: Float
        get() = if (remainingSeconds < 0.45f) (remainingSeconds / 0.45f).coerceIn(0f, 1f) else 1f
}

/**
 * Top-left HUD event notice stream (HUD Spec §4, §5.5, §9.1a).
 *
 * - Normal events: 4.0s TTL, rust square pip.
 * - Denial / unsupported / skipped-map warnings: 3.0s TTL, amber square pip + "▲ " prefix.
 * - Keeps at most 3 newest notices simultaneously; older entries render dimmed (opacity 0.55).
 * - Optional persistent camera-follow badge sits above timed notices and never fades out.
 */
class HudEventStream {
    private val items = ArrayDeque<HudNoticeItem>()
    private var nextId = 1L

    var cameraFollowing: Boolean = false
        private set

    fun clear() {
        items.clear()
        cameraFollowing = false
    }

    fun setCameraFollowing(following: Boolean) {
        cameraFollowing = following
    }

    fun pushInfo(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        push(clean, HudNoticeKind.INFO, INFO_DURATION_SECONDS)
    }

    /** Pushes an amber denial / unsupported warning. Avoids doubling a leading "▲". */
    fun pushWarning(text: String) {
        val clean = text.trim().removePrefix("▲").trim()
        if (clean.isEmpty()) return
        push(clean, HudNoticeKind.WARNING, WARNING_DURATION_SECONDS)
    }

    /** Convenience helper translating a core/UI reason code via [UiStrings.rejectionReason]. */
    fun pushRejectionCode(code: String, language: UiLanguage = UiStrings.currentLanguage) {
        pushWarning(UiStrings.rejectionReason(code, language))
    }

    private fun push(text: String, kind: HudNoticeKind, duration: Float) {
        // Deduplicate immediate identical consecutive notice by refreshing its timer
        val latest = items.firstOrNull()
        if (latest != null && latest.kind == kind && latest.text == text) {
            items.removeFirst()
            items.addFirst(latest.copy(remainingSeconds = duration, durationSeconds = duration))
            return
        }
        items.addFirst(HudNoticeItem(nextId++, text, kind, duration, duration))
        while (items.size > MAX_VISIBLE_NOTICES) {
            items.removeLast()
        }
    }

    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f || items.isEmpty()) return
        val iter = items.iterator()
        val updated = ArrayList<HudNoticeItem>(items.size)
        while (iter.hasNext()) {
            val item = iter.next()
            val rem = item.remainingSeconds - deltaSeconds
            if (rem > 0f) updated.add(item.copy(remainingSeconds = rem))
        }
        items.clear()
        updated.forEach(items::addLast)
    }

    fun visibleNotices(): List<HudNoticeItem> = items.toList()

    companion object {
        const val MAX_VISIBLE_NOTICES: Int = 3
        const val INFO_DURATION_SECONDS: Float = 4.0f
        const val WARNING_DURATION_SECONDS: Float = 3.0f
    }
}
