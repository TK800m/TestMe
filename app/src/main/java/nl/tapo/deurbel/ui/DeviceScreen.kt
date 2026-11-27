package nl.tapo.deurbel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.core.EventTypes
import nl.tapo.deurbel.core.PowerInfo
import nl.tapo.deurbel.core.TapoClient

/** Settings of one doorbell or camera. */
@Composable
fun DeviceScreen(devId: String, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx, devId) }
    var dev by remember { mutableStateOf(prefs.device) }
    val d = dev ?: run { onBack(); return }
    var power by remember { mutableStateOf<PowerInfo?>(null) }
    var chime by remember { mutableStateOf<Boolean?>(null) }
    var eventsOnly by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var zones by remember { mutableStateOf(false) }
    var ringTypes by remember { mutableStateOf(prefs.ringTypes) }
    var typeLabels by remember { mutableStateOf(prefs.typeLabels) }
    var learning by remember { mutableStateOf(false) }
    var learnText by remember { mutableStateOf<String?>(null) }
    var fwText by remember { mutableStateOf<String?>(null) }
    var fwNew by remember { mutableStateOf<String?>(null) }
    var fwBusy by remember { mutableStateOf(false) }
    var fwConfirm by remember { mutableStateOf(false) }

    if (zones) { ZoneEditor(devId, d.isDoorbell) { zones = false }; return }

    LaunchedEffect(devId) {
        withContext(Dispatchers.IO) {
            val c = try { Camera.client(ctx, devId) } catch (_: Exception) { return@withContext }
            power = try { c.power() } catch (_: Exception) { null }
            if (d.isDoorbell) chime = try { c.chimeRingPlan()?.optString("enabled")?.let { it == "on" } } catch (_: Exception) { null }
            eventsOnly = c.recordsEventsOnly()
        }
    }

    fun run(label: String, block: (TapoClient) -> Unit, after: () -> Unit = {}) {
        scope.launch {
            message = try { withContext(Dispatchers.IO) { block(Camera.client(ctx, devId)) }; after(); label }
            catch (e: Exception) { "Mislukt: ${Camera.friendly(e)}" }
        }
    }

    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---- header ----
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Naam wijzigen") }
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (d.isDoorbell) Icons.Filled.NotificationsActive else Icons.Filled.Videocam, if (d.isDoorbell) Palette.Accent else Palette.Blue, 56)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(d.name, style = MaterialTheme.typography.headlineSmall)
                Text((if (d.isDoorbell) "Deurbel" else "Camera") + " · " + d.host, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.secondary) }

        // ---- device ----
        SectionTitle("Apparaat")
        GroupCard {
            power?.let { p ->
                SettingRow(if (p.charging == true) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                    if (p.low) Palette.Red else Palette.Green, "Voeding", if (p.hasBattery) p.label() else "Netvoeding",
                    subtitleColor = if (p.low) MaterialTheme.colorScheme.error else null)
            }
            if (d.isDoorbell) ToggleRow(Icons.Filled.MusicNote, Palette.Violet, "Gong",
                if (chime == null) "Wordt opgehaald…" else "Belgeluid in huis", chime == true, enabled = chime != null) { on ->
                run(if (on) "Gong staat aan" else "Gong staat uit", { it.setChimeEnabled(on) }) { chime = on }
            }
            ToggleRow(Icons.Filled.Eco, Palette.Green, "Zuinig opnemen", "Alleen opnemen als er iets gebeurt",
                eventsOnly == true, enabled = eventsOnly != null) { on ->
                run(if (on) "Neemt alleen nog op bij gebeurtenissen" else "Neemt continu op", { it.setRecordEventsOnly(on) }) { eventsOnly = on }
            }
            SettingRow(Icons.Filled.CropFree, Palette.Blue, "Detectiezones", "Alleen in deze zones reageren", onClick = { zones = true })
            SettingRow(Icons.Filled.SystemUpdate, Palette.Grey, "Software",
                fwText ?: "Zoeken naar updates", divider = false, onClick = {
                    if (fwBusy) return@SettingRow
                    fwBusy = true; fwNew = null; fwText = "Zoeken naar updates…"
                    scope.launch {
                        fwText = try {
                            withContext(Dispatchers.IO) {
                                val c = Camera.client(ctx, devId)
                                val current = c.deviceInfo().optString("sw_version").substringBefore(" ")
                                val up = c.checkFirmware()
                                if (TapoClient.firmwareAvailable(up, current)) {
                                    fwNew = up.optString("version").substringBefore(" "); "Nieuwe versie $fwNew – tik om te installeren"
                                } else "Up-to-date (versie $current)"
                            }
                        } catch (e: Exception) { "Zoeken lukte niet: ${Camera.friendly(e)}" }
                        fwBusy = false
                        if (fwNew != null) fwConfirm = true
                    }
                }) { if (fwBusy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Chevron() }
        }

        // ---- notifications ----
        SectionTitle("Waarvoor een melding?")
        if (d.isDoorbell) {
            Button(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp), enabled = !learning, onClick = {
                learning = true; learnText = "Loop nu naar de deur en druk één keer op de bel (je hebt 90 seconden)…"
                scope.launch {
                    learnText = try {
                        withContext(Dispatchers.IO) {
                            val c = Camera.client(ctx, devId)
                            val corr = c.timeCorrection()
                            val startCam = System.currentTimeMillis() / 1000 - corr - 5
                            val candidates = linkedSetOf<String>()
                            var firstSeen = 0L
                            val until = System.currentTimeMillis() + 90_000
                            while (System.currentTimeMillis() < until) {
                                val camNow = System.currentTimeMillis() / 1000 - corr
                                c.events(startCam, camNow + 60).filter { it.startTime >= startCam && it.type.isNotEmpty() }
                                    .forEach { if (candidates.add(it.type) && firstSeen == 0L) firstSeen = System.currentTimeMillis() }
                                if (firstSeen > 0 && System.currentTimeMillis() - firstSeen > 10_000) break
                                Thread.sleep(2000)
                            }
                            val labels = prefs.typeLabels
                            val ring = EventTypes.pickRing(candidates, prefs.typeStats, labels)
                            if (ring == null) "Er is geen aanbelling gezien. Probeer het nog eens."
                            else {
                                labels.keys().asSequence().toList().forEach { if (labels.optString(it) == "ring") labels.remove(it) }
                                labels.put(ring, "ring")
                                prefs.typeLabels = labels
                                prefs.seenTypes = prefs.seenTypes + candidates
                                prefs.ringTypes = setOf(ring)
                                "Gelukt! Je krijgt nu alleen een melding als er wordt aangebeld."
                            }
                        }
                    } catch (e: Exception) { "Herkennen lukte niet: ${Camera.friendly(e)}" }
                    ringTypes = prefs.ringTypes; typeLabels = prefs.typeLabels; learning = false
                }
            }) {
                if (learning) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.AccentInk); Spacer(Modifier.width(10.dp)) }
                Text(if (learning) "Luisteren… druk nu op de bel" else "Aanbellen herkennen")
            }
            Text(learnText ?: "Druk één keer op de bel, dan weet de app welke gebeurtenis het aanbellen is.",
                Modifier.padding(horizontal = 32.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = if (learnText != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val stats = remember(learning) { prefs.typeStats }
        val seen = (prefs.seenTypes + stats.keys().asSequence().toList()).sortedWith(
            compareBy({ if (typeLabels.optString(it) == "ring") 0 else 1 }, { -(stats.optJSONObject(it)?.optInt("n") ?: 0) }))
        GroupCard {
            if (seen.isEmpty()) Text("Nog geen gebeurtenissen gezien.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            seen.forEachIndexed { i, t ->
                var menu by remember { mutableStateOf(false) }
                val label = typeLabels.optString(t)
                Box {
                    ToggleRow(if (label == "ring") Icons.Filled.NotificationsActive else Icons.Filled.Sensors,
                        if (label == "ring") Palette.Accent else Palette.Grey,
                        EventTypes.name(t, typeLabels) + "  ✎", EventTypes.describe(stats, t), t in ringTypes, divider = i < seen.size - 1) { on ->
                        ringTypes = if (on) ringTypes + t else ringTypes - t
                        prefs.ringTypes = ringTypes
                    }
                    // tap on the name (left part) to rename
                    Box(Modifier.matchParentSize().padding(end = 80.dp).clickable { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        EventTypes.LABELS.forEach { (key, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                val l = prefs.typeLabels
                                if (key == "ring") l.keys().asSequence().toList().forEach { if (l.optString(it) == "ring") l.remove(it) }
                                l.put(t, key); prefs.typeLabels = l; typeLabels = l; menu = false
                            })
                        }
                    }
                }
            }
        }
        Text(if (seen.isEmpty()) "" else if (ringTypes.isEmpty()) "Nu krijg je bij álle gebeurtenissen een melding (ook beweging)."
            else "Alleen wat aan staat geeft een melding. Tik op een naam om die te wijzigen.",
            Modifier.padding(horizontal = 32.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Apparaat verwijderen uit de app", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (rename) {
        var name by remember { mutableStateOf(d.name) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Naam wijzigen") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                val nd = d.copy(name = name.trim()); Prefs(ctx).updateDevice(nd); dev = nd; rename = false; onChanged()
            }) { Text("Opslaan") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Annuleren") } })
    }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("${d.name} verwijderen?") },
            text = { Text("Het apparaat verdwijnt uit de app. Op het apparaat zelf verandert niets.") },
            confirmButton = { TextButton(onClick = {
                Prefs(ctx).removeDevice(devId); Camera.reset(devId); DoorbellService.restart(ctx); confirmRemove = false; onChanged(); onBack()
            }) { Text("Verwijderen") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Annuleren") } })
    }
    if (fwConfirm) {
        AlertDialog(onDismissRequest = { fwConfirm = false }, title = { Text("Update installeren?") },
            text = { Text("${d.name} downloadt versie $fwNew zelf bij TP-Link en start daarna opnieuw op. Het apparaat is dan een paar minuten niet bereikbaar. Haal de stroom er niet af.") },
            confirmButton = { TextButton(onClick = {
                fwConfirm = false
                scope.launch {
                    fwText = try { withContext(Dispatchers.IO) { Camera.client(ctx, devId).startFirmwareUpgrade() }; fwNew = null
                        "Update gestart. Het apparaat start over een paar minuten opnieuw op."
                    } catch (e: Exception) { "Installeren mislukt: ${Camera.friendly(e)}" }
                }
            }) { Text("Installeren") } },
            dismissButton = { TextButton(onClick = { fwConfirm = false }) { Text("Annuleren") } })
    }
}
