package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ControlledScenarioTest {
    private val manifest = """
        format=rw-reforge.sandbox.v1
        p1.team=blue
        p1.x=110
        p1.y=110
        p1.heading=90
        p2.team=blue
        p2.x=510
        p2.y=310
        p2.heading=0
        p3.team=red
        p3.x=550
        p3.y=310
        p3.heading=0
    """.trimIndent()

    private fun fixture(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("reforge-scenario-test-")
        try {
            Files.createDirectories(root.resolve("maps"))
            Files.createDirectories(root.resolve("units"))
            DataOutputStream(Files.newOutputStream(root.resolve("atlas.png"))).use {
                it.writeLong(0x89504E470D0A1A0AuL.toLong())
                it.writeInt(13); it.writeInt(0x49484452); it.writeInt(20); it.writeInt(20)
            }
            Files.writeString(root.resolve("maps/p001-controlled.tmx"), """
                <map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">
                  <tileset firstgid="1" name="flat" tilewidth="20" tileheight="20" tilecount="1" columns="1">
                    <image source="../atlas.png" width="20" height="20"/>
                  </tileset>
                  <layer name="Ground" width="30" height="20"><data encoding="csv">${List(600) { "1" }.joinToString(",")}</data></layer>
                </map>
            """.trimIndent())
            Files.writeString(root.resolve("units/weaponless_tank.ini"), """
                [core]
                name: own_test_tank
                class: CustomUnitMetadata
                radius: 11
                displayRadius: 11
                fogOfWarSightRange: 3
                [graphics]
                total_frames: 1
                image: ../atlas.png
                [movement]
                movementType: LAND
                moveSpeed: 1.1
                moveAccelerationSpeed: 0.07
                moveDecelerationSpeed: 0.17
                maxTurnSpeed: 4.1
                turnAcceleration: 0.25
                moveSlidingMode: false
                moveIgnoringBody: false
            """.trimIndent())
            body(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun sc01_explicit_private_scenario_loads_three_owned_units_and_teams() = fixture { root ->
        Files.writeString(root.resolve("sandbox.properties"), manifest)
        val loaded = ControlledScenario.load(root)
        assertEquals("p002-knowledge", loaded.scenarioId)
        assertEquals(listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.NONE, "blue"),
            Player("p3", PlayerControl.NONE, "red")), loaded.players)
        assertEquals(listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0),
            UnitSpawn("u2", "p2", "tank", Position(510.0, 310.0), 0.0),
            UnitSpawn("u3", "p3", "tank", Position(550.0, 310.0), 0.0)), loaded.units)
        assertIs<InitResult.Accepted>(SimulationContext.initialize(loaded)).context.close()
    }

    @Test fun sc02_absent_manifest_preserves_the_original_two_player_fixture() = fixture { root ->
        val loaded = ControlledScenario.load(root)
        assertEquals("p001-windows", loaded.scenarioId)
        assertEquals(listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)), loaded.players)
        assertEquals(listOf("u1", "u2"), loaded.units.map { it.unitId })
        assertEquals(listOf(Position(110.0, 110.0), Position(510.0, 310.0)), loaded.units.map { it.position })
    }

    @Test fun sc03_bad_configuration_is_rejected_and_a_new_valid_load_recovers() = fixture { root ->
        val invalid = listOf(
            manifest.replace("format=rw-reforge.sandbox.v1", "format=other"),
            manifest.replace("p3.heading=0", ""),
            manifest + "\nunknown=1",
            manifest + "\np1.x=120",
            manifest + "\np1.\\u0078=120",
            manifest + "\np1.\\\nx=120",
            manifest.replace("p1.x=110", "p1.x=NaN"),
            manifest.replace("p2.y=310", "p2.y=Infinity"),
            manifest.replace("p3.heading=0", "p3.heading=-Infinity"),
            manifest.replace("p1.team=blue", "p1.team="),
            manifest.replace("p1.team=blue", "p1.team=\\u0020blue"),
            manifest.replace("p1.team=blue", "p1.team=blue "),
        )
        val file = root.resolve("sandbox.properties")
        for ((index, text) in invalid.withIndex()) {
            Files.writeString(file, text)
            val failure = assertFailsWith<IllegalArgumentException>("mutation $index") { ControlledScenario.load(root) }
            assertTrue(failure.message.orEmpty().contains("sandbox.properties"), "mutation $index must locate input")
        }
        Files.writeString(file, manifest)
        assertEquals(3, ControlledScenario.load(root).players.size)
    }

    @Test fun sc04_non_file_manifest_cannot_silently_fall_back_to_the_original_fixture() = fixture { root ->
        Files.createDirectory(root.resolve("sandbox.properties"))
        val failure = assertFailsWith<IllegalArgumentException> { ControlledScenario.load(root) }
        assertTrue(failure.message.orEmpty().contains("sandbox.properties"))
    }

    @Test fun sc08_linked_manifests_are_rejected_even_when_the_target_is_inside_the_input_root() = fixture { root ->
        val actual = Files.writeString(root.resolve("actual.properties"), manifest)
        Files.createSymbolicLink(root.resolve("sandbox.properties"), actual)
        val failure = assertFailsWith<IllegalArgumentException> { ControlledScenario.load(root) }
        assertTrue(failure.message.orEmpty().contains("sandbox.properties"))
        assertEquals(manifest, Files.readString(actual))
    }
}
