package com.rustedwarfare.reforge.client

import kotlin.test.Test

class PhaseFiveMixedSandboxTest {
    @Test fun all_five_normally_produced_representatives_move_stop_and_fire_in_the_paired_mixed_target_world_mixed() {
        verifyPhaseFiveRepresentativesMoveStopAndFire("mixed")
    }

    @Test fun the_paired_ordinary_chain_constructs_and_upgrades_four_factories_then_keeps_all_five_products_in_one_world_mixed() {
        verifyPhaseFiveOrdinaryChainConstructsAndUpgrades("mixed")
    }

    @Test fun the_pack_starts_with_a_center_and_normally_produces_the_builder_mixed() {
        verifyPhaseFiveCenterStartsAndProducesBuilder("mixed")
    }
}
