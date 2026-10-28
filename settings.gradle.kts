pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HyperPods-X"
val glasmioSource = file(providers.gradleProperty("glasmio.sourceDir").orNull ?: ".reference/Glasmio")
require(glasmioSource.resolve("settings.gradle.kts").isFile) {
    "Glasmio source is required. See docs/UI-STYLES.md for the pinned checkout and build instructions."
}
includeBuild(glasmioSource)
include(":app", ":core:protocol", ":core:wire", ":hook")
