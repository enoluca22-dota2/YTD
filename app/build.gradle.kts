import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.enoluca.ytd"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.enoluca.ytd"
        minSdk = 26
        targetSdk = 37
        // ── App version: change ONLY this line for a new release, then tag it (v + the same
        // version, e.g. v1.2.0). versionCode is derived from it (1.2.3 → 1002003), so every
        // release is higher than the previous one and installs over it. The release workflow
        // refuses a tag that doesn't match this value.
        versionName = "0.2.0"
        versionCode = versionCodeFor(versionName!!)

        // Where the in-app updater looks for releases (see update/UpdateConfig.kt). Set in
        // gradle.properties, or passed by the release workflow for the repository it runs in.
        buildConfigField("String", "GITHUB_OWNER", "\"${providers.gradleProperty("ytd.github.owner").orNull.orEmpty()}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${providers.gradleProperty("ytd.github.repo").orNull.orEmpty()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // youtubedl-android ships its python/ffmpeg binaries for arm64-v8a, armeabi-v7a, x86 and
    // x86_64. We ship the three that real phones/emulators use, as one APK per ABI plus a
    // universal APK for anyone unsure which one their device needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // Release signing credentials come from environment variables or the git-ignored
    // keystore.properties file in the project root — never from this file.
    val keystoreProps = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun signingValue(envName: String, propName: String): String? =
        System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(propName)

    val releaseStoreFile = signingValue("KEYSTORE_FILE", "storeFile")
    val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
    val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
    val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
    val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("Release signing credentials not found (keystore.properties or KEYSTORE_* env vars); the release APK will be unsigned.")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // youtubedl-android runs its python/ffmpeg binaries straight from nativeLibraryDir,
            // so native libs must be extracted at install time.
            useLegacyPackaging = true
            // 32-bit x86 was never shipped (it only matters for very old emulators); keep it
            // out of the universal APK as the old ndk.abiFilters did.
            excludes += "lib/x86/**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    // Liquid Glass effect (refraction, blur, vibrancy) — compiled into the APK, no runtime install.
    implementation(libs.kyant.backdrop)
    // Glass shapes (RoundedRectangle, Capsule) the lens shader needs; backdrop only ships it at runtime.
    implementation(libs.kyant.shapes)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.youtubedl.library)
    // Read yt-dlp's raw JSON (playlists) with the ObjectMapper youtubedl-android already bundles;
    // compileOnly so no second Jackson copy is packaged.
    compileOnly(libs.jackson.databind.ytdl)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.jackson.databind)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/** 1.2.3 → 1_002_003; must match AppVersion.versionCodeFor in the app. */
fun versionCodeFor(name: String): Int {
    val numbers = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val (major, minor, patch) = List(3) { numbers.getOrElse(it) { 0 }.coerceIn(0, 999) }
    return (major * 1_000_000 + minor * 1_000 + patch).coerceAtLeast(1)
}
