package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class ConstructionQueueIdentityTest {
    @Test fun the_exact_queue_marker_preserves_the_complete_maintenance_scenario_and_all_other_input_bytes() {
        val input = copyInput()
        try {
            val beforeBytes = identities(input)
            val maintenance = ControlledScenario.load(input)
            Files.writeString(input.resolve("construction.properties"), marker("construction-queue"))
            val queue = ControlledScenario.load(input)
            val afterBytes = identities(input)
            assertEquals(beforeBytes.filterKeys { it != "construction.properties" }, afterBytes.filterKeys { it != "construction.properties" })
            assertNotEquals(beforeBytes["construction.properties"], afterBytes["construction.properties"])
            assertEquals(maintenance.map.widthTiles, queue.map.widthTiles)
            assertEquals(maintenance.map.heightTiles, queue.map.heightTiles)
            assertEquals(maintenance.map.tileWidth, queue.map.tileWidth)
            assertEquals(maintenance.map.tileHeight, queue.map.tileHeight)
            assertContentEquals(maintenance.map.groundGids, queue.map.groundGids)
            assertEquals(maintenance.map.tileSets.map { listOf(it.firstGid, it.name, it.tileWidth, it.tileHeight, it.imagePath,
                it.blockedTileIds, it.tileCount, it.columns, it.spacing, it.margin, it.transparentColor, it.resourcePoolTileIds) },
                queue.map.tileSets.map { listOf(it.firstGid, it.name, it.tileWidth, it.tileHeight, it.imagePath,
                    it.blockedTileIds, it.tileCount, it.columns, it.spacing, it.margin, it.transparentColor, it.resourcePoolTileIds) })
            for (row in 0 until maintenance.map.heightTiles) for (column in 0 until maintenance.map.widthTiles)
                assertEquals(maintenance.map.blocking.isBlocked(column, row), queue.map.blocking.isBlocked(column, row))
            assertEquals(maintenance.unitDefinitions.mapValues { definition(it.value) }, queue.unitDefinitions.mapValues { definition(it.value) })
            assertEquals(maintenance.maintenanceContract!!.basePrices, queue.maintenanceContract!!.basePrices)
            // Only reference-identity containers already compared in full are substituted here.
            assertEquals(maintenance.copy(map = queue.map, unitDefinitions = queue.unitDefinitions,
                maintenanceContract = queue.maintenanceContract), queue)
            assertEquals(listOf("h"), queue.units.filter { it.ownerPlayerId == "p1" }.map { it.unitId })
            assertEquals("commandCenter", queue.units.single { it.unitId == "h" }.typeId)
            assertEquals(1750L, queue.economyContract!!.initialBalances["p1"])
            assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1"), queue.unitDefinitions.getValue("builder").builder!!.buildingTypeIds)
            assertEquals(listOf("builder", "c_tank"), queue.unitDefinitions.getValue("landFactory").production!!.unitTypeIds)
            assertEquals(setOf("extractorT1", "extractorT2", "extractorT3", "fabricatorT1", "fabricatorT2", "fabricatorT3"),
                queue.unitDefinitions.keys.filter { it.startsWith("extractor") || it.startsWith("fabricator") }.toSet())
        } finally { input.toFile().deleteRecursively() }
    }

    @Test fun unknown_or_extra_queue_markers_and_changed_build_capabilities_remain_rejected() {
        val input = copyInput()
        try {
            val file = input.resolve("construction.properties")
            Files.writeString(file, marker("construction-queue"))
            assertNotNull(ControlledScenario.load(input).maintenanceContract)
            for (bad in listOf(marker("construction-queue-unknown"), marker("construction-queue") + "ticket=ENG-P006-006\n",
                marker("construction-queue") + "variant=construction-queue\n", marker("construction-queue").replace(".v1", ".v2"))) {
                Files.writeString(file, bad)
                assertFailsWith<IllegalStateException> { ControlledScenario.load(input) }
            }
            Files.writeString(file, marker("construction-queue"))
            val builder = input.resolve("units/builder.properties")
            val builderBytes = Files.readString(builder)
            Files.writeString(builder, builderBytes.replace("constructionTypes=extractorT1,landFactory,fabricatorT1", "constructionTypes=landFactory"))
            assertFails { ControlledScenario.load(input) }
            Files.writeString(builder, builderBytes)
            val factory = input.resolve("units/landFactory.properties")
            val factoryBytes = Files.readString(factory)
            Files.writeString(factory, factoryBytes.replace("productionUnits=builder,c_tank", "productionUnits=builder"))
            assertFails { ControlledScenario.load(input) }
            Files.writeString(factory, factoryBytes)
            assertNotNull(ControlledScenario.load(input).maintenanceContract)
            Files.writeString(file, marker("building-maintenance"))
            assertNotNull(ControlledScenario.load(input).maintenanceContract)
            Files.writeString(file, marker("economy-upgrades"))
            assertNull(ControlledScenario.load(input).maintenanceContract)
        } finally { input.toFile().deleteRecursively() }
    }

    private fun definition(unit: UnitDefinition): List<Any?> = listOf(unit.id, unit.className, unit.radius,
        unit.displayRadius, unit.totalFrames, unit.imagePath, unit.imageScale, unit.movement, unit.retainedUnconsumed,
        unit.fogOfWarSightRange, unit.combat, unit.durability, unit.building, unit.production, unit.build,
        unit.directHitTargetKind, unit.pointDefense, unit.upgrades?.actions, unit.income,
        unit.builder?.let { listOf(it.buildingTypeIds, it.range, it.preparation, it.decay, it.repairRate) }, unit.construction, unit.deathExplosion)

    private fun marker(variant: String) = "format=rw-reforge.construction-sandbox.v1\nvariant=$variant\n"
    private fun copyInput(): Path {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val source = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
        val target = Files.createTempDirectory("p006-queue-identity-")
        Files.walk(source).use { paths -> paths.forEach { path ->
            val dest = target.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
        } }
        return target
    }
    private fun identities(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).joinToString("") { byte -> "%02x".format(byte) } }
    }
}
