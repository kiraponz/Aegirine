package com.kira3.scphx.data

import android.app.ActivityManager
import android.content.Context

/** Whether a game is running (or still shutting down) in the :emu process, which owns the cards and states then. */
internal fun isGameProcessRunning(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return manager.runningAppProcesses.orEmpty().any { it.processName == "${context.packageName}:emu" }
}
