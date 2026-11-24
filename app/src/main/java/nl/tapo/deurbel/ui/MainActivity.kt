package nl.tapo.deurbel.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.Prefs

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(0)
    private var themeMode by mutableStateOf("system")

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        themeMode = prefs.theme
        tab = if (prefs.configured) 0 else 3
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
        if (i?.getBooleanExtra(EXTRA_RING, false) == true && Prefs(this).configured) tab = 0
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
        val colors = if (dark) darkColorScheme(
            primary = Color(0xFFF5A524), onPrimary = Color(0xFF1A1203),
            secondary = Color(0xFF7FB3E6), background = Color(0xFF0B0D10), surface = Color(0xFF0B0D10),
            surfaceContainer = Color(0xFF15181D), surfaceVariant = Color(0xFF1E2228),
        ) else lightColorScheme(
            primary = Color(0xFFB45F00), onPrimary = Color.White,
            secondary = Color(0xFF1F5FA8), background = Color(0xFFF2F3F5), surface = Color(0xFFF2F3F5),
            surfaceContainer = Color.White, surfaceVariant = Color(0xFFE6E8EC),
        )
        MaterialTheme(colorScheme = colors) { Surface(color = MaterialTheme.colorScheme.background) {
            Scaffold(bottomBar = {
                NavigationBar {
                    listOf(
                        Triple("Live", Icons.Filled.Videocam, 0),
                        Triple("Opnames", Icons.Filled.VideoLibrary, 1),
                        Triple("Antwoorden", Icons.Filled.ChatBubble, 2),
                        Triple("Instellingen", Icons.Filled.Settings, 3),
                    ).forEach { (label, icon, i) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) }, label = { Text(label) })
                    }
                }
            }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when (tab) {
                        0 -> LiveScreen()
                        1 -> ClipsScreen()
                        2 -> ResponsesScreen()
                        else -> SettingsScreen(onSaved = { tab = 0 }, theme = themeMode,
                            onTheme = { themeMode = it; Prefs(this@MainActivity).theme = it })
                    }
                }
            }
        } }
    }

    companion object {
        const val EXTRA_RING = "ring"
    }
}
