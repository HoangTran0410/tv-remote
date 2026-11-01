pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.swordfish90") } }   // TV Games: LibretroDroid
    }
}
rootProject.name = "tv-remote"
// Only the build setup lives here, so that app/ shows the parts themselves: android, tv, tvkeys (TV Setup Keys, packed inside tv), home (TV Home), channels (TV Channels), games, shared, desktop (the computer
// app, on the shared Kotlin core). Open this folder in Android Studio.
include(":shared", ":android", ":tvsetup", ":tvkeys", ":tvvoice", ":tvhome", ":tvchannels", ":tvgames", ":tvapps", ":desktop")
for (name in listOf("shared", "android", "tvsetup", "tvkeys", "tvvoice", "tvhome", "tvchannels", "tvgames", "tvapps", "desktop")) project(":$name").projectDir = file("../$name")
