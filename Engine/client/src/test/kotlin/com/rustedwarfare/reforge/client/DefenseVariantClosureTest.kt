package com.rustedwarfare.reforge.client

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseVariantClosureTest {
    @Test fun missing_defense_targets_and_mixed_variant_builder_versions_fail_without_fallback_while_the_old_queue_variant_still_loads() {
        val source = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val temporary = Files.createTempDirectory("defense-variant-closure-")
        fun fixture(name: String): Path {
            val root = temporary.resolve(name)
            Files.walk(source).use { paths -> paths.forEach { file ->
                val dest = root.resolve(source.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(dest) else Files.copy(file, dest)
            } }
            return root
        }
        fun oldBuilder(root: Path) {
            val file = root.resolve("units/builder.properties")
            Files.writeString(file, Files.readString(file).replace("native-builder.v4", "native-builder.v3")
                .replace("extractorT1,landFactory,fabricatorT1,c_turret_t1,laserDefence", "extractorT1,landFactory,fabricatorT1"))
        }
        try {
            for ((index, missing) in listOf("units/turret_t2_gun.ini", "units/turret_t1_artillery.ini", "units/laserDefence.properties").withIndex()) {
                val root = fixture("missing-$index")
                val deleted = root.resolve(missing).toAbsolutePath().normalize()
                assertTrue(deleted.startsWith(temporary.toAbsolutePath().normalize()))
                Files.delete(deleted)
                val failure = assertFailsWith<IllegalStateException> { ControlledScenario.load(root) }
                assertTrue(failure.message.orEmpty().contains(Path.of(missing).fileName.toString()))
            }
            val v3InDefense = fixture("v3-in-defense")
            oldBuilder(v3InDefense)
            assertTrue(assertFailsWith<IllegalArgumentException> { ControlledScenario.load(v3InDefense) }
                .message.orEmpty().contains("construction capabilities must match the fixed variant"))
            val v4InQueue = fixture("v4-in-queue")
            Files.writeString(v4InQueue.resolve("construction.properties"), "format=rw-reforge.construction-sandbox.v1\nvariant=construction-queue\n")
            assertTrue(assertFailsWith<IllegalArgumentException> { ControlledScenario.load(v4InQueue) }
                .message.orEmpty().contains("construction capabilities must match the fixed variant"))
            oldBuilder(v4InQueue)
            val old = ControlledScenario.load(v4InQueue)
            assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1"), old.unitDefinitions.getValue("builder").builder!!.buildingTypeIds)
            assertTrue(listOf("c_turret_t1", "c_turret_t2_gun", "c_turret_t1_artillery", "laserDefence").none { it in old.unitDefinitions })
            val unknown = fixture("unknown-marker")
            Files.writeString(unknown.resolve("construction.properties"), "format=rw-reforge.construction-sandbox.v1\nvariant=defense-construction\nunknown=1\n")
            assertTrue(assertFailsWith<IllegalStateException> { ControlledScenario.load(unknown) }
                .message.orEmpty().contains("unsupported fixed construction fixture"))
        } finally { temporary.toFile().deleteRecursively() }
    }
}
