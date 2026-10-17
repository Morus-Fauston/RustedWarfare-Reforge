import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
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
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        require(inputFiles.files.size == 7) { "Missing P001 test assets" }
        val repository = project.rootProject.projectDir.parentFile
        inputFiles.files.forEach { source ->
            val target = outputDirectory.file("p001/" + source.relativeTo(repository).invariantSeparatorsPath).get().asFile
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
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

// Private P001 test assets only. Keep source bytes and relative references intact.
val stageSandboxAssets by tasks.registering(StageSandboxAssets::class) {
    inputFiles.from(fileTree(rootProject.projectDir.parentFile) {
        include("TestLab/local/P001-移动沙盒输入/maps/p001-controlled.tmx")
        include("TestLab/local/P001-移动沙盒输入/tilesets/*.tsx")
        include("TestLab/local/P001-移动沙盒输入/units/weaponless_tank.ini")
        include("Rusted Warfare/assets/tilesets/bitmaps/longgrass.png")
        include("Rusted Warfare/assets/tilesets/bitmaps/mountain.png")
        include("Rusted Warfare/assets/units/tanks/tank.png")
    })
    outputDirectory.set(layout.buildDirectory.dir("sandbox-assets"))
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
