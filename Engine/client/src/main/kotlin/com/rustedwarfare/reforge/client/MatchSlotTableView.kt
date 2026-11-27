package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reusable 1–10 participant seat table (`参战席位`, UI Supplement §4.1 & §5).
 *
 * Shared directly by:
 * - [SkirmishLobbyScreen] (`UI-P004-003`)
 * - `TestAndSandboxScreen` Free Sandbox mode (`UI-P004-004`)
 *
 * Structure & invariants:
 * - Top section header: left `参战席位` title + right `混战` (FFA) & `双队平分` (2 Teams) quick buttons.
 * - Fixed 7-column headerless-index table:
 *   `[01..10 (no header text)] | 颜色 | 名称 | AI 强度 | 队伍 | 出生点 | 操作`
 * - Local host row uses `theme.outGame.selfRowBg`; all player names use `theme.outGame.textStrong` (never colored).
 * - Color swatch + `P1..P10` clicks cycle & swap colors across the 10-color palette.
 * - `队伍` dropdown uses semantic team tinting; `出生点` dropdown uses semantic player-color tinting (swaps on collision).
 * - `操作` shows `—` for local host, danger `踢出` for other human seats, danger `移除` for AI seats.
 * - Renders ONLY active rows + immediate next row `＋ 添加 AI` / `＋ 添加沙盒席位` (hidden at 10 seats).
 */
