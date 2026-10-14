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

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractGdxAndroidNatives,
            ExtractGdxAndroidNatives::outputDirectory,
        )
    }
}
