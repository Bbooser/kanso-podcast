package com.kanso.podcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import androidx.compose.runtime.*
import com.kanso.podcast.ui.KansoApp
import com.kanso.podcast.ui.KansoPalette
import com.kanso.podcast.ui.KansoTheme
import com.kanso.podcast.data.AutomationWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationWorker.schedule(this, getSharedPreferences("automation", MODE_PRIVATE).getBoolean("wifi_only", true))
        setContent {
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            val prefs = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
            var palette by remember {
                mutableStateOf(runCatching { KansoPalette.valueOf(prefs.getString("palette", "Tierra")!!) }.getOrDefault(KansoPalette.Tierra))
            }
            KansoTheme(dark, palette) {
                KansoApp(dark, palette,
                    onDarkChange = { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                    onPaletteChange = { palette = it; prefs.edit().putString("palette", it.name).apply() }
                )
            }
        }
    }
}
