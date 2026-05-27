package com.ptylr.librearm

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ptylr.librearm.health.HealthConnectManager
import com.ptylr.librearm.prefs.Preferences
import com.ptylr.librearm.ui.LibreArmApp
import com.ptylr.librearm.ui.theme.LibreArmTheme

class MainActivity : ComponentActivity() {
    private val viewModel: BpViewModel by viewModels()
    private lateinit var healthManager: HealthConnectManager
    private lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bars (dark icons on a transparent scrim) so the status bar
        // remains legible over the light app background regardless of the system theme.
        // The Auto/Light/Dark selector in a later commit replaces this with a
        // theme-aware DisposableEffect.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        healthManager = HealthConnectManager(this)
        preferences = Preferences(this)

        setContent {
            LibreArmTheme {
                LibreArmApp(
                    viewModel = viewModel,
                    healthManager = healthManager,
                    preferences = preferences,
                    onOpenUrl = ::openUrl,
                    onLaunchInstallIntent = {
                        runCatching { startActivity(healthManager.installIntent()) }
                    }
                )
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
