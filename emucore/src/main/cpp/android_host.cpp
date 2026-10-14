// The CPU thread, the render window, the settings, the pads and the SAF files, and the Host::
// functions that go with them. The remaining Host:: functions are still placeholders in host_stubs.cpp.

#include "android_host.h"

#include "core/analog_controller.h"
#include "core/bus.h"
#include "core/cheats.h"
#include "core/controller.h"
#include "core/cpu_core.h"
#include "core/host.h"
#include "core/pad.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/gpu_device.h"
#include "util/image.h"
#include "util/imgui_manager.h"
#include "util/input_manager.h"
#include "util/window_info.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"
#include "common/string_util.h"

#include <android/native_window.h>

#include <algorithm>
#include <array>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>

Log_SetChannel(AndroidHost);

namespace AndroidHost {
static JNIEnv* GetJNIEnv();
static void CallVoidCallback(jmethodID method, jboolean arg);
static void OnPadVibration(u32 pad_index, float large_motor, float small_motor);
static void SendPadVibration(u32 pad_index, u8 large_motor, u8 small_motor);
static void StopPadVibration();
static void RestorePadVibration();
static void ApplyDisplayLayout();
static void InstallCheats();
static std::string GetResourcePath(std::string_view filename, bool allow_override);

static JavaVM* s_java_vm = nullptr;
static jclass s_native_core = nullptr;
static jmethodID s_on_vm_paused_changed = nullptr;
static jmethodID s_on_pad_rumble = nullptr;
static jmethodID s_saf_open = nullptr;
static jmethodID s_saf_stat = nullptr;
static jmethodID s_saf_list = nullptr;

/// The core's /saf/ paths, which NativeCore (SafFiles.kt) turns into SAF documents.
class SafFileProvider final : public FileSystem::VirtualFileProvider
{
public:
  bool IsVirtualPath(std::string_view path) const override;
  int OpenFD(const char* path, Error* error) override;
  bool StatFile(const char* path, FILESYSTEM_STAT_DATA* sd) override;
  bool ListDirectory(const char* path, FileSystem::FindResultsArray* results) override;
};

static SafFileProvider s_saf_file_provider;

static MemorySettingsInterface s_base_settings;

// The thread inside RunSystem. Work for it from other threads goes through the queue, which it
// runs once a frame (Host::PumpMessagesOnCPUThread), or straight away while paused.
static std::atomic<std::thread::id> s_cpu_thread_id;
static std::mutex s_queue_mutex;
static std::condition_variable s_queue_cv;
static std::condition_variable s_queue_done_cv;
static std::deque<std::function<void()>> s_queue;
static bool s_cpu_thread_running = false;

static std::atomic_bool s_system_active{false};

// What the game last set each pad's motors to (0..255), so a resume can start them again.
// CPU thread only.
static std::array<std::array<u8, 2>, NUM_CONTROLLER_AND_CARD_PORTS> s_pad_vibration = {};

// The window the GPU device renders to. We hold a reference to it until the device has
// switched to another one, so it stays valid however late the CPU thread gets to the switch.
static std::mutex s_window_mutex;
static std::condition_variable s_window_cv;
static ANativeWindow* s_window = nullptr;
static u32 s_window_width = 0;
static u32 s_window_height = 0;
static float s_refresh_rate = 0.0f;

// Where the picture sits (SetDisplayLayout). Guarded by s_window_mutex.
static bool s_portrait_top = true;
static u32 s_top_margin = 0;
static ImGuiManager::SafeAreaInsets s_osd_safe_area = {};

// The app's cheats (SetCheats), installed in each game the core runs.
static std::mutex s_cheats_mutex;
static std::vector<CheatCode> s_cheats;

// How long a boot waits for the activity's surface before it starts without one.
static constexpr auto WINDOW_WAIT_TIMEOUT = std::chrono::seconds(2);
} // namespace AndroidHost

