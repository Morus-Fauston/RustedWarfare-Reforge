package com.rustedwarfare.reforge.client

import java.util.Locale

enum class UiLanguage(val code: String) {
    ZH("zh"),
    EN("en"),
    ;

    companion object {
        /** First-launch default is Simplified Chinese regardless of OS locale (UI Supplement §8.1). */
        val DEFAULT: UiLanguage = ZH

        fun fromCodeOrNull(raw: String?): UiLanguage? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "zh", "zh-cn", "zh_cn", "zh-hans" -> ZH
            "en", "en-us", "en_us" -> EN
            else -> null
        }

        fun fromLocale(locale: Locale = Locale.getDefault()): UiLanguage =
            if (locale.language.equals("zh", ignoreCase = true)) ZH else EN
    }
}

/**
 * Unified bilingual (zh / en) UI string table for fixed out-game screens and in-game HUD chrome
 * (HUD Spec §11 & Phase VIII UI Supplement §8).
 *
 * Rules:
 * - Default language is Simplified Chinese (`UiLanguage.ZH`), never OS locale.
 * - `tableZh` and `tableEn` maintain 100% key parity (verified by headless unit test).
 * - Missing keys in production return generic localized fallback copy instead of raw key names.
 * - Raw rejection / unsupported codes are exposed ONLY in development mode or when F3 debug overlay is active.
 */
object UiStrings {
    @Volatile
    var currentLanguage: UiLanguage = UiLanguage.DEFAULT

