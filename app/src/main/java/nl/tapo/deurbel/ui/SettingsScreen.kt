package nl.tapo.deurbel.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.Scanner

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(prefs.host) }
    var password by remember { mutableStateOf(prefs.password) }
    var hd by remember { mutableStateOf(prefs.hdLive) }
    var rtspUser by remember { mutableStateOf(prefs.rtspUser) }
    var rtspPw by remember { mutableStateOf(prefs.rtspPassword) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var ringTypes by remember { mutableStateOf(prefs.ringTypes) }
    var advanced by remember { mutableStateOf(prefs.rtspUser.isNotBlank()) }
    var fwText by remember { mutableStateOf<String?>(null) }
    var fwNew by remember { mutableStateOf<String?>(null) }
    var fwBusy by remember { mutableStateOf(false) }
    var fwConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Deurbel koppelen", style = MaterialTheme.typography.titleLarge)
        Text("De app praat rechtstreeks met je Tapo D235 via je eigen wifi, zonder de Tapo-app of cloud. " +
            "Vul het IP-adres van de deurbel in en het wachtwoord van je TP-Link/Tapo-account.",
            style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP-adres deurbel") },
                placeholder = { Text("192.168.1.50") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f))
            TextButton(enabled = !scanning, onClick = {
                scanning = true; status = "Zoeken naar Tapo-apparaten op je wifi…"
                scope.launch {
                    found = try { Scanner.scan(ctx) } catch (e: Exception) { emptyList() }
                    status = if (found.isEmpty()) "Niets gevonden. Zoek het IP-adres op in je router." else "Gevonden: kies hieronder je deurbel."
                    scanning = false
                }
            }) { Text(if (scanning) "Zoeken…" else "Zoek") }
        }
        found.forEach { ip -> OutlinedButton(onClick = { host = ip }, modifier = Modifier.fillMaxWidth()) { Text(ip) } }

        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Wachtwoord TP-Link-account") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live beeld in hoge kwaliteit", Modifier.weight(1f)); Switch(checked = hd, onCheckedChange = { hd = it })
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (host.trim() != prefs.host) prefs.certPin = null
            prefs.host = host; prefs.password = password; prefs.hdLive = hd
            prefs.rtspUser = rtspUser; prefs.rtspPassword = rtspPw
            Camera.reset()
            status = "Verbinding testen…"
            scope.launch {
                status = try {
                    val info = withContext(Dispatchers.IO) { Camera.client(ctx).deviceInfo() }
                    DoorbellService.restart(ctx)
                    "Verbonden met ${info.optString("device_model", "deurbel")} " +
                        "(${info.optString("device_alias", "")}, firmware ${info.optString("sw_version", "?")})"
                } catch (e: Exception) { "Verbinden mislukt: ${Camera.friendly(e)}" }
                if (status!!.startsWith("Verbonden")) onSaved()
            }
        }) { Text("Opslaan en verbinden") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

        HorizontalDivider()
        Text("Meldingen", style = MaterialTheme.typography.titleMedium)
        Text("De app luistert op de achtergrond naar de deurbel zolang je telefoon op het thuis-wifi zit.",
            style = MaterialTheme.typography.bodySmall)
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
        val seen = prefs.seenTypes.sorted()
        if (seen.isNotEmpty()) {
            Text("Melden bij gebeurtenistype (niets aangevinkt = alles):", style = MaterialTheme.typography.bodySmall)
            seen.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t in ringTypes, onCheckedChange = { on ->
                        ringTypes = if (on) ringTypes + t else ringTypes - t
                        prefs.ringTypes = ringTypes
                    })
                    Text("Type $t")
                }
            }
        }

        HorizontalDivider()
        Text("Software van de deurbel", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !fwBusy && prefs.configured, onClick = {
            fwBusy = true; fwNew = null; fwText = "Zoeken naar updates…"
            scope.launch {
                fwText = try {
                    withContext(Dispatchers.IO) {
                        val c = Camera.client(ctx)
                        val current = c.deviceInfo().optString("sw_version")
                        val up = c.checkFirmware()
                        if (nl.tapo.deurbel.core.TapoClient.firmwareAvailable(up, current)) {
                            fwNew = up.optString("version")
                            "Nieuwe versie ${up.optString("version")} beschikbaar (nu: $current)." +
                                up.optString("release_note").let { if (it.isBlank()) "" else "\n\n$it" }
                        } else "Je deurbel is up-to-date (versie $current)."
                    }
                } catch (e: Exception) { "Zoeken lukte niet: ${Camera.friendly(e)}" }
                fwBusy = false
            }
        }) { Text(if (fwBusy) "Bezig…" else "Zoeken naar updates") }
        fwText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (fwNew != null) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = { fwConfirm = true }) { Text("Nu installeren") }
        }

        HorizontalDivider()
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Geavanceerd verbergen" else "Geavanceerd") }
        if (advanced) {
            Text("Optioneel: live beeld via RTSP met het camera-account (alleen nodig als het standaard live beeld niet werkt).",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = rtspUser, onValueChange = { rtspUser = it }, label = { Text("Camera-account gebruikersnaam") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = rtspPw, onValueChange = { rtspPw = it }, label = { Text("Camera-account wachtwoord") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                prefs.certPin = null; Camera.reset(); status = "Certificaat vergeten; wordt bij volgende verbinding opnieuw vastgelegd."
            }) { Text("Deurbel-certificaat opnieuw vastleggen") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                prefs.hiddenClips = emptySet(); Clips.clearCache(ctx); status = "Verwijderde opnames weer zichtbaar, tijdelijke bestanden gewist."
            }) { Text("Verborgen opnames terugzetten") }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (fwConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { fwConfirm = false },
            title = { Text("Update installeren?") },
            text = { Text("De deurbel downloadt versie $fwNew zelf bij TP-Link en start daarna opnieuw op. Hij is dan een paar minuten niet bereikbaar. Haal de stroom er niet af.") },
            confirmButton = {
                TextButton(onClick = {
                    fwConfirm = false
                    scope.launch {
                        fwText = try {
                            withContext(Dispatchers.IO) { Camera.client(ctx).startFirmwareUpgrade() }
                            fwNew = null
                            "Update gestart. De deurbel start over een paar minuten opnieuw op."
                        } catch (e: Exception) { "Installeren mislukt: ${Camera.friendly(e)}" }
                    }
                }) { Text("Installeren") }
            },
            dismissButton = { TextButton(onClick = { fwConfirm = false }) { Text("Annuleren") } }
        )
    }
}
