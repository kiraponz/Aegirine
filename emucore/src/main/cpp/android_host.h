// The Android frontend's side of the core: the CPU thread, the render window and the base
// settings layer. jni.cpp drives it from Kotlin; android_host.cpp implements it together with
// the Host:: functions the core calls back into.

#pragma once

#include "common/types.h"

#include <jni.h>
#include <string>

class Error;
struct ANativeWindow;

namespace AndroidHost {

/// Called from JNI_OnLoad. [native_core] is a global reference to the Kotlin NativeCore class.
void SetJavaVM(JavaVM* vm, jclass native_core);

/// Sets up the folders under [data_dir], the default settings and the core's process-wide
/// state. Once per process.
bool Initialize(const std::string& data_dir, Error* error);

/// Boots [path] (empty for the BIOS alone) on the calling thread, which becomes the CPU thread,
/// and runs it until the system shuts down. False if it did not boot.
bool RunSystem(const std::string& path, Error* error);

/// True from the moment the system has started until it is destroyed.
bool HasActiveSystem();

void RequestShutdown();
void RequestPause(bool paused);

/// Writes one value to the base settings layer. Not applied to a running system until
/// [CommitSettings].
void SetBaseSetting(const char* section, const char* key, const char* value);
void CommitSettings();

/// The surface to render to, or null for none. Takes the reference [window] holds. Blocks
/// until the CPU thread has let go of the previous window.
void SetWindow(ANativeWindow* window, u32 width, u32 height);
void SetDisplayRefreshRate(float hz);

} // namespace AndroidHost
