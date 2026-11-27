package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import kotlin.math.roundToInt

/**
 * Pixel-crisp FreeType font bundle for the redesigned HUD (HUD Spec §2.4, §2.6, §3.3).
 *
 * - Uses packaged OFL 1.1 `NotoSansSC-Regular.otf`, `NotoSansSC-Bold.otf` and `JetBrainsMono-Regular.ttf`.
 * - Generates glyphs at actual screen pixel size (`refSize * scale`) with `incremental = true`,
 *   so arbitrary content-pack CJK unit/action names render crisply without pre-baking the full CJK set.
 * - Pre-warms translation-table strings + current content pack strings on construction/rebuild.
 * - Releases all owned `BitmapFont` and `FreeTypeFontGenerator` native resources on [close].
 */
class UiFontSet private constructor(
    val scale: Float,
    /** Body regular (14 ref) */
    val body: BitmapFont,
    /** Title bold (16 ref) */
    val title: BitmapFont,
    /** Sub-page top bar centered bold title (22 ref) */
    val subPageTitle: BitmapFont,
    /** Section / modal heading bold (18 ref) */
    val sectionTitle: BitmapFont,
    /** Main menu item large bold (34 ref) */
    val menuItem: BitmapFont,
    /** Tooltip title bold (15 ref) */
    val tooltipTitle: BitmapFont,
    /** Command key / medium bold (13 ref) */
    val command: BitmapFont,
    /** Action key name bold (12 ref) */
    val keyBold: BitmapFont,
    /** Small label regular (12 ref) */
    val small: BitmapFont,
    /** Hero mono title / LOADING word (34 ref) */
    val heroMono: BitmapFont,
    /** Sub-hero mono version / percentage (14 ref) */
    val subHeroMono: BitmapFont,
    /** Credits mono (19 ref) */
    val creditsMono: BitmapFont,
    /** Queue/cargo/reserve count mono (13 ref) */
    val countMono: BitmapFont,
    /** Stat current / price / debug mono (12 ref) */
    val smallMono: BitmapFont,
    /** Stat "/max" and capacity caption mono (11 ref) */
    val captionMono: BitmapFont,
    private val generators: List<FreeTypeFontGenerator>,
) : AutoCloseable {

    private var closed = false

    /** All owned fonts for bulk cleanup or diagnostic inspection. */
    val allFonts: List<BitmapFont> = listOf(
        body, title, subPageTitle, sectionTitle, menuItem, tooltipTitle, command, keyBold, small,
        heroMono, subHeroMono, creditsMono, countMono, smallMono, captionMono,
    )

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        // Dispose incremental BitmapFonts BEFORE their backing FreeTypeFontGenerators!
        for (font in allFonts) {
            try {
                font.dispose()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        for (gen in generators) {
            try {
                gen.dispose()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        const val REGULAR_FONT_PATH: String = "fonts/NotoSansSC-Regular.otf"
        const val BOLD_FONT_PATH: String = "fonts/NotoSansSC-Bold.otf"
        const val MONO_FONT_PATH: String = "fonts/JetBrainsMono-Regular.ttf"

        /**
         * Creates a new pixel-crisp [UiFontSet] for [scale], pre-warming [preloadChars].
         * Call on the GL thread when initializing presentation or after resize debounce settles.
         */
        fun create(
            scale: Float,
            theme: UiTheme,
            assetResolver: UiThemeAssetResolver = UiThemeAssetResolver(),
            preloadChars: String = UiStrings.preloadCharacters(),
        ): UiFontSet = create(
            scale = scale,
            preloadChars = preloadChars,
            fileResolver = assetResolver::resolve,
            regularPath = theme.assets.fontRegular,
            boldPath = theme.assets.fontBold,
            monoPath = theme.assets.fontMono,
        )

        fun create(
            scale: Float,
            preloadChars: String = UiStrings.preloadCharacters(),
            fileResolver: (String) -> FileHandle = { path -> Gdx.files.classpath(path) },
            regularPath: String = REGULAR_FONT_PATH,
            boldPath: String = BOLD_FONT_PATH,
            monoPath: String = MONO_FONT_PATH,
        ): UiFontSet {
            val safeScale = scale.coerceAtLeast(0.5f)
            val regularGen = FreeTypeFontGenerator(fileResolver(regularPath))
            val boldGen = try {
                FreeTypeFontGenerator(fileResolver(boldPath))
            } catch (e: Throwable) {
                regularGen.dispose()
                throw e
            }
            val monoGen = try {
                FreeTypeFontGenerator(fileResolver(monoPath))
            } catch (e: Throwable) {
                boldGen.dispose()
                regularGen.dispose()
                throw e
            }

            val createdFonts = mutableListOf<BitmapFont>()
            fun genFont(
                generator: FreeTypeFontGenerator,
                refSize: Int,
                characters: String,
                incremental: Boolean = true,
            ): BitmapFont {
                val pxSize = (refSize * safeScale).roundToInt().coerceIn(8, 128)
                val param = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                    size = pxSize
                    this.characters = characters
                    this.incremental = incremental
                    minFilter = Texture.TextureFilter.Linear
                    magFilter = Texture.TextureFilter.Linear
                    hinting = FreeTypeFontGenerator.Hinting.AutoMedium
                    kerning = true
                    padTop = 1
                    padBottom = 1
                    padLeft = 1
                    padRight = 1
                }
                return generator.generateFont(param).also {
                    it.data.markupEnabled = false
                    it.data.setScale(1f / safeScale)
                    it.setUseIntegerPositions(false)
                    createdFonts += it
                }
            }

            val monoChars = buildString {
                for (c in 32..126) append(c.toChar())
                append("×·▲▼—…°")
            }

            return try {
                val body = genFont(regularGen, UiTokens.FONT_BODY, preloadChars)
                val title = genFont(boldGen, UiTokens.FONT_TITLE, preloadChars)
                val subPageTitle = genFont(boldGen, 22, preloadChars)
                val sectionTitle = genFont(boldGen, 18, preloadChars)
                val menuItem = genFont(boldGen, 34, preloadChars)
                val tooltipTitle = genFont(boldGen, UiTokens.FONT_TOOLTIP_TITLE, preloadChars)
                val command = genFont(boldGen, UiTokens.FONT_COMMAND, preloadChars)
                val keyBold = genFont(boldGen, UiTokens.FONT_SMALL, preloadChars)
                val small = genFont(regularGen, UiTokens.FONT_SMALL, preloadChars)
                val heroMono = genFont(monoGen, 34, monoChars)
                val subHeroMono = genFont(monoGen, 14, monoChars)
                val creditsMono = genFont(monoGen, UiTokens.FONT_CREDITS, monoChars)
                val countMono = genFont(monoGen, UiTokens.FONT_COMMAND, monoChars)
                val smallMono = genFont(monoGen, UiTokens.FONT_SMALL, monoChars)
                val captionMono = genFont(monoGen, UiTokens.FONT_CAPTION, monoChars)
                UiFontSet(
                    scale = safeScale,
                    body = body,
                    title = title,
                    subPageTitle = subPageTitle,
                    sectionTitle = sectionTitle,
                    menuItem = menuItem,
                    tooltipTitle = tooltipTitle,
                    command = command,
                    keyBold = keyBold,
                    small = small,
                    heroMono = heroMono,
                    subHeroMono = subHeroMono,
                    creditsMono = creditsMono,
                    countMono = countMono,
                    smallMono = smallMono,
                    captionMono = captionMono,
                    generators = listOf(regularGen, boldGen, monoGen),
                )
            } catch (failure: Throwable) {
                for (font in createdFonts) {
                    runCatching { font.dispose() }
                }
                runCatching { monoGen.dispose() }
                runCatching { boldGen.dispose() }
                runCatching { regularGen.dispose() }
                throw failure
            }
        }
    }
}
