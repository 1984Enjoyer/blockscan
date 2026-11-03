package ru.blockscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ru.blockscan.app.ui.AppNav
import ru.blockscan.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        setContent {
            val s by container.settings.flow.collectAsStateWithLifecycle()
            AppTheme(s.theme) { AppNav() }
        }
    }
}