    private val tableZh: Map<String, String> = mapOf(
        // Generic fallbacks (UI Supplement §8.3)
        "fallback.unknown" to "未知内容",
        "fallback.unknownReason" to "未知原因",

        // Common controls & secondary top bar (UI Supplement §2)
        "nav.back" to "← 返回",
        "common.confirm" to "确定",
        "common.cancel" to "取消",
        "common.close" to "×",
        "common.load" to "载入",
        "common.view" to "查看",
        "common.on" to "开",
        "common.off" to "关",
        "common.none" to "—",
        "common.restoreDefault" to "恢复默认",

        // Main menu (UI Supplement §3)
        "mainMenu.skirmish" to "遭遇战",
        "mainMenu.sandbox" to "测试与沙盒",
        "mainMenu.settings" to "设置",
        "mainMenu.quit" to "退出",
        "mainMenu.quitConfirmTitle" to "确认退出游戏？",
        "mainMenu.quitConfirmOk" to "退出",
        "mainMenu.quitConfirmCancel" to "取消",

        // Skirmish lobby & More Settings (UI Supplement §4)
        "lobby.title" to "遭遇战",
        "lobby.winMode" to "胜负模式",
        "lobby.win.standard" to "标准",
        "lobby.win.elimination" to "全歼",
        "lobby.win.sandbox" to "无胜负",
        "lobby.win.objectivesFallbackNotice" to "当前地图的目标类胜负条件暂未支持，已映射为标准模式",
        "lobby.fog" to "战争迷雾",
        "lobby.fog.none" to "无迷雾",
        "lobby.fog.basic" to "地形全明",
        "lobby.fog.memory" to "记忆",
        "lobby.fog.hardcore" to "硬核",
        "lobby.fog.memoryFull" to "记忆迷雾",
        "lobby.startingFunds" to "初始资金",
        "lobby.funds.infinite" to "无限",
        "lobby.moreSettings" to "更多设置",
        "lobby.incomeMultiplier" to "经济倍率",
        "lobby.sharedVision" to "共享视野",
        "lobby.spawnAssignment" to "出生点分配",
        "lobby.spawn.fixed" to "固定",
        "lobby.spawn.random" to "随机",
        "lobby.startingUnits" to "开局单位",
        "lobby.units.mapDefault" to "地图默认",
        "lobby.units.standardBase" to "标准基地",
        "lobby.noNukes" to "禁止核武",
        "lobby.seats" to "参战席位",
        "lobby.ffa" to "混战",
        "lobby.twoTeams" to "双队平分",
        "lobby.col.color" to "颜色",
        "lobby.col.name" to "名称",
        "lobby.col.ai" to "AI 强度",
        "lobby.col.team" to "队伍",
        "lobby.col.spawn" to "出生点",
        "lobby.col.action" to "操作",
        "lobby.seat.you" to "你",
        "lobby.seat.playerN" to "玩家 {N}",
        "lobby.seat.aiN" to "AI {N}",
        "lobby.seat.dummy" to "沙盒靶机",
        "lobby.seat.dummyN" to "沙盒靶机 {N}",
        "lobby.ai.idle" to "待机",
        "lobby.ai.easy" to "简单",
        "lobby.ai.normal" to "普通",
        "lobby.ai.hard" to "困难",
        "lobby.ai.pendingPhase9" to "九期实装 AI 逻辑前，AI 席位进入对局按待机占位处理",
        "lobby.action.kick" to "踢出",
        "lobby.action.remove" to "移除",
        "lobby.addAi" to "＋ 添加 AI",
        "lobby.addSandboxSeat" to "＋ 添加沙盒席位",
        "lobby.battlefieldMap" to "战场地图",
        "lobby.mapPlayersFormat" to "{NAME} · {N}P",
        "lobby.selectMap" to "选择地图",
        "lobby.tmxPathPlaceholder" to "输入本地 .tmx 地图路径…",
        "lobby.startMatch" to "开始对局",

        // Shared fullscreen MapPicker (UI Supplement §4.3)
        "mapPicker.title" to "选择地图",
        "mapPicker.empty" to "未找到可用地图",
        "map.river_crossing" to "河流渡口",
        "map.volcanic_archipelago" to "火山群岛",
        "map.ring_atoll" to "十人环礁",
        "map.twin_bridge_canyon" to "双桥峡谷",
        "map.desert_oasis" to "荒漠绿洲",
        "map.polar_outpost" to "极地前哨",
        "map.shattered_highland" to "破碎高地",
        "map.rusted_bay" to "铁锈海湾",

        // Test & Sandbox (UI Supplement §5)
        "sandbox.title" to "测试与沙盒",
        "sandbox.mode" to "模式",
        "sandbox.mode.free" to "自由沙盒",
        "sandbox.mode.scenes" to "测试场景",
        "sandbox.fastBuild" to "快速建造",
        "sandbox.cameraControl" to "镜头控制",
        "sandbox.camera.scripted" to "跟随种子脚本",
        "sandbox.camera.free" to "自由镜头",
        "sandbox.lockedScenes" to "固定种子场景",
        "sandbox.sha256Badge" to "SHA-256 锁定输入",
        "sandbox.scenePreview" to "场景预览",
        "sandbox.mapNoWinFormat" to "{NAME} · 无胜负",
        "sandbox.jsonPathPlaceholder" to "输入本地场景种子 .json 路径…",
        "sandbox.enterFree" to "进入自由沙盒",
        "sandbox.loadScene" to "载入测试场景",

        // Loading screen (UI Supplement §6)
        "loading.rule.victory" to "胜负",
        "loading.rule.fog" to "迷雾",
        "loading.rule.funds" to "资金",
        "loading.rule.economy" to "经济",
        "loading.rule.noNukes" to "禁核",
        "loading.cancel" to "取消",
        "loading.tip.1" to "按住 Shift 下达移动、巡逻或建造指令，可将多个动作依次排入单位队列；任务提前完成时会自动衔接下一项。",
        "loading.tip.2" to "抢占地图关键矿池并及时升级资源抽取器，可在中后期稳定支撑二级工厂与实验级单位产线。",
        "loading.tip.3" to "合理利用陆海空混编与地形视野差，配合防空炮塔与激光防御网拦截敌方远程火力。",
        "loading.tip.4" to "使用 Ctrl 加数字键编组主力部队，双击同屏同类己方单位可快速集结机动分队。",
        "loading.tip.5" to "运输艇与运输机可跨越水域与悬崖投送建造者，在侧翼开辟第二基地与前线维修支点。",

        // Settings, Dual-Column Pack Manager, UI Scale Modal & About (UI Supplement §7)
        "settings.title" to "设置",
        "settings.nav.general" to "常规",
        "settings.nav.audio" to "音频",
        "settings.nav.video" to "画面",
        "settings.nav.controls" to "操作",
        "settings.nav.resourcePacks" to "资源包",
        "settings.nav.contentPacks" to "内容包",
        "settings.nav.about" to "关于",
        "settings.language" to "语言",
        "settings.lang.zh" to "简体中文",
        "settings.lang.en" to "English",
        "settings.masterVolume" to "主音量",
        "settings.sfxVolume" to "音效音量",
        "settings.mute" to "静音",
        "settings.uiScale" to "UI 缩放",
        "settings.theme" to "界面主题",
        "settings.theme.dark" to "暗黑",
        "settings.theme.white" to "白色",
        "settings.theme.bone" to "骨白",
        "settings.touchJoystick" to "触控镜头摇杆",
        "settings.edgeScroll" to "窗口边缘滚屏",
        "settings.panSensitivity" to "镜头平移灵敏度",
        "settings.zoomSensitivity" to "镜头缩放灵敏度",
        "settings.packs.openResourceDir" to "打开资源包目录",
        "settings.packs.openContentDir" to "打开内容包目录",
        "settings.packs.enabled" to "已启用",
        "settings.packs.enabledHint" to "自上而下覆盖",
        "settings.packs.available" to "未启用",
        "settings.packs.baseLocked" to "基础常驻",
        "settings.packs.inMatchLocked" to "对局进行中不可修改资源包与内容包",
        "settings.packs.moveUp" to "上移优先级",
        "settings.packs.moveDown" to "下移优先级",
        "settings.packs.enable" to "移至已启用",
        "settings.packs.disable" to "移至未启用",
        "settings.packs.importBtn" to "导入目录",
        "settings.packs.extractBtn" to "提取原版",
        "settings.packs.importPathPlaceholder" to "输入本地包目录或原版安装目录…",
        "settings.scaleModal.title" to "是否保留新的 UI 缩放比例（{SCALE}）？",
        "settings.scaleModal.autoRevert" to "未确认将自动还原为 {PREV}",
        "settings.scaleModal.seconds" to "{SEC} 秒",
        "settings.scaleModal.revert" to "还原",
        "settings.scaleModal.keep" to "保留",
        "settings.about.version" to "当前版本",
        "settings.about.license" to "开源许可证",
        "settings.about.thirdParty" to "第三方开源组件与素材声明",
        "settings.about.unofficialTitle" to "非官方项目声明",
        "settings.about.unofficialBody" to "RustedWarfare-Reforge 是《铁锈战争》（Rusted Warfare）的社区独立重写项目，与原作者 Corroding Games 无官方隶属或授权关系。",

        // Existing in-game HUD chrome keys (100% preserved)
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
        "menu.backToStart" to "返回大厅",
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
        "pick.repeat" to "连续生成",
        "arrivalDeviatedMulti" to "{N} 个单位停在目标附近",
        "escClose" to "Esc 关闭 · 回车提交",
        "selected" to "已选 {N}",
        "noFunds" to "资金不足",
        "slotEmpty" to "空槽位",
        "slotPrefix" to "槽 {N}",
        "unsupported.guard" to "暂未支持：守卫",
        "unsupported.settings" to "暂未支持：设置",
        "unsupported.generic" to "该功能暂未支持",
        "unsupported.cancelBuildQueue" to "施工队列暂不支持单项取消",
        "arrivalDeviated" to "目标受阻，已停靠最近可达位置",
        "mapSkipped" to "已跳过 {DETAIL}",
        "buildAntiNuke" to "制造反核弹",
        "deploy" to "展开",
        "undeploy" to "收起",
        "enable" to "启用",
        "disable" to "停用",
        "afterburn" to "加力",
        "cancelBurn" to "停止加力",
        "afterburning" to "加力中",
        "cancelBuildDesc" to "立即取消该在建建筑并退还建造资金",
        "reclaimSiteDesc" to "拆除当前工地",
        "reclaimNeedWorker" to "需选工人",
        "reclaimNeedWorkerFull" to "需选中建造者方可回收，或直接点击取消施工",
        "launchNukeDesc" to "在地图上选择落点，光标处显示爆炸半径。",
    )