void AndroidHost::SetJavaVM(JavaVM* vm, jclass native_core)
{
  s_java_vm = vm;
  s_native_core = native_core;

  JNIEnv* env = GetJNIEnv();
  s_on_vm_paused_changed = env->GetStaticMethodID(native_core, "onVmPausedChanged", "(Z)V");
  s_on_pad_rumble = env->GetStaticMethodID(native_core, "onPadRumble", "(III)V");
  s_saf_open = env->GetStaticMethodID(native_core, "safOpen", "(Ljava/lang/String;)I");
  s_saf_stat = env->GetStaticMethodID(native_core, "safStat", "(Ljava/lang/String;)[J");
  s_saf_list = env->GetStaticMethodID(native_core, "safList", "(Ljava/lang/String;)[Ljava/lang/String;");
}

JNIEnv* AndroidHost::GetJNIEnv()
{
  JNIEnv* env;
  if (s_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK)
    return env;

  // A core thread Java has not seen yet. It stays attached until it exits.
  s_java_vm->AttachCurrentThread(&env, nullptr);
  return env;
}

void AndroidHost::CallVoidCallback(jmethodID method, jboolean arg)
{
  JNIEnv* env = GetJNIEnv();
  env->CallStaticVoidMethod(s_native_core, method, arg);
  if (env->ExceptionCheck())
  {
    // The Kotlin side catches everything; this is the last line, so the core thread lives on.
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

bool AndroidHost::Initialize(const std::string& data_dir, Error* error)
{
  Log::SetDebugOutputParams(true);
  Log::SetLogLevel(LOGLEVEL_INFO);

  EmuFolders::AppRoot = data_dir;
  EmuFolders::DataRoot = data_dir;
  EmuFolders::Resources = Path::Combine(data_dir, "resources");

  {
    auto lock = Host::GetSettingsLock();
    Host::Internal::SetBaseSettingsLayer(&s_base_settings);
    System::SetDefaultSettings(s_base_settings);
    EmuFolders::SetDefaults();
    EmuFolders::Save(s_base_settings);
    Settings::SetDefaultControllerConfig(s_base_settings);

    // Logcat. Settings::UpdateLogSettings reads this when the CPU thread loads the settings.
    s_base_settings.SetBoolValue("Logging", "LogToDebug", true);

    // There is no Android input source: the app drives the pads directly.
    s_base_settings.SetBoolValue("InputSources", "Android", false);

  }

  InputManager::SetPadVibrationCallback(&OnPadVibration);
  FileSystem::SetVirtualFileProvider(&s_saf_file_provider);

  if (!EmuFolders::EnsureFoldersExist())
    WARNING_LOG("Could not create every folder under {}", data_dir);

  INFO_LOG("Data directory: {}", EmuFolders::DataRoot);
  return System::Internal::PerformEarlyHardwareChecks(error) && System::Internal::ProcessStartup(error);
}

bool AndroidHost::RunSystem(const std::string& path, const std::string& save_state, Error* error)
{
  s_cpu_thread_id.store(std::this_thread::get_id());
  {
    std::unique_lock lock(s_queue_mutex);
    s_cpu_thread_running = true;
  }

  bool booted = false;
  if (System::Internal::CPUThreadInitialize(error))
  {
    {
      // The activity's surface usually exists by now; if not, the device starts surfaceless and
      // picks the window up when it comes.
      std::unique_lock lock(s_window_mutex);
      s_window_cv.wait_for(lock, WINDOW_WAIT_TIMEOUT, []() { return s_window != nullptr; });
    }

    SystemBootParameters parameters(path);
    parameters.save_state = save_state;
    booted = System::BootSystem(std::move(parameters), error);
    while (System::IsValid())
    {
      if (System::IsRunning())
      {
        // Returns when the system pauses or shuts down.
        System::Execute();
        continue;
      }

      {
        std::unique_lock lock(s_queue_mutex);
        s_queue_cv.wait(lock, []() { return !s_queue.empty(); });
      }
      Host::PumpMessagesOnCPUThread();
    }

    System::Internal::CPUThreadShutdown();
  }

  {
    std::unique_lock lock(s_queue_mutex);
    s_cpu_thread_running = false;
  }
  // Anything queued before the flag went down, including callers blocked on it.
  Host::PumpMessagesOnCPUThread();
  s_cpu_thread_id.store(std::thread::id());
  return booted;
}

bool AndroidHost::HasActiveSystem()
{
  return s_system_active.load();
}

void AndroidHost::RequestShutdown()
{
  Host::RunOnCPUThread([]() { System::ShutdownSystem(false); });
}

void AndroidHost::RequestPause(bool paused)
{
  Host::RunOnCPUThread([paused]() { System::PauseSystem(paused); });
}

void AndroidHost::SetBaseSetting(const char* section, const char* key, const char* value)
{
  auto lock = Host::GetSettingsLock();
  s_base_settings.SetStringValue(section, key, value);
}

void AndroidHost::CommitSettings()
{
  Host::RunOnCPUThread([]() {
    if (System::IsValid())
      System::ApplySettings(false);
  });
}

void AndroidHost::SetWindow(ANativeWindow* window, u32 width, u32 height)
{
  ANativeWindow* old;
  {
    std::unique_lock lock(s_window_mutex);
    old = s_window;
    s_window = window;
    s_window_width = width;
    s_window_height = height;
  }
  s_window_cv.notify_all();

  if (window && window == old)
  {
    // The same surface at a new size. We already hold a reference to it.
    ANativeWindow_release(window);
    Host::RunOnCPUThread([width, height]() {
      Host::ResizeDisplayWindow(width, height, 1.0f);
      ApplyDisplayLayout();
    });
    return;
  }

  // The device may still be rendering to the old window, so it only goes once the device has
  // moved to the new one (or to none).
  Host::RunOnCPUThread(
    []() {
      Host::UpdateDisplayWindow();
      ApplyDisplayLayout();
    },
    true);
  if (old)
    ANativeWindow_release(old);
}

void AndroidHost::SetDisplayRefreshRate(float hz)
{
  std::unique_lock lock(s_window_mutex);
  s_refresh_rate = hz;
}

void AndroidHost::SetDisplayLayout(bool portrait_top, u32 top_margin)
{
  {
    std::unique_lock lock(s_window_mutex);
    s_portrait_top = portrait_top;
    s_top_margin = top_margin;
  }
  Host::RunOnCPUThread([]() { ApplyDisplayLayout(); });
}

void AndroidHost::ApplyDisplayLayout()
{
  // The core aligns along the axis with room to spare, which is the vertical one on a portrait
  // surface; on a landscape one "top" would mean "left", so the picture stays in the middle.
  std::unique_lock lock(s_window_mutex);
  const bool top = s_portrait_top && s_window_height > s_window_width;
  g_settings.display_alignment = top ? DisplayAlignment::LeftOrTop : DisplayAlignment::Center;
  g_settings.display_top_margin = static_cast<u16>(top ? std::min<u32>(s_top_margin, 0xFFFF) : 0);
  ImGuiManager::SetSafeAreaInsets(s_osd_safe_area);
}

void AndroidHost::SetOsdSafeArea(u32 left, u32 top, u32 right, u32 bottom)
{
  {
    std::unique_lock lock(s_window_mutex);
    s_osd_safe_area = {static_cast<float>(left), static_cast<float>(top), static_cast<float>(right),
                       static_cast<float>(bottom)};
  }
  Host::RunOnCPUThread([]() { ApplyDisplayLayout(); });
}

void AndroidHost::SetPadBind(u32 port, u32 index, float value)
{
  if (!s_system_active.load() || port >= NUM_CONTROLLER_AND_CARD_PORTS)
    return;

  Host::RunOnCPUThread([port, index, value]() {
    if (Controller* controller = Pad::GetController(port))
      controller->SetBindState(index, value);
  });
}

void AndroidHost::SetPadStick(u32 port, bool right, float x, float y)
{
  if (!s_system_active.load() || port >= NUM_CONTROLLER_AND_CARD_PORTS)
    return;

  using HalfAxis = AnalogController::HalfAxis;
  static constexpr u32 FIRST = static_cast<u32>(AnalogController::Button::Count);
  const u32 left_axis = FIRST + static_cast<u32>(right ? HalfAxis::RLeft : HalfAxis::LLeft);
  const u32 right_axis = FIRST + static_cast<u32>(right ? HalfAxis::RRight : HalfAxis::LRight);
  const u32 down_axis = FIRST + static_cast<u32>(right ? HalfAxis::RDown : HalfAxis::LDown);
  const u32 up_axis = FIRST + static_cast<u32>(right ? HalfAxis::RUp : HalfAxis::LUp);

  Host::RunOnCPUThread([=]() {
    Controller* controller = Pad::GetController(port);
    if (!controller)
      return;

    // Release the opposite direction first, so the axis never has both halves set.
    if (x >= 0.0f)
    {
      controller->SetBindState(left_axis, 0.0f);
      controller->SetBindState(right_axis, std::min(x, 1.0f));
    }
    else
    {
      controller->SetBindState(right_axis, 0.0f);
      controller->SetBindState(left_axis, std::min(-x, 1.0f));
    }
    if (y >= 0.0f)
    {
      controller->SetBindState(up_axis, 0.0f);
      controller->SetBindState(down_axis, std::min(y, 1.0f));
    }
    else
    {
      controller->SetBindState(down_axis, 0.0f);
      controller->SetBindState(up_axis, std::min(-y, 1.0f));
    }
  });
}

void AndroidHost::SetFastForward(bool enabled)
{
  Host::RunOnCPUThread([enabled]() { System::SetFastForwardEnabled(enabled); });
}

bool AndroidHost::SaveState(const std::string& path, Error* error)
{
  bool ok = false;
  Host::RunOnCPUThread(
    [&path, error, &ok]() {
      if (!System::IsValid())
        Error::SetStringView(error, "No game is running.");
      else
        ok = System::SaveState(path.c_str(), error, false);
    },
    true);
  return ok;
}

bool AndroidHost::LoadState(const std::string& path, Error* error)
{
  bool ok = false;
  Host::RunOnCPUThread(
    [&path, error, &ok]() {
      if (!System::IsValid())
        Error::SetStringView(error, "No game is running.");
      else
        ok = System::LoadState(path.c_str(), error, false);
    },
    true);
  return ok;
}

bool AndroidHost::IsSavingMemoryCards()
{
  bool saving = false;
  Host::RunOnCPUThread([&saving]() { saving = System::IsValid() && System::IsSavingMemoryCards(); }, true);
  return saving;
}

std::vector<AndroidHost::RewindPoint> AndroidHost::GetRewindPoints()
{
  std::vector<System::RewindPoint> points;
  float frame_rate = 60.0f;
  Host::RunOnCPUThread(
    [&points, &frame_rate]() {
      if (!System::IsValid())
        return;
      points = System::GetRewindPoints();
      frame_rate = System::IsPALRegion() ? 50.0f : 59.94f;
    },
    true);

  // Encoded here, off the CPU thread.
  std::vector<RewindPoint> ret;
  ret.reserve(points.size());
  for (const System::RewindPoint& point : points)
  {
    RewindPoint& out = ret.emplace_back();
    out.age_ms = static_cast<u32>(static_cast<float>(point.frames_ago) * 1000.0f / frame_rate);
    if (point.thumbnail.IsValid())
    {
      if (std::optional<std::vector<u8>> png = point.thumbnail.SaveToBuffer("rewind.png"))
        out.png = std::move(*png);
    }
  }
  return ret;
}

bool AndroidHost::LoadRewindPoint(u32 index)
{
  bool ok = false;
  Host::RunOnCPUThread([index, &ok]() { ok = System::IsValid() && System::LoadRewindPoint(index); }, true);
  return ok;
}

void AndroidHost::SaveScreenshot(const std::string& path)
{
  Host::RunOnCPUThread([path]() {
    if (System::IsValid())
    {
      // The game's picture alone, at its own resolution and shape, not the whole portrait surface.
      System::SaveScreenshot(path.c_str(), DisplayScreenshotMode::InternalResolution, DisplayScreenshotFormat::PNG);
    }
  });
}

void AndroidHost::SetCheats(std::vector<std::pair<std::string, std::string>> cheats)
{
  std::vector<CheatCode> codes;
  codes.reserve(cheats.size());
  for (auto& [name, lines] : cheats)
  {
    CheatCode code;
    code.description = std::move(name);
    code.enabled = true;
    if (code.SetInstructionsFromString(lines) && code.Valid())
      codes.push_back(std::move(code));
    else
      WARNING_LOG("Skipping cheat '{}': no code the core understands", code.description);
  }

  {
    std::unique_lock lock(s_cheats_mutex);
    s_cheats = std::move(codes);
  }
  if (s_system_active.load())
    Host::RunOnCPUThread(&InstallCheats);
}

void AndroidHost::InstallCheats()
{
  std::unique_ptr<CheatList> list;
  {
    std::unique_lock lock(s_cheats_mutex);
    if (!s_cheats.empty())
    {
      list = std::make_unique<CheatList>();
      for (const CheatCode& code : s_cheats)
        list->AddCode(code);
    }
  }

  // Codes that hold a value in place let go of it when they are switched off.
  if (const CheatList* old = System::GetCheatList())
  {
    for (u32 i = 0; i < old->GetCodeCount(); i++)
    {
      const CheatCode& code = old->GetCode(i);
      bool kept = false;
      for (u32 j = 0; list && j < list->GetCodeCount(); j++)
        kept |= (list->GetCode(j).instructions.size() == code.instructions.size() &&
                 std::equal(code.instructions.begin(), code.instructions.end(), list->GetCode(j).instructions.begin(),
                            [](const auto& a, const auto& b) { return a.bits == b.bits; }));
      if (code.enabled && !kept)
        code.ApplyOnDisable();
    }
  }

  System::SetCheatList(std::move(list));
}

std::optional<std::vector<u8>> AndroidHost::ReadMemory(u32 address, u32 length)
{
  std::optional<std::vector<u8>> ret;
  Host::RunOnCPUThread(
    [address, length, &ret]() {
      if (!System::IsValid() || address > Bus::g_ram_size || length > Bus::g_ram_size - address)
        return;
      ret.emplace(Bus::g_ram + address, Bus::g_ram + address + length);
    },
    true);
  return ret;
}

bool AndroidHost::WriteMemory(u32 address, u32 size, u32 value)
{
  bool ok = false;
  Host::RunOnCPUThread(
    [address, size, value, &ok]() {
      if (!System::IsValid() || address > Bus::g_ram_size || size > Bus::g_ram_size - address)
        return;

      // Through the CPU, so recompiled code from the bytes written is thrown away.
      switch (size)
      {
        case 1:
          ok = CPU::SafeWriteMemoryByte(address, static_cast<u8>(value));
          break;
        case 2:
          ok = CPU::SafeWriteMemoryHalfWord(address, static_cast<u16>(value));
          break;
        case 4:
          ok = CPU::SafeWriteMemoryWord(address, value);
          break;
        default:
          break;
      }
    },
    true);
  return ok;
}

void AndroidHost::OnPadVibration(u32 pad_index, float large_motor, float small_motor)
{
  if (pad_index >= s_pad_vibration.size())
    return;

  const u8 large = static_cast<u8>(std::clamp(large_motor, 0.0f, 1.0f) * 255.0f);
  const u8 small = static_cast<u8>(std::clamp(small_motor, 0.0f, 1.0f) * 255.0f);
  if (s_pad_vibration[pad_index][0] == large && s_pad_vibration[pad_index][1] == small)
    return;

  s_pad_vibration[pad_index] = {large, small};
  SendPadVibration(pad_index, large, small);
}

void AndroidHost::SendPadVibration(u32 pad_index, u8 large_motor, u8 small_motor)
{
  JNIEnv* env = GetJNIEnv();
  env->CallStaticVoidMethod(s_native_core, s_on_pad_rumble, static_cast<jint>(pad_index),
                            static_cast<jint>(large_motor), static_cast<jint>(small_motor));
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void AndroidHost::StopPadVibration()
{
  for (u32 i = 0; i < s_pad_vibration.size(); i++)
  {
    if (s_pad_vibration[i][0] != 0 || s_pad_vibration[i][1] != 0)
      SendPadVibration(i, 0, 0);
  }
}

void AndroidHost::RestorePadVibration()
{
  for (u32 i = 0; i < s_pad_vibration.size(); i++)
  {
    if (s_pad_vibration[i][0] != 0 || s_pad_vibration[i][1] != 0)
      SendPadVibration(i, s_pad_vibration[i][0], s_pad_vibration[i][1]);
  }
}

bool AndroidHost::SafFileProvider::IsVirtualPath(std::string_view path) const
{
  return path.starts_with("/saf/");
}

int AndroidHost::SafFileProvider::OpenFD(const char* path, Error* error)
{
  JNIEnv* env = GetJNIEnv();
  jstring jpath = env->NewStringUTF(path);
  const jint fd = env->CallStaticIntMethod(s_native_core, s_saf_open, jpath);
  env->DeleteLocalRef(jpath);
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
    Error::SetErrno(error, EIO);
    return -1;
  }

  if (fd < 0)
    Error::SetErrno(error, ENOENT);
  return fd;
}

bool AndroidHost::SafFileProvider::StatFile(const char* path, FILESYSTEM_STAT_DATA* sd)
{
  JNIEnv* env = GetJNIEnv();
  jstring jpath = env->NewStringUTF(path);
  jlongArray result = static_cast<jlongArray>(env->CallStaticObjectMethod(s_native_core, s_saf_stat, jpath));
  env->DeleteLocalRef(jpath);
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  if (!result)
    return false;

  // [size, modified, is folder]
  jlong values[3] = {};
  env->GetLongArrayRegion(result, 0, 3, values);
  env->DeleteLocalRef(result);
  sd->Size = values[0];
  sd->ModificationTime = static_cast<std::time_t>(values[1]);
  sd->CreationTime = sd->ModificationTime;
  sd->Attributes = (values[2] != 0) ? FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY : 0;
  return true;
}

bool AndroidHost::SafFileProvider::ListDirectory(const char* path, FileSystem::FindResultsArray* results)
{
  JNIEnv* env = GetJNIEnv();
  jstring jpath = env->NewStringUTF(path);
  jobjectArray entries = static_cast<jobjectArray>(env->CallStaticObjectMethod(s_native_core, s_saf_list, jpath));
  env->DeleteLocalRef(jpath);
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  if (!entries)
    return false;

  // Each "name\tis folder\tsize\tmodified".
  const jsize count = env->GetArrayLength(entries);
  for (jsize i = 0; i < count; i++)
  {
    jstring jentry = static_cast<jstring>(env->GetObjectArrayElement(entries, i));
    const char* chars = env->GetStringUTFChars(jentry, nullptr);
    const std::vector<std::string_view> fields = StringUtil::SplitString(chars, '\t', false);
    if (fields.size() == 4)
    {
      FILESYSTEM_FIND_DATA& fd = results->emplace_back();
      fd.FileName = fields[0];
      fd.Attributes = (fields[1] == "1") ? FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY : 0;
      fd.Size = StringUtil::FromChars<s64>(fields[2]).value_or(0);
      fd.ModificationTime = static_cast<std::time_t>(StringUtil::FromChars<s64>(fields[3]).value_or(0));
      fd.CreationTime = fd.ModificationTime;
    }
    env->ReleaseStringUTFChars(jentry, chars);
    env->DeleteLocalRef(jentry);
  }
  env->DeleteLocalRef(entries);
  return true;
}

std::string AndroidHost::GetResourcePath(std::string_view filename, bool allow_override)
{
  return allow_override ? EmuFolders::GetOverridableResourcePath(filename) :
                          Path::Combine(EmuFolders::Resources, filename);
}

void Host::ReportFatalError(std::string_view title, std::string_view message)
{
  ERROR_LOG("{}: {}", title, message);
  std::abort();
}

void Host::ReportErrorAsync(std::string_view title, std::string_view message)
{
  ERROR_LOG("{}: {}", title, message);
}

void Host::LoadSettings(SettingsInterface& si, std::unique_lock<std::mutex>& lock)
{
  // Settings the frontend works out itself, over what the core just loaded.
  AndroidHost::ApplyDisplayLayout();
}

void Host::CheckForSettingsChanges(const Settings& old_settings)
{
}

void Host::CommitBaseSettingChanges()
{
  // The base layer lives in memory; the app writes it again before every boot.
}

bool Host::ResourceFileExists(std::string_view filename, bool allow_override)
{
  const std::string path = AndroidHost::GetResourcePath(filename, allow_override);
  return FileSystem::FileExists(path.c_str());
}

std::optional<DynamicHeapArray<u8>> Host::ReadResourceFile(std::string_view filename, bool allow_override)
{
  const std::string path = AndroidHost::GetResourcePath(filename, allow_override);
  std::optional<DynamicHeapArray<u8>> ret(FileSystem::ReadBinaryFile(path.c_str()));
  if (!ret.has_value())
    ERROR_LOG("Failed to read resource file '{}'", filename);
  return ret;
}

std::optional<std::string> Host::ReadResourceFileToString(std::string_view filename, bool allow_override)
{
  const std::string path = AndroidHost::GetResourcePath(filename, allow_override);
  std::optional<std::string> ret(FileSystem::ReadFileToString(path.c_str()));
  if (!ret.has_value())
    ERROR_LOG("Failed to read resource file to string '{}'", filename);
  return ret;
}

std::optional<std::time_t> Host::GetResourceFileTimestamp(std::string_view filename, bool allow_override)
{
  const std::string path = AndroidHost::GetResourcePath(filename, allow_override);
  FILESYSTEM_STAT_DATA sd;
  if (!FileSystem::StatFile(path.c_str(), &sd))
  {
    ERROR_LOG("Failed to stat resource file '{}'", filename);
    return std::nullopt;
  }

  return sd.ModificationTime;
}

void Host::OnGameChanged(const std::string& disc_path, const std::string& game_serial, const std::string& game_name)
{
  // The core drops the cheats when the game changes.
  if (System::IsValidOrInitializing())
    AndroidHost::InstallCheats();
}

void Host::OnSystemStarting()
{
}

void Host::OnSystemStarted()
{
  AndroidHost::s_system_active.store(true);
  AndroidHost::CallVoidCallback(AndroidHost::s_on_vm_paused_changed, JNI_FALSE);
}

void Host::OnSystemDestroyed()
{
  AndroidHost::s_system_active.store(false);
  AndroidHost::StopPadVibration();
  AndroidHost::s_pad_vibration = {};
}

void Host::OnSystemPaused()
{
  AndroidHost::StopPadVibration();
  AndroidHost::CallVoidCallback(AndroidHost::s_on_vm_paused_changed, JNI_TRUE);
}

void Host::OnSystemResumed()
{
  AndroidHost::RestorePadVibration();
  AndroidHost::CallVoidCallback(AndroidHost::s_on_vm_paused_changed, JNI_FALSE);
}

void Host::PumpMessagesOnCPUThread()
{
  std::deque<std::function<void()>> queue;
  {
    std::unique_lock lock(AndroidHost::s_queue_mutex);
    queue.swap(AndroidHost::s_queue);
  }

  for (std::function<void()>& function : queue)
    function();
}

void Host::RunOnCPUThread(std::function<void()> function, bool block /* = false */)
{
  using namespace AndroidHost;

  if (std::this_thread::get_id() == s_cpu_thread_id.load())
  {
    function();
    return;
  }

  std::unique_lock lock(s_queue_mutex);
  if (!s_cpu_thread_running)
  {
    // No system: a blocking call has nothing to wait for, and anything else waits for the
    // next boot.
    if (block)
    {
      lock.unlock();
      function();
    }
    else
    {
      s_queue.push_back(std::move(function));
    }
    return;
  }

  if (!block)
  {
    s_queue.push_back(std::move(function));
    s_queue_cv.notify_one();
    return;
  }

  bool done = false;
  s_queue.push_back([&function, &done]() {
    function();
    std::unique_lock lock(s_queue_mutex);
    done = true;
    s_queue_done_cv.notify_all();
  });
  s_queue_cv.notify_one();
  s_queue_done_cv.wait(lock, [&done]() { return done; });
}

std::optional<WindowInfo> Host::AcquireRenderWindow(bool recreate_window)
{
  using namespace AndroidHost;

  std::unique_lock lock(s_window_mutex);
  WindowInfo wi;
  if (s_window)
  {
    wi.type = WindowInfo::Type::Android;
    wi.window_handle = s_window;
    wi.surface_width = s_window_width;
    wi.surface_height = s_window_height;
    wi.surface_refresh_rate = s_refresh_rate;
  }
  return wi;
}

void Host::ReleaseRenderWindow()
{
  // The window's reference is ours (SetWindow), not the device's.
}

void Host::FrameDone()
{
}
