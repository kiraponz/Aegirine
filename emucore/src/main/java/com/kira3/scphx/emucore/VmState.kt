package com.kira3.scphx.emucore

enum class VmState {
    /** No game is running. */
    Stopped,

    /** [EmulatorCore.runVm] has been called and the VM is booting. */
    Starting,
    Running,
    Paused,
}
