#include "core/system.h"

#include <jni.h>

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
  return JNI_VERSION_1_6;
}

// Nothing calls this yet. It pulls the VM's entry points into the library, so the link
// checks every Host:: function the core needs; the real JNI functions replace it.
extern "C" JNIEXPORT void JNICALL Java_com_kira3_ametrine_emucore_NativeCore_linkCheck(JNIEnv* env, jclass clazz)
{
  System::BootSystem(SystemBootParameters(std::string()), nullptr);
  System::Execute();
  System::ShutdownSystem(false);
}
