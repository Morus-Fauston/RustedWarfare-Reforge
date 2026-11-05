package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipSoundTest {
    @Test fun actual_stock_launches_authorize_distinct_naval_events_and_original_volumes_once_per_step() = WaterTestScene.withMap { map ->
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val units = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("naval-production/ini/production-package"))).value.units
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = units.filterKeys { it in setOf("heavyBattleship", "c_tank", "helicopter", "navalUnderwaterTarget") },
            units = listOf(UnitSpawn("ship", "p1", "heavyBattleship", Position(500.0, 200.0), 90.0),
                UnitSpawn("ground", "p2", "c_tank", Position(300.0, 200.0), 0.0),
                UnitSpawn("air", "p2", "helicopter", Position(650.0, 200.0), 0.0),
                UnitSpawn("sub", "p2", "navalUnderwaterTarget", Position(600.0, 300.0), 0.0, -8.0)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L, "ground" to 9L, "air" to 10L, "sub" to 11L), 12L))
        val initialized = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            val heard = mutableListOf<AuthorizedSound>()
            val fired = mutableSetOf<String>()
            val listener = SoundListener(Position(500.0, 200.0), 800f)
            repeat(240) { q ->
                val sample = context.advance(Step(q.toLong(), 1.0, "sim-tick"))
                val births = sample.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "ship" }
                fired += births.mapNotNull { it.projectile.sourceWeaponName }
                val host = sample.soundTriggers
                assertTrue(host.all { it.eventId in setOf("reforge:events/naval-main-fire", "reforge:events/naval-missile-fire") })
                val expected = births.mapNotNull { when (it.projectile.sourceWeaponName) {
                    "1", "2" -> "reforge:events/naval-main-fire" to .3f
                    "4", "5" -> "reforge:events/naval-missile-fire" to .1f
                    else -> null
                } }
                assertEquals(expected.sortedBy { it.first }, host.map { it.eventId to it.volume }.sortedBy { it.first })
                heard += context.consumeSounds(context.world, "p1", listener).sounds
                assertTrue(context.consumeSounds(context.world, "p1", listener).sounds.isEmpty())
            }
            assertEquals(setOf("1", "2", "3", "4", "5"), fired)
            assertEquals(setOf("reforge:events/naval-main-fire", "reforge:events/naval-missile-fire"), heard.map { it.eventId }.toSet())
            for (sound in heard) {
                assertEquals(if (sound.eventId.endsWith("main-fire")) .3f else .1f, sound.volume)
                assertTrue(sound.pitch in .93f..1.07f)
            }
        } finally { context.close() }
    }
}
