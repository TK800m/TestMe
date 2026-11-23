@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.tapo.deurbel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.DoorbellService
import nl.tapo.deurbel.LivePlayer
import nl.tapo.deurbel.Prefs
import nl.tapo.deurbel.PushToTalk
import nl.tapo.deurbel.Responses
import nl.tapo.deurbel.VisitorAudio

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    if (!prefs.configured) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Stel eerst de deurbel in via Instellingen.", textAlign = TextAlign.Center)
        }
        return
    }

    var notify by remember { mutableStateOf(prefs.notifications) }
    var chime by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var talking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val visitor = remember { VisitorAudio() }
    val ptt = remember { PushToTalk(ctx.applicationContext) }
    val responses = remember { Responses.list(ctx) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumed by remember { mutableStateOf(true) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) resumed = false
            if (e == Lifecycle.Event.ON_START) resumed = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // live player, recreated on retry and released when leaving the screen / app
    DisposableEffect(attempt, resumed) {
        if (!resumed) return@DisposableEffect onDispose { }
        error = null; buffering = true
        val p = LivePlayer.create(ctx, visitor)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(e: PlaybackException) {
                error = "Geen live beeld: " + (e.cause?.let { Camera.friendly(it) } ?: e.errorCodeName)
            }
        })
        player = p
        onDispose { p.release(); player = null; visitor.release() }
    }

    LaunchedEffect(Unit) {
        chime = withContext(Dispatchers.IO) {
            try { Camera.client(ctx).chimeRingPlan()?.optString("enabled")?.let { it == "on" } } catch (_: Exception) { null }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Meldingen", Modifier.weight(1f))
            Switch(checked = notify, onCheckedChange = {
                notify = it; prefs.notifications = it; DoorbellService.sync(ctx)
            })
            Spacer(Modifier.width(16.dp))
            Text("Gong")
            Spacer(Modifier.width(8.dp))
            Switch(checked = chime == true, enabled = chime != null, onCheckedChange = { on ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Camera.client(ctx).setChimeEnabled(on) }
                        chime = on
                        message = if (on) "Gong staat aan" else "Gong staat uit"
                    } catch (e: Exception) { message = "Gong wijzigen mislukt: ${Camera.friendly(e)}" }
                }
            })
        }

        Box(Modifier.fillMaxWidth().height(300.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            player?.let { p ->
                AndroidView(factory = { c ->
                    PlayerView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = p
                    }
                }, update = { it.player = p }, modifier = Modifier.fillMaxSize())
            }
            if (error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(error!!, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { Camera.reset(); attempt++ }) {
                        Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Opnieuw")
                    }
                }
            } else if (buffering) {
                CircularProgressIndicator()
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            FilledIconToggleButton(checked = sound, onCheckedChange = {
                sound = it; visitor.muted = !it || talking
            }, modifier = Modifier.size(56.dp)) {
                Icon(if (sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Geluid van buiten")
            }
            Surface(
                color = if (talking) Color(0xFFD0021B) else MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(88.dp).clip(CircleShape).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        talking = true
                        visitor.muted = true // avoid echo while talking
                        ptt.start { msg -> message = "Praten mislukt: $msg" }
                        tryAwaitRelease()
                        ptt.stop()
                        talking = false
                        visitor.muted = !sound
                    })
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, "Houd ingedrukt om te praten", tint = Color.Black, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.size(56.dp))
        }
        Text(if (talking) "Je praat nu via de deurbel…" else "Houd de microfoon ingedrukt om te praten",
            Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)

        Text("Snel antwoorden", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            responses.forEach { r ->
                AssistChip(onClick = {
                    if (sending != null) return@AssistChip
                    sending = r.id
                    scope.launch {
                        message = try {
                            Responses.sendToDoor(ctx, r); "Afgespeeld: \"${r.text}\""
                        } catch (e: Exception) { "Mislukt: ${Camera.friendly(e)}" }
                        sending = null
                    }
                }, label = { Text(if (sending == r.id) "Bezig… ${r.text}" else r.text) })
            }
        }
        message?.let {
            Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(24.dp))
    }
}
