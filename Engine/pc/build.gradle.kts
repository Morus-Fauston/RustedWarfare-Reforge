plugins {
    kotlin("jvm")
    application
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
    implementation(project(":client"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:1.13.1")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-desktop")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.rustedwarfare.reforge.pc.WindowsLauncherKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile
}

tasks.test {
    useJUnitPlatform()
}
