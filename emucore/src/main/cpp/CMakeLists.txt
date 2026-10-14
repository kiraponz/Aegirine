# The Android frontend of aegirine-core. The core's own CMakeLists.txt is the root of the native
# build (emucore/build.gradle.kts points at it) and adds this directory through
# ANDROID_FRONTEND_DIR, so `core`, `util` and `common` are targets here.

add_library(emucore SHARED
  android_host.cpp
  android_host.h
  host_stubs.cpp
  jni.cpp
)

target_link_libraries(emucore PRIVATE core util common scmversion log android)