    private val tableEn: Map<String, String> = mapOf(
        // Generic fallbacks (UI Supplement §8.3)
        "fallback.unknown" to "Unknown",
        "fallback.unknownReason" to "Unknown reason",

        // Common controls & secondary top bar (UI Supplement §2)
        "nav.back" to "← Back",
        "common.confirm" to "OK",
        "common.cancel" to "Cancel",
        "common.close" to "×",
        "common.load" to "Load",
        "common.view" to "View",
        "common.on" to "On",
        "common.off" to "Off",
        "common.none" to "—",
        "common.restoreDefault" to "Reset Defaults",

        // Main menu (UI Supplement §3)
        "mainMenu.skirmish" to "Skirmish",
        "mainMenu.sandbox" to "Test & Sandbox",
        "mainMenu.settings" to "Settings",
        "mainMenu.quit" to "Quit",
        "mainMenu.quitConfirmTitle" to "Quit RustedWarfare-Reforge?",
        "mainMenu.quitConfirmOk" to "Quit",
        "mainMenu.quitConfirmCancel" to "Cancel",

        // Skirmish lobby & More Settings (UI Supplement §4)
        "lobby.title" to "Skirmish",
        "lobby.winMode" to "Victory",
        "lobby.win.standard" to "Standard",
        "lobby.win.elimination" to "Elimination",
        "lobby.win.sandbox" to "Sandbox",
        "lobby.win.objectivesFallbackNotice" to "Objective victory is not supported yet; mapped to Standard",
        "lobby.fog" to "Fog of War",
        "lobby.fog.none" to "No Fog",
        "lobby.fog.basic" to "Revealed Terrain",
        "lobby.fog.memory" to "Memory",
        "lobby.fog.hardcore" to "Hardcore",
        "lobby.fog.memoryFull" to "Memory Fog",
        "lobby.startingFunds" to "Starting Credits",
        "lobby.funds.infinite" to "Infinite",
        "lobby.moreSettings" to "More Settings",
        "lobby.incomeMultiplier" to "Income Rate",
        "lobby.sharedVision" to "Shared Vision",
        "lobby.spawnAssignment" to "Spawn Points",
        "lobby.spawn.fixed" to "Fixed",
        "lobby.spawn.random" to "Random",
        "lobby.startingUnits" to "Starting Units",
        "lobby.units.mapDefault" to "Map Default",
        "lobby.units.standardBase" to "Command Center",
        "lobby.noNukes" to "No Nukes",
        "lobby.seats" to "Players",
        "lobby.ffa" to "FFA",
        "lobby.twoTeams" to "2 Teams",
        "lobby.col.color" to "Color",
        "lobby.col.name" to "Name",
        "lobby.col.ai" to "AI",
        "lobby.col.team" to "Team",
        "lobby.col.spawn" to "Spawn",
        "lobby.col.action" to "Action",
        "lobby.seat.you" to "You",
        "lobby.seat.playerN" to "Player {N}",
        "lobby.seat.aiN" to "AI {N}",
        "lobby.seat.dummy" to "Sandbox Dummy",
        "lobby.seat.dummyN" to "Dummy {N}",
        "lobby.ai.idle" to "Idle",
        "lobby.ai.easy" to "Easy",
        "lobby.ai.normal" to "Normal",
        "lobby.ai.hard" to "Hard",
        "lobby.ai.pendingPhase9" to "AI logic arrives in Phase IX; AI slots currently spawn idle",
        "lobby.action.kick" to "Kick",
        "lobby.action.remove" to "Remove",
        "lobby.addAi" to "＋ Add AI",
        "lobby.addSandboxSeat" to "＋ Add Seat",
        "lobby.battlefieldMap" to "Battlefield Map",
        "lobby.mapPlayersFormat" to "{NAME} · {N}P",
        "lobby.selectMap" to "Select Map",
        "lobby.tmxPathPlaceholder" to "Enter local .tmx map path…",
        "lobby.startMatch" to "Start Match",

        // Shared fullscreen MapPicker (UI Supplement §4.3)
        "mapPicker.title" to "Select Map",
        "mapPicker.empty" to "No maps available",
        "map.river_crossing" to "River Crossing",
        "map.volcanic_archipelago" to "Volcanic Archipelago",
        "map.ring_atoll" to "Ten-Player Atoll",
        "map.twin_bridge_canyon" to "Twin Bridge Canyon",
        "map.desert_oasis" to "Desert Oasis",
        "map.polar_outpost" to "Polar Outpost",
        "map.shattered_highland" to "Shattered Highland",
        "map.rusted_bay" to "Rusted Bay",

        // Test & Sandbox (UI Supplement §5)
        "sandbox.title" to "Test & Sandbox",
        "sandbox.mode" to "Mode",
        "sandbox.mode.free" to "Free Sandbox",
        "sandbox.mode.scenes" to "Test Scenes",
        "sandbox.fastBuild" to "Fast Build",
        "sandbox.cameraControl" to "Camera",
        "sandbox.camera.scripted" to "Follow Script",
        "sandbox.camera.free" to "Free Camera",
        "sandbox.lockedScenes" to "Locked Seed Scenarios",
        "sandbox.sha256Badge" to "SHA-256 Locked",
        "sandbox.scenePreview" to "Scene Preview",
        "sandbox.mapNoWinFormat" to "{NAME} · Sandbox",
        "sandbox.jsonPathPlaceholder" to "Enter local scenario .json path…",
        "sandbox.enterFree" to "Enter Free Sandbox",
        "sandbox.loadScene" to "Load Test Scene",

        // Loading screen (UI Supplement §6)
        "loading.rule.victory" to "Victory",
        "loading.rule.fog" to "Fog",
        "loading.rule.funds" to "Credits",
        "loading.rule.economy" to "Income",
        "loading.rule.noNukes" to "No Nukes",
        "loading.cancel" to "Cancel",
        "loading.tip.1" to "Hold Shift while issuing move, patrol, or build orders to queue multiple tasks; completed or invalid steps advance automatically.",
        "loading.tip.2" to "Secure resource pools early and upgrade extractors to sustain Tier 2 factories and experimental units.",
        "loading.tip.3" to "Combine land, naval, and air forces with sight-range control and laser point defense against long-range strikes.",
        "loading.tip.4" to "Press Ctrl + number to assign control groups, or double-click a unit to select matching friendly units on screen.",
        "loading.tip.5" to "Use hovercrafts and dropships to ferry builders across water or cliffs and establish forward repair outposts.",

        // Settings, Dual-Column Pack Manager, UI Scale Modal & About (UI Supplement §7)
        "settings.title" to "Settings",
        "settings.nav.general" to "General",
        "settings.nav.audio" to "Audio",
        "settings.nav.video" to "Display",
        "settings.nav.controls" to "Controls",
        "settings.nav.resourcePacks" to "Resource Packs",
        "settings.nav.contentPacks" to "Content Packs",
        "settings.nav.about" to "About",
        "settings.language" to "Language",
        "settings.lang.zh" to "简体中文",
        "settings.lang.en" to "English",
        "settings.masterVolume" to "Master Volume",
        "settings.sfxVolume" to "SFX Volume",
        "settings.mute" to "Mute",
        "settings.uiScale" to "UI Scale",
        "settings.theme" to "UI Theme",
        "settings.theme.dark" to "Dark",
        "settings.theme.white" to "White",
        "settings.theme.bone" to "Bone",
        "settings.touchJoystick" to "Touch Camera Joystick",
        "settings.edgeScroll" to "Window Edge Scroll",
        "settings.panSensitivity" to "Camera Pan Sensitivity",
        "settings.zoomSensitivity" to "Camera Zoom Sensitivity",
        "settings.packs.openResourceDir" to "Open Resource Folder",
        "settings.packs.openContentDir" to "Open Content Folder",
        "settings.packs.enabled" to "Enabled",
        "settings.packs.enabledHint" to "Top overrides bottom",
        "settings.packs.available" to "Available",
        "settings.packs.baseLocked" to "Base Locked",
        "settings.packs.inMatchLocked" to "Resource and content packs are locked during an active match",
        "settings.packs.moveUp" to "Move Up",
        "settings.packs.moveDown" to "Move Down",
        "settings.packs.enable" to "Enable",
        "settings.packs.disable" to "Disable",
        "settings.packs.importBtn" to "Import Pack",
        "settings.packs.extractBtn" to "Extract Original",
        "settings.packs.importPathPlaceholder" to "Enter local pack or original game path…",
        "settings.scaleModal.title" to "Keep new UI scale ({SCALE})?",
        "settings.scaleModal.autoRevert" to "Reverting to {PREV} unless confirmed",
        "settings.scaleModal.seconds" to "{SEC}s",
        "settings.scaleModal.revert" to "Revert",
        "settings.scaleModal.keep" to "Keep",
        "settings.about.version" to "Current Version",
        "settings.about.license" to "Open-Source License",
        "settings.about.thirdParty" to "Third-Party Notices",
        "settings.about.unofficialTitle" to "Unofficial Project Notice",
        "settings.about.unofficialBody" to "RustedWarfare-Reforge is an independent community rewrite inspired by Rusted Warfare, with no official affiliation or endorsement from Corroding Games.",

        // Existing in-game HUD chrome keys (100% preserved)
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
        "menu.backToStart" to "Back to lobby",
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
        "pick.repeat" to "Repeat",
        "arrivalDeviatedMulti" to "{N} units stopped near target",
        "escClose" to "Esc closes · Enter submits",
        "selected" to "{N} selected",
        "noFunds" to "Not enough credits",
        "slotEmpty" to "Empty slot",
        "slotPrefix" to "Slot {N}",
        "unsupported.guard" to "Not supported yet: Guard",
        "unsupported.settings" to "Not supported yet: Settings",
        "unsupported.generic" to "Feature not yet supported",
        "unsupported.cancelBuildQueue" to "Canceling single build-queue task not supported yet",
        "arrivalDeviated" to "Target obstructed, stopped at nearest reachable point",
        "mapSkipped" to "Skipped {DETAIL}",
        "buildAntiNuke" to "Build interceptor",
        "deploy" to "Deploy",
        "undeploy" to "Undeploy",
        "enable" to "Enable",
        "disable" to "Disable",
        "afterburn" to "Afterburn",
        "cancelBurn" to "Cancel burn",
        "afterburning" to "Afterburn",
        "cancelBuildDesc" to "Stop building and refund the credits spent so far.",
        "reclaimSiteDesc" to "Dismantle this site.",
        "reclaimNeedWorker" to "Need builder",
        "reclaimNeedWorkerFull" to "Select a builder to reclaim, or use Cancel build directly.",
        "launchNukeDesc" to "Pick a target on the map. The blast radius is shown at the cursor.",
    )

