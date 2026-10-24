package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.*

class TileMetadataTest {
    @Test fun controlled_load_exposes_one_frozen_atlas_layout_for_external_and_inline_tilesets() {
        for (external in listOf(false, true)) {
            val root = Files.createTempDirectory("p004-atlas-")
            val image = root.resolve("atlas.png")
            DataOutputStream(Files.newOutputStream(image)).use {
                it.writeLong(0x89504E470D0A1A0AuL.toLong())
                it.writeInt(13); it.writeInt(0x49484452); it.writeInt(45); it.writeInt(45)
            }
            val body = """name="atlas" tilewidth="20" tileheight="20" tilecount="4" columns="2" spacing="1" margin="2">
                <image source="atlas.png" width="45" height="45" trans="ff00ff"/>
            </tileset>"""
            val reference = if (external) {
                Files.writeString(root.resolve("atlas.tsx"), "<tileset $body")
                """<tileset firstgid="1" source="atlas.tsx"/>"""
            } else """<tileset firstgid="1" $body"""
            val map = root.resolve("map.tmx")
            Files.writeString(map, """<map orientation="orthogonal" renderorder="right-down" width="1" height="1" tilewidth="20" tileheight="20">
                $reference<layer name="Ground" width="1" height="1"><data encoding="csv">1</data></layer></map>""")
            val loaded = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(map)).value
            val atlas = loaded.tileSets.single()
            assertEquals(listOf(2, 1, 2, 4), listOf(atlas.columns, atlas.spacing, atlas.margin, atlas.tileCount))
            assertEquals("ff00ff", atlas.transparentColor)
            assertEquals(image, atlas.imagePath)
            // Once loaded, later XML changes do not redefine the published appearance metadata.
            Files.writeString(if (external) root.resolve("atlas.tsx") else map, "changed after load")
            assertEquals(2, atlas.columns)
            assertEquals(4, atlas.tileCount)
            val legacy = TileSetDefinition(1, "legacy", 20, 20, image, emptySet())
            assertEquals(0, legacy.columns)
            assertEquals(Int.MAX_VALUE, legacy.tileCount)
        }
    }
}
