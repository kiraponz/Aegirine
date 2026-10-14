// The Android frontend's side of the core: the CPU thread, the render window, the base
// settings layer and the pads. jni.cpp drives it from Kotlin; android_host.cpp implements it
// together with the Host:: functions the core calls back into.

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

/// Sets bind [index] of the controller in [port] to [value] (0..1): one of AnalogController's
/// buttons, or one of its half axes after them. Ignored with no system running.
void SetPadBind(u32 port, u32 index, float value);

/// Moves the left or right stick of the controller in [port] to ([x], [y]), each -1..1 with +y down.
void SetPadStick(u32 port, bool right, float x, float y);

void SetFastForward(bool enabled);

} // namespace AndroidHost
