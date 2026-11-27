@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.tapo.deurbel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.Dnd
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.LivePlayer
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.PushToTalk
import nl.tapo.deurbel.Responses
import nl.tapo.deurbel.VisitorAudio
import nl.tapo.deurbel.core.EventTypes
import nl.tapo.deurbel.core.PowerInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class LiveState { Connecting, Live, Paused, Error }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onSelectDevice: (String) -> Unit = {}, onOpenClips: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val snackbar = LocalSnackbar.current

    if (!prefs.configured) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            EmptyState(Icons.Filled.Videocam, "Nog geen deurbel of camera", "Koppel je Tapo-deurbel of camera. Dat duurt een minuut.") {
                Button(onClick = onOpenSettings) { Text("Apparaat koppelen") }
            }
        }
        return
    }

    val dev = remember { prefs.device }
    val onBattery = remember { ctx.getSharedPreferences("deurbel", 0).getBoolean("dev.${prefs.currentId}.on_battery", false) }
    var state by remember { mutableStateOf(LiveState.Connecting) }
    var error by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf(false) }
    var talking by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    var power by remember { mutableStateOf<PowerInfo?>(null) }
    var showReplies by remember { mutableStateOf(false) }
    var showDnd by remember { mutableStateOf(false) }
    var dndUntil by remember { mutableLongStateOf(prefs.dndUntil) }
    var notify by remember { mutableStateOf(prefs.notifications) }
    val visitor = remember { VisitorAudio() }
    val ptt = remember { PushToTalk(ctx.applicationContext) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paused = state == LiveState.Paused

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) resumed = false
            if (e == Lifecycle.Event.ON_START) resumed = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // live player: only while the screen is visible and not paused
    DisposableEffect(attempt, resumed, paused) {
        if (!resumed || paused) return@DisposableEffect onDispose { }
        state = LiveState.Connecting
        val p = LivePlayer.create(ctx, visitor)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) state = LiveState.Live }
            override fun onPlayerError(e: PlaybackException) {
                error = e.cause?.let { Camera.friendly(it) } ?: "Geen beeld"; state = LiveState.Error
            }
        })
        player = p
        onDispose { p.release(); player = null; visitor.release() }
    }

    // energy: live view keeps the camera awake, so it pauses by itself
    LaunchedEffect(attempt, resumed, paused, talking) {
        if (!resumed || paused || talking) return@LaunchedEffect
        delay(if (onBattery) 90_000 else 180_000)
        state = LiveState.Paused
    }
    LaunchedEffect(Unit) { power = withContext(Dispatchers.IO) { try { Camera.client(ctx).power() } catch (_: Exception) { null } } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DeviceSwitch(onSelectDevice)

        // ---------------- video ----------------
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .aspectRatio(if (dev?.isDoorbell == false) 16f / 9f else 4f / 3f)
            .clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            player?.let { p ->
                AndroidView(factory = { c ->
                    PlayerView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        this.player = p
                    }
                }, update = { it.player = p }, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LivePill(state)
                Spacer(Modifier.weight(1f))
                power?.takeIf { it.hasBattery }?.let { BatteryPill(it) }
            }
            when (state) {
                LiveState.Connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                LiveState.Paused -> Overlay(Icons.Filled.BatteryFull, "Live beeld gepauzeerd om energie te besparen", "Verder kijken") {
                    haptic(false); state = LiveState.Connecting; attempt++
                }
                LiveState.Error -> Overlay(Icons.Filled.WifiOff, "${dev?.name ?: "Apparaat"} reageert niet. $error", "Opnieuw proberen") {
                    haptic(false); Camera.reset(); attempt++
                }
                LiveState.Live -> {}
            }
            if (talking) Box(Modifier.align(Alignment.BottomCenter).padding(14.dp)) {
                Pill(color = Palette.Red) {
                    SoundBars()
                    Text("Je praat via ${dev?.name ?: "de deurbel"}", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---------------- ring banner ----------------
        val last = remember(state) { prefs.history.let { if (it.length() > 0) it.getJSONObject(it.length() - 1) else null } }
        if (last != null && System.currentTimeMillis() / 1000 - last.optLong("t") < 180) RingBanner(last.optLong("t"))

        // ---------------- big actions (thumb zone) ----------------
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.Bottom) {
            BigAction(if (sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (sound) "Geluid aan" else "Geluid uit",
                Modifier.weight(1f), active = sound) {
                haptic(false); sound = !sound; visitor.muted = !sound || talking
            }
            BigAction(Icons.Filled.Mic, if (talking) "Loslaten = stop" else "Houd vast: praten",
                Modifier.weight(1f).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                        }
                        haptic(true); talking = true; visitor.muted = true
                        ptt.start { msg -> scope.launch { snackbar.showSnackbar("Praten mislukt: $msg") } }
                        tryAwaitRelease()
                        ptt.stop(); talking = false; visitor.muted = !sound; haptic(false)
                    })
                }, primary = true, active = talking, activeColor = Palette.Red)
            BigAction(Icons.Filled.ChatBubble, "Antwoord", Modifier.weight(1f)) { haptic(false); showReplies = true }
        }

        // ---------------- status ----------------
        if (!notify) {
            GroupCard {
                SettingRow(Icons.Filled.NotificationsOff, Palette.Red, "Meldingen staan uit", "Tik om weer een melding te krijgen",
                    divider = false, onClick = { notify = true; prefs.notifications = true; DoorbellService.sync(ctx) })
            }
            Spacer(Modifier.height(12.dp))
        }
        GroupCard {
            SettingRow(Icons.Filled.DarkMode, Palette.Indigo, "Niet storen",
                if (dndUntil > System.currentTimeMillis()) "Aan tot ${hhmm(dndUntil / 1000)} · gong stil" else "Uit",
                divider = false, onClick = { showDnd = true })
        }

        // ---------------- recent ----------------
        SectionTitle("Recent")
        val history = remember(state) { prefs.history }
        val devices = remember { prefs.devices }
        if (history.length() == 0) {
            GroupCard {
                Text("Nog niemand aan de deur geweest", Modifier.padding(20.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else GroupCard {
            val n = history.length()
            val first = maxOf(0, n - 4)
            (n - 1 downTo first).forEach { i ->
                val h = history.getJSONObject(i)
                val d = devices.firstOrNull { it.id == h.optString("dev") }
                val label = Prefs(ctx, h.optString("dev")).typeLabels.optString(h.optString("type"))
                SettingRow(if (label == "ring") Icons.Filled.NotificationsActive else Icons.Filled.Person,
                    if (label == "ring") Palette.Accent else Palette.Blue,
                    (if (devices.size > 1 && d != null) d.name + " · " else "") + (EventTypes.LABELS[label] ?: "Iemand gezien"),
                    ago(h.optLong("t")), divider = i > first, onClick = onOpenClips)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showReplies) ReplySheet(onDismiss = { showReplies = false })
    if (showDnd) DndSheet(dndUntil, onDismiss = { showDnd = false }) { until ->
        showDnd = false
        scope.launch {
            Dnd.set(ctx, until); dndUntil = prefs.dndUntil
            snackbar.showSnackbar(if (until <= 0) "Niet storen staat uit" else "Niet storen tot ${hhmm(until / 1000)} – de gong is stil")
        }
    }
}

@Composable
private fun LivePill(state: LiveState) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Pill {
        Box(Modifier.size(8.dp).alpha(if (state == LiveState.Live) a else 1f).clip(CircleShape)
            .background(if (state == LiveState.Live) Palette.Red else Color(0xFF9AA1AB)))
        Text(when (state) { LiveState.Live -> "Live"; LiveState.Connecting -> "Verbinden…"; LiveState.Paused -> "Gepauzeerd"; LiveState.Error -> "Offline" },
            color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BatteryPill(p: PowerInfo) {
    Pill(color = if (p.low) Palette.Red.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f)) {
        Icon(when { p.charging == true -> Icons.Filled.BatteryChargingFull; p.low -> Icons.Filled.BatteryAlert; else -> Icons.Filled.BatteryFull },
            null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text("${p.percent}%", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SoundBars() {
    val t = rememberInfiniteTransition(label = "bars")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            val h by t.animateFloat(4f, 14f, infiniteRepeatable(tween(500, delayMillis = i * 120), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

@Composable
private fun Overlay(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAction) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(action) }
    }
}

@Composable
private fun RingBanner(t: Long) {
    val wiggle = rememberInfiniteTransition(label = "ring")
    val r by wiggle.animateFloat(-14f, 14f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "r")
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Color(0xFFF5A524), Color(0xFFFF7A1A)))).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.NotificationsActive, null, tint = Palette.AccentInk, modifier = Modifier.size(28.dp).rotate(r))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Er wordt aangebeld", color = Palette.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("${hhmm(t)} · ${ago(t).lowercase()}", color = Palette.AccentInk.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Quick replies: one tap plays it at the door, with clear progress and confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplySheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val replies = remember { Responses.list(ctx) }
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snel antwoorden", style = MaterialTheme.typography.headlineSmall)
            Text(failed ?: "Tik om het bij de deur af te spelen",
                color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            replies.forEach { r ->
                val isDone = done == r.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (isDone) Palette.Green.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = busy == null) {
                        haptic(false); busy = r.id; failed = null
                        scope.launch {
                            try { Responses.sendToDoor(ctx, r); done = r.id; haptic(true); delay(700); onDismiss() }
                            catch (e: Exception) { failed = "Afspelen mislukt: ${Camera.friendly(e)}" }
                            busy = null
                        }
                    }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    when {
                        busy == r.id -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        isDone -> Icon(Icons.Filled.Check, "Afgespeeld", tint = Palette.Green)
                        else -> Icon(if (r.useVoice && Responses.hasVoice(ctx, r.id)) Icons.Filled.RecordVoiceOver else Icons.Filled.ChatBubble,
                            null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DndSheet(current: Long, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    val haptic = rememberHaptic()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Niet storen", style = MaterialTheme.typography.headlineSmall)
            Text("Geen meldingen en de gong blijft stil. Opnames gaan gewoon door.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            val morning = Calendar.getInstance().apply {
                if (get(Calendar.HOUR_OF_DAY) >= 7) add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            listOf("1 uur" to System.currentTimeMillis() + 3600_000L, "3 uur" to System.currentTimeMillis() + 3 * 3600_000L,
                "Tot morgenochtend 07:00" to morning).forEach { (label, until) ->
                Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { haptic(true); onChoose(until) }.padding(18.dp), style = MaterialTheme.typography.titleMedium)
            }
            if (current > System.currentTimeMillis()) {
                Text("Uitzetten", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { haptic(true); onChoose(0) }.padding(18.dp), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Switch between doorbell and cameras (only with more than one device). */
@Composable
fun DeviceSwitch(onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val devices = prefs.devices
    if (devices.size < 2) return
    Segmented(devices.map { it.id to it.name }, prefs.currentId, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onSelect)
}

private val nl = Locale("nl", "NL")

fun hhmm(sec: Long): String = SimpleDateFormat("HH:mm", nl).format(Date(sec * 1000))

fun dayLabel(sec: Long): String {
    fun startOfDay(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when (((startOfDay(System.currentTimeMillis()) - startOfDay(sec * 1000)) / 86_400_000L).toInt()) {
        0 -> "Vandaag"
        1 -> "Gisteren"
        else -> SimpleDateFormat("EEEE d MMMM", nl).format(Date(sec * 1000)).replaceFirstChar { it.uppercase() }
    }
}

fun ago(sec: Long): String {
    val m = (System.currentTimeMillis() / 1000 - sec) / 60
    return when {
        m < 1 -> "Zojuist"
        m < 60 -> "$m min geleden"
        m < 180 -> "${m / 60} uur geleden"
        else -> dayLabel(sec) + " om " + hhmm(sec)
    }
}
