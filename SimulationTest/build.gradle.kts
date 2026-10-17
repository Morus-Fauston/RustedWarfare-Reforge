plugins {
    kotlin("jvm") version "2.3.20"
    application
}
kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
application { mainClass.set("com.rustedwarfare.reforge.testing.MainKt") }
project(":core") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core"))
}
tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", projectDir.parentFile.absolutePath)
}