class MatchSlotTableView(
    private val skin: Skin,
    private val lobbyState: SkirmishLobbyState,
    private val themeProvider: UiThemeProvider,
    private val iconsProvider: () -> OutGameControlIcons?,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onStateChanged: () -> Unit,
) : Group() {

    private val glyphLayout = GlyphLayout()

    val ffaButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("lobby.ffa") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = {
            lobbyState.applyFreeForAllTeams()
            syncRows(forceRebuild = false)
            onStateChanged()
        },
    )

    val twoTeamsButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("lobby.twoTeams") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = {
            lobbyState.applyTwoTeamsSplit()
            syncRows(forceRebuild = false)
            onStateChanged()
        },
    )

    val addSeatButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = {
            if (lobbyState.freeSandboxMode) UiStrings.get("lobby.addSandboxSeat") else UiStrings.get("lobby.addAi")
        },
        variant = OutGameButton.Variant.DASHED_ADD,
        onClick = {
            if (lobbyState.addAiSeat() != null) {
                syncRows(forceRebuild = true)
                onStateChanged()
            }
        },
    )

    private val rowGroups = ArrayList<SeatRowGroup>()
    private var lastStructureSignature: String = ""

    // Cached column X offsets and widths computed in layoutTable()
    private var colIdxX = 0f
    private var colIdxW = 28f
    private var colColorX = 34f
    private var colColorW = 58f
    private var colNameX = 98f
    private var colNameW = 140f
    private var colAiX = 244f
    private var colAiW = 78f
    private var colTeamX = 328f
    private var colTeamW = 54f
    private var colSpawnX = 388f
    private var colSpawnW = 58f
    private var colActionX = 452f
    private var colActionW = 56f

    private var sectionTopY = 0f
    private var colHeaderY = 0f
    private var rowsTopY = 0f
    private var currentRowHeight = 38f
    private var addRowIndex = -1
    private var addRowCenterY = 0f

    var enabled: Boolean = true
        set(value) {
            field = value
            ffaButton.enabled = value
            twoTeamsButton.enabled = value
            addSeatButton.enabled = value
            for (row in rowGroups) row.setInteractive(value)
        }

    init {
        setTransform(false)
        addActor(ffaButton)
        addActor(twoTeamsButton)
        addActor(addSeatButton)
        syncRows(forceRebuild = true)
    }

    fun syncRows(forceRebuild: Boolean = false) {
        val seats = lobbyState.seats
        val maxSpawn = lobbyState.availableSpawnCount
        val sig = buildString {
            append(seats.size).append('@').append(maxSpawn).append('@').append(lobbyState.freeSandboxMode).append(':')
            for (s in seats) append(s.seatId).append('/').append(s.kind.ordinal).append(',')
        }
        if (forceRebuild || sig != lastStructureSignature || rowGroups.size != seats.size) {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            for (old in rowGroups) old.remove()
            rowGroups.clear()
            for (idx in seats.indices) {
                val row = SeatRowGroup(idx)
                row.setInteractive(enabled)
                rowGroups += row
                addActor(row)
            }
            lastStructureSignature = sig
        }
        val spawnOptions = (1..maxSpawn).toList()
        val teamOptions = (0 until MatchSeatConfig.MAX_TEAMS).toList()
        val aiOptions = if (lobbyState.freeSandboxMode) {
            SkirmishAiDifficulty.SANDBOX_OPTIONS
        } else {
            SkirmishAiDifficulty.SKIRMISH_OPTIONS
        }
        for (idx in seats.indices) {
            val seat = seats[idx]
            val row = rowGroups[idx]
            row.seatIndex = idx
            row.aiDropdown?.setOptionsAndSelection(aiOptions, seat.aiDifficulty)
            row.teamDropdown.setOptionsAndSelection(teamOptions, seat.teamIndex)
            row.spawnDropdown.setOptionsAndSelection(spawnOptions, seat.spawnPoint)
        }
        layoutTable()
    }

    override fun setBounds(x: Float, y: Float, width: Float, height: Float) {
        super.setBounds(x, y, width, height)
        layoutTable()
    }

    override fun setSize(width: Float, height: Float) {
        super.setSize(width, height)
        layoutTable()
    }

    private fun layoutTable() {
        if (width <= 0f || height <= 0f) return
        val gap = 6f
        colIdxW = 28f
        colColorW = 58f
        colAiW = 78f
        colTeamW = 54f
        colSpawnW = 58f
        colActionW = 56f

        val fixedSum = colIdxW + colColorW + colAiW + colTeamW + colSpawnW + colActionW + gap * 6f
        colNameW = max(84f, width - fixedSum)

        colIdxX = 0f
        colColorX = colIdxX + colIdxW + gap
        colNameX = colColorX + colColorW + gap
        colAiX = colNameX + colNameW + gap
        colTeamX = colAiX + colAiW + gap
        colSpawnX = colTeamX + colTeamW + gap
        colActionX = colSpawnX + colSpawnW + gap

        // Top section title + quick team split buttons
        sectionTopY = height
        val btnH = 27f
        val font = fontsProvider()?.command ?: fallbackFontProvider()
        glyphLayout.setText(font, UiStrings.get("lobby.twoTeams"))
        val twoTeamW = max(68f, (glyphLayout.width + 20f).roundToInt().toFloat())
        glyphLayout.setText(font, UiStrings.get("lobby.ffa"))
        val ffaW = max(52f, (glyphLayout.width + 20f).roundToInt().toFloat())

        twoTeamsButton.setBounds(width - twoTeamW, sectionTopY - btnH, twoTeamW, btnH)
        ffaButton.setBounds(twoTeamsButton.x - 6f - ffaW, sectionTopY - btnH, ffaW, btnH)

        colHeaderY = sectionTopY - 34f - 22f
        rowsTopY = colHeaderY

        val seats = lobbyState.seats
        val totalDisplayedRows = seats.size + if (lobbyState.canAddSeat) 1 else 0
        val availRowsH = max(120f, rowsTopY)
        val naturalRowH = when {
            totalDisplayedRows >= 10 -> 33f
            totalDisplayedRows >= 8 -> 35f
            else -> 38f
        }
        currentRowHeight = minOf(naturalRowH, (availRowsH / max(1, totalDisplayedRows)).coerceAtLeast(30f))

        for (i in rowGroups.indices) {
            val ry = rowsTopY - (i + 1) * currentRowHeight
            rowGroups[i].setBounds(0f, ry, width, currentRowHeight)
            rowGroups[i].layoutRowCells()
        }

        if (lobbyState.canAddSeat) {
            addSeatButton.isVisible = true
            addRowIndex = seats.size + 1
            val addRowBottom = rowsTopY - (seats.size + 1) * currentRowHeight
            val addBtnH = (currentRowHeight - 8f).coerceIn(24f, 29f)
            val addBtnY = addRowBottom + (currentRowHeight - addBtnH) * 0.5f
            glyphLayout.setText(font, addSeatButton.labelProvider())
            val addBtnW = max(92f, (glyphLayout.width + 24f).roundToInt().toFloat())
            addSeatButton.setBounds(colColorX, addBtnY, addBtnW, addBtnH)
            addRowCenterY = addRowBottom + currentRowHeight * 0.5f
        } else {
            addSeatButton.isVisible = false
            addRowIndex = -1
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")

        // 1. Section title `参战席位`
        val secFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
        val secTitle = UiStrings.get("lobby.seats")
        glyphLayout.setText(secFont, secTitle)
        val prevSec = secFont.color.cpy()
        val strong = theme.outGame.textStrong
        secFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
        secFont.draw(batch, secTitle, x, y + sectionTopY - (27f - glyphLayout.height) * 0.5f)
        secFont.color = prevSec

        // 2. Column headers (Column 0 intentionally blank — no "行" header per UI Supplement §4.1)
        val hdrFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
        val prevHdr = hdrFont.color.cpy()
        val dim = theme.colors.dim
        hdrFont.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)
        val hdrBaselineY = y + colHeaderY + 15f
        hdrFont.draw(batch, UiStrings.get("lobby.col.color"), x + colColorX, hdrBaselineY)
        hdrFont.draw(batch, UiStrings.get("lobby.col.name"), x + colNameX, hdrBaselineY)
        hdrFont.draw(batch, UiStrings.get("lobby.col.ai"), x + colAiX, hdrBaselineY)
        hdrFont.draw(batch, UiStrings.get("lobby.col.team"), x + colTeamX, hdrBaselineY)
        hdrFont.draw(batch, UiStrings.get("lobby.col.spawn"), x + colSpawnX, hdrBaselineY)
        hdrFont.draw(batch, UiStrings.get("lobby.col.action"), x + colActionX, hdrBaselineY)
        hdrFont.color = prevHdr

        // Header bottom crisp line
        OutGameDraw.fillRect(batch, white, x, y + colHeaderY, width, 1f, theme.colors.line, parentAlpha)

        // Add-row index number (e.g. `05`) when `< 10` seats
        if (addRowIndex > 0 && addSeatButton.isVisible) {
            val mono = fontsProvider()?.smallMono ?: hdrFont
            val numStr = String.format(Locale.ROOT, "%02d", addRowIndex)
            glyphLayout.setText(mono, numStr)
            val prevM = mono.color.cpy()
            mono.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)
            mono.draw(batch, numStr, x + colIdxX + 2f, y + addRowCenterY + glyphLayout.height * 0.5f)
            mono.color = prevM
        }

        batch.packedColor = saved
        super.draw(batch, parentAlpha)
    }

    internal fun drawnActions(prefix: String = "lobby"): List<DrawnAction> {
        if (!isVisible) return emptyList()
        val out = ArrayList<DrawnAction>(rowGroups.size * 5 + 3)
        out += DrawnAction("$prefix-ffa", ffaButton, UiStrings.get("lobby.ffa"), enabled = ffaButton.enabled)
        out += DrawnAction("$prefix-two-teams", twoTeamsButton, UiStrings.get("lobby.twoTeams"), enabled = twoTeamsButton.enabled)
        if (addSeatButton.isVisible) {
            out += DrawnAction("$prefix-add-seat", addSeatButton, addSeatButton.labelProvider(), enabled = addSeatButton.enabled)
        }
        val seats = lobbyState.seats
        for (i in rowGroups.indices) {
            if (i >= seats.size) break
            val seat = seats[i]
            val r = rowGroups[i]
            val num = i + 1
            out += DrawnAction("$prefix-seat-$num-color", r.colorCellActor, seat.colorSlotLabel, enabled = enabled)
            r.aiDropdown?.let { dd ->
                out += DrawnAction("$prefix-seat-$num-ai", dd, seat.aiDifficulty.localizedLabel(), enabled = dd.enabled)
            }
            out += DrawnAction("$prefix-seat-$num-team", r.teamDropdown, seat.teamLabel, enabled = r.teamDropdown.enabled)
            out += DrawnAction("$prefix-seat-$num-spawn", r.spawnDropdown, seat.spawnPoint.toString(), enabled = r.spawnDropdown.enabled)
            r.actionButton?.let { btn ->
                out += DrawnAction("$prefix-seat-$num-remove", btn, btn.labelProvider(), enabled = btn.enabled)
            }
        }
        return out
    }

    private inner class SeatRowGroup(var seatIndex: Int) : Group() {
        private val rowGlyph = GlyphLayout()
        private var colorHovered = false

        val colorCellActor = object : Actor() {
            init {
                touchable = Touchable.enabled
                addListener(object : InputListener() {
                    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                        if (pointer == -1) colorHovered = true
                    }

                    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                        if (pointer == -1) colorHovered = false
                    }

                    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean =
                        pointer == 0 && enabled

                    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                        if (pointer == 0 && enabled && x in 0f..width && y in 0f..height) {
                            lobbyState.cycleSeatColor(seatIndex)
                            onStateChanged()
                        }
                    }
                })
            }

            override fun draw(batch: Batch, parentAlpha: Float) {
                val seat = lobbyState.seats.getOrNull(seatIndex) ?: return
                val saved = batch.packedColor
                val theme = themeProvider.currentTheme()
                val white = skin.getRegion("white")
                if (enabled && colorHovered) {
                    OutGameDraw.fillRect(batch, white, x - 2f, y + 2f, width + 4f, height - 4f, theme.outGame.hoverSoft, parentAlpha)
                }
                val swatchSz = 13f
                val sy = y + (height - swatchSz) * 0.5f
                val pCol = theme.palettes.playerColor(seat.colorIndex)
                OutGameDraw.fillRect(batch, white, x, sy, swatchSz, swatchSz, pCol, parentAlpha)
                val borderCol = if (colorHovered) theme.outGame.textStrong else theme.colors.line
                OutGameDraw.strokeRect(batch, white, x, sy, swatchSz, swatchSz, 1f, borderCol, parentAlpha)

                val mono = fontsProvider()?.smallMono ?: fallbackFontProvider()
                val tag = seat.colorSlotLabel
                rowGlyph.setText(mono, tag)
                val prev = mono.color.cpy()
                val fg = if (colorHovered) theme.outGame.textStrong else theme.colors.dim
                mono.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
                mono.draw(batch, tag, x + swatchSz + 6f, y + (height + rowGlyph.height) * 0.5f)
                mono.color = prev
                batch.packedColor = saved
            }
        }

        val aiDropdown: UnifiedDropdown<SkirmishAiDifficulty>? =
            if (lobbyState.seats.getOrNull(seatIndex)?.kind == MatchSeatKind.AI) {
                val opts = if (lobbyState.freeSandboxMode) {
                    SkirmishAiDifficulty.SANDBOX_OPTIONS
                } else {
                    SkirmishAiDifficulty.SKIRMISH_OPTIONS
                }
                val initVal = lobbyState.seats.getOrNull(seatIndex)?.aiDifficulty ?: opts.first()
                UnifiedDropdown(
                    skin = skin,
                    themeProvider = themeProvider,
                    iconsProvider = iconsProvider,
                    fontsProvider = fontsProvider,
                    fallbackFontProvider = fallbackFontProvider,
                    options = opts,
                    initialSelected = initVal,
                    labelOf = { it.localizedLabel() },
                    onSelected = { chosen ->
                        lobbyState.assignSeatAiDifficulty(seatIndex, chosen)
                        onStateChanged()
                    },
                )
            } else {
                null
            }

        val teamDropdown = UnifiedDropdown(
            skin = skin,
            themeProvider = themeProvider,
            iconsProvider = iconsProvider,
            fontsProvider = fontsProvider,
            fallbackFontProvider = fallbackFontProvider,
            options = (0 until MatchSeatConfig.MAX_TEAMS).toList(),
            initialSelected = lobbyState.seats.getOrNull(seatIndex)?.teamIndex ?: 0,
            labelOf = { ('A' + it).toString() },
            tintColorProvider = {
                val seat = lobbyState.seats.getOrNull(seatIndex)
                seat?.let { themeProvider.currentTheme().palettes.teamColor(it.teamIndex) }
            },
            useMonoFont = true,
            onSelected = { chosenTeam ->
                lobbyState.assignSeatTeam(seatIndex, chosenTeam)
                onStateChanged()
            },
        )

        val spawnDropdown = UnifiedDropdown(
            skin = skin,
            themeProvider = themeProvider,
            iconsProvider = iconsProvider,
            fontsProvider = fontsProvider,
            fallbackFontProvider = fallbackFontProvider,
            options = (1..lobbyState.availableSpawnCount).toList(),
            initialSelected = lobbyState.seats.getOrNull(seatIndex)?.spawnPoint ?: 1,
            labelOf = { it.toString() },
            tintColorProvider = {
                val seat = lobbyState.seats.getOrNull(seatIndex)
                seat?.let { themeProvider.currentTheme().palettes.playerColor(it.colorIndex) }
            },
            useMonoFont = true,
            onSelected = { chosenSpawn ->
                lobbyState.assignSeatSpawnPoint(seatIndex, chosenSpawn)
                // Another row's spawn may have swapped! Sync all dropdown values cleanly.
                syncRows(forceRebuild = false)
                onStateChanged()
            },
        )

        val actionButton: OutGameButton? = when (lobbyState.seats.getOrNull(seatIndex)?.kind) {
            MatchSeatKind.REMOTE_HUMAN -> OutGameButton(
                skin = skin,
                themeProvider = themeProvider,
                fontsProvider = fontsProvider,
                fallbackFontProvider = fallbackFontProvider,
                labelProvider = { UiStrings.get("lobby.action.kick") },
                variant = OutGameButton.Variant.DANGER,
                onClick = {
                    if (lobbyState.removeOrKickSeat(seatIndex)) {
                        syncRows(forceRebuild = true)
                        onStateChanged()
                    }
                },
            )
            MatchSeatKind.AI -> OutGameButton(
                skin = skin,
                themeProvider = themeProvider,
                fontsProvider = fontsProvider,
                fallbackFontProvider = fallbackFontProvider,
                labelProvider = { UiStrings.get("lobby.action.remove") },
                variant = OutGameButton.Variant.DANGER,
                onClick = {
                    if (lobbyState.removeOrKickSeat(seatIndex)) {
                        syncRows(forceRebuild = true)
                        onStateChanged()
                    }
                },
            )
            else -> null
        }

        init {
            setTransform(false)
            addActor(colorCellActor)
            if (aiDropdown != null) addActor(aiDropdown)
            addActor(teamDropdown)
            addActor(spawnDropdown)
            if (actionButton != null) addActor(actionButton)
        }

        fun setInteractive(active: Boolean) {
            aiDropdown?.enabled = active
            teamDropdown.enabled = active
            spawnDropdown.enabled = active
            actionButton?.enabled = active
        }

        fun layoutRowCells() {
            val cellH = (height - 8f).coerceIn(23f, 28f)
            val cy = (height - cellH) * 0.5f
            colorCellActor.setBounds(colColorX, 0f, colColorW, height)
            aiDropdown?.setBounds(colAiX, cy, colAiW, cellH)
            teamDropdown.setBounds(colTeamX, cy, colTeamW, cellH)
            spawnDropdown.setBounds(colSpawnX, cy, colSpawnW, cellH)
            actionButton?.setBounds(colActionX, cy, colActionW, cellH)
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val seat = lobbyState.seats.getOrNull(seatIndex) ?: return
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")

            // Local host gentle highlight & bottom hairline divider
            if (seat.kind == MatchSeatKind.LOCAL_HUMAN) {
                OutGameDraw.fillRect(batch, white, x, y, width, height, theme.outGame.selfRowBg, parentAlpha)
            }
            OutGameDraw.fillRect(batch, white, x, y, width, 1f, theme.colors.hair, parentAlpha)

            // Column 0: 01..10 mono index
            val mono = fontsProvider()?.smallMono ?: fallbackFontProvider()
            val numStr = String.format(Locale.ROOT, "%02d", seatIndex + 1)
            rowGlyph.setText(mono, numStr)
            val prevMono = mono.color.cpy()
            val dim = theme.colors.dim
            mono.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)
            mono.draw(batch, numStr, x + colIdxX + 2f, y + (height + rowGlyph.height) * 0.5f)
            mono.color = prevMono

            // Column 2: Unified primary strong text player name (never colored by player swatch)
            val nameFont = if (seat.kind == MatchSeatKind.LOCAL_HUMAN) {
                fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
            } else {
                fontsProvider()?.command ?: fontsProvider()?.body ?: fallbackFontProvider()
            }
            val nameStr = seat.displayName()
            rowGlyph.setText(nameFont, nameStr)
            val prevName = nameFont.color.cpy()
            val strong = theme.outGame.textStrong
            nameFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            nameFont.draw(batch, nameStr, x + colNameX, y + (height + rowGlyph.height) * 0.5f)
            nameFont.color = prevName

            // Dash placeholders for human `AI 强度` (`—`) and local host `操作` (`—`)
            val dashStr = UiStrings.get("common.none")
            val bodyFont = fontsProvider()?.body ?: fallbackFontProvider()
            val prevBody = bodyFont.color.cpy()
            bodyFont.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)
            rowGlyph.setText(bodyFont, dashStr)
            if (aiDropdown == null) {
                bodyFont.draw(batch, dashStr, x + colAiX + 2f, y + (height + rowGlyph.height) * 0.5f)
            }
            if (actionButton == null) {
                bodyFont.draw(batch, dashStr, x + colActionX + 4f, y + (height + rowGlyph.height) * 0.5f)
            }
            bodyFont.color = prevBody

            batch.packedColor = saved
            super.draw(batch, parentAlpha)
        }
    }
}
