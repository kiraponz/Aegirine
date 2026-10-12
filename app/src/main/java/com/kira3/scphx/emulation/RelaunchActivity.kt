package com.kira3.scphx.emulation

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat

/**
 * Runs in the main process and starts EmulationActivity again. EmulationActivity cannot do
 * that itself: a start request made from the :emu process that is about to be killed could be
 * delivered to that dying process. Shows nothing.
 */
class RelaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java)
        if (target == null) {
            finish()
            return
        }
        // Give the old :emu process time to die, so the new one starts clean.
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(target)
            finish()
        }, RESTART_DELAY_MS)
    }

    companion object {
        private const val EXTRA_TARGET = "com.kira3.scphx.extra.RELAUNCH_TARGET"
        private const val RESTART_DELAY_MS = 400L

        fun intent(context: android.content.Context, target: Intent): Intent =
            Intent(context, RelaunchActivity::class.java).putExtra(EXTRA_TARGET, target)
    }
}
