package io.github.pablocollantes.cfusage.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.ui.theme.statusColor
import io.github.pablocollantes.cfusage.ui.theme.statusLabel

val CardShape = RoundedCornerShape(12.dp)
val ControlShape = RoundedCornerShape(8.dp)
val BadgeShape = RoundedCornerShape(6.dp)

/** shadcn Card: hairline border, 12 dp radius, flat. */
@Composable
fun CfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = Cf.colors.border,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .clip(CardShape)
        .background(Cf.colors.card)
        .border(BorderStroke(1.dp, borderColor), CardShape)
    Column(if (onClick != null) base.clickable(onClick = onClick) else base, content = content)
}

@Composable
fun CardHeader(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Cf.type.title, color = Cf.colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = Cf.type.small, color = Cf.colors.mutedForeground)
            }
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun CardContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(16.dp), content = content)
}

enum class BadgeVariant { Default, Secondary, Outline, Destructive }

@Composable
fun Badge(text: String, variant: BadgeVariant = BadgeVariant.Secondary, dot: Color? = null, modifier: Modifier = Modifier) {
    val c = Cf.colors
    val (bg, fg, border) = when (variant) {
        BadgeVariant.Default -> Triple(c.primary, c.primaryForeground, Color.Transparent)
        BadgeVariant.Secondary -> Triple(c.secondary, c.foreground, Color.Transparent)
        BadgeVariant.Outline -> Triple(Color.Transparent, c.foreground, c.border)
        BadgeVariant.Destructive -> Triple(c.destructive, Color.White, Color.Transparent)
    }
    Row(
        modifier.clip(BadgeShape).background(bg).border(1.dp, border, BadgeShape).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    if (status == "over" || status == "critical") {
        Badge(statusLabel(status), BadgeVariant.Destructive, modifier = modifier)
    } else {
        Badge(statusLabel(status), BadgeVariant.Outline, dot = statusColor(status), modifier = modifier)
    }
}

/** shadcn Tabs: a muted track with the active segment raised to the background color. */
@Composable
fun <T> SegmentedTabs(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Cf.colors
    Row(modifier.clip(ControlShape).background(c.muted).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((value, label) in options) {
            val active = value == selected
            val bg by animateColorAsState(if (active) c.background else Color.Transparent, label = "tab")
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .then(if (active) Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Cf.type.small.copy(fontWeight = FontWeight.Medium),
                    color = if (active) c.foreground else c.mutedForeground,
                )
            }
        }
    }
}

enum class ButtonVariant { Default, Secondary, Outline, Ghost, Destructive }

@Composable
fun CfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    small: Boolean = false,
) {
    val c = Cf.colors
    val (bg, fg, border) = when (variant) {
        ButtonVariant.Default -> Triple(c.primary, c.primaryForeground, Color.Transparent)
        ButtonVariant.Secondary -> Triple(c.secondary, c.foreground, Color.Transparent)
        ButtonVariant.Outline -> Triple(c.background, c.foreground, c.border)
        ButtonVariant.Ghost -> Triple(Color.Transparent, c.foreground, Color.Transparent)
        ButtonVariant.Destructive -> Triple(c.destructive, Color.White, Color.Transparent)
    }
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier
            .heightIn(min = if (small) 32.dp else 40.dp)
            .clip(ControlShape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .border(1.dp, border, ControlShape)
            .clickable(
                enabled = enabled && !loading,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = if (small) 10.dp else 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = fg.copy(alpha = alpha))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = fg.copy(alpha = alpha), maxLines = 1)
    }
}

@Composable
fun IconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Cf.colors.foreground) {
    Box(
        modifier.size(40.dp).clip(ControlShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
fun ProgressBar(fraction: Double, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp, track: Color = Cf.colors.muted) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        val f = fraction.toFloat().coerceIn(0f, 1f)
        if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.01f)).height(height).clip(CircleShape).background(color))
    }
}

@Composable
fun Separator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Cf.colors.border))
}

/** Muted uppercase-free header row for tables; columns are (label, weight, alignEnd). */
data class Col(val label: String, val weight: Float, val end: Boolean = false)

@Composable
fun TableHeader(cols: List<Col>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        for (col in cols) {
            Text(
                col.label,
                Modifier.weight(col.weight),
                style = Cf.type.caption.copy(fontWeight = FontWeight.Medium),
                color = Cf.colors.mutedForeground,
                textAlign = if (col.end) TextAlign.End else TextAlign.Start,
                maxLines = 1,
            )
        }
    }
    Separator()
}

@Composable
fun TableRow(cells: List<Pair<String, Col>>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, last: Boolean = false) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, (text, col) ->
            Text(
                text,
                Modifier.weight(col.weight),
                style = if (col.end) Cf.type.figure.copy(fontWeight = FontWeight.Normal) else Cf.type.small,
                color = if (i == 0) Cf.colors.foreground else Cf.colors.foreground,
                textAlign = if (col.end) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (!last) Separator(Modifier.padding(horizontal = 16.dp))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.mutedForeground)
        if (trailing != null) trailing()
    }
}

