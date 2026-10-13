package com.kira3.ametrine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kira3.ametrine.ui.AmetrineApp
import com.kira3.ametrine.ui.theme.AmetrineTheme

/** Library, settings and BIOS setup. Gameplay runs in its own activity. */
class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        // Settings changed from the game's menu were written by another process.
        (application as AmetrineApplication).container.settingsRepository.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmetrineTheme {
                AmetrineApp()
            }
        }
    }
}
