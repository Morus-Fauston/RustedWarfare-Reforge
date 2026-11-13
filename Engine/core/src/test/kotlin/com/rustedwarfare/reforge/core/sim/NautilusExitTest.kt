package com.rustedwarfare.reforge.core.sim

import kotlin.test.*
import com.rustedwarfare.reforge.core.content.*

class NautilusExitTest {
    @Test fun successive_internal_births_use_current_heading_and_alternate_the_original_seven_unit_side_offset() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            for (index in 1..2) assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue-$index", "p1", "mother",
                "hoverTank", index.toString().padStart(32, '0'), c.world)))
            var step = 0L
            repeat(250) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val first = c.observe().units.single { it.unitId != "mother" }
            // Source worked example: parent (500,180), heading 90 = original 0;
            // original half-bin 8192-entry float trigonometry, not ideal sin(0)=0.
            assertEquals(Position(529.997314453125, 187.01150512695312), first.position)
            assertEquals(90.0, first.heading)
            assertEquals("mother", first.carriedBy)
            assertNotNull(first.moveTarget)
            repeat(250) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val second = c.observe().units.single { it.unitId != "mother" && it.unitId != first.unitId }
            assertEquals(Position(530.002685546875, 173.01150512695312), second.position)
            assertEquals(90.0, second.heading)
            assertEquals("mother", second.carriedBy)
            assertEquals(first.position, c.observe().units.single { it.unitId == first.unitId }.position)
            assertEquals(2L, c.observe().production!!.lastBirthSerial)
        } finally { c.close() }
    }

    @Test fun all_five_blocked_exit_cells_keep_the_paid_child_at_the_world_axis_fallback_inside_the_shared_manifest() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        // WATER spawn admission uses the center cell; map bounds still contain the whole radius.
        // At heading 180 the five original helper candidates hit (24,10), (25,10),
        // (24,10), (23,10), (23,9), while the mother remains legally at (25,9).
        val csv = List(800) { index ->
            val row = index / 40; val column = index % 40
            if (row >= 10 || row == 9 && column < 25) "3" else "2"
        }.joinToString(",")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="40" height="20" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="nautilus-blocked-exit" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="1"><properties><property name="water" value=""/></properties></tile>
              <tile id="2"><properties><property name="large-cliff" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="40" height="20"><data encoding="csv">$csv</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            assertFalse(map.waterBlocking.isBlocked(25, 9))
            for ((column, row) in listOf(24 to 10, 25 to 10, 24 to 10, 23 to 10, 23 to 9))
                assertTrue(map.hoverBlocking.isBlocked(column, row))
            val scenario = NautilusScene.scenario(map).copy(units = listOf(
                UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 180.0), 180.0)))
            val c = NautilusScene.open(scenario)
            try {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue", "p1", "mother", "hoverTank",
                    "a".repeat(32), c.world)))
                repeat(250) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val observed = c.observe()
                val child = observed.units.single { it.unitId != "mother" }
                assertEquals(Position(500.0, 210.0), child.position)
                assertEquals(180.0, child.heading) // Q's initial heading survives failed F helper.
                assertEquals(Position(499.0, 270.0), child.moveTarget)
                assertEquals("mother", child.carriedBy)
                assertEquals(listOf(child.unitId), observed.units.single { it.unitId == "mother" }.transport!!.passengerIds)
                assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
                assertEquals(1L, observed.production!!.lastBirthSerial)
                assertTrue(observed.events.any { it.kind == "production-exit-fallback" && it.unitId == child.unitId })
            } finally { c.close() }
        }
    }
}