    /**
     * Translates a fixed UI key in [language].
     * Missing keys return localized generic fallback copy rather than exposing raw key identifiers (UI Supplement §8.3).
     */
    fun get(key: String, language: UiLanguage = currentLanguage): String {
        val table = if (language == UiLanguage.ZH) tableZh else tableEn
        return table[key] ?: table.getValue("fallback.unknown")
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
     * Maps core command rejection / denial reason codes to localized user-facing copy (UI Supplement §8.3).
     * Raw unmapped codes are shown ONLY when [showRawCode] is true (`development` mode or F3 debug layer active);
     * in standard release play, unmapped codes fall back to localized generic copy (`未知原因` / `Unknown reason`).
     */
    fun rejectionReason(
        code: String,
        language: UiLanguage = currentLanguage,
        showRawCode: Boolean = false,
    ): String {
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
        if (key != null) {
            val localized = get(key, language)
            return if (showRawCode) "$localized [$code]" else localized
        }
        return if (showRawCode) code else get("fallback.unknownReason", language)
    }

    /**
     * Returns all fixed characters present in both translation tables plus digits & UI/HUD symbols
     * for pre-warming FreeType glyph atlases during loading (HUD Spec §2.6 & UI Supplement §8.3).
     */
    fun preloadCharacters(extraContentStrings: Iterable<String> = emptyList()): String {
        val chars = LinkedHashSet<Char>(1024)
        for (c in 32..126) chars.add(c.toChar())
        for (c in "≡×·▲▼◀▶←©◆●○—…“”‘’：，。！？（）【】《》＋－＄％°、；") chars.add(c)
        for (v in tableZh.values) for (c in v) if (!c.isISOControl()) chars.add(c)
        for (v in tableEn.values) for (c in v) if (!c.isISOControl()) chars.add(c)
        for (extra in extraContentStrings) for (c in extra) if (!c.isISOControl()) chars.add(c)
        return String(chars.toCharArray())
    }

    /** Exposes all registered keys for parity and glyph-coverage verification in tests. */
    internal fun keysZh(): Set<String> = tableZh.keys
    internal fun keysEn(): Set<String> = tableEn.keys
    internal fun valuesZh(): Collection<String> = tableZh.values
    internal fun valuesEn(): Collection<String> = tableEn.values
}
