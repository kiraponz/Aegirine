package com.kira3.scphx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kira3.scphx.ui.ScphxApp
import com.kira3.scphx.ui.theme.ScphxTheme

/** Library, settings and BIOS setup. Gameplay runs in its own activity. */
class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        // Settings changed from the game's menu were written by another process.
        (application as ScphxApplication).container.settingsRepository.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScphxTheme {
                ScphxApp()
            }
        }
    }
}
