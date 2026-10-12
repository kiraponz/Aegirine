# Native code finds these by name (FindClass / GetStaticMethodID / RegisterNatives),
# so R8 must keep their names and members.
-keep class kr.co.iefriends.pcsx2.NativeApp { *; }
-keep class com.armsx2.BiosInfo { *; }
-keep class org.libsdl.app.** { *; }
