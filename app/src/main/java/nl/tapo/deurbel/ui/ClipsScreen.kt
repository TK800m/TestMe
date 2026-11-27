@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.tapo.deurbel.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private data class Clip(val rec: Recording, val day: String, val rang: Boolean)

@Composable
fun ClipsScreen(onSelectDevice: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val haptic = rememberHaptic()
    val prefs = remember { Prefs(ctx) }
    if (!prefs.configured) {
        EmptyState(Icons.Filled.SdCard, "Nog geen apparaat", "Koppel eerst je deurbel of camera in Instellingen.")
        return
    }
    var days by remember { mutableStateOf<List<String>>(emptyList()) }
    var shownDays by remember { mutableIntStateOf(0) }
    val clips = remember { mutableStateListOf<Clip>() }
    var corr by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }
    var open by remember { mutableStateOf<Clip?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    // which clips had a doorbell press (from the notification history)
    fun rang(r: Recording): Boolean {
        if (prefs.ringTypes.isEmpty()) return false
        val h = prefs.history
        val start = r.startTime + corr; val end = r.endTime + corr
        return (0 until h.length()).map { h.getJSONObject(it) }.any {
            it.optString("dev") == prefs.currentId && it.optLong("t") in (start - 10)..(end + 10) && it.optString("type") in prefs.ringTypes
        }
    }

    suspend fun loadMore() {
        loading = true
        try {
            days.drop(shownDays).take(3).forEach { d ->
                clips.addAll(Clips.recordings(ctx, d).map { Clip(it, d, false) }.map { it.copy(rang = rang(it.rec)) })
                shownDays++
            }
        } catch (e: Exception) { error = Camera.friendly(e) }
        loading = false
    }

    LaunchedEffect(reload) {
        loading = true; error = null; clips.clear(); shownDays = 0
        try { corr = Clips.timeCorrection(ctx); days = Clips.days(ctx); loadMore() }
        catch (e: Exception) { error = Camera.friendly(e); loading = false }
    }

    open?.let { c ->
        ClipViewer(c.rec, corr, c.rang, onBack = { open = null }, onDelete = {
            open = null
            val index = clips.indexOf(c); clips.remove(c)
            scope.launch {
                val r = snackbar.showSnackbar("Opname verwijderd", actionLabel = "Ongedaan maken", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) clips.add(index.coerceAtMost(clips.size), c) else Clips.delete(ctx, c.rec)
            }
        })
        return
    }

    val visible = clips.filter { filter == "all" || it.rang }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DeviceSwitch(onSelectDevice)
            ScreenTitle("Opnames") {
                Segmented(listOf("all" to "Alles", "rang" to "Aangebeld"), filter, Modifier.width(190.dp)) { filter = it }
            }
        }
        if (error != null && clips.isEmpty()) item {
            EmptyState(Icons.Filled.SdCard, "Opnames niet bereikbaar", error!!) { Button(onClick = { reload++ }) { Text("Opnieuw proberen") } }
        } else if (!loading && visible.isEmpty()) item {
            EmptyState(Icons.Filled.SdCard,
                if (filter == "rang") "Niemand aangebeld" else "Nog geen opnames",
                if (filter == "rang") "Hier verschijnen opnames waarbij is aangebeld." else "Zit er een micro-SD-kaart in het apparaat en staat opnemen aan?")
        }
        visible.groupBy { it.day }.forEach { (day, list) ->
            item(key = "h$day") {
                Text(dayLabel(list.first().rec.startTime + corr), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp))
            }
            item(key = "c$day") {
                GroupCard {
                    list.forEachIndexed { i, c ->
                        val dur = c.rec.endTime - c.rec.startTime
                        SettingRow(if (c.rang) Icons.Filled.NotificationsActive else Icons.Filled.PlayArrow,
                            if (c.rang) Palette.Accent else Palette.Grey,
                            hhmm(c.rec.startTime + corr),
                            (if (dur >= 60) "${dur / 60}:${"%02d".format(dur % 60)} min" else "$dur sec") + if (c.rang) " · aangebeld" else "",
                            divider = i < list.size - 1, onClick = { haptic(false); open = c })
                    }
                }
            }
        }
        if (loading) item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) }
            }
        } else if (shownDays < days.size) item {
            OutlinedButton(onClick = { scope.launch { loadMore() } }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Oudere opnames laden") }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClipViewer(rec: Recording, corr: Long, rang: Boolean, onBack: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val haptic = rememberHaptic()
    var file by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)

    LaunchedEffect(attempt) {
        error = null
        try { file = Clips.prepare(ctx, rec) { progress = it } } catch (e: Exception) { error = "Ophalen mislukt: ${Camera.friendly(e)}" }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug", tint = Color.White) }
            Column {
                Text("${dayLabel(rec.startTime + corr)} · ${hhmm(rec.startTime + corr)}", color = Color.White, fontWeight = FontWeight.Bold)
                Text((if (rang) "Aangebeld · " else "") + "${rec.endTime - rec.startTime} sec", color = Color(0xFF9AA1AB),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val f = file
            when {
                f != null -> {
                    val player = remember(f) {
                        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f))); prepare(); playWhenReady = true }
                    }
                    DisposableEffect(player) { onDispose { player.release() } }
                    AndroidView(factory = { c ->
                        PlayerView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            this.player = player
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(error!!, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { attempt++ }) { Text("Opnieuw proberen") }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Opname ophalen…", color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(220.dp).clip(CircleShape),
                        color = Palette.Accent, trackColor = Color.White.copy(alpha = 0.15f))
                    Text("${(progress * 100).toInt()}%", color = Color(0xFF9AA1AB), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigAction(Icons.Filled.Download, "Bewaren") {
                val f = file ?: return@BigAction
                haptic(true)
                scope.launch {
                    snackbar.showSnackbar(try { Clips.export(ctx, rec, f, corr); "Opgeslagen in Video's › Deurbel" } catch (e: Exception) { "Opslaan mislukt: ${e.message}" })
                }
            }
            BigAction(Icons.Filled.Delete, "Verwijderen") { haptic(true); onDelete() }
        }
    }
}
