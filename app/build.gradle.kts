import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing. Locally it comes from keystore.properties (git-ignored, next to this file's
 * parent); on CI from environment variables. A clone with neither still builds a release - it
 * falls back to the debug key, which is installable but is NOT the identity users update from.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val releaseStorePath = signingValue("storeFile", "SIGNING_STORE_FILE")

android {
    namespace = "com.os4.musiccover"
    compileSdk = 37

    defaultConfig {
        // The GitHub identity the module ships under. LSPosed keys a module on this, so
        // changing it means the module has to be re-enabled and its scope re-picked by hand,
        // and any older build under a different id has to be uninstalled or the two both hook
        // SystemUI. The Java package and the probe broadcast actions stay com.os4.musiccover:
        // nothing user-visible hangs off them, and every documented adb command does.
        applicationId = "com.github.zyl6932.HyperMusicCover"
        minSdk = 35
        targetSdk = 37
        // CI stamps nightlies with -PmcVersionCode / -PmcVersionSuffix so every build is
        // distinguishable in LSPosed and in the About page; a plain local build keeps 1.0.
        versionCode = (findProperty("mcVersionCode") as String?)?.toInt() ?: 2
        versionName = "0.0.1" + ((findProperty("mcVersionSuffix") as String?) ?: "")
    }

    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The settings UI drags in Compose and material-icons-extended, which is tens of
            // megabytes of generated icon code that this app uses a handful of. Without R8 the
            // APK is ~47MB; the module's own code is a rounding error either way.
            // proguard-rules.pro keeps the hook classes, which nothing on the classpath calls.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Modern Xposed API. compileOnly on purpose: the framework provides it at runtime and
    // packaging it would shadow the real one. Zero bytes in the APK either way.
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.shader)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    implementation(libs.material.icons.extended)
}
