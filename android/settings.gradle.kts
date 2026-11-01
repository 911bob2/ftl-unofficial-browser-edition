pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ftl-android"
// :savecore compiles ../savecore (shared with the browser build), :mods is the website's mod merge in Kotlin, :engine compiles ../engine/src/main/java with the
// browser host files swapped for the Android ones in engine/src/main/java here.
include(":savecore", ":mods", ":engine", ":app")
