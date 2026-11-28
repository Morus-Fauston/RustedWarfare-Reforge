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
    implementation(project(":client"))
    implementation(project(":pc"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
application {
    mainClass.set("com.rustedwarfare.reforge.testing.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Xmx512m",
        "-XX:MaxMetaspaceSize=160m",
        "-XX:ReservedCodeCacheSize=64m",
    )
}
// Windows cmd expands every absolute JAR path before Java starts. Keep deep installs below its line limit.
tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        val generated = windowsScript.readText()
        val assignment = Regex("(?m)^set CLASSPATH=[^\\r\\n]*")
        check(assignment.findAll(generated).count() == 1) { "Expected one Windows classpath assignment" }
        windowsScript.writeText(generated.replace(assignment) { "set CLASSPATH=%APP_HOME%\\lib\\*" })
    }
}
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
}
gradle.projectsEvaluated {
    val publicInputs = System.getProperty("public.inputs.dir")
    if (publicInputs != null) {
        allprojects {
            tasks.withType<Test>().configureEach {
                systemProperty("p001.inputs.dir", publicInputs)
                systemProperty("public.fixtures.dir", rootProject.file("public/inputs").absolutePath)
                systemProperty("repo.root", rootProject.projectDir.parentFile.absolutePath)
            }
        }
    }
}
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "768m"
    jvmArgs("-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=64m")
    systemProperty("repo.root", System.getProperty("repo.root") ?: projectDir.parentFile.absolutePath)
    System.getProperty("evidence.dir")?.let { systemProperty("evidence.dir", it) }
}

// Separate accepted P001 suite entry; the existing run/compare CLI stays unchanged.
tasks.register<JavaExec>("p001Suite") {
    group = "verification"
    description = "Run the accepted P001 self-behavior suite with explicit repo-root and immutable batches"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rustedwarfare.reforge.testing.p001.P001SuiteMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.register<JavaExec>("diagnosticBaselineCapture") {
    group = "verification"
    description = "Run offline diagnostic baseline capture and render 2s stall-highlighted contact sheets"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rustedwarfare.reforge.testing.DiagnosticBaselineCaptureMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.register<JavaExec>("movementProbe") {
    group = "verification"
    description = "Run second-level movement micro-benchmark & 2s stall-highlighted contact sheet probe"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rustedwarfare.reforge.testing.MovementMicroProbeMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
