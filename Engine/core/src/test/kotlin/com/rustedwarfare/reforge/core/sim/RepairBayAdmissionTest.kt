package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class RepairBayAdmissionTest {
    @Test fun host_definitions_cannot_admit_a_negative_explicit_reclaim_price() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/ini/production-package"))).value
        val worker = pack.units.getValue("builder")
        val forged = UnitDefinition(worker.id, worker.className, worker.radius, worker.displayRadius, worker.totalFrames,
            worker.imagePath, worker.imageScale, worker.movement, worker.retainedUnconsumed, worker.fogOfWarSightRange,
            durability = worker.durability, build = worker.build, builder = worker.builder, reclaimPrice = -1L)
        val base = CombatTestContent.scenario()
        val input = base.copy(unitDefinitions = pack.units + ("builder" to forged),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
        when (val result = SimulationContext.initialize(input)) {
            is InitResult.Accepted -> { result.context.close(); fail("Negative reclaim price must not enter a host world") }
            is InitResult.Rejected -> assertEquals("invalid-reclaim-price", result.error.code)
        }
    }

    @Test fun the_native_work_marker_cannot_turn_an_ordinary_mobile_builder_into_a_repair_bay() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/ini/production-package"))).value
        val worker = pack.units.getValue("builder")
        val forged = UnitDefinition(worker.id, worker.className, worker.radius, worker.displayRadius, worker.totalFrames,
            worker.imagePath, worker.imageScale, worker.movement, worker.retainedUnconsumed, worker.fogOfWarSightRange,
            durability = worker.durability, build = worker.build, builder = worker.builder,
            maintenance = MaintenanceProfile.NATIVE_REPAIR_BAY)
        val base = CombatTestContent.scenario()
        val input = base.copy(unitDefinitions = pack.units + ("builder" to forged),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
        when (val result = SimulationContext.initialize(input)) {
            is InitResult.Accepted -> { result.context.close(); fail("A mobile worker must not inherit the native bay capability") }
            is InitResult.Rejected -> assertEquals("unsupported-repair-bay", result.error.code)
        }
    }
}
