import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The ARMSX2 commit the core is built from, shown in About so the source of a release can be
// found. A source archive without .git has no commit; scripts/make-source-archive.sh writes it
// to external/armsx2.commit instead.
val coreCommit: String = rootProject.file("external/armsx2.commit").takeIf { it.isFile }?.readText()?.trim()
    ?: providers.exec {
        commandLine("git", "-C", rootProject.file("external/armsx2").path, "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }

// The release signing key, from keystore.properties at the root (never committed; see
// RELEASING.md). Without it a release build is unsigned.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.kira3.ametrine"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kira3.ametrine"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CORE_COMMIT", "\"$coreCommit\"")
        ndk {
            // :emucore only ships arm64-v8a.
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    packaging {
        jniLibs {
            // libadrenotools' hook .so files must be extracted to nativeLibraryDir at install time.
            useLegacyPackaging = true
            // ARMSX2's screensavers and Flurry background, which the core build always makes;
            // both are out of scope for v1, and their licenses would need listing if shipped.
            excludes += setOf("**/libflurry.so", "**/libsavers.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":emucore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}