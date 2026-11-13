package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class NuclearPresentationTest {
    @Test fun intercepted_nuclear_round_has_a_distinct_airborne_effect_and_sound() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nuclear-silo/ini")
        val content = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(root.resolve("interception-package"))).value
        val base = contentPackScenario(root, ContentPackChoice(content, "commandCenter"))
        val builder = content.units.getValue("builder")
        val witness = UnitDefinition("witness", builder.className, builder.radius, builder.displayRadius,
            builder.totalFrames, builder.imagePath, builder.imageScale, builder.movement, emptySet(), 3)
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) } +
                Player("p3", PlayerControl.LOCAL, "observer"),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            unitDefinitions = base.unitDefinitions + (witness.id to witness),
            units = listOf(UnitSpawn("silo", "p2", "nukeLauncherC", Position(180.0, 180.0), 90.0),
                UnitSpawn("defense", "p1", "antiNukeLauncherC", Position(280.0, 280.0), 90.0),
                UnitSpawn("observer", "p3", witness.id, Position(345.0, 185.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "defense" to 2L, "observer" to 3L), 4L))
        val core = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene.copy(
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 11000L, "p3" to 0L))))).context
        try {
            val presentation = NuclearPresentation()
            val witnessPresentation = NuclearPresentation()
            val listener = SoundListener(Position(345.0, 185.0), 300f)
            val launch = ResourceId.parse("reforge:events/nuke-launch")
            val intercept = ResourceId.parse("reforge:events/nuke-intercept")
            val launchSound = ResourceId.parse("owned:sounds/test-launch")
            val interceptSound = ResourceId.parse("owned:sounds/test-intercept")
            val sound = PreparedResourceSound(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                .normalize().resolve("launch.wav"), 1, 22050, 10, 64, "a".repeat(64))
            val resources = ResolvedResourceSounds(emptyList(), mapOf(launchSound to sound,
                interceptSound to sound.copy(path = sound.path.resolveSibling("intercept.wav"))),
                mapOf(launch to ResourceSoundEvent(launchSound), intercept to ResourceSoundEvent(interceptSound)),
                emptyMap(), emptyMap())
            val calls = mutableListOf<Pair<Path, Float>>()
            val factory = PlaybackSoundFactory { prepared ->
                object : PlaybackSound {
                    override fun play(volume: Float, pitch: Float): Long {
                        calls += prepared.path to volume
                        return calls.size.toLong()
                    }
                    override fun stop() = Unit
                    override fun close() = Unit
                }
            }
            assertIs<ActionResult.Accepted>(core.submit(UnitAction("stock", "p1", "defense", "buildAntiNuke", core.world)))
            assertIs<ActionResult.Accepted>(core.submit(UnitAction("arm", "p2", "silo", "buildNuke", core.world)))
            var step = 0L
            repeat(3334) { core.advance(Step(step++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(core.submit(UnitAction("send", "p2", "silo", "launchNuke", core.world,
                target = Position(440.0, 180.0))))
            val effects = mutableSetOf<String>()
            val sounds = mutableSetOf<Pair<String, Float>>()
            var doorsOpened = false
            var sawNuke = false
            var witnessInterceptions = 0
            SoundPlayback(core.consumeSounds(core.world, "p3", listener), resources, factory).use { playback ->
                repeat(750) {
                    val observation = core.advance(Step(step++, 1.0, "sim-tick"))
                    val visible = core.observePlayer(core.world, "p1")
                    effects += presentation.project(visible).map { it.kind.name }
                    sounds += observation.soundTriggers.map { it.eventId to it.volume }
                    doorsOpened = doorsOpened || visible.units.single { it.unitId == "defense" }.nuclearDoors!! > 0f
                    val other = core.observePlayer(core.world, "p3")
                    assertTrue(other.units.none { it.unitId in setOf("defense", "silo") })
                    sawNuke = sawNuke || other.projectiles.any { it.appearance == ProjectileAppearance.NUCLEAR }
                    val drawn = witnessPresentation.project(other)
                    assertTrue(drawn.none { it.kind == NuclearEffectKind.EXPLOSION })
                    assertTrue(drawn.count { it.kind == NuclearEffectKind.INTERCEPTION } <= 1)
                    for (event in other.projectileEvents.filter { it.kind == "intercepted" }) {
                        witnessInterceptions++
                        assertNull(event.interceptorUnitId)
                        assertNull(event.sourceUnitId)
                        assertNull(event.sourceWeaponName)
                        val cell = com.rustedwarfare.reforge.core.knowledge.VisionCell(
                            (event.position.x / scene.map.tileWidth).toInt(), (event.position.y / scene.map.tileHeight).toInt())
                        assertTrue(cell in core.visionSnapshot(core.world, "p3").effectiveCells)
                        assertEquals(1, drawn.count { it.kind == NuclearEffectKind.INTERCEPTION })
                        assertEquals(drawn, witnessPresentation.project(other))
                    }
                    playback.beginFrame()
                    playback.consume(core.consumeSounds(core.world, "p3", listener))
                }
            }
            assertTrue(sawNuke)
            assertEquals(1, witnessInterceptions)
            assertEquals(1, calls.count { it == (sound.path to .15f) })
            assertEquals(listOf(.8f), calls.filter { it.first == sound.path.resolveSibling("intercept.wav") }.map { it.second })
            assertTrue("INTERCEPTION" in effects)
            assertFalse("EXPLOSION" in effects)
            assertTrue("reforge:events/nuke-launch" to .15f in sounds)
            assertTrue("reforge:events/nuke-intercept" to .8f in sounds)
            assertTrue(doorsOpened)
        } finally {
            core.close()
        }
    }

    @Test fun real_launch_retains_visible_trails_and_flashes_once_then_clears_every_effect_on_reopen() {
        val scene=NuclearSandboxScene.scenario()
        val c=assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val presentation=NuclearPresentation()
            fun project()=presentation.project(c.observePlayer(c.world,"p1"))
            assertTrue(project().isEmpty())
            var step=0L
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("build","p1","silo","buildNuke",c.world)))
            repeat(3334) { c.advance(Step(step++,1.0,"sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch","p1","silo","launchNuke",c.world,target=Position(520.0,180.0))))
            val kinds=mutableSetOf<NuclearEffectKind>();val sounds=mutableSetOf<String>()
            repeat(750) {
                c.advance(Step(step++,1.0,"sim-tick"))
                val effects=project()
                kinds+=effects.map { it.kind }
                sounds+=c.observe().soundTriggers.map { it.eventId }
                assertEquals(effects,project(),"Repeated draws must not emit duplicate particles or flashes")
                assertTrue(effects.all { it.radius>0 && it.alpha in 0f..1f && it.height.isFinite() })
            }
            assertEquals(setOf(NuclearEffectKind.TRAIL,NuclearEffectKind.LAUNCH,NuclearEffectKind.EXPLOSION),kinds)
            assertTrue(sounds.containsAll(setOf("reforge:events/nuke-launch","reforge:events/nuke-explode")))
            c.close();assertIs<InitResult.Accepted>(c.reopen(scene))
            assertTrue(project().isEmpty())
        } finally { c.close() }
    }
}
