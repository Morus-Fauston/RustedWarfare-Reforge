package com.rustedwarfare.reforge.testing

import kotlin.test.Test

class NavalProductionArchiveJsonTest {
    @Test fun normal_json_production_archive_preserves_and_compares_every_intermediate_gameplay_field() {
        verifyNavalProductionFormatArchive("json", javaClass)
    }
}
