// Placeholders for the Host:: functions the frontend does not implement yet, so the library
// links. Each one goes away as the real frontend implements it (android_host.cpp).

#include "core/achievements.h"
#include "core/fullscreen_ui.h"
#include "core/game_list.h"
#include "core/host.h"
#include "core/system.h"

#include "util/gpu_device.h"
#include "util/http_downloader.h"
#include "util/imgui_fullscreen.h"
#include "util/imgui_manager.h"
#include "util/input_manager.h"
#include "util/input_source.h"
#include "util/platform_misc.h"

#include "common/error.h"
#include "common/log.h"
#include "common/small_string.h"

#include <cstdlib>
#include <cstring>

Log_SetChannel(Host);

bool Host::ConfirmMessage(std::string_view title, std::string_view message)
{
  return true;
}

void Host::ReportDebuggerMessage(std::string_view message)
{
}

std::span<const std::pair<const char*, const char*>> Host::GetAvailableLanguageList()
{
  return {};
}

bool Host::ChangeLanguage(const char* new_language)
{
  return false;
}

s32 Host::Internal::GetTranslatedStringImpl(std::string_view context, std::string_view msg, char* tbuf,
                                            size_t tbuf_space)
{
  // Untranslated: the message itself.
  if (msg.size() > tbuf_space)
    return -1;
  std::memcpy(tbuf, msg.data(), msg.size());
  return static_cast<s32>(msg.size());
}

std::string Host::TranslatePluralToString(const char* context, const char* msg, const char* disambiguation, int count)
{
  return std::string(TranslatePluralToSmallString(context, msg, disambiguation, count).view());
}

SmallString Host::TranslatePluralToSmallString(const char* context, const char* msg, const char* disambiguation,
                                               int count)
{
  SmallString ret(msg);
  ret.replace("%n", TinyString::from_format("{}", count));
  return ret;
}

void Host::OnIdleStateChanged()
{
}

void Host::OnPerformanceCountersUpdated()
{
}

void Host::OnGameChanged(const std::string& disc_path, const std::string& game_serial, const std::string& game_name)
{
}

void Host::OnMediaCaptureStarted()
{
}

void Host::OnMediaCaptureStopped()
{
}

void Host::RequestResizeHostDisplay(s32 width, s32 height)
{
}

void Host::RequestExitApplication(bool save_state_if_running)
{
}

void Host::RequestExitBigPicture()
{
}

void Host::RequestSystemShutdown(bool allow_confirm, bool save_state)
{
  Host::RunOnCPUThread([save_state]() { System::ShutdownSystem(save_state); });
}

bool Host::IsFullscreen()
{
  return true;
}

void Host::SetFullscreen(bool enabled)
{
}

void Host::OpenURL(std::string_view url)
{
}

bool Host::CopyTextToClipboard(std::string_view text)
{
  return false;
}

void Host::SetMouseMode(bool relative, bool hide_cursor)
{
}

void Host::OnAchievementsLoginRequested(Achievements::LoginRequestReason reason)
{
}

void Host::OnAchievementsLoginSuccess(const char* username, u32 points, u32 sc_points, u32 unread_messages)
{
}

void Host::OnAchievementsRefreshed()
{
}

void Host::OnAchievementsHardcoreModeChanged(bool enabled)
{
}

void Host::OnCoverDownloaderOpenRequested()
{
}

bool Host::ShouldPreferHostFileSelector()
{
  return false;
}

void Host::OpenHostFileSelectorAsync(std::string_view title, bool select_directory, FileSelectorCallback callback,
                                     FileSelectorFilters filters /* = FileSelectorFilters() */,
                                     std::string_view initial_directory /* = std::string_view() */)
{
  callback(std::string());
}

std::optional<u32> InputManager::ConvertHostKeyboardStringToCode(std::string_view str)
{
  return std::nullopt;
}

std::optional<std::string> InputManager::ConvertHostKeyboardCodeToString(u32 code)
{
  return std::nullopt;
}

const char* InputManager::ConvertHostKeyboardCodeToIcon(u32 code)
{
  return nullptr;
}

void Host::AddFixedInputBindings(SettingsInterface& si)
{
}

void Host::OnInputDeviceConnected(std::string_view identifier, std::string_view device_name)
{
}

void Host::OnInputDeviceDisconnected(InputBindingKey key, std::string_view identifier)
{
}

std::optional<WindowInfo> Host::GetTopLevelWindowInfo()
{
  return std::nullopt;
}

void Host::RefreshGameListAsync(bool invalidate_cache)
{
}

void Host::CancelGameListRefresh()
{
}

std::unique_ptr<InputSource> InputSource::CreateAndroidSource()
{
  return nullptr;
}

std::unique_ptr<HTTPDownloader> HTTPDownloader::Create(std::string user_agent)
{
  return nullptr;
}

BEGIN_HOTKEY_LIST(g_host_hotkeys)
END_HOTKEY_LIST()
