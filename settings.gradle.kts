pluginManagement {
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "HyperMusicCover"
include(":app")
