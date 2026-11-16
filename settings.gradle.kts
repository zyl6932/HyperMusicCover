pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // SuperLyricApi publishes through JitPack only - nothing else of ours lives there.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.HChenX") }
        }
    }
}
rootProject.name = "HyperMusicCover"
include(":app")
