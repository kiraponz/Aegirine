# Ametrine

A PS2 emulator for Android. The emulation core is [ARMSX2](https://github.com/ARMSX2/ARMSX2),
an ARM64 fork of [PCSX2](https://github.com/PCSX2/pcsx2); the app around it (library, settings,
touch controls) is written from scratch in Jetpack Compose.

> Ametrine is an unofficial project. It is not affiliated with Sony Interactive Entertainment,
> PCSX2 or ARMSX2. Please report problems with Ametrine here, not to the ARMSX2 or PCSX2 teams.

## Features

- Game library from a folder you pick, with favorites, search and play time
- Global settings with per-game overrides
- Touch controls you can rearrange and resize, and physical controllers
- Save states, memory card manager, cheats, input macros
- Backup and restore of settings and saves
- Custom Vulkan drivers (adrenotools) on Adreno GPUs
- Optional: ask Google Gemini about the current screen

## Requirements

- Android 10 or later, arm64-v8a
- A device with 4 KB memory pages (16 KB page devices are not supported yet)
- **A BIOS dumped from your own PS2.** Ametrine does not include, download or link to any
  BIOS or games. Use only games you own.

## Download

APKs are on the [Releases](https://github.com/kiraponz/Ametrine/releases) page. Each release
also carries the complete source it was built from.

## Privacy

Ametrine has no analytics and makes no network requests on its own. The one exception is the
optional Gemini feature: when you use it, the screenshot and your question are sent to Google's
Gemini API with your own API key, under [Google's terms](https://ai.google.dev/gemini-api/terms).

## Building

Requires Android Studio (for its JDK), NDK 29.0.14206865, CMake 3.31.6, Python 3 and git.

```bash
git clone --recurse-submodules https://github.com/kiraponz/Ametrine.git
cd Ametrine
./scripts/setup-armsx2.sh
./gradlew :app:assembleDebug
```

`setup-armsx2.sh` checks out the ARMSX2 submodule, fetches shaderc's dependencies and applies the
patches in `patches/armsx2/`. It is safe to run again. From the command line, point `JAVA_HOME`
at Android Studio's JDK (on macOS,
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

The submodule points at [a fork of ARMSX2](https://github.com/kiraponz/ARMSX2) so that the commit
a release is built from stays available.

## License

Ametrine is free software under the [GNU General Public License v3.0](LICENSE) or later, the
same license as ARMSX2 and PCSX2. The third-party libraries, fonts and data the app includes are
listed with their licenses in the app, under Settings > About > Open source licenses.

PlayStation and PS2 are trademarks of Sony Interactive Entertainment Inc.
