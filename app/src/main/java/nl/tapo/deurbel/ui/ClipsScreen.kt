@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.tapo.deurbel.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.Clips
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.core.Recording
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val nl = Locale("nl", "NL")

@Composable
fun ClipsScreen(onSelectDevice: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    if (!Prefs(ctx).configured) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Stel eerst de deurbel in via Instellingen.") }
        return
    }
    var days by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var clips by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var corr by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Recording?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = null
        try {
            corr = Clips.timeCorrection(ctx)
            days = Clips.days(ctx)
            if (selected == null || selected !in days) selected = days.firstOrNull()
        } catch (e: Exception) { error = Camera.friendly(e) }
        loading = false
    }
    LaunchedEffect(selected, reload) {
        val d = selected ?: return@LaunchedEffect
        loading = true
        try { clips = Clips.recordings(ctx, d) } catch (e: Exception) { error = Camera.friendly(e) }
        loading = false
    }

    open?.let { rec ->
        ClipPlayer(rec, corr, onBack = { open = null }, onDeleted = { open = null; clips = clips.filter { it != rec } })
        return
    }

    Column(Modifier.fillMaxSize()) {
        DeviceChips(onSelectDevice)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Opnames van ${Prefs(ctx).device?.name ?: "het apparaat"}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Vernieuwen") }
        }
        LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days) { d ->
                FilterChip(selected = d == selected, onClick = { selected = d }, label = { Text(dayLabel(d)) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (!loading && error == null && clips.isEmpty()) {
            Text("Geen opnames gevonden. Zit er een micro-SD-kaart in het apparaat en staat opnemen aan?",
                Modifier.padding(16.dp), textAlign = TextAlign.Center)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(clips, key = { it.startTime }) { r ->
                Row(Modifier.fillMaxWidth().clickable { open = r }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(time(r.startTime + corr), style = MaterialTheme.typography.titleMedium)
                        Text("Duur ${duration(r.endTime - r.startTime)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (Clips.cached(ctx, r) != null) Icon(Icons.Filled.Download, "Al gedownload", tint = Color.Gray)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ClipPlayer(rec: Recording, corr: Long, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try { file = Clips.prepare(ctx, rec) { progress = it } } catch (e: Exception) { error = "Ophalen mislukt: ${Camera.friendly(e)}" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") }
            Text("${day(rec.startTime + corr)} ${time(rec.startTime + corr)}", style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.fillMaxWidth().height(320.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            val f = file
            if (f != null) {
                val player = remember(f) {
                    ExoPlayer.Builder(ctx).build().apply {
                        setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f))); prepare(); playWhenReady = true
                    }
                }
                DisposableEffect(player) { onDispose { player.release() } }
                AndroidView(factory = { c ->
                    PlayerView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                    }
                }, modifier = Modifier.fillMaxSize())
            } else if (error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(error!!, color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { attempt++ }) { Text("Opnieuw") }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Opname ophalen van de deurbel…", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = file != null, onClick = {
                scope.launch {
                    info = try { Clips.export(ctx, rec, file!!, corr); "Opgeslagen in Video's › Deurbel" }
                    catch (e: Exception) { "Opslaan mislukt: ${e.message}" }
                }
            }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("Downloaden") }
            OutlinedButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(6.dp)); Text("Verwijderen")
            }
        }
        if (file?.extension == "ts") {
            Text("Let op: deze opname kon niet naar MP4 worden omgezet en is opgeslagen als .ts (afspelen met bijv. VLC).",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        info?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.secondary) }
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Opname verwijderen?") },
            text = { Text("De opname verdwijnt uit de app en van deze telefoon. Losse opnames van de SD-kaart wissen kan via de lokale verbinding niet; de deurbel overschrijft oude opnames vanzelf als de kaart vol is.") },
            confirmButton = { TextButton(onClick = { Clips.delete(ctx, rec); confirmDelete = false; onDeleted() }) { Text("Verwijderen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } })
    }
}

private fun dayLabel(d: String): String = try {
    SimpleDateFormat("EEE d MMM", nl).format(SimpleDateFormat("yyyyMMdd", Locale.US).parse(d)!!)
} catch (_: Exception) { d }

private fun time(sec: Long) = SimpleDateFormat("HH:mm:ss", nl).format(Date(sec * 1000))
private fun day(sec: Long) = SimpleDateFormat("EEE d MMM", nl).format(Date(sec * 1000))
private fun duration(s: Long) = if (s >= 60) "${s / 60}:${"%02d".format(s % 60)}" else "${s}s"
