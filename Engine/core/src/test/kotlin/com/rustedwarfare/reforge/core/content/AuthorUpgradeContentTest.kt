package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AuthorUpgradeContentTest {
    @Test fun three_formats_reject_partial_invalid_and_incompatible_upgrade_definitions_then_reload_the_repaired_input() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("author-upgrade/$format/package")
            val root = Files.createTempDirectory("rwr-author-upgrade-invalid-").toAbsolutePath().normalize()
            try {
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val target = root.resolve(source.relativize(file)).normalize()
                    require(target.startsWith(root))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                val input = root.resolve("units/spfactory.$format")
                val original = Files.readString(input)
                val price = if (format == "ini") "upgradePrice=2100" else "\"upgradePrice\": 2100"
                val rate = if (format == "ini") "upgradeBuildSpeed=0.004" else "\"upgradeBuildSpeed\": 0.004"
                val target = if (format == "ini") "upgradeTo=spfactoryt2" else "\"upgradeTo\": \"spfactoryt2\""
                fun field(name: String, value: String) = if (format == "ini") "$name=$value" else "\"$name\": $value"
                val badInputs = listOf(
                    original.replace(price, field("upgradePrice", "-1")),
                    original.replace(price, field("upgradePrice", "2100.5")),
                    original.replace(price, field("upgradePrice", "9223372036854775808")),
                    original.replace(rate, field("upgradeBuildSpeed", "0")),
                    original.replace(rate, field("upgradeBuildSpeed", if (format == "ini") "NaN" else "\"NaN\"")),
                    original.replace(rate, field("upgradeBuildSpeed", "1e300")),
                    original.replace(target, field("upgradeTo", if (format == "ini") "missing" else "\"missing\"")),
                    original.replace(target, field("upgradeTo", if (format == "ini") "spfactory" else "\"spfactory\"")),
                    original.replace(target, field("upgradeTo", if (format == "ini") "builder" else "\"builder\"")),
                    original.replace(target, field("upgradeTo", if (format == "ini") "spfactoryt2,builder" else "\"spfactoryt2,builder\"")),
                    original.replace("upgradeTo", "unknownUpgradeField"),
                )
                for ((index, bad) in badInputs.withIndex()) {
                    assertNotEquals(original, bad, "$format mutation $index must change its input")
                    Files.writeString(input, bad)
                    assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)), "$format mutation $index")
                    Files.writeString(input, original)
                    assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)), "$format repaired $index")
                }
                val completed = root.resolve("units/spfactoryt2.$format")
                val completedOriginal = Files.readString(completed)
                val radius = if (format == "ini") "\nradius=29\n" else ",\n\"radius\":29\n"
                val incompatible = if (format == "ini") completedOriginal + radius
                    else completedOriginal.trimEnd().dropLast(1) + radius + "}"
                Files.writeString(completed, incompatible)
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)), "$format changed target collision radius")
                Files.writeString(completed, completedOriginal)
                assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)))
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file ->
                    require(file.toAbsolutePath().normalize().startsWith(root))
                    Files.deleteIfExists(file)
                } }
            }
        }
    }
}
