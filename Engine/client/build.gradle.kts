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
    api(project(":core"))
    api("com.badlogicgames.gdx:gdx:1.13.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("p001.inputs.dir", rootProject.projectDir.parentFile.resolve("TestLab/local/P001-移动沙盒输入").absolutePath)
}
