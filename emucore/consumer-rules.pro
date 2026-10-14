# Native code finds this by name (FindClass / GetStaticMethodID), so R8 must keep its name
# and members.
-keep class com.kira3.ametrine.emucore.NativeCore { *; }
