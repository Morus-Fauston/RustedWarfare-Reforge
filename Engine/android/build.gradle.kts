import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("com.android.application")
    id("jvm-toolchains")
}

val gdxVersion = "1.13.1"
val gdxAndroidNatives by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
val nativeAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

abstract class ExtractGdxAndroidNatives : DefaultTask() {
    @get:InputFiles
    abstract val nativeJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        nativeJars.files.forEach { nativeJar ->
            val abi = abis.single { nativeJar.name.endsWith("natives-$it.jar") }
            project.copy {
                from(project.zipTree(nativeJar))
                include("*.so")
                into(outputDirectory.get().dir(abi))
            }
        }
    }
}

abstract class StageSandboxAssets : DefaultTask() {
    @get:Input
    val relativePaths: List<String>
        get() = buildList {
            for (phase in listOf("P001-移动沙盒输入", "P002-知识沙盒输入")) {
                for (name in listOf("maps/p001-controlled.tmx", "tilesets/p001-ground.tsx",
                    "tilesets/p001-rock.tsx", "units/weaponless_tank.ini")) {
                    add("TestLab/local/$phase/$name")
                }
            }
            add("TestLab/local/P002-知识沙盒输入/sandbox.properties")
            add("Rusted Warfare/assets/tilesets/bitmaps/longgrass.png")
            add("Rusted Warfare/assets/tilesets/bitmaps/mountain.png")
            add("Rusted Warfare/assets/units/tanks/tank.png")
        }

    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val repository = project.rootProject.projectDir.parentFile.toPath().toRealPath()
        val sources = relativePaths.map { repository.resolve(it) }.toSet()
        require(inputFiles.files.map { it.toPath().toAbsolutePath().normalize() }.toSet() == sources) {
            "Expected the exact twelve sandbox assets"
        }
        val root = outputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val files = sources.associate { source ->
            checkPath(source)
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Missing sandbox asset: $source" }
            root.resolve("p001").resolve(repository.relativize(source)) to Files.readAllBytes(source)
        }
        val directories = mutableSetOf(root)
        files.keys.forEach { target ->
            var parent = target.parent
            while (parent.startsWith(root)) {
                directories.add(parent)
                parent = parent.parent
            }
        }
        checkPath(root)
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkPath(directory)
                    require(directory in directories) { "Unknown sandbox directory: $directory" }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkPath(file)
                    require(attributes.isRegularFile && files[file]?.contentEquals(Files.readAllBytes(file)) == true) {
                        "Unknown or changed sandbox file: $file"
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        // Validate the complete existing tree before creating any missing file.
        files.forEach { (target, bytes) ->
            checkPath(target)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target.parent)
                checkPath(target)
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS).use { it.write(bytes) }
            }
        }
    }

    private fun checkPath(path: Path) {
        var component: Path? = path
        while (component != null) {
            if (Files.exists(component, LinkOption.NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(component, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink && !attributes.isOther && component.toRealPath() == component) {
                    "Linked or noncanonical sandbox path: $component"
                }
            }
            component = component.parent
        }
    }
}

android {
    namespace = "com.rustedwarfare.reforge.android"
    compileSdk = 34
    buildToolsVersion = "36.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.rustedwarfare.reforge.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.rustedwarfare.reforge.android.XmlSecurityInstrumentation"
    }

}

dependencies {
    implementation(project(":client"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    nativeAbis.forEach { abi ->
        add(gdxAndroidNatives.name, "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
    }
}

val extractGdxAndroidNatives by tasks.registering(ExtractGdxAndroidNatives::class) {
    nativeJars.from(gdxAndroidNatives)
    outputDirectory.set(layout.buildDirectory.dir("gdx-natives"))
}

// Private test assets only. Keep source bytes and relative references intact.
val stageSandboxAssets by tasks.registering(StageSandboxAssets::class) {
    inputFiles.from(relativePaths.map { rootProject.projectDir.parentFile.resolve(it) })
    outputDirectory.set(layout.buildDirectory.dir("sandbox-assets"))
    outputs.upToDateWhen { false }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageSandboxAssets, StageSandboxAssets::outputDirectory)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractGdxAndroidNatives,
            ExtractGdxAndroidNatives::outputDirectory,
        )
    }
}

// These existing main-method harnesses use real JDK filesystem/protocol adapters, not JUnit/Android mocks.
// Keep the four pure TEST probes out of MAIN; compile this finite host entry separately from androidTest.
val hostToolchains = extensions.getByType<JavaToolchainService>()
val compileSandboxHostChecks by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compile the existing pure-JDK Android asset and data-protection harnesses."
    source(fileTree("src/test/java"))
    source("src/main/java/com/rustedwarfare/reforge/android/SandboxAssetFiles.java")
    source(listOf("metadata/MetadataProbe.java", "metadata/MetadataJson.java",
        "ownruns/OwnRunsProbe.java", "ownruns/OwnRunsJson.java").map {
        "src/androidTest/java/com/rustedwarfare/reforge/android/$it"
    })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("sandbox-host-checks/classes"))
    javaCompiler.set(hostToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    options.release.set(17)
    options.encoding = "UTF-8"
}
val sandboxHostChecks = listOf(
    "checkSandboxAssetsHost" to "com.rustedwarfare.reforge.android.SandboxAssetFilesTest",
    "checkStorageMetadataHost" to "com.rustedwarfare.reforge.android.metadata.MetadataProbeHostTest",
    "checkOwnRunsHost" to "com.rustedwarfare.reforge.android.ownruns.OwnRunsProbeHostTest",
    "checkOwnRunsAbsenceHost" to "com.rustedwarfare.reforge.android.ownruns.OwnRunsDirectedAbsenceHostTest",
).map { (taskName, entry) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Run the existing pure-JDK harness $entry (no Android/device proof)."
        dependsOn(compileSandboxHostChecks)
        classpath = files(compileSandboxHostChecks.flatMap { it.destinationDirectory })
        mainClass.set(entry)
        javaLauncher.set(hostToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        maxHeapSize = "256m"
        args("all")
        if (entry.endsWith(".OwnRunsProbeHostTest")) {
            doFirst {
                // Existing real-filesystem harness requires a fresh system-Temp subtree and retains it.
                systemProperty("ownruns.fixtureDir", Files.createTempDirectory("s04-ownruns-gradle-").toString())
            }
        } else if (entry.endsWith(".SandboxAssetFilesTest")) {
            doFirst {
                val fixture = Files.createTempDirectory("s04-assets-gradle-")
                setArgs(listOf(fixture.toString()))
                logger.lifecycle("Sandbox asset host fixture: $fixture")
            }
        }
    }
}
tasks.register("verifySandboxHostChecks") {
    group = "verification"
    description = "Run all four supported main-method Android host harnesses; not a JUnit/device result."
    dependsOn(sandboxHostChecks)
}
