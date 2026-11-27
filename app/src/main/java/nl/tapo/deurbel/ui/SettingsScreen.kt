package nl.tapo.deurbel.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.Clips
import nl.tapo.deurbel.Dev
import nl.tapo.deurbel.Dnd
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.QuickResponse
import nl.tapo.deurbel.Responses
import nl.tapo.deurbel.Scanner
import nl.tapo.deurbel.VoiceRecorder
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
    val snackbar = LocalSnackbar.current
    var version by remember { mutableIntStateOf(0) }
    val devices = remember(version) { prefs.devices }
    var openDev by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editPw by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(prefs.notifications) }
    var dndUntil by remember { mutableLongStateOf(prefs.dndUntil) }
    var showDnd by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    var autoTime by remember { mutableStateOf(prefs.autoUpdateTime) }
    var hd by remember { mutableStateOf(prefs.hdLive) }
    var advanced by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf(Responses.list(ctx)) }
    var editing by remember { mutableStateOf<QuickResponse?>(null) }
    var newReply by remember { mutableStateOf(false) }
    // re-check system permissions when coming back from Android settings
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Instellingen")

        // ---------------- first run: one clear next step ----------------
        if (!prefs.configured) {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Palette.Accent.copy(alpha = 0.14f)).border(1.dp, Palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Welkom! Koppel je eerste apparaat", style = MaterialTheme.typography.titleLarge)
                Text("1. Vul het wachtwoord van je TP-Link-account in (zelfde als in de Tapo-app).\n2. Tik op Apparaat toevoegen; de app zoekt je deurbel zelf.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { if (prefs.password.isBlank()) editPw = true else adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (prefs.password.isBlank()) "Wachtwoord invullen" else "Apparaat toevoegen")
                }
            }
        }

        // ---------------- devices ----------------
        SectionTitle("Apparaten")
        GroupCard {
            devices.forEach { d ->
                SettingRow(if (d.isDoorbell) Icons.Filled.NotificationsActive else Icons.Filled.Videocam,
                    if (d.isDoorbell) Palette.Accent else Palette.Blue, d.name,
                    (if (d.isDoorbell) "Deurbel" else "Camera") + " · " + d.host, onClick = { openDev = d.id })
            }
            SettingRow(Icons.Filled.Add, Palette.Green, "Apparaat toevoegen", "Deurbel of camera op je wifi", divider = false,
                onClick = { if (prefs.password.isBlank()) editPw = true else adding = true })
        }

        // ---------------- notifications ----------------
        SectionTitle("Meldingen")
        val pm = remember(resumeTick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
        val fullScreenOk = remember(resumeTick) {
            Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }
        val notifPermission = remember(resumeTick) {
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
        GroupCard {
            ToggleRow(Icons.Filled.Notifications, Palette.Red, "Meldingen", if (notify) "Bij aanbellen en gekozen gebeurtenissen" else "Uit",
                notify) { notify = it; prefs.notifications = it; DoorbellService.sync(ctx) }
            SettingRow(Icons.Filled.DarkMode, Palette.Indigo, "Niet storen",
                if (dndUntil > System.currentTimeMillis()) "Aan tot ${hhmm(dndUntil / 1000)}" else "Uit", onClick = { showDnd = true })
            if (!notifPermission) SettingRow(Icons.Filled.Notifications, Palette.Red, "Meldingen toestaan", "Android blokkeert nu de meldingen",
                subtitleColor = MaterialTheme.colorScheme.error, onClick = { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) })
            if (!pm) SettingRow(Icons.Filled.BatterySaver, Palette.Accent, "Batterijbeperking uitzetten", "Nodig zodat meldingen altijd binnenkomen",
                subtitleColor = MaterialTheme.colorScheme.error, onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                })
            if (!fullScreenOk) SettingRow(Icons.Filled.Fullscreen, Palette.Accent, "Volledig scherm bij aanbellen", "Toestaan, zodat je het direct ziet",
                onClick = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) })
            SettingRow(Icons.Filled.Tune, Palette.Grey, "Waarvoor een melding?", "Per apparaat in te stellen", divider = false,
                onClick = { devices.firstOrNull()?.let { openDev = it.id } })
        }

        // ---------------- quick replies ----------------
        SectionTitle("Snelle antwoorden")
        GroupCard {
            replies.forEach { r ->
                val own = r.useVoice && Responses.hasVoice(ctx, r.id)
                SettingRow(if (own) Icons.Filled.RecordVoiceOver else Icons.Filled.ChatBubble, if (own) Palette.Red else Palette.Blue,
                    r.text, if (own) "Eigen stem" else "Computerstem", onClick = { editing = r })
            }
            SettingRow(Icons.Filled.Add, Palette.Green, "Nieuw antwoord", null, divider = false, onClick = { newReply = true })
        }

        // ---------------- software ----------------
        SectionTitle("Software")
        GroupCard {
            ToggleRow(Icons.Filled.SystemUpdate, Palette.Green, "Automatisch bijwerken", "Alle apparaten installeren zelf updates",
                autoUpdate, divider = autoUpdate) { autoUpdate = it; applyAutoUpdate() }
            if (autoUpdate) SettingRow(Icons.Filled.Schedule, Palette.Grey, "Tijdstip", "Lokale tijd, als niemand kijkt", divider = false,
                onClick = {
                    val (h, m) = autoTime.split(":").map { it.toInt() }
                    TimePickerDialog(ctx, { _, hh, mm -> autoTime = String.format("%02d:%02d", hh, mm); applyAutoUpdate() }, h, m, true).show()
                }) { Text(autoTime, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
        }

        // ---------------- look ----------------
        SectionTitle("Weergave")
        GroupCard {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Filled.Palette, Palette.Grey)
                Spacer(Modifier.width(14.dp))
                Segmented(listOf("system" to "Automatisch", "light" to "Licht", "dark" to "Donker"), theme, Modifier.weight(1f), onTheme)
            }
        }

        // ---------------- account & advanced ----------------
        SectionTitle("Account")
        GroupCard {
            SettingRow(Icons.Filled.Key, Palette.Grey, "TP-Link-wachtwoord", if (prefs.password.isBlank()) "Nog niet ingevuld" else "Ingesteld • tik om te wijzigen",
                onClick = { editPw = true })
            SettingRow(Icons.Filled.Tune, Palette.Grey, "Geavanceerd", if (advanced) "Verbergen" else "Kwaliteit en opslag", divider = advanced,
                onClick = { advanced = !advanced }) { }
            if (advanced) {
                ToggleRow(Icons.Filled.HighQuality, Palette.Grey, "Live beeld in hoge kwaliteit", "Uit = zuiniger met wifi en batterij", hd) {
                    hd = it; prefs.hdLive = it
                }
                SettingRow(Icons.Filled.DeleteSweep, Palette.Grey, "Tijdelijke opnamebestanden wissen", "Worden ook na 24 uur vanzelf gewist",
                    divider = false, onClick = {
                        Clips.cleanup(ctx, 0); scope.launch { snackbar.showSnackbar("Tijdelijke bestanden gewist") }
                    })
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (editPw) PasswordDialog(onDismiss = { editPw = false }) { pw ->
        prefs.password = pw; Camera.reset(); DoorbellService.restart(ctx); editPw = false; version++
        scope.launch { snackbar.showSnackbar("Wachtwoord opgeslagen") }
        if (prefs.devices.isEmpty()) adding = true
    }
    if (showDnd) DndSheet(dndUntil, onDismiss = { showDnd = false }) { until ->
        showDnd = false
        scope.launch { Dnd.set(ctx, until); dndUntil = prefs.dndUntil }
    }
    if (editing != null || newReply) ReplyEditor(editing, onDismiss = { editing = null; newReply = false }, onSaved = { list ->
        replies = list; editing = null; newReply = false
    }, onDeleted = { r ->
        editing = null
        val before = replies
        replies = replies.filter { it.id != r.id }
        scope.launch {
            val res = snackbar.showSnackbar("Antwoord verwijderd", actionLabel = "Ongedaan maken", duration = SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed) replies = before else Responses.delete(ctx, r)
        }
    })
    if (adding) AddDeviceDialog(
        onDismiss = { adding = false },
        onAdded = { first ->
            adding = false; version++; DoorbellService.restart(ctx); onDevicesChanged()
            if (first) onSaved()
        },
        passwordProvider = { prefs.password },
        savePassword = { },
    )
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("TP-Link-wachtwoord") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hetzelfde wachtwoord als waarmee je inlogt in de Tapo-app. Het blijft versleuteld op deze telefoon.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Wachtwoord") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton(enabled = pw.isNotBlank(), onClick = { onSave(pw) }) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } })
}

/** Edit a quick reply: text, computer voice or own voice (big record button). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplyEditor(reply: QuickResponse?, onDismiss: () -> Unit, onSaved: (List<QuickResponse>) -> Unit, onDeleted: (QuickResponse) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val id = remember { reply?.id ?: Responses.newId() }
    var text by remember { mutableStateOf(reply?.text ?: "") }
    var voice by remember { mutableStateOf(if (reply?.useVoice == true && Responses.hasVoice(ctx, id)) "own" else "tts") }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var hasVoice by remember { mutableStateOf(Responses.hasVoice(ctx, id)) }
    var info by remember { mutableStateOf<String?>(null) }
    val recorder = remember { VoiceRecorder() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(recording) { seconds = 0; while (recording && seconds < 20) { delay(1000); seconds++ }; if (recording) { recorder.stop(); recording = false; hasVoice = true } }

    fun current() = QuickResponse(id, text.trim(), voice == "own" && hasVoice)
    fun save(): List<QuickResponse> {
        val list = Responses.list(ctx)
        val updated = if (list.any { it.id == id }) list.map { if (it.id == id) current() else it } else list + current()
        Responses.save(ctx, updated); return updated
    }

    ModalBottomSheet(onDismissRequest = { if (recording) recorder.stop(); onDismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (reply == null) "Nieuw antwoord" else "Antwoord wijzigen", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text("Bijv. Ik kom eraan!") }, textStyle = MaterialTheme.typography.titleMedium)
            Segmented(listOf("tts" to "Computerstem", "own" to "Eigen stem"), voice, Modifier.fillMaxWidth()) { voice = it }
            if (voice == "own") Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            mic.launch(Manifest.permission.RECORD_AUDIO); return@clickable
                        }
                        haptic(true)
                        if (recording) { recorder.stop(); recording = false; hasVoice = true }
                        else { recorder.start(Responses.voiceFile(ctx, id)); recording = true }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (recording) 24.dp else 30.dp).clip(if (recording) RoundedCornerShape(6.dp) else CircleShape).background(Palette.Red))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(when { recording -> "Opnemen… 0:%02d".format(seconds); hasVoice -> "Eigen stem opgenomen ✓"; else -> "Neem je eigen stem op" },
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (recording) "Tik op stop als je klaar bent" else if (hasVoice) "Tik om opnieuw op te nemen" else "Tik op de rode knop en spreek de tekst in",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            info?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = text.isNotBlank() && !recording, modifier = Modifier.weight(1f), onClick = {
                    haptic(false); scope.launch {
                        info = try { Responses.preview(ctx, current()); null } catch (e: Exception) { "Afspelen mislukt: ${e.message}" }
                    }
                }) { androidx.compose.material3.Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Beluisteren") }
                Button(enabled = text.isNotBlank() && !recording && (voice == "tts" || hasVoice), modifier = Modifier.weight(1f), onClick = {
                    haptic(true); onSaved(save())
                }) { Text("Opslaan") }
            }
            if (reply != null) TextButton(onClick = { onDeleted(reply) }, modifier = Modifier.fillMaxWidth()) {
                Text("Antwoord verwijderen", color = MaterialTheme.colorScheme.error)
            }
        }
    }
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
