package com.rustedwarfare.reforge.core.content

import kotlin.test.*

class OrdinaryUpgradeContentTest {
    @Test fun fixed_relations_keep_economic_prices_source_times_and_explicit_base_identity() {
        assertEquals(UpgradeActionProfile("upgrade_t2", "extractorT2", 1400L, .0006f),
            OrdinaryUpgradeContent.economic.getValue("extractorT1").action)
        assertEquals(UpgradeActionProfile("upgrade_t3", "fabricatorT3", 7500L, 1f / (93.3f * 60f)),
            OrdinaryUpgradeContent.economic.getValue("fabricatorT2").action)
        assertEquals("65.6s", OrdinaryUpgradeContent.economic.getValue("fabricatorT1").sourceSpeed)
        assertEquals("extractorT1", OrdinaryUpgradeContent.baseTypeId("extractorT3"))
        assertEquals("fabricatorT1", OrdinaryUpgradeContent.constructionBaseTypeId("fabricatorT2"))
        assertEquals("c_turret_t1", OrdinaryUpgradeContent.baseTypeId("c_turret_t1_artillery"))
        assertNull(OrdinaryUpgradeContent.constructionBaseTypeId("c_turret_t1_artillery"))
        assertNull(OrdinaryUpgradeContent.baseTypeId("extractorT3_overclocked"))
        assertEquals("c_turret_t2_gun", OrdinaryUpgradeContent.turret.getValue("upgrade_gun").targetTypeId)
        assertTrue(OrdinaryUpgradeContent.turret.getValue("upgrade_artillery").groundProjectile)
        assertFalse(OrdinaryUpgradeContent.turret.getValue("upgrade_gun").groundProjectile)
    }
}
