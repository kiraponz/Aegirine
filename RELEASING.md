# Releasing

APKs go to GitHub Releases only.

## Signing key (once)

Every release must be signed with the same key, or users cannot update. Losing the key means
the app can only be reinstalled under a new application ID.

`keytool` comes with Android Studio's JDK (macOS has no Java of its own):

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v -keystore ametrine-release.jks -alias ametrine -keyalg RSA -keysize 4096 -validity 36500
```

Keep the `.jks` outside the repository, with a backup somewhere else. Then create
`keystore.properties` at the repository root (ignored by git):

```properties
storeFile=/absolute/path/to/ametrine-release.jks
storePassword=...
keyAlias=ametrine
keyPassword=...
```

## Each release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` and commit.
2. Tag the commit: `git tag v1.0 && git push origin main v1.0`.
3. If the ARMSX2 submodule moved to a new commit, push that commit to the fork's `ametrine`
   branch so it stays available:
   `git -C external/armsx2 push https://github.com/kiraponz/ARMSX2.git HEAD:refs/heads/ametrine`
   (an older pinned commit stays reachable from an older tag of this repository only if the
   fork still has it, so do not force-push that branch backwards).
4. Build from a clean checkout of the tag:
   ```bash
   ./scripts/setup-armsx2.sh
   ./gradlew :app:assembleRelease
   ./scripts/make-source-archive.sh
   ```
5. Check the core links nothing proprietary (must print nothing):
   ```bash
   unzip -p app/build/outputs/apk/release/app-release.apk lib/arm64-v8a/libemucore_4k.so > /tmp/core.so
   "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" -d /tmp/core.so | grep -i discord
   ```
6. Create the GitHub release for the tag and attach:
   - `app-release.apk`, renamed `Ametrine-<version>.apk`
   - `build/ametrine-<version>-source.tar.gz`
7. In the release notes, give the SHA-256 of both files (`shasum -a 256 <file>`) and the
   signing certificate's fingerprint (`apksigner verify --print-certs <apk>`), which stays the
   same across releases.

## Before the first public release

- Look through Settings > About > Open source licenses once more if the ARMSX2 commit changed:
  a new library in the core needs a row in `ui/about/OpenSourceComponents.kt` and its license
  file in `collectArmsx2Licenses` (`emucore/build.gradle.kts`).
- Screenshots in the README or release notes: use homebrew or the BIOS menu rather than
  commercial games, and never show where to get BIOS files or games.
