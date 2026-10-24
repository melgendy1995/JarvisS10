package com.jarvis.app

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.app.system.KioskGate
import com.jarvis.app.ui.JarvisScreen
import com.jarvis.app.ui.theme.JarvisTheme

/**
 * Kiosk shell. Lock task stays off until a device owner whitelists the package
 * (see android:lockTaskMode="if_whitelisted"). Screen pinning is the daily-driver path.
 * The HOME intent-filter is commented in the manifest so a dev install does not steal the launcher.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            JarvisTheme {
                JarvisScreen(viewModel = viewModel())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KioskGate.attach(this)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        KioskGate.onJarvisVisible()
    }

    override fun onStop() {
        KioskGate.detach(this)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
