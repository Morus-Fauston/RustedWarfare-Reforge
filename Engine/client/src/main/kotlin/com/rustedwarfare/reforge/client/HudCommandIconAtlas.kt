package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.JsonReader

/** Pure metadata entry for one command icon inside [HudCommandIconAtlas]. */
data class HudCommandIconRect(val name: String, val x: Int, val y: Int, val width: Int, val height: Int)

/** Pure metadata index for the 65 self-owned command icons (HUD Spec §8A). */
data class HudCommandIconManifest(
    val version: Int,
    val iconSizePx: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val license: String,
    val icons: Map<String, HudCommandIconRect>,
) {
    companion object {
        const val ATLAS_PNG_PATH: String = "icons/hud-command-icons.png"
        const val ATLAS_JSON_PATH: String = "icons/hud-command-icons.json"

        fun parse(jsonText: String): HudCommandIconManifest {
            val root = JsonReader().parse(jsonText)
            val iconsObj = root.get("icons")
            val map = LinkedHashMap<String, HudCommandIconRect>(65)
            var child = iconsObj?.child
            while (child != null) {
                val name = child.name
                map[name] = HudCommandIconRect(
                    name = name,
                    x = child.getInt("x"),
                    y = child.getInt("y"),
                    width = child.getInt("w"),
                    height = child.getInt("h"),
                )
                child = child.next
            }
            return HudCommandIconManifest(
                version = root.getInt("version", 1),
                iconSizePx = root.getInt("iconSizePx", 72),
                atlasWidth = root.getInt("atlasWidth", 0),
                atlasHeight = root.getInt("atlasHeight", 0),
                license = root.getString("license", ""),
                icons = map,
            )
        }
    }
}

/**
 * Runtime loader for the 65 self-owned white-on-transparent 72px command icon PNG atlas
 * with Linear + MipMapLinearLinear filtering so icons scale crisply at any reference scale s.
 * Tint via SpriteBatch.setColor() using UiTokens.INK (on bone keycaps) or UiTokens.TEXT/DIM (on dark surfaces).
 */
class HudCommandIconAtlas private constructor(
    val manifest: HudCommandIconManifest,
    private val texture: Texture,
    private val regions: Map<String, TextureRegion>,
) : AutoCloseable {

    private var closed = false

    fun icon(name: String): TextureRegion? = regions[name] ?: regions["rw-info"]

    fun hasIcon(name: String): Boolean = name in regions

    val iconNames: Set<String> get() = regions.keys

    override fun close() {
        if (closed) return
        closed = true
        texture.dispose()
    }

    companion object {
        fun load(
            fileResolver: (String) -> FileHandle = { path -> Gdx.files.classpath(path) },
        ): HudCommandIconAtlas {
            val manifest = HudCommandIconManifest.parse(fileResolver(HudCommandIconManifest.ATLAS_JSON_PATH).readString("UTF-8"))
            val texture = Texture(fileResolver(HudCommandIconManifest.ATLAS_PNG_PATH), true).apply {
                setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
                setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
            }
            val regions = LinkedHashMap<String, TextureRegion>(manifest.icons.size)
            for ((name, rect) in manifest.icons) {
                regions[name] = TextureRegion(texture, rect.x, rect.y, rect.width, rect.height)
            }
            return HudCommandIconAtlas(manifest, texture, regions)
        }
    }
}
