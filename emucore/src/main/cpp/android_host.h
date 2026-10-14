// The Android frontend's side of the core: the CPU thread, the render window, the base
// settings layer and the pads. jni.cpp drives it from Kotlin; android_host.cpp implements it
// together with the Host:: functions the core calls back into.

#pragma once

#include "common/types.h"

#include <jni.h>
#include <optional>
#include <string>
#include <utility>
#include <vector>

class Error;
struct ANativeWindow;

namespace AndroidHost {

/// Called from JNI_OnLoad. [native_core] is a global reference to the Kotlin NativeCore class.
void SetJavaVM(JavaVM* vm, jclass native_core);

/// Sets up the folders under [data_dir], the default settings and the core's process-wide
/// state. Once per process.
bool Initialize(const std::string& data_dir, Error* error);

/// Boots [path] (empty for the BIOS alone) on the calling thread, which becomes the CPU thread,
/// and runs it until the system shuts down. Starts from the save state at [save_state] unless
/// it is empty. False if it did not boot.
bool RunSystem(const std::string& path, const std::string& save_state, Error* error);

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

/// Puts the picture at the top of a portrait surface, [top_margin] pixels down, when
/// [portrait_top]; otherwise in the middle.
void SetDisplayLayout(bool portrait_top, u32 top_margin);

/// Keeps the on-screen messages and overlays clear of these edges of the surface (the display cutout), in pixels.
void SetOsdSafeArea(u32 left, u32 top, u32 right, u32 bottom);

/// Sets bind [index] of the controller in [port] to [value] (0..1): one of AnalogController's
/// buttons, or one of its half axes after them. Ignored with no system running.
void SetPadBind(u32 port, u32 index, float value);

/// Moves the left or right stick of the controller in [port] to ([x], [y]), each -1..1 with +y down.
void SetPadStick(u32 port, bool right, float x, float y);

void SetFastForward(bool enabled);

/// Saves the running game to the state file at [path], or loads it from there. Blocks until done.
bool SaveState(const std::string& path, Error* error);
bool LoadState(const std::string& path, Error* error);

/// True while a memory card is waiting to be written; a save state is refused then.
bool IsSavingMemoryCards();

/// A point the running game can rewind to.
struct RewindPoint
{
  u32 age_ms;
  std::vector<u8> png;
};

/// The rewind history, newest first. Blocks until the CPU thread has read it.
std::vector<RewindPoint> GetRewindPoints();

/// Goes back to the point at [index] in GetRewindPoints(). Blocks until done.
bool LoadRewindPoint(u32 index);

/// The titles of the discs in the loaded playlist (.m3u), and which one is in the drive. Empty
/// when the loaded image is not a playlist or nothing runs. Blocks.
std::vector<std::string> GetDiscPlaylist(u32* current);

/// Puts disc [index] of the loaded playlist in the drive; the game sees the lid open and close.
/// Blocks.
bool SwitchDisc(u32 index);

/// Puts the disc image at [path] in the drive. Blocks.
bool InsertDisc(const std::string& path);

/// Writes what is on screen to [path] as a PNG. Returns straight away; the file is written on a
/// thread of its own.
void SaveScreenshot(const std::string& path);

/// The cheats to run: (name, code lines) pairs. They are installed in the running game now, and
/// again whenever the game changes (a boot, a disc change, a state load).
void SetCheats(std::vector<std::pair<std::string, std::string>> cheats);

/// [length] bytes of main RAM from [address], or nothing with no game or out of range. Blocks.
std::optional<std::vector<u8>> ReadMemory(u32 address, u32 length);

/// Writes [value] as [size] (1, 2 or 4) bytes at [address] of main RAM. Blocks.
bool WriteMemory(u32 address, u32 size, u32 value);

} // namespace AndroidHost
