package nl.tapo.deurbel.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
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
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFF5A623), secondary = Color(0xFF7FB3E6))) {
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
                        else -> SettingsScreen(onSaved = { tab = 0 })
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_RING = "ring"
    }
}
