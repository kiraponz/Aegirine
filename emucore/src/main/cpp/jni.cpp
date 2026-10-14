// The JNI functions of com.kira3.ametrine.emucore.NativeCore.

#include "android_host.h"

#include "core/bios.h"
#include "core/system.h"

#include "common/error.h"
#include "common/log.h"
#include "common/md5_digest.h"

#include "scmversion/scmversion.h"

#include <android/native_window_jni.h>
#include <jni.h>
#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>

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

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(runSystem)(JNIEnv* env, jclass, jstring path)
{
  Error error;
  const bool ok = AndroidHost::RunSystem(ToString(env, path), &error);
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
