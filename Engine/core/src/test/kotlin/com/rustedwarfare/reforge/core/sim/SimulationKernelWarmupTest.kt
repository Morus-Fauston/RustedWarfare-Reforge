package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.Test
import kotlin.test.assertTrue

class SimulationKernelWarmupTest {
    @Test
    fun warmup_scenario_succeeds_even_when_template_tile_zero_is_blocked() {
        val base = TestContentFactory.openScenario()
        val w = base.map.widthTiles
        val h = base.map.heightTiles
        val blocked = BooleanArray(w * h) { idx ->
            idx == 0 || base.map.blocking.isBlocked(idx % w, idx / w)
        }
        val gids = base.map.groundGids.copyOf()
        if (gids.isNotEmpty()) gids[0] = 99
        val mapWithBlockedOrigin = MapDefinition(
            widthTiles = w,
            heightTiles = h,
            tileWidth = base.map.tileWidth,
            tileHeight = base.map.tileHeight,
            tileSets = base.map.tileSets,
            groundGids = gids,
            blocking = BlockingGrid(w, h, blocked),
        )
        val template = base.copy(map = mapWithBlockedOrigin)
        val diag = SimulationKernelWarmup.warmUpScenarioDiagnostic(template)
        assertTrue(diag == null, "Kernel warmup failed: $diag")
    }
}
