plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.os4.musiccover"
    compileSdk = 37

    defaultConfig {
        // Deliberately unchanged: this is the identity LSPosed already knows, and the module's
        // state files and probe broadcasts all hang off it. Renaming it would mean re-enabling
        // the module and re-picking its scope by hand.
        applicationId = "com.os4.musiccover"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    compileOnly("de.robv.android.xposed:api:82")

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
}
