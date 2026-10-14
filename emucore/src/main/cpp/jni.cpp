// The JNI functions of com.kira3.ametrine.emucore.NativeCore.

#include "android_host.h"

#include "core/bios.h"
#include "core/cheats.h"
#include "core/game_database.h"
#include "core/system.h"

#include "util/cd_image.h"

#include "common/error.h"
#include "common/log.h"
#include "common/md5_digest.h"

#include "scmversion/scmversion.h"

#include <android/native_window_jni.h>
#include <jni.h>
#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <optional>
#include <span>
#include <string>
#include <vector>

Log_SetChannel(JNI);

namespace {
std::string ToString(JNIEnv* env, jstring str)
{
  if (!str)
    return {};

  const char* chars = env->GetStringUTFChars(str, nullptr);
  std::string ret(chars);
  env->ReleaseStringUTFChars(str, chars);
  return ret;
}

jbyteArray ToByteArray(JNIEnv* env, std::span<const u8> data)
{
  jbyteArray array = env->NewByteArray(static_cast<jsize>(data.size()));
  env->SetByteArrayRegion(array, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
  return array;
}

/// Alternating names and code lines, for the cheats in [list].
jobjectArray CheatsToArray(JNIEnv* env, const CheatList& list)
{
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(list.GetCodeCount() * 2), string_class, nullptr);
  for (u32 i = 0; i < list.GetCodeCount(); i++)
  {
    const CheatCode& code = list.GetCode(i);
    std::string lines;
    for (const CheatCode::Instruction& inst : code.instructions)
    {
      // 16-bit values as 4 digits, the 32-bit ones of the core's own codes as 8.
      if (inst.second > 0xFFFF)
        lines += fmt::format("{:08X} {:08X}\n", inst.first, inst.second);
      else
        lines += fmt::format("{:08X} {:04X}\n", inst.first, inst.second);
    }

    jstring name = env->NewStringUTF(code.description.c_str());
    jstring jlines = env->NewStringUTF(lines.c_str());
    env->SetObjectArrayElement(array, static_cast<jsize>(i * 2), name);
    env->SetObjectArrayElement(array, static_cast<jsize>(i * 2 + 1), jlines);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(jlines);
  }
  env->DeleteLocalRef(string_class);
  return array;
}

/// Null for no error.
jstring ErrorToString(JNIEnv* env, bool ok, const Error& error)
{
  if (ok)
    return nullptr;

  const std::string& description = error.GetDescription();
  return env->NewStringUTF(description.empty() ? "Unknown error" : description.c_str());
}
} // namespace

