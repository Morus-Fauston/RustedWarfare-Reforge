package com.rustedwarfare.reforge.client

import java.util.Locale

enum class UiLanguage {
    ZH,
    EN,
    ;

    companion object {
        fun fromLocale(locale: Locale = Locale.getDefault()): UiLanguage =
            if (locale.language.equals("zh", ignoreCase = true)) ZH else EN
    }
}

/**
 * Bilingual (zh / en) UI string table for fixed HUD chrome (HUD Spec §11).
 *
 * Content-pack texts (unit display names, action names, descriptions) are NEVER translated here;
 * callers pass them verbatim as arguments or display them directly.
 */
object UiStrings {
    @Volatile
    var currentLanguage: UiLanguage = UiLanguage.fromLocale()

    private val tableZh: Map<String, String> = mapOf(
        "menu" to "≡ 菜单",
        "pause" to "暂停",
        "resume" to "继续",
        "paused" to "已暂停",
        "funds" to "资金",
        "queue" to "队列",
        "build" to "施工",
        "stop" to "停止",
        "patrol" to "巡逻",
        "guard" to "守卫",
        "unload" to "卸载",
        "unloadHere" to "就地卸载",
        "stock" to "储备",
        "stockFull" to "储备已满",
        "cancelBuild" to "取消施工",
        "upgradeQ" to "升级",
        "branchTaken" to "分支已定",
        "branchFull" to "已选择另一个升级分支，此分支不可用",
        "charging" to "充能中",
        "surfaced" to "水面",
        "submerged" to "水下",
        "flying" to "飞行中",
        "landed" to "已降落",
        "deployed" to "已展开",
        "undeployed" to "已收起",
        "enabled" to "已启用",
        "disabled" to "已停用",
        "dive" to "下潜",
        "surface" to "上浮",
        "takeoff" to "起飞",
        "land" to "降落",
        "buildNuke" to "制造核弹",
        "launchNuke" to "发射核弹",
        "nukeLeft" to "剩余 {N} 发",
        "avail" to "可用 {N}",
        "pickTarget" to "左键选择目标",
        "pickTargetTouch" to "点选地图目标",
        "cancelHint" to "右键或 Esc 取消",
        "cancelBtn" to "取消",
        "following" to "跟随中 · 拖动地图退出",
        "blocked" to "目标不可通行",
        "menu.resume" to "继续",
        "menu.restart" to "重开本局",
        "menu.backToStart" to "返回启动页",
        "menu.settings" to "设置",
        "debugBtn" to "调试",
        "rally" to "集结点",
        "reclaim" to "回收",
        "repair" to "修理",
        "owner" to "{NAME} 的动作",
        "more" to "还有 {N} 项 ▼",
        "short" to "差 \${N}",
        "cost" to "费用",
        "queued" to "排队",
        "hp" to "血量",
        "shield" to "护盾",
        "energy" to "能量",
        "cargo" to "货舱",
        "leftQueue" to "左键 加入队列 · 右键 取消一个",
        "leftTarget" to "左键 选择目标 · 右键或 Esc 取消",
        "leftUse" to "左键 使用",
        "touchPreviewHint" to "再次点击执行",
        "longHint" to "长按查看说明，松手不执行",
        "placeOk" to "可以放置",
        "placeBlocked" to "此位置不可放置",
        "placeKey" to "左键放置 · 右键或 Esc 取消",
        "foe" to "敌方",
        "neutral" to "中立",
        "playerSlot" to "玩家 {N}",
        "done" to "{NAME} 生产完成",
        "damage" to "单位受到攻击",
        "holdGroup" to "按住保存编组",
        "groupHintHold" to "松手调用/存入",
        "groupHintAppend" to "松手追加编组",
        "groupHintReplace" to "松手替换整组",
        "cool" to "冷却 {N} 秒",
        "empty" to "空",
        "readout" to "实际 {ACTUAL}× · 积压 {BACKLOG}",
        "pick.spawn" to "生成",
        "pick.destroy" to "摧毁",
        "pick.health" to "调整生命",
        "escClose" to "Esc 关闭 · 回车提交",
        "selected" to "已选 {N}",
        "noFunds" to "资金不足",
        "slotEmpty" to "空槽位",
        "slotPrefix" to "槽 {N}",
        "unsupported.guard" to "暂未支持：守卫",
        "unsupported.settings" to "暂未支持：设置",
        "unsupported.generic" to "该功能暂未支持",
        "arrivalDeviated" to "目标受阻，已停靠最近可达位置",
        "mapSkipped" to "已跳过 {DETAIL}",
    )

