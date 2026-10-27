pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal { content { includeGroup("io.github.neoruaa.glasmio") } }
    }
}
if (providers.gradleProperty("glasmio.source").orNull == "true") {
    val source = file(providers.gradleProperty("glasmio.sourceDir").orNull ?: "../Glasmio")
    require(source.resolve("settings.gradle.kts").isFile) { "glasmio.source requires a Glasmio checkout at glasmio.sourceDir (default: ../Glasmio)." }
    includeBuild(source)
}
rootProject.name = "HyperPods-X"
include(":app", ":core:protocol", ":hook")
