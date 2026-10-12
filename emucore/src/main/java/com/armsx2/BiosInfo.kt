package com.armsx2

/**
 * BIOS info as parsed by the core's IsBIOSFromFd. Built from native code
 * (native-lib.cpp) with FindClass("com/armsx2/BiosInfo"), so the class name and the
 * constructor signature (IILjava/lang/String;Ljava/lang/String;)V must not change.
 *
 * [region] is the ROMVER region code (BiosTools.cpp): 0 Japan, 1 USA, 2 Europe,
 * 4 Asia, 6 China, 8 T10K, 9 Test, 10 Free. [version] packs the major version in the
 * high byte and the minor in the low byte (v2.30 is 0x0230).
 */
internal class BiosInfo(
    @JvmField val version: Int,
    @JvmField val region: Int,
    @JvmField val description: String,
    @JvmField val zone: String,
)
