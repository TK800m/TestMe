package nl.tapo.deurbel.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import nl.tapo.deurbel.Clips
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.Prefs

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(0)
    private var themeMode by mutableStateOf("system")
    private var devId by mutableStateOf("")

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        themeMode = prefs.theme
        devId = prefs.currentId
        Clips.cleanup(this)
        tab = if (prefs.configured) TAB_LIVE else TAB_SETTINGS
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        DoorbellService.sync(this)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.getStringExtra(EXTRA_DEV)?.let { selectDevice(it) }
        if (i?.getBooleanExtra(EXTRA_RING, false) == true && Prefs(this).configured) tab = TAB_LIVE
    }

    private fun selectDevice(id: String) {
        val p = Prefs(this)
        if (p.devices.any { it.id == id }) { p.currentId = id; devId = id }
    }

    @Composable
    private fun App() {
        val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
        LaunchedEffect(dark) {
            val transparent = android.graphics.Color.TRANSPARENT
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            )
        }
        val snackbar = remember { SnackbarHostState() }
        val haptic = rememberHaptic()
        MaterialTheme(colorScheme = appColors(dark), typography = AppTypography) {
            CompositionLocalProvider(LocalSnackbar provides snackbar) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            listOf(
                                Tab("Live", Icons.Filled.Videocam, Icons.Outlined.Videocam, TAB_LIVE),
                                Tab("Opnames", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, TAB_CLIPS),
                                Tab("Instellingen", Icons.Filled.Settings, Icons.Outlined.Settings, TAB_SETTINGS),
                            ).forEach { t ->
                                val on = tab == t.index
                                NavigationBarItem(
                                    selected = on, onClick = { if (!on) { haptic(false); tab = t.index } },
                                    icon = { Icon(if (on) t.selected else t.icon, contentDescription = null) },
                                    label = { Text(t.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Palette.AccentInk, indicatorColor = Palette.Accent,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        Crossfade(tab, label = "tab") { current ->
                            // everything below is rebuilt when another device is chosen
                            key(devId) {
                                when (current) {
                                    TAB_LIVE -> LiveScreen(onSelectDevice = ::selectDevice, onOpenClips = { tab = TAB_CLIPS },
                                        onOpenSettings = { tab = TAB_SETTINGS })
                                    TAB_CLIPS -> ClipsScreen(onSelectDevice = ::selectDevice)
                                    else -> SettingsScreen(onSaved = { tab = TAB_LIVE }, theme = themeMode,
                                        onTheme = { themeMode = it; Prefs(this@MainActivity).theme = it },
                                        onDevicesChanged = { devId = Prefs(this@MainActivity).currentId })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class Tab(val label: String, val selected: androidx.compose.ui.graphics.vector.ImageVector,
                           val icon: androidx.compose.ui.graphics.vector.ImageVector, val index: Int)

    companion object {
        const val EXTRA_RING = "ring"
        const val EXTRA_DEV = "dev"
        private const val TAB_LIVE = 0
        private const val TAB_CLIPS = 1
        private const val TAB_SETTINGS = 2
    }
}
