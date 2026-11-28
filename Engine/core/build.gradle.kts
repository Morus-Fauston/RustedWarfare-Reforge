plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.hjson:hjson:3.1.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "768m"
    jvmArgs("-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=64m")
    val configuredForks = providers.gradleProperty("core.test.maxParallelForks")
        .orElse(providers.systemProperty("core.test.maxParallelForks"))
        .map { it.toInt().coerceAtLeast(1) }
        .orElse((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8))
    maxParallelForks = configuredForks.get()
    val repoRoot = rootProject.projectDir.parentFile
    val privateP001 = repoRoot.resolve("TestLab/local/P001-移动沙盒输入")
    val fallbackP001 = repoRoot.resolve("SimulationTest/public/inputs/content-movement-knowledge/P001-移动沙盒输入")
    systemProperty(
        "p001.inputs.dir",
        System.getProperty("p001.inputs.dir")
            ?: (if (privateP001.isDirectory) privateP001 else fallbackP001).absolutePath,
    )
    systemProperty(
        "public.fixtures.dir",
        System.getProperty("public.fixtures.dir")
            ?: repoRoot.resolve("SimulationTest/public/inputs").absolutePath,
    )
    systemProperty(
        "repo.root",
        System.getProperty("repo.root") ?: repoRoot.absolutePath,
    )
}
