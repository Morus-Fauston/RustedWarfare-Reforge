package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color

/**
 * Design tokens for the redesigned in-game HUD (HUD Spec §3).
 *
 * All values match `Docs/规格/局内HUD重设计/局内HUD演示稿.html` and `局内HUD规格.md` §3.1–3.3.
 */
object UiTokens {
    // --- §3.1 Color Tokens ---
    /** Sidebar background (except opaque minimap), system bar, control group cells: #212528 @ 0.86 */
    val RAIL: Color = hex(0x212528, 0.86f)
    /** Solid popup/tooltip/placement card/console background: #24292C */
    val SOLID: Color = hex(0x24292C, 1f)
    /** Common command keys, disabled keys, module slot keys: #353B3F */
    val CELL: Color = hex(0x353B3F, 1f)
    /** Progress bar trough, icon well, queue slot well: #1A1E20 */
    val WELL: Color = hex(0x1A1E20, 1f)
    /** Standard 1px structural border: #596167 */
    val LINE: Color = hex(0x596167, 1f)
    /** Hairline divider between sidebar sections: white @ 0.11 */
    val HAIR: Color = Color(1f, 1f, 1f, 0.11f)
    /** Primary text: #DFE3E5 */
    val TEXT: Color = hex(0xDFE3E5, 1f)
    /** Secondary text & labels: #9AA3A8 */
    val DIM: Color = hex(0x9AA3A8, 1f)
    /** Stat bar fill & active selection tint: #C9D1D5 */
    val VALUE: Color = hex(0xC9D1D5, 1f)
    /** Action keycap surface (bone white): #DBD5C6 */
    val BONE: Color = hex(0xDBD5C6, 1f)
    /** Action keycap hover & active mode surface: #F2EDE0 */
    val BONE_HI: Color = hex(0xF2EDE0, 1f)
    /** Text & icon ink on bone keycaps: #1A1A18 */
    val INK: Color = hex(0x1A1A18, 1f)
    /** Sole in-progress accent (production/build progress, queue badge, event pip, active mode border): #DC5A22 */
    val RUST: Color = hex(0xDC5A22, 1f)
    /** Insufficient funds / warning / unsupported notice accent (always paired with ▲): #E6AA2D */
    val AMBER: Color = hex(0xE6AA2D, 1f)
    /** Enemy accent (minimap foe dots, enemy card stripe, selection ring): #E0564A */
    val FOE: Color = hex(0xE0564A, 1f)
    /** Modal/pause scrim overlay: black @ 0.45 */
    val SCRIM: Color = Color(0f, 0f, 0f, 0.45f)
    /** F3 debug overlay line background: black @ 0.60 */
    val DEBUG_BG: Color = Color(0f, 0f, 0f, 0.60f)
    /** F3 debug text: #E8ECEE */
    val DEBUG_TEXT: Color = hex(0xE8ECEE, 1f)

    // --- §3.2 Dimension Tokens (reference units at short edge = 640) ---
    const val SIDEBAR_WIDTH: Float = 232f
    const val SCREEN_MARGIN: Float = 8f
    const val SIDEBAR_PAD_H: Float = 10f
    const val SIDEBAR_PAD_V: Float = 8f
    const val ACTION_KEY_HEIGHT_PC: Float = 52f
    const val ACTION_KEY_HEIGHT_TOUCH: Float = 58f
    const val COMMON_KEY_HEIGHT_PC: Float = 28f
    const val COMMON_KEY_HEIGHT_TOUCH: Float = 34f
    const val SLOT_KEY_HEIGHT_PC: Float = 40f
    const val SLOT_KEY_HEIGHT_TOUCH: Float = 46f
    const val KEY_GAP: Float = 5f
    const val KEY_RADIUS: Float = 4f
    const val GROUP_CELL_W_PC: Float = 44f
    const val GROUP_CELL_H_PC: Float = 40f
    const val GROUP_CELL_W_TOUCH: Float = 50f
    const val GROUP_CELL_H_TOUCH: Float = 50f
    const val TOOLTIP_WIDTH: Float = 248f
    const val PLACEMENT_CARD_WIDTH: Float = 300f
    const val MENU_PANEL_WIDTH: Float = 214f
    const val STAT_VALUE_COL_WIDTH: Float = 74f
    const val STAT_BAR_HEIGHT: Float = 8f
    const val ICON_BOX_SIZE: Float = 40f
    const val RESERVE_SQUARE_SIZE: Float = 14f

    fun actionKeyHeight(touch: Boolean): Float = if (touch) ACTION_KEY_HEIGHT_TOUCH else ACTION_KEY_HEIGHT_PC
    fun commonKeyHeight(touch: Boolean): Float = if (touch) COMMON_KEY_HEIGHT_TOUCH else COMMON_KEY_HEIGHT_PC
    fun slotKeyHeight(touch: Boolean): Float = if (touch) SLOT_KEY_HEIGHT_TOUCH else SLOT_KEY_HEIGHT_PC

    // --- §3.3 Typography Reference Sizes (reference units) ---
    /** Credits value (mono) */
    const val FONT_CREDITS: Int = 19
    /** Selection card unit name (bold) */
    const val FONT_TITLE: Int = 16
    /** Tooltip title (bold) */
    const val FONT_TOOLTIP_TITLE: Int = 15
    /** Body text */
    const val FONT_BODY: Int = 14
    /** Common command keys / queue pct / cargo count */
    const val FONT_COMMAND: Int = 13
    /** Action key name (bold), price (mono), stat current value (mono), labels, badges, debug */
    const val FONT_SMALL: Int = 12
    /** Stat "/max", reserve & cargo capacity (sole size < 12 per spec §3.3) */
    const val FONT_CAPTION: Int = 11

    fun hex(rgb: Int, alpha: Float = 1f): Color = Color(
        ((rgb ushr 16) and 0xFF) / 255f,
        ((rgb ushr 8) and 0xFF) / 255f,
        (rgb and 0xFF) / 255f,
        alpha,
    )
}
