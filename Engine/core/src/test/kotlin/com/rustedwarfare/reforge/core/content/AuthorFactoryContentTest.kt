package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AuthorFactoryContentTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun invalid_products_exits_and_inherited_values_fail_and_a_repaired_three_format_package_loads_again() {
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("author-production/$format/package")
            val root = Files.createTempDirectory("rwr-author-factory-content-").toAbsolutePath().normalize()
            try {
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val target = root.resolve(source.relativize(file)).normalize()
                    require(target.startsWith(root))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                val child = root.resolve("units/spfactory.$format")
                val parent = root.resolve("units/factory-base.ini")
                val text = Files.readString(child)
                val body = Files.readString(parent)
                val missingExit = if (format == "ini") text.replace("exitProfile=NATIVE_LAND", "dont_load=false")
                    else text.replace("exitProfile", "dont_load").replace("\"NATIVE_LAND\"", "false")
                for ((file, invalid) in listOf(
                    child to text.replace("builder,c_tank", "builder,builder"),
                    child to text.replace("builder,c_tank", "builder,missing"),
                    child to text.replace("builder,c_tank", ""),
                    child to text.replace("builder,c_tank", "spfactory"),
                    child to text.replace("NATIVE_LAND", "TELEPORT"),
                    child to text.replace("NATIVE_LAND", "NATIVE_AIR"),
                    child to text.replace("NATIVE_LAND", "NATIVE_SEA"),
                    child to missingExit,
                    parent to body.replace("foundationMovementType=LAND", "foundationMovementType=WATER"),
                    parent to body.replace("buildSpeed=0.001", "buildSpeed=NaN"),
                    parent to body.replace("price=700", "price=-1"),
                    parent to body.replace("image=workshop.png", "image=../../outside.png"))) {
                    val original = Files.readString(file)
                    assertNotEquals(original, invalid)
                    Files.writeString(file, invalid)
                    assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)), "$format $invalid")
                    Files.writeString(file, original)
                    val repaired = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)))
                    assertEquals(listOf("builder", "c_tank"), repaired.value.units.getValue("spfactory").production!!.unitTypeIds)
                }
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file ->
                    require(file.toAbsolutePath().normalize().startsWith(root))
                    Files.deleteIfExists(file)
                } }
            }
        }
    }
}
