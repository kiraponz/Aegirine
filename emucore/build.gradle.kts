import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
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
    namespace = "com.kira3.ametrine.emucore"
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
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
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
            // RetroAchievements' sounds and logo; achievements are out of scope for v1.
            "sounds/achievements/**",
            "icons/ra-icon.svg",
        )
    )
    // ARMSX2's mobile GameDB fixes, read next to GameIndex.yaml.
    extraFiles.from(File(armsx2Dir, "bin/resources-overlay/armsx2_overrides.yaml"))
    // The built-in game patches (pcsx2_patches), which ARMSX2 keeps only in its Android app.
    extraFiles.from(File(armsx2Dir, "platforms/android/app/src/main/assets/resources/patches.zip"))
    intoPath.set("resources")
    outputDir.set(layout.buildDirectory.dir("generated/armsx2/assets"))
}

// Copies named files into one folder of a generated source root, each under its own name.
abstract class CollectFiles @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {
    /** Output name to source file. */
    @get:Input
    abstract val files: MapProperty<String, File>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources get() = files.get().values

    /** Subfolder of [outputDir] the files land in. */
    @get:Input
    abstract val intoPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val entries = files.get()
        val subdir = intoPath.get()
        entries.values.filterNot { it.isFile }.let {
            check(it.isEmpty()) { "License files missing (run scripts/setup-armsx2.sh): $it" }
        }
        fs.sync {
            into(outputDir)
            for ((name, file) in entries) {
                from(file) {
                    into(subdir)
                    rename { name }
                }
            }
        }
    }
}

// The license texts of the core and of what it links or ships, shown by the app's licenses
// screen (app/.../OpenSourceLicenses.kt names each file). Paths are in the ARMSX2 tree; the
// Android build uses the 3rdparty folder beside its CMakeLists, not the root one.
val collectArmsx2Licenses = tasks.register<CollectFiles>("collectArmsx2Licenses") {
    val lib = "platforms/android/app/src/main/cpp/3rdparty"
    val paths = mapOf(
        "gpl-3.0.txt" to "COPYING.GPLv3",
        "apache-2.0.txt" to "$lib/shaderc/LICENSE",
        "ofl-1.1.txt" to "bin/resources/fonts/promptfont-license",
        "adrenotools.txt" to "$lib/adrenotools/LICENSE",
        "cpuinfo.txt" to "$lib/cpuinfo/LICENSE",
        "cubeb.txt" to "$lib/cubeb/LICENSE",
        "demangler.txt" to "$lib/demangler/DemanglerLicenseLGPL.txt",
        "discord-rpc.txt" to "$lib/discord-rpc/LICENSE",
        "fast_float.txt" to "$lib/fast_float/LICENSE-MIT",
        "fmt.txt" to "$lib/fmt/LICENSE",
        "freesurround.txt" to "$lib/freesurround/GPL.txt",
        "freetype.txt" to "$lib/freetype/LICENSE.TXT",
        "freetype-ftl.txt" to "$lib/freetype/docs/FTL.TXT",
        "imgui.txt" to "$lib/imgui/LICENSE.txt",
        "libchdr.txt" to "$lib/libchdr/LICENSE.txt",
        "libjpeg-turbo.txt" to "$lib/libjpeg-turbo/LICENSE.md",
        "libjpeg-turbo-ijg.txt" to "$lib/libjpeg-turbo/README.ijg",
        "libpng.txt" to "$lib/libpng/LICENSE.md",
        "libwebp.txt" to "$lib/libwebp/COPYING",
        "libwebp-patents.txt" to "$lib/libwebp/PATENTS",
        "libzip.txt" to "$lib/libzip/LICENSE",
        "lz4.txt" to "$lib/lz4/lib/LICENSE",
        "lzma.txt" to "$lib/lzma/LICENSE.txt",
        "plutosvg.txt" to "$lib/plutosvg/LICENSE",
        "plutovg.txt" to "$lib/plutovg/LICENSE",
        "rapidjson.txt" to "$lib/rapidjson/license.txt",
        "rcheevos.txt" to "$lib/rcheevos/LICENSE",
        "sdl3.txt" to "$lib/sdl3/LICENSE.txt",
        "glslang.txt" to "$lib/shaderc/third_party/glslang/LICENSE.txt",
        "spirv-headers.txt" to "$lib/shaderc/third_party/spirv-headers/LICENSE",
        "simpleini.txt" to "$lib/simpleini/LICENCE.txt",
        "soundtouch.txt" to "$lib/soundtouch/COPYING.TXT",
        "vixl.txt" to "$lib/vixl/LICENCE",
        "vulkan.txt" to "$lib/vulkan/LICENSE.md",
        "vulkan-vma.txt" to "$lib/vulkan/include/LICENSE.txt",
        "zstd.txt" to "$lib/zstd/LICENSE",
        "roboto.txt" to "bin/resources/fonts/Roboto-Regular-copyright",
        "flag-icons.txt" to "bin/resources/icons/flags/LICENSE",
        "raisr.txt" to "bin/resources/upscale/raisr/LICENSE.Intel",
    )
    files.set(paths.mapValues { File(armsx2Dir, it.value) })
    intoPath.set("licenses")
    outputDir.set(layout.buildDirectory.dir("generated/armsx2/licenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(syncArmsx2Java, SyncFromArmsx2::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(syncArmsx2Resources, SyncFromArmsx2::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(collectArmsx2Licenses, CollectFiles::outputDir)
    }
}