#define NATIVE_CORE(name) Java_com_kira3_ametrine_emucore_NativeCore_##name

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;

  jclass native_core = env->FindClass("com/kira3/ametrine/emucore/NativeCore");
  if (!native_core)
    return JNI_ERR;

  AndroidHost::SetJavaVM(vm, static_cast<jclass>(env->NewGlobalRef(native_core)));
  env->DeleteLocalRef(native_core);
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(initialize)(JNIEnv* env, jclass, jstring data_dir)
{
  Error error;
  const bool ok = AndroidHost::Initialize(ToString(env, data_dir), &error);
  return ErrorToString(env, ok, error);
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getBuildVersion)(JNIEnv* env, jclass)
{
  return env->NewStringUTF(g_scm_tag_str);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setSetting)(JNIEnv* env, jclass, jstring section, jstring key,
                                                          jstring value)
{
  AndroidHost::SetBaseSetting(ToString(env, section).c_str(), ToString(env, key).c_str(),
                              ToString(env, value).c_str());
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(commitSettings)(JNIEnv*, jclass)
{
  AndroidHost::CommitSettings();
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(runSystem)(JNIEnv* env, jclass, jstring path, jstring save_state)
{
  Error error;
  const bool ok = AndroidHost::RunSystem(ToString(env, path), ToString(env, save_state), &error);
  return ErrorToString(env, ok, error);
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(hasActiveSystem)(JNIEnv*, jclass)
{
  return AndroidHost::HasActiveSystem();
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(pause)(JNIEnv*, jclass)
{
  AndroidHost::RequestPause(true);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(resume)(JNIEnv*, jclass)
{
  AndroidHost::RequestPause(false);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(shutdown)(JNIEnv*, jclass)
{
  AndroidHost::RequestShutdown();
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setSurface)(JNIEnv* env, jclass, jobject surface, jint width,
                                                          jint height)
{
  ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
  AndroidHost::SetWindow(window, window ? static_cast<u32>(width) : 0, window ? static_cast<u32>(height) : 0);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setDisplayRefreshRate)(JNIEnv*, jclass, jfloat hz)
{
  AndroidHost::SetDisplayRefreshRate(hz);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setDisplayLayout)(JNIEnv*, jclass, jboolean portrait_top,
                                                                jint top_margin)
{
  AndroidHost::SetDisplayLayout(portrait_top, static_cast<u32>(std::max(top_margin, 0)));
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setPadBind)(JNIEnv*, jclass, jint port, jint index, jfloat value)
{
  AndroidHost::SetPadBind(static_cast<u32>(port), static_cast<u32>(index), value);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setPadStick)(JNIEnv*, jclass, jint port, jboolean right, jfloat x,
                                                           jfloat y)
{
  AndroidHost::SetPadStick(static_cast<u32>(port), right, x, y);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setFastForward)(JNIEnv*, jclass, jboolean enabled)
{
  AndroidHost::SetFastForward(enabled);
}

extern "C" JNIEXPORT jfloat JNICALL NATIVE_CORE(getFps)(JNIEnv*, jclass)
{
  return AndroidHost::HasActiveSystem() ? System::GetFPS() : 0.0f;
}

extern "C" JNIEXPORT jlong JNICALL NATIVE_CORE(getFrameNumber)(JNIEnv*, jclass)
{
  return AndroidHost::HasActiveSystem() ? System::GetFrameNumber() : 0;
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getGameSerial)(JNIEnv* env, jclass, jstring jpath)
{
  const std::string path = ToString(env, jpath);
  // A PS-EXE or PSF has no disc to read a serial from.
  if (System::IsExeFileName(path) || System::IsPsfFileName(path))
    return nullptr;

  Error error;
  std::unique_ptr<CDImage> image = CDImage::Open(path.c_str(), false, &error);
  if (!image)
  {
    WARNING_LOG("Could not open {}: {}", path, error.GetDescription());
    return nullptr;
  }

  // The database's serial where it knows the disc; it also knows games by hash.
  std::string id;
  System::GameHash hash;
  System::GetGameDetailsFromImage(image.get(), &id, &hash);
  if (const GameDatabase::Entry* entry = GameDatabase::GetEntryForGameDetails(id, hash))
    return env->NewStringUTF(entry->serial.c_str());

  return id.empty() ? nullptr : env->NewStringUTF(id.c_str());
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getGameDbTitle)(JNIEnv* env, jclass, jstring serial)
{
  const GameDatabase::Entry* entry = GameDatabase::GetEntryForSerial(ToString(env, serial));
  return entry ? env->NewStringUTF(entry->title.c_str()) : nullptr;
}

/// "<region>\t<description>" for a 512 KiB PS1 BIOS image, or null. region is the core's
/// ConsoleRegion (1 NTSC-J, 2 NTSC-U, 3 PAL), or -1 for an image the core does not know.
/// Takes ownership of [fd].
extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getBiosInfoFromFd)(JNIEnv* env, jclass, jint fd)
{
  // The core also takes a PS2 or PS3 image (it uses the PS1 part), but those are not what the
  // player means to pick here.
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size != BIOS::BIOS_SIZE)
  {
    WARNING_LOG("Not a PS1 BIOS: {} bytes", static_cast<s64>(st.st_size));
    close(fd);
    return nullptr;
  }

  // Read through the descriptor: reopening a SAF file by its /proc/self/fd path is refused.
  std::vector<u8> data(BIOS::BIOS_SIZE);
  size_t done = 0;
  while (done < data.size())
  {
    const ssize_t n = read(fd, data.data() + done, data.size() - done);
    if (n <= 0)
      break;
    done += static_cast<size_t>(n);
  }
  close(fd);
  if (done != data.size())
  {
    WARNING_LOG("Reading the BIOS stopped at {} bytes (errno {})", done, errno);
    return nullptr;
  }

  const BIOS::ImageInfo* info = BIOS::GetInfoForHash(data, MD5Digest::HashData(std::span<const u8>(data)));
  const int region = info ? static_cast<int>(info->region) : -1;
  const char* description = info ? info->description : "Unknown BIOS";
  return env->NewStringUTF(fmt::format("{}\t{}", region, description).c_str());
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(saveState)(JNIEnv* env, jclass, jstring path)
{
  Error error;
  const bool ok = AndroidHost::SaveState(ToString(env, path), &error);
  return ErrorToString(env, ok, error);
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(loadState)(JNIEnv* env, jclass, jstring path)
{
  Error error;
  const bool ok = AndroidHost::LoadState(ToString(env, path), &error);
  return ErrorToString(env, ok, error);
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(isSavingMemoryCards)(JNIEnv*, jclass)
{
  return AndroidHost::IsSavingMemoryCards();
}

/// [serial, PNG screenshot or null] from a save state's header, or null if [path] is not a save state.
extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(getSaveStateInfo)(JNIEnv* env, jclass, jstring jpath)
{
  const std::string path = ToString(env, jpath);
  std::optional<ExtendedSaveStateInfo> info = System::GetExtendedSaveStateInfo(path.c_str());
  // A header that does not read gives back the error as the title, and no time.
  if (!info.has_value() || info->timestamp == 0)
    return nullptr;

  jclass object_class = env->FindClass("java/lang/Object");
  jobjectArray ret = env->NewObjectArray(2, object_class, nullptr);
  env->DeleteLocalRef(object_class);

  jstring serial = env->NewStringUTF(info->serial.c_str());
  env->SetObjectArrayElement(ret, 0, serial);
  env->DeleteLocalRef(serial);

  if (info->screenshot.IsValid())
  {
    if (std::optional<std::vector<u8>> png = info->screenshot.SaveToBuffer("state.png"))
    {
      jbyteArray array = ToByteArray(env, *png);
      env->SetObjectArrayElement(ret, 1, array);
      env->DeleteLocalRef(array);
    }
  }
  return ret;
}

/// [ages in ms (long[]), PNG thumbnails (byte[][], an entry null when there is none)], newest first.
extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(getRewindPoints)(JNIEnv* env, jclass)
{
  const std::vector<AndroidHost::RewindPoint> points = AndroidHost::GetRewindPoints();
  const jsize count = static_cast<jsize>(points.size());

  jlongArray ages = env->NewLongArray(count);
  jclass byte_array_class = env->FindClass("[B");
  jobjectArray thumbnails = env->NewObjectArray(count, byte_array_class, nullptr);
  env->DeleteLocalRef(byte_array_class);
  for (jsize i = 0; i < count; i++)
  {
    const jlong age = points[i].age_ms;
    env->SetLongArrayRegion(ages, i, 1, &age);
    if (!points[i].png.empty())
    {
      jbyteArray png = ToByteArray(env, points[i].png);
      env->SetObjectArrayElement(thumbnails, i, png);
      env->DeleteLocalRef(png);
    }
  }

  jclass object_class = env->FindClass("java/lang/Object");
  jobjectArray ret = env->NewObjectArray(2, object_class, nullptr);
  env->DeleteLocalRef(object_class);
  env->SetObjectArrayElement(ret, 0, ages);
  env->SetObjectArrayElement(ret, 1, thumbnails);
  env->DeleteLocalRef(ages);
  env->DeleteLocalRef(thumbnails);
  return ret;
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(loadRewindPoint)(JNIEnv*, jclass, jint index)
{
  return index >= 0 && AndroidHost::LoadRewindPoint(static_cast<u32>(index));
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(saveScreenshot)(JNIEnv* env, jclass, jstring path)
{
  AndroidHost::SaveScreenshot(ToString(env, path));
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setCheats)(JNIEnv* env, jclass, jobjectArray names, jobjectArray codes)
{
  std::vector<std::pair<std::string, std::string>> cheats;
  const jsize count = std::min(env->GetArrayLength(names), env->GetArrayLength(codes));
  for (jsize i = 0; i < count; i++)
  {
    jstring name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    jstring code = static_cast<jstring>(env->GetObjectArrayElement(codes, i));
    cheats.emplace_back(ToString(env, name), ToString(env, code));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(code);
  }
  AndroidHost::SetCheats(std::move(cheats));
}

/// The cheats in a cheat file's [text] (PCSXR, libretro or ePSXe), as alternating names and code
/// lines, or null if it has none.
extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(parseCheats)(JNIEnv* env, jclass, jstring text)
{
  CheatList list;
  if (!list.LoadFromString(ToString(env, text), CheatList::Format::Autodetect) || list.GetCodeCount() == 0)
    return nullptr;
  return CheatsToArray(env, list);
}

/// The cheat database's cheats for [serial], as alternating names and code lines, or null if it has none.
extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(getDatabaseCheats)(JNIEnv* env, jclass, jstring serial)
{
  CheatList list;
  if (!list.LoadFromPackage(ToString(env, serial)) || list.GetCodeCount() == 0)
    return nullptr;
  return CheatsToArray(env, list);
}

extern "C" JNIEXPORT jbyteArray JNICALL NATIVE_CORE(readMemory)(JNIEnv* env, jclass, jint address, jint length)
{
  if (address < 0 || length < 0)
    return nullptr;
  const std::optional<std::vector<u8>> data =
    AndroidHost::ReadMemory(static_cast<u32>(address), static_cast<u32>(length));
  return data.has_value() ? ToByteArray(env, *data) : nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(writeMemory)(JNIEnv*, jclass, jint address, jint size, jint value)
{
  return address >= 0 &&
         AndroidHost::WriteMemory(static_cast<u32>(address), static_cast<u32>(size), static_cast<u32>(value));
}
