// The CPU thread, the render window and the settings, and the Host:: functions that go with
// them. The remaining Host:: functions are still placeholders in host_stubs.cpp.

#include "android_host.h"

#include "core/host.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/gpu_device.h"
#include "util/window_info.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"

#include <android/native_window.h>

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
static std::string GetResourcePath(std::string_view filename, bool allow_override);

static JavaVM* s_java_vm = nullptr;
static jclass s_native_core = nullptr;
static jmethodID s_on_vm_paused_changed = nullptr;

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

// The window the GPU device renders to. We hold a reference to it until the device has
// switched to another one, so it stays valid however late the CPU thread gets to the switch.
static std::mutex s_window_mutex;
static std::condition_variable s_window_cv;
static ANativeWindow* s_window = nullptr;
static u32 s_window_width = 0;
static u32 s_window_height = 0;
static float s_refresh_rate = 0.0f;

// How long a boot waits for the activity's surface before it starts without one.
static constexpr auto WINDOW_WAIT_TIMEOUT = std::chrono::seconds(2);
} // namespace AndroidHost

void AndroidHost::SetJavaVM(JavaVM* vm, jclass native_core)
{
  s_java_vm = vm;
  s_native_core = native_core;

  JNIEnv* env = GetJNIEnv();
  s_on_vm_paused_changed = env->GetStaticMethodID(native_core, "onVmPausedChanged", "(Z)V");
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

    // TODO: AAudio (step 3).
    s_base_settings.SetStringValue("Audio", "Backend", "Null");
  }

  if (!EmuFolders::EnsureFoldersExist())
    WARNING_LOG("Could not create every folder under {}", data_dir);

  INFO_LOG("Data directory: {}", EmuFolders::DataRoot);
  return System::Internal::PerformEarlyHardwareChecks(error) && System::Internal::ProcessStartup(error);
}

bool AndroidHost::RunSystem(const std::string& path, Error* error)
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

    booted = System::BootSystem(SystemBootParameters(path), error);
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
    Host::RunOnCPUThread([width, height]() { Host::ResizeDisplayWindow(width, height, 1.0f); });
    return;
  }

  // The device may still be rendering to the old window, so it only goes once the device has
  // moved to the new one (or to none).
  Host::RunOnCPUThread([]() { Host::UpdateDisplayWindow(); }, true);
  if (old)
    ANativeWindow_release(old);
}

void AndroidHost::SetDisplayRefreshRate(float hz)
{
  std::unique_lock lock(s_window_mutex);
  s_refresh_rate = hz;
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
}

void Host::OnSystemPaused()
{
  AndroidHost::CallVoidCallback(AndroidHost::s_on_vm_paused_changed, JNI_TRUE);
}

void Host::OnSystemResumed()
{
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
