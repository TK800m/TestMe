@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.tapo.deurbel.ui

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.Camera
import nl.tapo.deurbel.LivePlayer
import nl.tapo.deurbel.VisitorAudio
import nl.tapo.deurbel.core.DetectionZones

/**
 * Draw the motion-detection zones on top of the live image. Only movement inside a
 * zone triggers a notification/recording: fewer false alarms and less energy.
 */
@Composable
fun ZoneEditor(devId: String, doorbell: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<DetectionZones?>(null) }
    val zones = remember { mutableStateListOf<List<Pair<Double, Double>>>() }
    var selected by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Zones ophalen…") }
    var details by remember { mutableStateOf<String?>(null) }
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(devId) {
        try {
            val z = withContext(Dispatchers.IO) { Camera.client(ctx, devId).detectionZones() }
            original = z; zones.clear(); zones.addAll(z.zones.map { it.points })
            message = if (zones.isEmpty()) "Nog geen zones: het hele beeld telt. Tik op \"Zone erbij\"." else "Sleep de hoeken om de zone aan te passen."
        } catch (e: Exception) {
            message = "Zones ophalen lukte niet: ${Camera.friendly(e)}" + ((e as? nl.tapo.deurbel.core.TapoException)?.code?.let { " (code $it)" } ?: "")
        }
    }

    val visitor = remember { VisitorAudio() }
    val player = remember { LivePlayer.create(ctx, visitor, devId) }
    DisposableEffect(Unit) { onDispose { player.release(); visitor.release() } }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug", tint = Color.White) }
            Column {
                Text("Detectiezones", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(message, color = Color(0xFF9AA1AB), style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(if (doorbell) 4f / 3f else 16f / 9f)) {
            AndroidView(factory = { c ->
                PlayerView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL // zone coordinates cover the whole image
                    this.player = player
                }
            }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        // tap inside a zone (bounding box) selects it
                        zones.indexOfFirst { z ->
                            val xs = z.map { it.first * size.width }; val ys = z.map { it.second * size.height }
                            pos.x.toDouble() in xs.min()..xs.max() && pos.y.toDouble() in ys.min()..ys.max()
                        }.takeIf { it >= 0 }?.let { selected = it }
                    }
                }
                .pointerInput(Unit) {
                    var handle = -1
                    detectDragGestures(
                        onDragStart = { pos ->
                            val z = zones.getOrNull(selected) ?: return@detectDragGestures
                            handle = z.indices.minByOrNull { i ->
                                val dx = z[i].first * size.width - pos.x; val dy = z[i].second * size.height - pos.y; dx * dx + dy * dy
                            }?.takeIf { i ->
                                val dx = z[i].first * size.width - pos.x; val dy = z[i].second * size.height - pos.y
                                dx * dx + dy * dy < (48.dp.toPx() * 48.dp.toPx())
                            } ?: -1
                        },
                        onDrag = { change, _ ->
                            val z = zones.getOrNull(selected)
                            if (z != null && handle >= 0) {
                                change.consume()
                                val nx = (change.position.x / size.width).toDouble().coerceIn(0.0, 1.0)
                                val ny = (change.position.y / size.height).toDouble().coerceIn(0.0, 1.0)
                                zones[selected] = z.toMutableList().also { it[handle] = nx to ny }
                            }
                        },
                        onDragEnd = { handle = -1 },
                    )
                }) {
                zones.forEachIndexed { i, z ->
                    val pts = if (z.size == 2) {
                        val (a, b) = z
                        listOf(a.first to a.second, b.first to a.second, b.first to b.second, a.first to b.second)
                    } else z
                    val path = Path().apply {
                        pts.forEachIndexed { n, (x, y) ->
                            val o = Offset((x * size.width).toFloat(), (y * size.height).toFloat())
                            if (n == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                        }
                        close()
                    }
                    val sel = i == selected
                    drawPath(path, accent.copy(alpha = if (sel) 0.28f else 0.12f))
                    drawPath(path, if (sel) accent else Color.White.copy(alpha = 0.75f), style = Stroke(width = if (sel) 6f else 4f))
                    if (sel) z.forEach { (x, y) ->
                        val c = Offset((x * size.width).toFloat(), (y * size.height).toFloat())
                        drawCircle(accent, radius = 26f, center = c)
                        drawCircle(Color.White, radius = 18f, center = c)
                    }
                }
            }
        }
        details?.let { d ->
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Detectiezones", "Tapo ${Camera.client(ctx, devId).host}\n$d"))
                message = "Technische details gekopieerd – plak ze in een bericht aan de ontwikkelaar"
            }, modifier = Modifier.fillMaxWidth()) { Text("Technische details kopiëren", color = Color.White) }
        }
        Text("Alleen beweging binnen de zones geeft een melding en opname. Kleiner = minder onnodige meldingen en minder energie.",
            color = Color(0xFF9AA1AB), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(enabled = original != null && zones.size < 4, modifier = Modifier.weight(1f), onClick = {
                val polygon = zones.firstOrNull()?.let { it.size > 2 } ?: false
                zones.add(if (polygon) listOf(0.25 to 0.45, 0.75 to 0.45, 0.85 to 0.95, 0.15 to 0.95) else listOf(0.2 to 0.35, 0.8 to 0.95))
                selected = zones.size - 1
            }) { Text("Zone erbij") }
            OutlinedButton(enabled = zones.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                zones.removeAt(selected.coerceIn(0, zones.size - 1)); selected = 0
            }) { Text("Zone weg") }
            Button(enabled = original != null, modifier = Modifier.weight(1f), onClick = {
                val o = original ?: return@Button
                scope.launch {
                    message = try {
                        withContext(Dispatchers.IO) {
                            val list = zones.mapIndexed { i, pts ->
                                o.zones.getOrNull(i)?.takeIf { it.points.size == pts.size }?.moved(pts) ?: DetectionZones.Zone(pts, null, null)
                            }
                            Camera.client(ctx, devId).setDetectionZones(o.withZones(list), o)
                        }
                        onClose(); "Opgeslagen"
                    } catch (e: nl.tapo.deurbel.core.TapoClient.ZoneSaveException) {
                        details = e.details; e.message ?: "Opslaan lukte niet"
                    } catch (e: Exception) { "Opslaan lukte niet: ${Camera.friendly(e)}" }
                }
            }) { Text("Opslaan") }
        }
    }
}
