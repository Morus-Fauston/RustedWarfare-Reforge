package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MapLoadTest {
    @Test
    fun loadsRealExternalTilesetsAndApprovedControlledLayout() {
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(mapPath())).value
        assertEquals(30, map.widthTiles)
        assertEquals(20, map.heightTiles)
        assertEquals(20, map.tileWidth)
        assertEquals(20, map.tileHeight)
        assertEquals(600, map.groundGids.size)
        // 33 blocking cells: 17 wall cells (col 14, rows outside the 3-cell gap 9-11)
        // + 16 enclosed-box border cells. Was 35 before the 2026-10-15 gap widening.
        assertEquals(33, map.blocking.blockedCount)
        assertEquals(listOf(1, 41), map.tileSets.map { it.firstGid })
        assertEquals(setOf(16), map.tileSets[0].blockedTileIds)
        assertEquals(setOf(7, 8, 21, 22, 28, 29), map.tileSets[1].blockedTileIds)
        assertTrue(map.tileSets.all { it.imagePath.isAbsolute && Files.isRegularFile(it.imagePath) })
        // The wall gap is rows 9-11 so that a radius-11 unit (diameter 22 > tile 20)
        // can pass: see ENG-P001-004 spec review evidence for the geometry.
        for (row in 0 until 20) assertEquals(row !in 9..11, map.blocking.isBlocked(14, row))
        for (row in 4..6) for (col in 21..23) {
            assertEquals(1, map.groundGids[row * 30 + col])
            assertEquals(false, map.blocking.isBlocked(col, row))
        }
        assertFailsWith<IndexOutOfBoundsException> { map.blocking.isBlocked(30, 0) }
        assertFailsWith<IndexOutOfBoundsException> { map.blocking.isBlocked(-1, 1) }
    }

    @Test
    fun acceptsDifferentDimensionsInlineTilesetAndBlocksZeroGid() {
        val image = inputs().resolve("tilesets/../../../../Rusted Warfare/assets/tilesets/bitmaps/longgrass.png")
            .toAbsolutePath().normalize().toString().replace("&", "&amp;")
        val csv = List(100) { if (it == 55) "0" else "1" }.joinToString(",")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="10" height="10" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="inline" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
            </tileset>
            <layer name="Ground" width="10" height="10"><data encoding="csv">$csv</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            assertEquals(10, map.widthTiles)
            assertEquals(100, map.groundGids.size)
            assertEquals(1, map.blocking.blockedCount)
            assertTrue(map.blocking.isBlocked(5, 5))
            map.groundGids[0] = 999
            assertEquals(1, map.groundGids[0])
            assertEquals(false, map.blocking.isBlocked(0, 0))
        }
    }

    @Test
    fun definitionsDefensivelyCopyConstructorCollectionsAndArrays() {
        val blocked = booleanArrayOf(true, false)
        val grid = BlockingGrid(2, 1, blocked)
        blocked[0] = false
        assertTrue(grid.isBlocked(0, 0))
        assertEquals(1, grid.blockedCount)
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(mapPath())).value
        assertFailsWith<UnsupportedOperationException> { (map.tileSets as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (map.tileSets[0].blockedTileIds as MutableSet<*>).clear() }
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(unitPath())).value
        assertFailsWith<UnsupportedOperationException> { (unit.retainedUnconsumed as MutableSet<*>).clear() }
    }
}

internal fun withMap(text: String, action: (Path) -> Unit) {
    val path = Files.createTempFile("p001-map-", ".tmx")
    try {
        Files.writeString(path, text)
        action(path)
    } finally {
        Files.deleteIfExists(path)
    }
}
