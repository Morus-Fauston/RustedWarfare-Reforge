package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class SandboxProjectileDeploymentTest {
    private fun scene(nonDeflectable: Boolean = false): Scenario {
        val input = PointDefenseTestContent.scene(nonDeflectable = nonDeflectable)
        val shooter = input.unitDefinitions.getValue("shooter")
        val closeRange = PointDefenseTestContent.variant(shooter, combat = shooter.combat!!.copy(attackRange = 10.0))
        return input.copy(unitDefinitions = input.unitDefinitions + (shooter.id to closeRange))
    }

    @Test fun development_queues_a_defined_shot_and_preserves_its_real_interception() {
        val scene = scene()
        OfflineSandboxHost(scene, deploymentEnabled = true, generation = 5).use { host ->
            val batch = SandboxCommand.ProjectileBatch(listOf(
                SandboxCommand.ProjectileDeployment("incoming", "s", "t", null)))
            val request = SandboxCommandRequest(1, host.context.world, 5, batch)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request).status)
            assertEquals("stale-world", host.submit(request.copy(generation = 4)).code)
            val born = host.advance(Step(0, 1.0, "sim-tick"))
            val applied = assertNotNull(host.receipt(1))
            assertEquals(SandboxCommandStatus.APPLIED, applied.status)
            assertEquals(0L, applied.stepIndex)
            val shotId = applied.projectileIds.getValue("incoming")
            val event = born.projectileEvents.single { it.kind == "born" && it.projectile.objectId == shotId }
            assertEquals("s", event.projectile.sourceUnitId)
            assertEquals("t", event.projectile.targetUnitId)
            assertEquals(10000.0, event.projectile.remainingLife)
            val steps = (1L..12L).map { host.advance(Step(it, 1.0, "sim-tick")) }
            val sameShot = steps.flatMap { it.projectileEvents }.filter { it.projectile.objectId == shotId }
            assertTrue(sameShot.any { it.kind == "deflected" && it.interceptorUnitId == "d" })
            assertTrue(steps.first().projectiles.single { it.objectId == shotId }.position != event.projectile.position)
            assertEquals(applied, host.submit(request))
            assertFalse(steps.last().projectiles.any { it.objectId == shotId })
        }
    }

    @Test fun partial_rejections_make_no_projectiles_and_ordinary_worlds_deny_deployment() {
        val scene = scene(nonDeflectable = true)
        val good = SandboxCommand.ProjectileDeployment("valid", "s", "t", null)
        val bad = SandboxCommand.ProjectileDeployment("invalid", "s", "missing", null)
        OfflineSandboxHost(scene).use { host ->
            val command = SandboxCommandRequest(1, host.context.world, 1, SandboxCommand.ProjectileBatch(listOf(good)))
            assertEquals("deployment-disabled", host.submit(command).code)
        }
        OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
            val command = SandboxCommandRequest(1, host.context.world, 1, SandboxCommand.ProjectileBatch(listOf(good,bad)))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(command).status)
            val rejected = host.advance(Step(0,1.0,"sim-tick"))
            val receipt = assertNotNull(host.receipt(1))
            assertEquals("unknown-projectile-target", receipt.code)
            assertEquals(1, receipt.failedEntry)
            assertTrue(receipt.projectileIds.isEmpty())
            assertTrue(rejected.projectileEvents.none { it.kind == "born" })
            assertEquals(receipt, host.submit(command))

            val accepted = SandboxCommandRequest(2, host.context.world, 1, SandboxCommand.ProjectileBatch(listOf(good)))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(accepted).status)
            val rows = (1L..12L).map { host.advance(Step(it,1.0,"sim-tick")) }
            val id = checkNotNull(host.receipt(2)).projectileIds.getValue("valid")
            assertEquals(11L, id)
            assertTrue(rows.any { r -> r.projectileEvents.any { it.kind == "born" && it.projectile.objectId == id } })
            assertTrue(rows.none { r -> r.projectileEvents.any { it.kind == "deflected" && it.projectile.objectId == id } })
        }
    }
}
