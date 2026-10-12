import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
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
    namespace = "com.kira3.scphx.emucore"
    compileSdk {
        version = release(37)
    }
    // Same NDK that ARMSX2 pins for its releases.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // The core's JIT targets ARM64 only.
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
}

// Copies a slice of the ARMSX2 tree into a generated source root at build time, so the
// files stay in step with the submodule instead of drifting as committed copies.
abstract class SyncFromArmsx2 @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val includePatterns: ListProperty<String>

    @get:Input
    abstract val excludePatterns: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val extraFiles: ConfigurableFileCollection

    /** Subfolder of [outputDir] the files land in. */
    @get:Input
    abstract val intoPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        // Read everything up front: inside the CopySpec lambdas, names like `includes`
        // would resolve to the spec's own properties.
        val src = sourceDir.get().asFile
        val includes = includePatterns.get()
        val excludes = excludePatterns.get()
        val extras = extraFiles.files
        val subdir = intoPath.get()
        fs.sync {
            into(outputDir)
            into(subdir) {
                from(src) {
                    include(includes)
                    exclude(excludes)
                }
                from(extras)
            }
        }
    }
}

// Java classes the core looks up by name over JNI. SDL's JNI_OnLoad registers natives on
// the org.libsdl.app classes, and ContentChdFiles backs NativeApp.findSiblingChds.
val syncArmsx2Java = tasks.register<SyncFromArmsx2>("syncArmsx2Java") {
    sourceDir.set(File(armsx2Dir, "platforms/android/app/src/main/java"))
    includePatterns.set(listOf("org/libsdl/app/**", "kr/co/iefriends/pcsx2/ContentChdFiles.java"))
    excludePatterns.set(emptyList())
    intoPath.set(".")
    outputDir.set(layout.buildDirectory.dir("generated/armsx2/java"))
}

// Runtime resources (GameDB, shaders, fonts). EmulatorCore copies assets/resources into
// the data folder before the core starts, because the core reads them as plain files.
val syncArmsx2Resources = tasks.register<SyncFromArmsx2>("syncArmsx2Resources") {
    sourceDir.set(File(armsx2Dir, "bin/resources"))
    includePatterns.set(emptyList())
    excludePatterns.set(
        listOf(
            // Direct3D shaders; Android uses OpenGL/Vulkan only.
            "shaders/dx11/**",
            // Desktop emoji font; nothing on Android loads it.
            "fonts/Twemoji*",
            // Namco System 246/256 data; arcade games are out of scope for v1.
            "arcade/**",
        )
    )
    // ARMSX2's mobile GameDB fixes, read next to GameIndex.yaml.
    extraFiles.from(File(armsx2Dir, "bin/resources-overlay/armsx2_overrides.yaml"))
    // The built-in game patches (pcsx2_patches), which ARMSX2 keeps only in its Android app.
    extraFiles.from(File(armsx2Dir, "platforms/android/app/src/main/assets/resources/patches.zip"))
    intoPath.set("resources")
    outputDir.set(layout.buildDirectory.dir("generated/armsx2/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(syncArmsx2Java, SyncFromArmsx2::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(syncArmsx2Resources, SyncFromArmsx2::outputDir)
    }
}
