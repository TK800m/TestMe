package nl.tapo.deurbel.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.QuickResponse
import nl.tapo.deurbel.Responses
import nl.tapo.deurbel.VoiceRecorder

@Composable
fun ResponsesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(Responses.list(ctx)) }
    var editing by remember { mutableStateOf<QuickResponse?>(null) }
    var adding by remember { mutableStateOf(false) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val recorder = remember { VoiceRecorder() }
    var voiceVersion by remember { mutableStateOf(0) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun update(newList: List<QuickResponse>) { list = newList; Responses.save(ctx, newList) }

    fun run(id: String, label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = id
        scope.launch {
            message = try { block(); label } catch (e: Exception) { "Mislukt: ${Camera.friendly(e)}" }
            busy = null
        }
    }

    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nieuw antwoord") })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Snelle antwoorden worden in het Nederlands uitgesproken door de deurbel. Neem per antwoord je eigen stem op als je dat liever hebt.",
                    style = MaterialTheme.typography.bodyMedium)
                message?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.secondary) }
            }
            items(list, key = { it.id }) { r ->
                val hasVoice = remember(r.id, voiceVersion) { Responses.hasVoice(ctx, r.id) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.text, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r.useVoice && hasVoice) "Eigen stem" else "Computerstem (Nederlands)",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("Eigen stem", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = r.useVoice && hasVoice, enabled = hasVoice, onCheckedChange = { on ->
                                update(list.map { if (it.id == r.id) it.copy(useVoice = on) else it })
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { run(r.id, "Afgespeeld op telefoon") { Responses.preview(ctx, r) } }) {
                                Icon(Icons.Filled.PlayArrow, "Beluisteren op telefoon")
                            }
                            IconButton(onClick = { run(r.id, "Afgespeeld bij de deur: \"${r.text}\"") { Responses.sendToDoor(ctx, r) } }) {
                                Icon(Icons.Filled.Campaign, "Afspelen bij de deur", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO); return@IconButton
                                }
                                if (recordingId == r.id) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { recorder.stop() }
                                        recordingId = null
                                        voiceVersion++
                                        update(list.map { if (it.id == r.id) it.copy(useVoice = true) else it })
                                        message = "Eigen stem opgeslagen"
                                    }
                                } else if (recordingId == null) {
                                    recorder.start(Responses.voiceFile(ctx, r.id))
                                    recordingId = r.id
                                    message = "Opnemen… spreek nu in en tik op stop"
                                }
                            }) {
                                when {
                                    recordingId == r.id -> Icon(Icons.Filled.Stop, "Stop opname", tint = Color(0xFFD0021B))
                                    hasVoice -> Icon(Icons.Filled.Mic, "Opnieuw opnemen")
                                    else -> Icon(Icons.Filled.FiberManualRecord, "Eigen stem opnemen", tint = Color(0xFFD0021B))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (busy == r.id) Text("Bezig…", style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { editing = r }) { Icon(Icons.Filled.Edit, "Tekst wijzigen") }
                            IconButton(onClick = { Responses.delete(ctx, r); list = Responses.list(ctx) }) { Icon(Icons.Filled.Delete, "Verwijderen") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }

    if (adding || editing != null) {
        var text by remember { mutableStateOf(editing?.text ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (adding) "Nieuw antwoord" else "Antwoord wijzigen") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Tekst (Nederlands)") }, minLines = 2)
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val e = editing
                    if (e != null) update(list.map { if (it.id == e.id) it.copy(text = text.trim()) else it })
                    else update(list + QuickResponse(Responses.newId(), text.trim(), false))
                    adding = false; editing = null
                }) { Text("Opslaan") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Annuleren") } }
        )
    }
}
