package nl.tapo.deurbel.ui

import androidx.compose.foundation.clickable
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") }
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.headlineSmall)
                Text((if (d.isDoorbell) "Deurbel" else "Camera") + " · " + d.host, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Naam wijzigen") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

        power?.let { p ->
            Text("Voeding: " + if (p.hasBattery) p.label() else "netvoeding",
                color = if (p.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
        }

        if (d.isDoorbell) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Gong"); Text("Belgeluid in huis", style = MaterialTheme.typography.bodySmall) }
            Switch(checked = chime == true, enabled = chime != null, onCheckedChange = { on ->
                run(if (on) "Gong staat aan" else "Gong staat uit", { it.setChimeEnabled(on) }) { chime = on }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Zuinig opnemen")
                Text("Alleen opnemen als er iets gebeurt (minder stroom en opslag)", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = eventsOnly == true, enabled = eventsOnly != null, onCheckedChange = { on ->
                run(if (on) "Neemt alleen nog op bij gebeurtenissen" else "Neemt continu op", { it.setRecordEventsOnly(on) }) { eventsOnly = on }
            })
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { zones = true }) { Text("Detectiezones bewerken") }

        // ---- which events give a notification ----
        HorizontalDivider()
        Text("Waarvoor een melding?", style = MaterialTheme.typography.titleMedium)
        if (d.isDoorbell) {
            Button(modifier = Modifier.fillMaxWidth(), enabled = !learning, onClick = {
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
                                "Gelukt! Aanbellen is herkend. Je krijgt nu alleen een melding als er wordt aangebeld."
                            }
                        }
                    } catch (e: Exception) { "Herkennen lukte niet: ${Camera.friendly(e)}" }
                    ringTypes = prefs.ringTypes; typeLabels = prefs.typeLabels; learning = false
                }
            }) { Text(if (learning) "Bezig met luisteren…" else "Aanbellen herkennen") }
            learnText?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        }
        val stats = remember(learning) { prefs.typeStats }
        val seen = (prefs.seenTypes + stats.keys().asSequence().toList()).sortedWith(
            compareBy({ if (typeLabels.optString(it) == "ring") 0 else 1 }, { -(stats.optJSONObject(it)?.optInt("n") ?: 0) }))
        if (seen.isEmpty()) Text("Nog geen gebeurtenissen gezien.", style = MaterialTheme.typography.bodySmall)
        else Text(if (ringTypes.isEmpty()) "Nu krijg je bij álle gebeurtenissen een melding. Vink aan waarvoor je er een wilt:"
            else "Melding bij de aangevinkte gebeurtenissen. Tik op een naam om die te wijzigen.", style = MaterialTheme.typography.bodySmall)
        seen.forEach { t ->
            var menu by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t in ringTypes, onCheckedChange = { on ->
                    ringTypes = if (on) ringTypes + t else ringTypes - t
                    prefs.ringTypes = ringTypes
                })
                Column(Modifier.weight(1f).clickable { menu = true }.padding(vertical = 6.dp)) {
                    Text(EventTypes.name(t, typeLabels), style = MaterialTheme.typography.bodyLarge)
                    Text(EventTypes.describe(stats, t), style = MaterialTheme.typography.bodySmall)
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

        // ---- software ----
        HorizontalDivider()
        Text("Software", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !fwBusy, onClick = {
            fwBusy = true; fwNew = null; fwText = "Zoeken naar updates…"
            scope.launch {
                fwText = try {
                    withContext(Dispatchers.IO) {
                        val c = Camera.client(ctx, devId)
                        val current = c.deviceInfo().optString("sw_version")
                        val up = c.checkFirmware()
                        if (TapoClient.firmwareAvailable(up, current)) {
                            fwNew = up.optString("version")
                            "Nieuwe versie ${up.optString("version")} beschikbaar (nu: $current)." +
                                up.optString("release_note").let { if (it.isBlank()) "" else "\n\n$it" }
                        } else "Up-to-date (versie $current)."
                    }
                } catch (e: Exception) { "Zoeken lukte niet: ${Camera.friendly(e)}" }
                fwBusy = false
            }
        }) { Text(if (fwBusy) "Bezig…" else "Zoeken naar updates") }
        fwText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (fwNew != null) Button(modifier = Modifier.fillMaxWidth(), onClick = { fwConfirm = true }) { Text("Nu installeren") }

        HorizontalDivider()
        TextButton(onClick = { confirmRemove = true }) { Text("Apparaat verwijderen uit de app", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(32.dp))
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