    private val tableEn: Map<String, String> = mapOf(
        "menu" to "≡ Menu",
        "pause" to "Pause",
        "resume" to "Resume",
        "paused" to "Paused",
        "funds" to "Credits",
        "queue" to "Queue",
        "build" to "Build",
        "stop" to "Stop",
        "patrol" to "Patrol",
        "guard" to "Guard",
        "unload" to "Unload",
        "unloadHere" to "Unload here",
        "stock" to "Stock",
        "stockFull" to "Stock full",
        "cancelBuild" to "Cancel build",
        "upgradeQ" to "Upgrade",
        "branchTaken" to "Branch set",
        "branchFull" to "The other upgrade branch was chosen",
        "charging" to "Charging",
        "surfaced" to "Surfaced",
        "submerged" to "Submerged",
        "flying" to "Flying",
        "landed" to "Landed",
        "deployed" to "Deployed",
        "undeployed" to "Packed",
        "enabled" to "Enabled",
        "disabled" to "Disabled",
        "dive" to "Dive",
        "surface" to "Surface",
        "takeoff" to "Take off",
        "land" to "Land",
        "buildNuke" to "Build nuke",
        "launchNuke" to "Launch nuke",
        "nukeLeft" to "{N} left",
        "avail" to "{N} ready",
        "pickTarget" to "Left: pick target",
        "pickTargetTouch" to "Tap map target",
        "cancelHint" to "Right/Esc: cancel",
        "cancelBtn" to "Cancel",
        "following" to "Following · drag map to stop",
        "blocked" to "Target unreachable",
        "menu.resume" to "Resume",
        "menu.restart" to "Restart match",
        "menu.backToStart" to "Back to start",
        "menu.settings" to "Settings",
        "debugBtn" to "Debug",
        "rally" to "Rally",
        "reclaim" to "Reclaim",
        "repair" to "Repair",
        "owner" to "Actions of {NAME}",
        "more" to "{N} more ▼",
        "short" to "need \${N}",
        "cost" to "Cost",
        "queued" to "Queued",
        "hp" to "HP",
        "shield" to "Shield",
        "energy" to "Energy",
        "cargo" to "Cargo",
        "leftQueue" to "Left: queue · Right: cancel one",
        "leftTarget" to "Left: pick target · Right/Esc: cancel",
        "leftUse" to "Left: use",
        "touchPreviewHint" to "Tap again to execute",
        "longHint" to "Hold to read, release does nothing",
        "placeOk" to "Can place here",
        "placeBlocked" to "Cannot place here",
        "placeKey" to "Left: place · Right/Esc: cancel",
        "foe" to "Enemy",
        "neutral" to "Neutral",
        "playerSlot" to "Player {N}",
        "done" to "{NAME} complete",
        "damage" to "Unit under attack",
        "holdGroup" to "Hold to save group",
        "groupHintHold" to "Release: recall / save",
        "groupHintAppend" to "Release: append group",
        "groupHintReplace" to "Release: replace group",
        "cool" to "Cooldown {N}s",
        "empty" to "Empty",
        "readout" to "actual {ACTUAL}× · backlog {BACKLOG}",
        "pick.spawn" to "Spawn",
        "pick.destroy" to "Destroy",
        "pick.health" to "Health",
        "escClose" to "Esc closes · Enter submits",
        "selected" to "{N} selected",
        "noFunds" to "Not enough credits",
        "slotEmpty" to "Empty slot",
        "slotPrefix" to "Slot {N}",
        "unsupported.guard" to "Not supported yet: Guard",
        "unsupported.settings" to "Not supported yet: Settings",
        "unsupported.generic" to "Feature not yet supported",
        "arrivalDeviated" to "Target obstructed, stopped at nearest reachable point",
        "mapSkipped" to "Skipped {DETAIL}",
    )

    /** Translates a fixed UI key in [language]. Returns [key] verbatim if unmapped. */
    fun get(key: String, language: UiLanguage = currentLanguage): String {
        val table = if (language == UiLanguage.ZH) tableZh else tableEn
        return table[key] ?: key
    }

    /** Formats a fixed UI template with placeholder replacements (e.g. `"N" to 3`, `"NAME" to contentName`). */
    fun format(key: String, vararg args: Pair<String, Any?>, language: UiLanguage = currentLanguage): String {
        var result = get(key, language)
        for ((name, value) in args) {
            result = result.replace("{$name}", value?.toString() ?: "")
        }
        return result
    }

    /**
     * Maps core command rejection / denial reason codes to localized user-facing copy (HUD Spec §9.1a).
     * Unknown codes are returned verbatim so unmapped gaps remain visible during development.
     */
    fun rejectionReason(code: String, language: UiLanguage = currentLanguage): String {
        val norm = code.trim().replace('-', '_').uppercase(Locale.ROOT)
        val key = when (norm) {
            "INSUFFICIENT_FUNDS", "NOT_ENOUGH_CREDITS", "NO_FUNDS" -> "noFunds"
            "BLOCKED", "TARGET_BLOCKED", "UNREACHABLE", "PATH_BLOCKED",
            "ACTION_TARGET_IMPASSABLE", "TARGET_IMPASSABLE", "CANNOT_PLACE" -> "blocked"
            "STOCK_FULL", "AMMO_FULL", "QUEUE_FULL", "AMMO_QUEUE_LIMIT" -> "stockFull"
            "BRANCH_TAKEN", "UPGRADE_BRANCH_LOCKED" -> "branchFull"
            "ENERGY_LOCKED", "RECHARGING", "UNIT_ACTION_LOCKED" -> "charging"
            "GUARD_UNSUPPORTED" -> "unsupported.guard"
            "SETTINGS_UNSUPPORTED" -> "unsupported.settings"
            else -> null
        }
        return if (key != null) get(key, language) else code
    }

    /**
     * Returns all fixed characters present in both translation tables plus digits & HUD symbols
     * for pre-warming FreeType glyph atlases during loading (HUD Spec §2.6).
     */
    fun preloadCharacters(extraContentStrings: Iterable<String> = emptyList()): String {
        val chars = LinkedHashSet<Char>(512)
        // Basic printable ASCII + common HUD symbols
        for (c in 32..126) chars.add(c.toChar())
        for (c in "≡×·▲▼◆●○—…“”‘’：，。！？（）【】＋－＄％°") chars.add(c)
        for (v in tableZh.values) for (c in v) chars.add(c)
        for (v in tableEn.values) for (c in v) chars.add(c)
        for (extra in extraContentStrings) for (c in extra) chars.add(c)
        return String(chars.toCharArray())
    }

    /** Exposes all registered keys for parity verification in tests. */
    internal fun keysZh(): Set<String> = tableZh.keys
    internal fun keysEn(): Set<String> = tableEn.keys
}
