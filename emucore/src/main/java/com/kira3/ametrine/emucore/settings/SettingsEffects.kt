package com.kira3.ametrine.emucore.settings

/** When a changed setting reaches a running game. Everything not listed applies right away. */
object SettingsEffects {
    /** Dotted paths of the settings that only take effect when a game next boots. */
    val appliesOnNextBoot: Set<String> = setOf(
        "output.renderer",
        "output.customDriverId",
        "output.turnipUbwcHint",
        "output.affinityMode",
        "output.swThreadsHeight",
        "output.autoProgressiveScan",
        "display.disableFramebufferFetch",
        "display.hwAa1",
        "display.forceMaliFbFetch",
        "display.useAngleOpenGL",
        "display.overrideTextureBarriers",
        "display.gsBackThreadMode",
        "display.disableVertexShaderExpand",
        "display.useBlitSwapChain",
        "display.disableShaderCache",
        "display.vsyncEnable",
        "hwFixes.gpuProfile",
        "emuCore.enableFastBoot",
        "emuCore.hostFs",
        "emuCore.gamefixGoemonTlb",
        "emuCore.gamefixVuSync",
        "audio.spu2NeonReverb",
        "system.biosFilename",
        "system.memoryCardSlot1Enabled",
        "system.memoryCardSlot1Filename",
        "system.memoryCardSlot2Enabled",
        "system.memoryCardSlot2Filename",
    )
}