/** Status pill shown in the top bar: green dot for live, red for stale/offline. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(CircleShape).border(1.dp, Cf.colors.border, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground, maxLines = 1)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = Cf.colors
    Row(
        modifier.height(40.dp).clip(ControlShape).border(1.dp, c.border, ControlShape).background(c.background).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Search, null, Modifier.size(16.dp), tint = c.mutedForeground)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Cf.type.small, color = c.mutedForeground)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Cf.type.small.copy(color = c.foreground),
                cursorBrush = SolidColor(c.foreground),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Lucide.X, "Clear", Modifier.size(16.dp).clickable { onChange("") }, tint = c.mutedForeground)
        }
    }
}

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    style: TextStyle = Cf.type.small,
) {
    val c = Cf.colors
    Box(
        modifier.heightIn(min = 40.dp).clip(ControlShape).border(1.dp, c.border, ControlShape).background(c.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = style, color = c.mutedForeground)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style.copy(color = c.foreground),
            cursorBrush = SolidColor(c.foreground),
            keyboardOptions = keyboard,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Toggle chip: an outline badge that fills when selected. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cf.colors
    Row(
        modifier.height(32.dp).clip(ControlShape)
            .background(if (selected) c.primary else c.background)
            .border(1.dp, if (selected) c.primary else c.border, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Lucide.Check, null, Modifier.size(14.dp), tint = c.primaryForeground)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = if (selected) c.primaryForeground else c.foreground)
    }
}

/** "Sort: Requests ↓" outline button with a dropdown; tapping the active field flips direction. */
@Composable
fun <T> SortMenu(
    options: List<Pair<T, String>>,
    selected: T,
    descending: Boolean,
    onSelect: (T, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: ""
    Box(modifier) {
        Row(
            Modifier.height(32.dp).clip(ControlShape).border(1.dp, Cf.colors.border, ControlShape).clickable { open = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.ArrowUpDown, null, Modifier.size(14.dp), tint = Cf.colors.mutedForeground)
            Spacer(Modifier.width(6.dp))
            Text("$label ${if (descending) "↓" else "↑"}", style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Cf.colors.card, shape = ControlShape, border = BorderStroke(1.dp, Cf.colors.border)) {
            for ((value, text) in options) {
                val active = value == selected
                DropdownMenuItem(
                    text = { Text(text, style = Cf.type.small, color = Cf.colors.foreground) },
                    trailingIcon = {
                        if (active) Text(if (descending) "↓" else "↑", style = Cf.type.small, color = Cf.colors.mutedForeground)
                    },
                    onClick = {
                        onSelect(value, if (active) !descending else descending)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Cf.type.body.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
            if (description != null) Text(description, style = Cf.type.small, color = Cf.colors.mutedForeground)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Cf.colors.primaryForeground,
                checkedTrackColor = Cf.colors.primary,
                uncheckedThumbColor = Cf.colors.background,
                uncheckedTrackColor = Cf.colors.muted,
                uncheckedBorderColor = Cf.colors.border,
            ),
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, icon: ImageVector = Lucide.Info, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Cf.colors.muted), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Cf.colors.mutedForeground)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = Cf.type.title, color = Cf.colors.foreground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = Cf.type.small, color = Cf.colors.mutedForeground, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Cf.colors.mutedForeground, strokeWidth = 2.dp)
    }
}

/** A shadcn "Alert" box: bordered, icon, title and description; destructive variant is red. */
@Composable
fun Callout(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = Lucide.Info,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Cf.colors
    val tone = if (destructive) c.destructive else c.foreground
    Row(
        modifier.fillMaxWidth().clip(CardShape).background(c.card)
            .border(1.dp, if (destructive) c.destructive.copy(alpha = 0.5f) else c.border, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tone)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Cf.type.small.copy(fontWeight = FontWeight.SemiBold), color = tone)
            if (body != null) Text(body, style = Cf.type.small, color = if (destructive) tone.copy(alpha = 0.9f) else c.mutedForeground)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}

/** Label/value tile used in stat grids. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, valueColor: Color = Cf.colors.foreground) {
    Column(modifier) {
        Text(label, style = Cf.type.caption, color = Cf.colors.mutedForeground, maxLines = 1)
        Text(value, style = Cf.type.figure.copy(fontWeight = FontWeight.SemiBold, fontSize = Cf.type.title.fontSize), color = valueColor, maxLines = 1)
        if (sub != null) Text(sub, style = Cf.type.caption, color = Cf.colors.mutedForeground, maxLines = 1)
    }
}

@Composable
fun ScreenSurface(content: @Composable () -> Unit) {
    Surface(color = Cf.colors.background, contentColor = Cf.colors.foreground, content = content)
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
