plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// aegirine-core (DuckStation's last GPL-3.0 version), pulled in as a git submodule. Its CMake
// reads the sources by CMAKE_SOURCE_DIR, so its own CMakeLists.txt is the root of the native
// build, and it adds our frontend (src/main/cpp) through ANDROID_FRONTEND_DIR.
//
// The third-party libraries it needs are prebuilt once into external/core-deps by
// external/aegirine-core/scripts/deps/build-dependencies-android.sh (see AGENTS.md).
val coreDir = rootProject.file("external/aegirine-core")
val coreDepsDir = rootProject.file("external/core-deps/arm64-v8a")

fun coreArgs(buildType: String) = listOf(
    "-DANDROID_STL=c++_static",
    "-DCMAKE_BUILD_TYPE=$buildType",
    "-DCMAKE_PREFIX_PATH=$coreDepsDir",
    // The NDK toolchain only searches inside the find root.
    "-DCMAKE_FIND_ROOT_PATH=$coreDepsDir",
    "-DANDROID_FRONTEND_DIR=${file("src/main/cpp")}",
)

android {
    namespace = "com.kira3.ametrine.emucore"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // The core's recompiler and fastmem target ARM64 only here.
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            externalNativeBuild { cmake { arguments += coreArgs("Release") } }
        }
        debug {
            externalNativeBuild { cmake { arguments += coreArgs("Debug") } }
        }
    }

    externalNativeBuild {
        cmake {
            path = File(coreDir, "CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Fail early with the fix, rather than deep inside CMake's find_package.
tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    // Locals, so the action doesn't capture the build script (the configuration cache can't store it).
    val coreDir = coreDir
    val coreDepsDir = coreDepsDir
    doFirst {
        check(File(coreDir, "CMakeLists.txt").isFile) {
            "external/aegirine-core is missing: run `git submodule update --init`"
        }
        check(File(coreDepsDir, "lib").isDirectory) {
            "The core's libraries are not built: run external/aegirine-core/scripts/deps/build-dependencies-android.sh " +
                "<ndk> external/core-deps/arm64-v8a"
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
