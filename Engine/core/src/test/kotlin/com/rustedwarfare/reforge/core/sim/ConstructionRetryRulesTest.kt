package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

/** Internal timing-rule evidence; these are not public delta-one clock trajectories. */
class ConstructionRetryRulesTest {
    @Test fun a_retry_requires_strictly_more_than_two_hundred_clock_units() {
        assertFalse(ConstructionRetryRules.isDue(2000, 2199))
        assertFalse(ConstructionRetryRules.isDue(2000, 2200))
        assertTrue(ConstructionRetryRules.isDue(2000, 2201))
    }

    @Test fun a_clock_rollback_requires_strictly_more_than_one_thousand_clock_units() {
        assertFalse(ConstructionRetryRules.isDue(2000, 1001))
        assertFalse(ConstructionRetryRules.isDue(2000, 1000))
        assertTrue(ConstructionRetryRules.isDue(2000, 999))
        assertFalse(ConstructionRetryRules.isDue(2000, 2000))
    }

    @Test fun the_existing_signed_integer_overflow_semantics_are_retained() {
        assertTrue(ConstructionRetryRules.isDue(Int.MAX_VALUE - 100, Int.MAX_VALUE - 100))
        assertTrue(ConstructionRetryRules.isDue(Int.MIN_VALUE + 500, Int.MIN_VALUE + 500))
        assertTrue(ConstructionRetryRules.isDue(Int.MAX_VALUE, Int.MIN_VALUE))
        assertTrue(ConstructionRetryRules.isDue(Int.MIN_VALUE, Int.MAX_VALUE))
        assertFalse(ConstructionRetryRules.isDue(Int.MIN_VALUE + 1000, Int.MIN_VALUE + 1000))
        assertFalse(ConstructionRetryRules.isDue(Int.MAX_VALUE - 200, Int.MAX_VALUE - 200))
    }
}
