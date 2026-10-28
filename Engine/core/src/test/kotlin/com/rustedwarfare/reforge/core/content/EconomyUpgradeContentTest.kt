package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class EconomyUpgradeContentTest {
    private fun source(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-004/20261028-implementation-01/input/units")

    @Test fun a_windows_root_relative_image_cannot_escape_the_economic_content_directory() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val temporary = Files.createTempDirectory("economic-root-reference-")
        try {
            val content = Files.createDirectory(temporary.resolve("units"))
            Files.list(source()).use { files -> files.forEach { Files.copy(it, content.resolve(it.fileName)) } }
            val outside = temporary.resolve("outside.png")
            Files.copy(source().resolve("extractor_t2.png"), outside)
            val input = content.resolve("extractorT2.ini")
            val rootRelative = outside.toString().substring(2)
            Files.writeString(input, Files.readString(input).replace("extractor_t2.png", rootRelative))
            val result = ContentLoader.loadEconomyUnit(input, true)
            assertEquals(ContentErrorCode.BAD_REFERENCE, assertIs<ContentResult.Failure>(result).error.code)
        } finally {
            Files.walk(temporary).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun upgraded_content_rejects_broken_inheritance_unknown_actions_and_bad_images() {
        val temporary = Files.createTempDirectory("economic-invalid-content-")
        try {
            fun fixture(name: String): Path {
                val dir = Files.createDirectory(temporary.resolve(name))
                Files.list(source()).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
                return dir
            }
            val changes = listOf<Pair<String, (String) -> String>>(
                "fabricatorT2.ini" to { it.replace("@copyFrom_skipThisSection:true", "") },
                "fabricatorT3.ini" to { it.replace("@copyFrom_skipThisSection:true", "@copyFrom_skipThisSection:false") },
                "fabricatorT3.ini" to { it.replace("copyFrom: fabricatorT1.ini", "copyFrom: fabricatorT2.ini") },
                "extractorT2.ini" to { it.replace("convertTo: extractorT3", "convertTo: fabricatorT3") },
                "extractorT3.ini" to { it + "\n[action_unknown]\nprice: 0\n" },
                "fabricatorT2.ini" to { it + "\n[graphics]\nimage: fabricatorT2.png\n" },
                "extractorT2.ini" to { it.replace("extractor_t2.png", "../outside.png") },
                "extractorT2.ini" to { it.replace("extractor_t2.png", "missing.png") },
                "fabricatorT2.ini" to { it.replace("maxHp: 1000", "maxHp: NaN") },
                "extractorT2.ini" to { it.replace("buildSpeed: 0.0003", "buildSpeed: NaN") },
                "extractorT2.ini" to { it.replace("buildSpeed: 0.0003", "") },
                "extractorT2.ini" to { it.replace("maxHp: 1000", "maxHp: 1000\nmaxHp: 1000") },
            )
            for ((index, change) in changes.withIndex()) {
                val input = fixture("case-$index").resolve(change.first)
                val before = Files.readString(input)
                val after = change.second(before)
                assertNotEquals(before, after, "Mutation $index must affect its input")
                Files.writeString(input, after)
                assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(input, true), "Mutation $index")
            }
            val dimensions = fixture("wrong-dimensions")
            Files.copy(source().resolve("fabricatorT2.png"), dimensions.resolve("extractor_t2.png"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(ContentErrorCode.BAD_REFERENCE, assertIs<ContentResult.Failure>(
                ContentLoader.loadEconomyUnit(dimensions.resolve("extractorT2.ini"), true)).error.code)
            val linked = fixture("linked-image")
            Files.delete(linked.resolve("extractor_t2.png"))
            Files.createSymbolicLink(linked.resolve("extractor_t2.png"), source().resolve("extractor_t2.png"))
            assertEquals(ContentErrorCode.BAD_REFERENCE, assertIs<ContentResult.Failure>(
                ContentLoader.loadEconomyUnit(linked.resolve("extractorT2.ini"), true)).error.code)
            for ((index, file) in listOf("extractorT2.ini", "extractorT3.ini", "fabricatorT2.ini", "fabricatorT3.ini").withIndex()) {
                val dir = fixture("image-$index")
                val image = if (index < 2) "extractor_t${index + 2}.png" else "fabricatorT${index}.png"
                Files.write(dir.resolve(image), byteArrayOf(1, 2, 3))
                assertEquals(ContentErrorCode.BAD_REFERENCE,
                    assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(dir.resolve(file), true)).error.code)
            }
            val missing = fixture("missing-parent")
            Files.delete(missing.resolve("fabricatorT1.ini"))
            assertEquals(ContentErrorCode.BAD_REFERENCE,
                assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(missing.resolve("fabricatorT3.ini"), true)).error.code)
        } finally {
            Files.walk(temporary).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun explicit_loading_opens_only_the_two_adjacent_ordinary_economic_chains() {
        data class Expected(val file: String, val id: String, val hp: Double, val price: Long,
                            val income: Float, val action: UpgradeActionProfile?, val explosion: Float?)
        val rows = listOf(
            Expected("extractor", "extractorT1", 800.0, 700L, 8f,
                UpgradeActionProfile("upgrade_t2", "extractorT2", 1400L, .0006f), null),
            Expected("extractorT2", "extractorT2", 1000.0, 2100L, 12f,
                UpgradeActionProfile("upgrade_t3", "extractorT3", 4000L, .0003f), null),
            Expected("extractorT3", "extractorT3", 2000.0, 6100L, 20f, null, null),
            Expected("fabricatorT1", "fabricatorT1", 500.0, 2200L, 2f,
                UpgradeActionProfile("upgrade_t2", "fabricatorT2", 4400L, 1f / (65.6f * 60f)), 350f),
            Expected("fabricatorT2", "fabricatorT2", 1000.0, 6600L, 7f,
                UpgradeActionProfile("upgrade_t3", "fabricatorT3", 7500L, 1f / (93.3f * 60f)), 450f),
            Expected("fabricatorT3", "fabricatorT3", 2200.0, 14100L, 14f, null, 850f),
        )
        for (row in rows) {
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadEconomyUnit(source().resolve("${row.file}.ini"), true), row.id).value
            assertEquals(row.id, unit.id)
            assertEquals(row.hp, unit.maximumHealth)
            assertEquals(row.price, unit.construction?.price)
            assertEquals(IncomeProfile(40f, row.income), unit.income)
            assertEquals(listOfNotNull(row.action), unit.upgrades?.actions.orEmpty())
            assertEquals(row.explosion, unit.deathExplosion?.damage)
            assertTrue(unit.upgrades?.actions.orEmpty().none { it.targetTypeId.contains("overclock") || it.targetTypeId.contains("reinforce") })
        }
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadEconomyUnit(source().resolve("extractor.ini"))).value
        assertNull(legacy.upgrades)
        assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(source().resolve("extractorT2.ini")))
        assertNull(assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadEconomyUnit(source().resolve("fabricatorT1.ini"))).value.upgrades)
        assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(source().resolve("fabricatorT2.ini")))
    }
}
