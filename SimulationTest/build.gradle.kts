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
    systemProperty("repo.root", projectDir.parentFile.absolutePath)
}

// Separate accepted P001 suite entry; the existing run/compare CLI stays unchanged.
tasks.register<JavaExec>("p001Suite") {
    group = "verification"
    description = "Run the accepted P001 self-behavior suite with explicit repo-root and immutable batches"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rustedwarfare.reforge.testing.p001.P001SuiteMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
