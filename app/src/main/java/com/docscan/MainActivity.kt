package com.docscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.docscan.ui.navigation.AppNavGraph
import com.docscan.ui.theme.MyApplicationTheme
import com.docscan.ui.viewmodel.ScannerViewModel
import com.docscan.util.ExternalFileRouter
import com.docscan.util.NotificationHelper
import com.docscan.util.ThemeManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            org.opencv.android.OpenCVLoader.initLocal()
        } catch (_: Throwable) {
            try {
                org.opencv.android.OpenCVLoader.initDebug()
            } catch (_: Throwable) {}
        }
        ThemeManager.init(this)
        NotificationHelper.initChannel(this)

        handleIncomingIntent(intent)

        setContent {
            val themeMode by ThemeManager.themeMode.collectAsStateWithLifecycle()
            val isDark = ThemeManager.isDarkThemeActive(themeMode)

            // Request POST_NOTIFICATIONS permission on Android 13+ (API 33+)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ -> }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            // Dynamically adjust status and navigation bars for clear text & icon contrast
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                )
                onDispose {}
            }

            MyApplicationTheme(darkTheme = isDark) {
                val navController = rememberNavController()
                AppNavGraph(
                    navController = navController,
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        lifecycleScope.launch {
            ExternalFileRouter.processIntent(this@MainActivity, intent)
        }
    }
}


