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

// Java expands this JAR wildcard itself, avoiding cmd's limit for deeply nested Windows installs.
tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        val generated = windowsScript.readText()
        val assignment = Regex("(?m)^set CLASSPATH=[^\\r\\n]*")
        check(assignment.findAll(generated).count() == 1) { "Expected one Windows classpath assignment" }
        windowsScript.writeText(generated.replace(assignment) { "set CLASSPATH=%APP_HOME%\\lib\\*" })
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile
}

tasks.test {
    useJUnitPlatform()
}
