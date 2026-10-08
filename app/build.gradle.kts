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
        // CI stamps builds so every one is distinguishable in LSPosed and in the About page:
        // release.yml derives -PmcVersionName / -PmcVersionCode from the tag, nightly.yml adds
        // -PmcVersionSuffix. A plain local build keeps the values below.
        versionCode = (findProperty("mcVersionCode") as String?)?.toInt() ?: 2
        versionName = ((findProperty("mcVersionName") as String?) ?: "0.0.1") +
                ((findProperty("mcVersionSuffix") as String?) ?: "")
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
            // minSdk 35 never runs on a 32-bit or x86 phone; the other three copies of the one
            // Compose .so are dead weight.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    // The app is translated into Chinese (the default values/) and English, nothing else. The
    // libraries bring strings for dozens more locales, and resources.arsc - which has to be stored
    // uncompressed - carries all of them.
    androidResources {
        localeFilters += listOf("zh", "en")
        // assets/coloros/pcr_plugin.apk is copied back out whole when the pickup recognizer is
        // loaded. Deflating it would only be undone; storing it keeps that copy a straight read.
        noCompress += "apk"
    }

    // Library licence texts, version markers and Kotlin's reflection metadata: nothing at runtime
    // reads them. The licences are credited on the About page instead.
    packaging {
        resources.excludes += listOf(
            "META-INF/**/LICENSE*",
            "META-INF/*.version",
            "META-INF/*.kotlin_module",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }

    // Pinned, not left to the platform default. NcmLyrics' health check compares against a
    // song title written as characters, and a javac that read this file as the system codepage
    // would hand it a mangled one - the search would then never find the id it looks for, the
    // endpoint would look permanently dishonest, and no miss would ever be cached again. It
    // builds correctly here only because this toolchain's javac already defaults to UTF-8.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The module and the app both log a full account of the preview pipeline in debug
        // builds and stay quiet in release ones, which needs BuildConfig.DEBUG to exist.
        buildConfig = true
    }
}

// Deflate the dex instead of storing it page-aligned. The default (stored) lets ART mmap it
// straight out of the APK, but this APK is downloaded from GitHub by hand and by the
// self-updater, and stored dex is half its size. LSPosed reads the module's dex into memory
// either way, so SystemUI does not care. Release only: debug keeps the fast install path.
androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.dex.useLegacyPackaging.set(true)
    }
}

dependencies {
    // Modern Xposed API. compileOnly on purpose: the framework provides it at runtime and
    // packaging it would shadow the real one. Zero bytes in the APK either way.
    compileOnly("io.github.libxposed:api:102.0.0")
    // The app's side of it: LSPosed binds this to the app, which then writes the remote
    // preferences every hooked process reads (the log level) and reads which packages the
    // module is actually enabled in (重启全部作用域). Only the app uses it.
    implementation("io.github.libxposed:service:102.0.0")

    // The lyric parser. Its classes end up in the same dex as Main.java's, so they are also
    // loaded into SystemUI when the module is - see LyricProbe, which is why it has to stay
    // dependency-light and Android-free.
    implementation(libs.lyrics.core)
    // The lyric bridge the LyricProvider plugins publish through. Optional at runtime: when
    // nothing on the device implements it, LyriconSource simply never connects.
    implementation(libs.lyricon.subscriber)

    // The release notes are Markdown and are rendered as such in the update dialog.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.androidx.webkit)

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

    // ColorOS's pickup plugin (assets/coloros/pcr_plugin.apk) is loaded at runtime into a class
    // loader whose parent is this module's, and it resolves Gson and the Kotlin stdlib by name out
    // of this dex - its OEM host supplies them, so the plugin bundles neither. Gson is therefore
    // here for a caller nothing on our own classpath can see; see proguard-rules.pro, which has to
    // keep it for the same reason.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for the JVM tests: android.jar's is a stub (AmapTransitCardTest).
    testImplementation("org.json:json:20231013")
}
