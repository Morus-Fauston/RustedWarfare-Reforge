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

// Self-owned, paired scene inputs travel with the installed PC application.
distributions {
    main {
        contents {
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/scenarios")) {
                include("phase-five-*.json")
                include("phase-six-a-*.json")
                into("test-scenarios/scenarios")
            }
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/inputs/phase-five-sandbox")) {
                into("test-scenarios/inputs/phase-five-sandbox")
            }
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/inputs/phase-six-a-sandbox")) {
                into("test-scenarios/inputs/phase-six-a-sandbox")
            }
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/contracts")) {
                include("phase-five-*.json")
                include("phase-six-a-*.json")
                into("test-scenarios/contracts")
            }
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/scenarios/dual-ship-production.json")) {
                into("test-scenarios/scenarios")
            }
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/contracts/dual-ship-production.json")) {
                into("test-scenarios/contracts")
            }
            from(rootProject.projectDir.parentFile.resolve("SimulationTest/public/inputs/author-naval-chain/json")) {
                into("test-scenarios/inputs/author-naval-chain/json")
            }
        }
    }
}
