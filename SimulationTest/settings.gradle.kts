pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "simulation-test"
include(":core")
project(":core").projectDir = file("../Engine/core")
include(":client", ":pc")
project(":client").projectDir = file("../Engine/client")
project(":pc").projectDir = file("../Engine/pc")
