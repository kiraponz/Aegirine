plugins {
    alias(libs.plugins.android.application)
}

// ARMSX2 (PCSX2 fork) emulation core, pulled in as a git submodule.
// The core's CMake assumes its own Android entry point is the CMake source root,
// so we point externalNativeBuild straight at it rather than wrapping it.
val armsx2Dir = rootProject.file("external/armsx2")
val armsx2CMakeLists = File(armsx2Dir, "platforms/android/app/src/main/cpp/CMakeLists.txt")

fun armsx2Args(buildType: String) = listOf(
    "-DANDROID=true",
    "-DANDROID_STL=c++_static",
    "-DCMAKE_BUILD_TYPE=$buildType",
    "-DARMSX2_EMUCORE_LIBRARY_NAME=emucore_4k",
    "-DARMSX2_ANDROID_HOST_PAGE_SIZE=0x1000",
    "-DARMSX2_ENABLE_LSFG=OFF",
    // RetroArch shader chains (Rust/cargo). Optional; needs `rustup target add aarch64-linux-android`.
    "-DARMSX2_DISABLE_LIBRASHADER=ON",
    "-DCMAKE_C_FLAGS=-O3 -g",
    "-DCMAKE_CXX_FLAGS=-O3 -g",
)

android {
    namespace = "com.kira3.scphx"
    compileSdk {
        version = release(37)
    }
    // Same NDK that ARMSX2 pins for its releases.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.kira3.scphx"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The core's JIT targets ARM64 only.
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            externalNativeBuild { cmake { arguments += armsx2Args("Release") } }
        }
        debug {
            externalNativeBuild { cmake { arguments += armsx2Args("Debug") } }
        }
    }

    externalNativeBuild {
        cmake {
            path = armsx2CMakeLists
            version = "3.31.6"
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}