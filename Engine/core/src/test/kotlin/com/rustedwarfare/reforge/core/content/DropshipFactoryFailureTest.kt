package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DropshipFactoryFailureTest {
    @TempDir lateinit var temporary: Path

    @Test fun the_air_factory_rejects_tier_one_transport_duplicates_and_unknown_products_in_each_format() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("air-transport-production/$format/production-package")
            val root = temporary.resolve(format)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = root.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            for ((factory, old, replacement) in listOf(
                Triple("airFactory", "helicopter", "dropship"),
                Triple("airFactoryT2", "helicopter,amphibiousJet,dropship", "dropship,dropship"),
                Triple("airFactoryT2", "helicopter,amphibiousJet,dropship", "dropship,unknown"))) {
                val input = root.resolve("units/$factory.$format")
                val original = Files.readString(input)
                val modified = if (format == "ini") original.replace("productionUnits=$old", "productionUnits=$replacement")
                    else original.replace("\"productionUnits\": \"$old\"", "\"productionUnits\": \"$replacement\"")
                assertNotEquals(original, modified)
                Files.writeString(input, modified)
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)))
                assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, failure.error.code)
                assertEquals(input.toAbsolutePath().normalize().toString(), failure.error.file)
                assertEquals("productionUnits", failure.error.field)
                Files.writeString(input, original)
                assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)))
            }
        }
    }
}
