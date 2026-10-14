# Aegirine

An experimental PS1 emulator for Android. The emulation core is
[aegirine-core](https://github.com/kiraponz/aegirine-core), a fork of DuckStation's last GPL-3.0
version; the app around it (library, settings, touch controls) comes from
[Ametrine](https://github.com/kiraponz/Ametrine) and is written in Jetpack Compose.

> Aegirine is an unofficial project. It is not affiliated with Sony Interactive Entertainment or
> DuckStation. Please report problems with Aegirine here, not to the DuckStation project.

## Features

- Game library from a folder you pick, with favorites, search, play time and multi-disc games
- OpenGL ES and Vulkan rendering
- Global settings with per-game overrides
- Touch controls you can rearrange and resize, and physical controllers
- Save states, rewind, fast forward, memory cards, GameShark cheats, memory search
- Backup and restore of settings and saves
- Optional: ask Google Gemini about the current screen

## Requirements

- Android 10 or later, arm64-v8a (4 KB and 16 KB memory pages)
- **A BIOS dumped from your own PS1.** Aegirine does not include, download or link to any
  BIOS or games. Use only games you own.

## Download

APKs are on the [Releases](https://github.com/kiraponz/Aegirine/releases) page. Each release
also carries the complete source it was built from.

## Privacy

Aegirine has no analytics and makes no network requests on its own. The one exception is the
optional Gemini feature: when you use it, the screenshot and your question are sent to Google's
Gemini API with your own API key, under [Google's terms](https://ai.google.dev/gemini-api/terms).

## Building

Requires Android Studio (for its JDK), NDK 29.0.14206865, CMake 3.31.6 and git.

```bash
git clone --recurse-submodules https://github.com/kiraponz/Aegirine.git
cd Aegirine
external/aegirine-core/scripts/deps/build-dependencies-android.sh ~/Library/Android/sdk/ndk/29.0.14206865 external/core-deps/arm64-v8a
./gradlew :app:assembleDebug
```

`build-dependencies-android.sh` downloads and builds the core's third-party libraries (zstd,
libpng, libjpeg-turbo, libwebp, FreeType, cpuinfo, SoundTouch, shaderc, SPIRV-Cross) into
`external/core-deps/`; it only needs to run once. From the command line, point `JAVA_HOME` at
Android Studio's JDK (on macOS, `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

The source archive attached to a release already holds the core and the libraries' source
tarballs, so skip the clone there; the libraries then build without network.

## License

Aegirine is free software under the [GNU General Public License v3.0](LICENSE), the license
aegirine-core is used under. The third-party libraries, fonts and data the app includes are
listed with their licenses in the app, under Settings > About > Open source licenses.

PlayStation is a trademark of Sony Interactive Entertainment Inc.
