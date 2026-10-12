plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kira3.scphx"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kira3.scphx"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // :emucore only ships arm64-v8a.
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":emucore"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}