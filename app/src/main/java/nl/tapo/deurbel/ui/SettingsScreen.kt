package nl.tapo.deurbel.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.Clips
import nl.tapo.deurbel.Dev
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.Scanner
import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.TapoClient

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(
    onSaved: () -> Unit,
    theme: String = "system",
    onTheme: (String) -> Unit = {},
    onDevicesChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val devices = remember(version) { prefs.devices }
    var openDev by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf(prefs.password) }
    var pwText by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    var autoTime by remember { mutableStateOf(prefs.autoUpdateTime) }
    var hd by remember { mutableStateOf(prefs.hdLive) }
    var advanced by remember { mutableStateOf(prefs.rtspUser.isNotBlank()) }
    var rtspUser by remember { mutableStateOf(prefs.rtspUser) }
    var rtspPw by remember { mutableStateOf(prefs.rtspPassword) }

    openDev?.let { id ->
        DeviceScreen(id, onBack = { openDev = null; version++ }, onChanged = { version++; onDevicesChanged() })
        return
    }

    fun applyAutoUpdate() {
        prefs.autoUpdate = autoUpdate; prefs.autoUpdateTime = autoTime
        scope.launch(Dispatchers.IO) {
            prefs.devices.forEach { d -> try { Camera.applyAutoUpdate(ctx, Camera.client(ctx, d.id)) } catch (_: Exception) {} }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Instellingen", style = MaterialTheme.typography.headlineMedium)

        // ---- account ----
        Text("TP-Link-account", style = MaterialTheme.typography.titleMedium)
        Text("De app praat rechtstreeks met je deurbel en camera's via je eigen wifi. Het wachtwoord is hetzelfde als in de Tapo-app.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Wachtwoord TP-Link-account") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (password != prefs.password) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                prefs.password = password; Camera.reset(); DoorbellService.restart(ctx); pwText = "Wachtwoord opgeslagen"
            }) { Text("Wachtwoord opslaan") }
        }
        pwText?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

        // ---- devices ----
        HorizontalDivider()
        Text("Apparaten", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) Text("Nog geen apparaat gekoppeld.", style = MaterialTheme.typography.bodySmall)
        devices.forEach { d ->
            Card(Modifier.fillMaxWidth().clickable { openDev = d.id }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (d.isDoorbell) Icons.Filled.NotificationsActive else Icons.Filled.Videocam, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text((if (d.isDoorbell) "Deurbel" else "Camera") + " · " + d.host, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { adding = true }) { Text("+ Apparaat toevoegen") }

        // ---- automatic updates ----
        HorizontalDivider()
        Text("Software", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatisch bijwerken")
                Text("Alle apparaten zoeken en installeren zelf updates", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it; applyAutoUpdate() })
        }
        if (autoUpdate) {
            Row(Modifier.fillMaxWidth().clickable {
                val (h, m) = autoTime.split(":").map { it.toInt() }
                TimePickerDialog(ctx, { _, hh, mm -> autoTime = String.format("%02d:%02d", hh, mm); applyAutoUpdate() }, h, m, true).show()
            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tijdstip")
                    Text("Lokale tijd, als niemand kijkt", style = MaterialTheme.typography.bodySmall)
                }
                Text(autoTime, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        // ---- look ----
        HorizontalDivider()
        Text("Weergave", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Automatisch", "light" to "Licht", "dark" to "Donker").forEach { (key, label) ->
                FilterChip(selected = theme == key, onClick = { onTheme(key) }, label = { Text(label) })
            }
        }

        // ---- notifications ----
        HorizontalDivider()
        Text("Meldingen", style = MaterialTheme.typography.titleMedium)
        Text("De app luistert op de achtergrond zolang je telefoon op het thuis-wifi zit.", style = MaterialTheme.typography.bodySmall)
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Batterijbeperking uitzetten (aanbevolen)") }
        }
        if (Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Volledig scherm bij aanbellen toestaan") }
        }

        // ---- advanced ----
        HorizontalDivider()
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Geavanceerd verbergen" else "Geavanceerd") }
        if (advanced) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live beeld in hoge kwaliteit", Modifier.weight(1f)); Switch(checked = hd, onCheckedChange = { hd = it; prefs.hdLive = it })
            }
            Text("Optioneel: live beeld via RTSP met het camera-account (alleen nodig als het standaard live beeld niet werkt).",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = rtspUser, onValueChange = { rtspUser = it; prefs.rtspUser = it }, label = { Text("Camera-account gebruikersnaam") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = rtspPw, onValueChange = { rtspPw = it; prefs.rtspPassword = it }, label = { Text("Camera-account wachtwoord") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                Clips.clearCache(ctx); Clips.cleanup(ctx, 0); pwText = "Tijdelijke opnamebestanden gewist"
            }) { Text("Tijdelijke opnamebestanden wissen") }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (adding) AddDeviceDialog(
        onDismiss = { adding = false },
        onAdded = { first ->
            adding = false; version++; DoorbellService.restart(ctx); onDevicesChanged()
            if (first) onSaved()
        },
        passwordProvider = { password.ifBlank { prefs.password } },
        savePassword = { if (it != prefs.password) { prefs.password = it; Camera.reset() } },
    )
}

/** Finds Tapo devices on the wifi, tests the login and adds the chosen one. */
@Composable
private fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onAdded: (first: Boolean) -> Unit,
    passwordProvider: () -> String,
    savePassword: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var ip by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val known = prefs.devices.map { it.host }.toSet()
        found = try { Scanner.scan(ctx).filter { it !in known } } catch (_: Exception) { emptyList() }
        found?.firstOrNull()?.let { if (ip.isBlank()) ip = it }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Apparaat toevoegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    found == null -> Text("Zoeken naar Tapo-apparaten op je wifi…")
                    found!!.isEmpty() -> Text("Geen nieuw apparaat gevonden. Vul het adres in (te vinden in de app van je router).")
                    else -> found!!.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { ip = f }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = ip == f, onClick = { ip = f }); Text("Tapo-apparaat · $f")
                        }
                    }
                }
                OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP-adres") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Naam, bijv. Tuin") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && ip.isNotBlank(), onClick = {
                val pw = passwordProvider()
                if (pw.isBlank()) { error = "Vul eerst bovenaan het wachtwoord van je TP-Link-account in"; return@TextButton }
                busy = true; error = null
                scope.launch {
                    try {
                        var pin: String? = null
                        val (info, doorbell) = withContext(Dispatchers.IO) {
                            val c = TapoClient(ip.trim(), pw, CameraHttps(null) { pin = it })
                            val i = c.deviceInfo(); i to c.isDoorbell(i)
                        }
                        savePassword(pw)
                        val first = prefs.devices.isEmpty()
                        val id = prefs.newDeviceId()
                        val nice = name.trim().ifEmpty { info.optString("device_alias").ifEmpty { if (doorbell) "Voordeur" else "Camera" } }
                        prefs.devices = prefs.devices + Dev(id, nice, ip.trim(), if (doorbell) "doorbell" else "camera")
                        Prefs(ctx, id).certPin = pin
                        if (first) prefs.currentId = id
                        onAdded(first)
                    } catch (e: Exception) {
                        error = "Toevoegen mislukt: ${Camera.friendly(e)}"
                    }
                    busy = false
                }
            }) { Text(if (busy) "Bezig…" else "Toevoegen") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annuleren") } },
    )
}
