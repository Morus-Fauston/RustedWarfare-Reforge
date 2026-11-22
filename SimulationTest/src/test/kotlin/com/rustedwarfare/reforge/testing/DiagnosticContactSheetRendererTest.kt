package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class DiagnosticContactSheetRendererTest {
    @Test fun renders_contact_sheet_and_peak_congestion_zoom_from_projected_sequence() {
        val blocked = BooleanArray(20 * 15) { idx ->
            val x = idx % 20
            val y = idx / 20
            x == 0 || x == 19 || y == 0 || y == 14 || (x == 10 && y in 4..10)
        }
        val map = MapDefinition(20, 15, 20, 20, emptyList(), IntArray(300), BlockingGrid(20, 15, blocked))
        val tank = UnitDefinition(
            id = "c_tank", className = "CustomUnitMetadata", radius = 8.0, displayRadius = 8.0,
            totalFrames = 1, imagePath = java.nio.file.Path.of("unit.png"), imageScale = 1.0,
            movement = MovementProfile(MovementType.LAND, 1.0, 0.1, 0.1, 4.0, 0.25, false, false),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(8.0), mass = 3000.0
        )
        val defs = mapOf("c_tank" to tank)
        val goal = Position(320.0, 150.0)

        val observations = (0L..240L).map { step ->
            val u2Done = step >= 180L
            val events = buildList {
                if (step == 0L) {
                    add(Event("movement-started", "u1"))
                    add(Event("movement-started", "u2"))
                }
                if (step == 180L) add(Event("movement-arrived", "u2"))
            }
            Observation(
                sampleId = "s$step", worldId = "w0", scenarioId = "test-render",
                stepIndex = step, simulationTime = step / 60.0,
                units = listOf(
                    // u1 stalls against the rock wall at x=192
                    UnitObservation("u1", "p1", "c_tank", Position(192.0, 150.0), 90.0, 0.0, 0.0,
                        goal, listOf(goal), MovementState.BLOCKED),
                    // u2 drives smoothly around the top
                    UnitObservation("u2", "p2", "c_tank",
                        if (u2Done) Position(320.0, 60.0) else Position(80.0 + step * 1.3, 60.0),
                        90.0, if (u2Done) 0.0 else 1.3, 0.0,
                        if (u2Done) null else Position(320.0, 60.0),
                        if (u2Done) emptyList() else listOf(Position(320.0, 60.0)),
                        if (u2Done) MovementState.IDLE else MovementState.MOVING)
                ),
                events = events, actionResults = emptyList()
            )
        }

        val sequence = DiagnosticSceneProjector.projectSequence(observations, defs)
        assertTrue(sequence.frames[125].stalledCount >= 1)

        val tempDir = Files.createTempDirectory("rw-contact-sheet-test-")
        try {
            val contactSheetPath = tempDir.resolve("contact-sheet.png")
            val zoomPath = tempDir.resolve("peak-zoom.png")
            val artifacts = DiagnosticContactSheetRenderer.writeArtifacts(
                sequence = sequence,
                map = map,
                title = "Unit Render Smoke",
                contactSheetPath = contactSheetPath,
                peakZoomPath = zoomPath,
            )
            assertTrue(Files.isRegularFile(artifacts.contactSheetPath))
            assertTrue(Files.size(artifacts.contactSheetPath) > 2048L)
            val zoomFile = assertNotNull(artifacts.peakZoomPath)
            assertTrue(Files.isRegularFile(zoomFile))
            assertTrue(Files.size(zoomFile) > 1024L)

            val sheetImg = ImageIO.read(artifacts.contactSheetPath.toFile())
            assertTrue(sheetImg.width >= 600 && sheetImg.height >= 400)
            val zoomImg = ImageIO.read(zoomFile.toFile())
            assertTrue(zoomImg.width >= 400 && zoomImg.height >= 400)

            // Verify bright red stalled pixels exist in the peak zoom image
            var redPixels = 0
            for (y in 0 until zoomImg.height) {
                for (x in 0 until zoomImg.width) {
                    val rgb = zoomImg.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    if (r >= 210 && g <= 70 && b <= 70) redPixels++
                }
            }
            assertTrue(redPixels >= 20, "Peak zoom image must render bright red stalled unit highlight, found $redPixels red pixels")
        } finally {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}