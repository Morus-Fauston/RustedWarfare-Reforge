package com.rustedwarfare.reforge.testing

import kotlin.test.Test

class NavalProductionArchiveHjsonTest {
    @Test fun normal_hjson_production_archive_preserves_and_compares_every_intermediate_gameplay_field() {
        verifyNavalProductionFormatArchive("hjson", javaClass)
    }
}
