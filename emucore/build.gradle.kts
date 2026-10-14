import javax.inject.Inject

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
    // Built for 16 KiB pages, which also runs on the 4 KiB ones (the core only needs the system's
    // pages to divide its own), so one library serves both kinds of device.
    "-DHOST_PAGE_SIZE=16384",
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
            // Optimized: an unoptimized core runs games far below full speed.
            externalNativeBuild { cmake { arguments += coreArgs("RelWithDebInfo") } }
        }
    }

    externalNativeBuild {
        cmake {
            path = File(coreDir, "CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        // assets/resources: the core's shaders, fonts and databases. CoreResources copies them
        // out to plain files at the first launch. Also puts the core's data/LICENSE.txt at the
        // root of the assets.
        getByName("main").assets.directories.add(File(coreDir, "data").path)
        // Filled by the coreJniLibs task below.
        getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("generated/coreJniLibs").get().asFile.path)
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

// The core opens shaderc and SPIRV-Cross with dlopen rather than linking them, so CMake does not
// package them; they go in as prebuilt native libraries.
val coreJniLibs = tasks.register<Sync>("coreJniLibs") {
    from(File(coreDepsDir, "lib")) {
        include("libshaderc_shared.so", "libspirv-cross-c-shared.so")
    }
    into(layout.buildDirectory.dir("generated/coreJniLibs/arm64-v8a"))
}

tasks.named("preBuild") { dependsOn(coreJniLibs) }

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
            check(it.isEmpty()) { "License files missing (run build-dependencies-android.sh again, see AGENTS.md): $it" }
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
// screen (app/.../OpenSourceComponents.kt names each file). The prebuilt libraries' texts are
// saved by build-dependencies-android.sh; the app keeps the plain texts (MIT, zlib, OFL) itself.
val collectCoreLicenses = tasks.register<CollectFiles>("collectCoreLicenses") {
    val dep = "external/aegirine-core/dep"
    val prebuilt = "external/core-deps/arm64-v8a/share/licenses"
    val paths = mapOf(
        "gpl-3.0.txt" to "external/aegirine-core/LICENSE",
        "apache-2.0.txt" to "external/aegirine-core/data/resources/fonts/LICENSE.txt",
        "fast_float.txt" to "$dep/fast_float/LICENSE-MIT",
        "fmt.txt" to "$dep/fmt/LICENSE.rst",
        "imgui.txt" to "$dep/imgui/LICENSE.txt",
        "libchdr.txt" to "$dep/libchdr/LICENSE.txt",
        "lzma.txt" to "$dep/lzma/LICENSE",
        "rapidyaml.txt" to "$dep/rapidyaml/LICENSE.txt",
        "rcheevos.txt" to "$dep/rcheevos/LICENSE",
        "reshadefx.txt" to "$dep/reshadefx/LICENSE.md",
        "simpleini.txt" to "$dep/simpleini/LICENCE.txt",
        "vixl.txt" to "$dep/vixl/LICENCE",
        "xxhash.txt" to "$dep/xxhash/LICENSE",
    ) + listOf(
        "cpuinfo", "freetype", "glslang", "libjpeg-turbo", "libpng", "libwebp", "shaderc", "soundtouch",
        "spirv-cross", "spirv-headers", "spirv-tools", "zstd",
    ).associate { "$it.txt" to "$prebuilt/$it.txt" }
    files.set(paths.mapValues { rootProject.file(it.value) })
    intoPath.set("licenses")
    outputDir.set(layout.buildDirectory.dir("generated/coreLicenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(collectCoreLicenses, CollectFiles::outputDir)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
