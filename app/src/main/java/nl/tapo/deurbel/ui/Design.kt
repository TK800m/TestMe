package nl.tapo.deurbel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ */
/*  Design tokens – same look as the web app                          */
/* ------------------------------------------------------------------ */
object Palette {
    val Accent = Color(0xFFF5A524)
    val AccentInk = Color(0xFF1A1203)
    val Red = Color(0xFFFF453A)
    val Green = Color(0xFF30D158)
    val Blue = Color(0xFF0A84FF)
    val Violet = Color(0xFFBF5AF2)
    val Indigo = Color(0xFF5E5CE6)
    val Grey = Color(0xFF5B636E)
}

fun appColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.AccentInk,
    secondary = Color(0xFF7FB3E6), error = Palette.Red,
    background = Color(0xFF0B0D10), onBackground = Color(0xFFF2F4F7),
    surface = Color(0xFF0B0D10), onSurface = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFF15181D), surfaceContainerHigh = Color(0xFF1E2228), surfaceVariant = Color(0xFF1E2228),
    onSurfaceVariant = Color(0xFF8B93A1), outlineVariant = Color(0x14FFFFFF),
) else lightColorScheme(
    primary = Palette.Accent, onPrimary = Palette.AccentInk,
    secondary = Color(0xFF1F5FA8), error = Color(0xFFD70015),
    background = Color(0xFFF2F3F5), onBackground = Color(0xFF111318),
    surface = Color(0xFFF2F3F5), onSurface = Color(0xFF111318),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFECEEF1), surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = Color(0xFF6B7280), outlineVariant = Color(0x12000000),
)

val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Light tap feedback for every action (UX: immediate, tactile confirmation). */
@Composable
fun rememberHaptic(): (Boolean) -> Unit {
    val h = LocalHapticFeedback.current
    return remember(h) { { strong -> h.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove) } }
}

/* ------------------------------------------------------------------ */
/*  Components                                                        */
/* ------------------------------------------------------------------ */

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp))
}

/** Rounded card holding rows (iOS/Android settings style). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer), content = content)
}

@Composable
fun IconTile(icon: ImageVector, color: Color, size: Int = 34) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28).dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (color == Palette.Accent) Palette.AccentInk else Color.White, modifier = Modifier.size((size * 0.56).dp))
    }
}

/** One row: icon tile, title, optional subtitle, trailing content. Min 56dp touch target. */
@Composable
fun SettingRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = if (onClick != null) ({ Chevron() }) else null,
) {
    val haptic = rememberHaptic()
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button) { haptic(false); onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ToggleRow(icon: ImageVector, color: Color, title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true,
              divider: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptic = rememberHaptic()
    SettingRow(icon, color, title, subtitle, divider = divider, onClick = if (enabled) ({ haptic(true); onChange(!checked) }) else null) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = { haptic(true); onChange(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Green, checkedThumbColor = Color.White, checkedBorderColor = Palette.Green))
    }
}

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))

/** Segmented control (e.g. Automatisch / Licht / Donker). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val haptic = rememberHaptic()
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (on) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                .clickable(role = Role.Tab) { if (!on) { haptic(false); onSelect(key) } }
                .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** Big round action with label underneath (Geluid / Praten / Antwoord). */
@Composable
fun BigAction(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
    primary: Boolean = false, active: Boolean = false, activeColor: Color = MaterialTheme.colorScheme.onSurface,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: (() -> Unit)? = null,
) {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    val bg by animateColorAsState(when {
        active -> activeColor
        primary -> Palette.Accent
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }, label = "bg")
    val fg = when {
        active && activeColor == Palette.Red -> Color.White
        active -> MaterialTheme.colorScheme.background
        primary -> Palette.AccentInk
        else -> MaterialTheme.colorScheme.onSurface
    }
    val dim = if (primary) 80.dp else 68.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Box(Modifier.scale(scale).size(dim)
            .shadow(if (primary || active) 12.dp else 0.dp, CircleShape, ambientColor = bg, spotColor = bg)
            .clip(CircleShape).background(bg)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick() } else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = fg, modifier = Modifier.size(if (primary) 34.dp else 28.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Glass pill on top of video ("● Live", "82%"). */
@Composable
fun Pill(modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.5f), content: @Composable () -> Unit) {
    Row(modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Friendly empty state: what is going on + what you can do. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** Snackbar (with "Ongedaan maken") available on every screen. */
val LocalSnackbar = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.material3.SnackbarHostState() }
