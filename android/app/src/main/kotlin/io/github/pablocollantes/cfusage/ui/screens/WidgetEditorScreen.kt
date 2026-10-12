package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.ui.DetailTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.Callout
import io.github.pablocollantes.cfusage.ui.components.CardHeader
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.ControlShape
import io.github.pablocollantes.cfusage.ui.components.EmptyState
import io.github.pablocollantes.cfusage.ui.components.FilterChip
import io.github.pablocollantes.cfusage.ui.components.IconButton
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SectionLabel
import io.github.pablocollantes.cfusage.ui.components.SegmentedTabs
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.components.SwitchRow
import io.github.pablocollantes.cfusage.ui.components.TextInput
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.widget.Accent
import io.github.pablocollantes.cfusage.widget.BillBlock
import io.github.pablocollantes.cfusage.widget.Block
import io.github.pablocollantes.cfusage.widget.CardContent
import io.github.pablocollantes.cfusage.widget.CardsBlock
import io.github.pablocollantes.cfusage.widget.ColorMode
import io.github.pablocollantes.cfusage.widget.Corners
import io.github.pablocollantes.cfusage.widget.Density
import io.github.pablocollantes.cfusage.widget.Designs
import io.github.pablocollantes.cfusage.widget.HeaderBlock
import io.github.pablocollantes.cfusage.widget.Indicator
import io.github.pablocollantes.cfusage.widget.ListBlock
import io.github.pablocollantes.cfusage.widget.MAX_BLOCKS
import io.github.pablocollantes.cfusage.widget.MetricBlock
import io.github.pablocollantes.cfusage.widget.MetricVisual
import io.github.pablocollantes.cfusage.widget.Pinning
import io.github.pablocollantes.cfusage.widget.ProductSel
import io.github.pablocollantes.cfusage.widget.SectionLayout
import io.github.pablocollantes.cfusage.widget.SectionsBlock
import io.github.pablocollantes.cfusage.widget.SizePreset
import io.github.pablocollantes.cfusage.widget.StatusBlock
import io.github.pablocollantes.cfusage.widget.Templates
import io.github.pablocollantes.cfusage.widget.TextSize
import io.github.pablocollantes.cfusage.widget.TopBlock
import io.github.pablocollantes.cfusage.widget.ValueStyle
import io.github.pablocollantes.cfusage.widget.WidgetCharts
import io.github.pablocollantes.cfusage.widget.WidgetDesign
import io.github.pablocollantes.cfusage.widget.WidgetPlacements
import io.github.pablocollantes.cfusage.widget.WidgetStage
import io.github.pablocollantes.cfusage.widget.WidgetTheme
import io.github.pablocollantes.cfusage.widget.WidgetText
import io.github.pablocollantes.cfusage.widget.WidgetUpdater
import io.github.pablocollantes.cfusage.widget.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Edits one design. Every change shows in the preview at once and is saved shortly after. */
class WidgetEditorViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val _design = MutableStateFlow(c.designStore.get(id))
    val design = _design.asStateFlow()
    val summary = c.summaryStore.summary
    private val _placed = MutableStateFlow<List<DpSize>>(emptyList())

    /** Sizes this design has on the home screen right now, for "as placed" previews. */
    val placed = _placed.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var saveJob: Job? = null
    private var dirty = false

    fun refresh() {
        viewModelScope.launch {
            val all = runCatching { WidgetPlacements.all(c.appContext) }.getOrDefault(emptyList())
            _placed.value = all.filter { it.designId == id }.mapNotNull { p -> p.sizes.maxByOrNull { it.width.value * it.height.value } }.distinct()
        }
    }

    fun edit(f: (WidgetDesign) -> WidgetDesign) {
        val d = _design.value ?: return
        val next = f(d)
        if (next == d) return
        _design.value = next
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(350)
            persist()
        }
    }

    private fun persist() {
        val d = _design.value ?: return
        if (!dirty) return
        dirty = false
        c.designStore.save(d)
        WidgetUpdater.designsChanged(c.appContext)
    }

    fun editBlock(blockId: String, f: (Block) -> Block) = edit { d -> d.copy(blocks = d.blocks.map { if (it.id == blockId) f(it) else it }) }

    fun move(blockId: String, delta: Int) = edit { d ->
        val i = d.blocks.indexOfFirst { it.id == blockId }
        val j = i + delta
        if (i < 0 || j !in d.blocks.indices) d else d.copy(blocks = d.blocks.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun remove(blockId: String) = edit { d -> d.copy(blocks = d.blocks.filterNot { it.id == blockId }) }

    fun add(block: Block) = edit { d -> if (d.blocks.size >= MAX_BLOCKS) d else d.copy(blocks = d.blocks + block) }

    fun pin() {
        persist()
        val d = _design.value ?: return
        _message.value = when {
            !Pinning.supported(c.appContext) -> "This launcher can't add widgets for apps. Long-press the home screen → Widgets → CF Usage instead."
            Pinning.pin(c.appContext, d) -> null
            else -> "The launcher refused the request."
        }
    }

    fun duplicate(): String? {
        persist()
        val d = _design.value ?: return null
        return Designs.duplicate(d).also { c.designStore.save(it) }.id
    }

    fun delete() {
        saveJob?.cancel()
        dirty = false
        c.designStore.delete(id)
        _design.value = null
        WidgetUpdater.designsChanged(c.appContext)
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() {
        persist()
    }
}

@Composable
fun WidgetEditorScreen(nav: Nav, id: String) {
    val vm = appViewModel(key = "widget:$id") { WidgetEditorViewModel(it, id) }
    val design by vm.design.collectAsState()
    val summary by vm.summary.collectAsState()
    val placed by vm.placed.collectAsState()
    val message by vm.message.collectAsState()
    var previewSize by remember { mutableStateOf<DpSize?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    val d = design
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(
            d?.name ?: "Widget",
            nav,
            subtitle = if (placed.isEmpty()) "Saved as you edit" else "Saved as you edit · on home screen ×${placed.size}",
        ) {
            if (d != null) IconButton(Lucide.HomeAdd, "Add to home screen", vm::pin)
        }
        if (d == null) {
            EmptyState("This widget was deleted", "Pick another design on the Widgets tab.", icon = Lucide.LayoutGrid)
            return@Column
        }
        // The preview stays put while the options scroll underneath.
        Column(Modifier.fillMaxWidth().background(Cf.colors.background).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
            val w = previewSize?.width?.value ?: d.size.widthDp
            val h = previewSize?.height?.value ?: d.size.heightDp
            WidgetStage(d, summary, w, h, maxHeight = 230.dp)
            Spacer(Modifier.height(10.dp))
            SegmentedTabs<SizePreset?>(SizePreset.entries.map { it to it.label }, if (previewSize == null) d.size else null, { p ->
                previewSize = null
                if (p != null) vm.edit { it.copy(size = p) }
            })
            if (placed.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sz in placed) {
                        val label = "As placed ${sz.width.value.roundToInt()}×${sz.height.value.roundToInt()}"
                        FilterChip(label, previewSize == sz, { previewSize = if (previewSize == sz) null else sz })
                    }
                }
            }
        }
        Separator()
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { m -> item { Callout(m, null, icon = Lucide.Info, onClick = vm::dismissMessage) } }
            item {
                Column {
                    FieldLabel("Name")
                    TextInput(d.name, { name -> vm.edit { it.copy(name = name) } }, Modifier.fillMaxWidth(), placeholder = "Widget name")
                }
            }
            item { StyleCard(d, vm) }
            item {
                SectionLabel("Blocks") {
                    Text("${d.blocks.size} of $MAX_BLOCKS · top to bottom", style = Cf.type.caption, color = Cf.colors.mutedForeground)
                }
            }
            items(d.blocks, key = { it.id }) { b ->
                BlockCard(b, first = b == d.blocks.first(), last = b == d.blocks.last(), vm)
            }
            item { AddBlock(enabled = d.blocks.size < MAX_BLOCKS, onAdd = vm::add) }
            item {
                Text(
                    "Blocks that don't fit the widget's size are left out, and lists show as many rows as fit. Resize the widget on the home screen to show more.",
                    style = Cf.type.caption,
                    color = Cf.colors.mutedForeground,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CfButton("Add to home screen", vm::pin, icon = Lucide.HomeAdd, small = true)
                    CfButton("Duplicate", { vm.duplicate()?.let { nav.widget(it) } }, variant = ButtonVariant.Outline, icon = Lucide.Copy, small = true)
                    CfButton("Delete", { confirmDelete = true }, variant = ButtonVariant.Ghost, icon = Lucide.Trash, small = true)
                }
            }
        }
    }
    if (confirmDelete && d != null) {
        DeleteDesignDialog(d, placed.size, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            vm.delete()
            nav.back()
        }
    }
}

@Composable
private fun FieldLabel(text: String, hint: String? = null) {
    Text(text, style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
    if (hint != null) Text(hint, style = Cf.type.caption, color = Cf.colors.mutedForeground)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun <T> Field(label: String, options: List<Pair<T, String>>, selected: T, hint: String? = null, onSelect: (T) -> Unit) {
    Column {
        FieldLabel(label, hint)
        SegmentedTabs(options, selected, onSelect, Modifier.fillMaxWidth())
    }
}

@Composable
private fun StyleCard(d: WidgetDesign, vm: WidgetEditorViewModel) {
    val st = d.style
    fun set(f: (io.github.pablocollantes.cfusage.widget.WidgetStyle) -> io.github.pablocollantes.cfusage.widget.WidgetStyle) =
        vm.edit { it.copy(style = f(it.style)) }
    CfCard(Modifier.fillMaxWidth()) {
        CardHeader("Style")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field("Theme", listOf(WidgetTheme.System to "System", WidgetTheme.Light to "Light", WidgetTheme.Dark to "Dark"), st.theme) { v -> set { it.copy(theme = v) } }
            Field("Background", listOf(100 to "100%", 85 to "85%", 70 to "70%", 50 to "50%", 0 to "None"), st.opacity) { v -> set { it.copy(opacity = v) } }
            Field("Corners", listOf(Corners.Square to "Square", Corners.Rounded to "Rounded", Corners.Round to "Round"), st.corners) { v -> set { it.copy(corners = v) } }
            Column {
                FieldLabel("Accent", "Rings and charts; warnings still turn amber and red")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (a in Accent.entries) Swatch(a, a == st.accent) { set { it.copy(accent = a) } }
                }
            }
            Field("Text size", listOf(TextSize.Compact to "Compact", TextSize.Default to "Default", TextSize.Large to "Large"), st.text) { v -> set { it.copy(text = v) } }
            Column {
                SwitchRow("Border", "A hairline outline, like the app's cards", st.border, { v -> set { it.copy(border = v) } })
                SwitchRow("Updated time", "When the numbers last refreshed; red when they're stale", st.showUpdated, { v -> set { it.copy(showUpdated = v) } })
            }
        }
    }
}

@Composable
private fun Swatch(a: Accent, selected: Boolean, onClick: () -> Unit) {
    val c = Color(WidgetCharts.accentColor(a))
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .border(BorderStroke(2.dp, if (selected) Cf.colors.foreground else Color.Transparent), CircleShape)
            .clickable(onClickLabel = a.name, onClick = onClick)
            .semantics {
                contentDescription = "${a.name} accent"
                this.selected = selected
            }
            .padding(4.dp)
            .clip(CircleShape)
            .background(c),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Color.White)
    }
}

private fun blockTitle(b: Block) = when (b) {
    is HeaderBlock -> "Header"
    is SectionsBlock -> "Product sections"
    is CardsBlock -> "Product cards"
    is ListBlock -> "Detail rows"
    is MetricBlock -> "Single metric"
    is TopBlock -> "Top list"
    is BillBlock -> "Bill & cycle"
    is StatusBlock -> "Alerts & kill switches"
}

private fun blockIcon(b: Block): ImageVector = when (b) {
    is HeaderBlock -> Lucide.Dashboard
    is SectionsBlock -> Lucide.LayoutGrid
    is CardsBlock -> Lucide.Layers
    is ListBlock -> Lucide.Database
    is MetricBlock -> Lucide.Activity
    is TopBlock -> Lucide.Server
    is BillBlock -> Lucide.Zap
    is StatusBlock -> Lucide.Bell
}

private fun valueLabel(v: ValueStyle) = when (v) {
    ValueStyle.Percent -> "%"
    ValueStyle.UsedOfTotal -> "used / total"
    ValueStyle.Used -> "used"
    ValueStyle.Projected -> "projected"
}

private fun blockSummary(b: Block): String = when (b) {
    is HeaderBlock -> listOfNotNull("“${b.title}”", "status".takeIf { b.statusDot }, "bill".takeIf { b.bill }, "days left".takeIf { b.cycle }).joinToString(" · ")
    is SectionsBlock -> listOf(
        b.products.filter { it.metrics.isNotEmpty() }.joinToString(", ") { MetricCatalog.groupShort(it.group) }.ifEmpty { "nothing chosen" },
        b.layout.name.lowercase(),
        b.indicator.name.lowercase(),
        valueLabel(b.value),
    ).joinToString(" · ")
    is CardsBlock -> listOfNotNull(
        if (b.products.isEmpty()) "all in use" else b.products.joinToString(", ") { MetricCatalog.groupShort(it) },
        cardContentLabel(b.content).lowercase(),
        "compact".takeIf { b.density == Density.Compact },
        "projection".takeIf { b.showProjected },
    ).joinToString(" · ")
    is ListBlock -> "${b.metrics.size} metric${if (b.metrics.size == 1) "" else "s"} · ${valueLabel(b.value)}"
    is MetricBlock -> "${MetricCatalog.label(b.key)} · ${b.visual.name.lowercase()}"
    is TopBlock -> "${WidgetText.topTitle(b.key)} · ${b.count}"
    is BillBlock -> listOfNotNull("bill", "cycle".takeIf { b.cycleProgress }, "overage".takeIf { b.overage }).joinToString(" · ")
    is StatusBlock -> if (b.hideWhenClear) "hidden when all clear" else "always shown"
}

@Composable
private fun BlockCard(b: Block, first: Boolean, last: Boolean, vm: WidgetEditorViewModel) {
    var open by rememberSaveable(b.id) { mutableStateOf(false) }
    CfCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(ControlShape).background(Cf.colors.muted), contentAlignment = Alignment.Center) {
                Icon(blockIcon(b), null, Modifier.size(16.dp), tint = Cf.colors.foreground)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(blockTitle(b), style = Cf.type.small.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.foreground)
                Text(blockSummary(b), style = Cf.type.caption, color = Cf.colors.mutedForeground, maxLines = 1)
            }
            Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, null, Modifier.size(16.dp), tint = Cf.colors.mutedForeground)
        }
        if (open) {
            Separator()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (b) {
                    is HeaderBlock -> HeaderEditor(b) { f -> vm.editBlock(b.id) { f(it as HeaderBlock) } }
                    is SectionsBlock -> SectionsEditor(b) { f -> vm.editBlock(b.id) { f(it as SectionsBlock) } }
                    is CardsBlock -> {
                        val summary by vm.summary.collectAsState()
                        CardsEditor(b, summary) { f -> vm.editBlock(b.id) { f(it as CardsBlock) } }
                    }
                    is ListBlock -> ListEditor(b) { f -> vm.editBlock(b.id) { f(it as ListBlock) } }
                    is MetricBlock -> MetricEditor(b) { f -> vm.editBlock(b.id) { f(it as MetricBlock) } }
                    is TopBlock -> TopEditor(b) { f -> vm.editBlock(b.id) { f(it as TopBlock) } }
                    is BillBlock -> BillEditor(b) { f -> vm.editBlock(b.id) { f(it as BillBlock) } }
                    is StatusBlock -> StatusEditor(b) { f -> vm.editBlock(b.id) { f(it as StatusBlock) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CfButton("Up", { vm.move(b.id, -1) }, variant = ButtonVariant.Outline, icon = Lucide.ArrowUp, small = true, enabled = !first)
                    CfButton("Down", { vm.move(b.id, 1) }, variant = ButtonVariant.Outline, icon = Lucide.ArrowDown, small = true, enabled = !last)
                    Spacer(Modifier.weight(1f))
                    CfButton("Remove", { vm.remove(b.id) }, variant = ButtonVariant.Ghost, icon = Lucide.Trash, small = true)
                }
            }
        }
    }
}

@Composable
private fun HeaderEditor(b: HeaderBlock, set: ((HeaderBlock) -> HeaderBlock) -> Unit) {
    Column {
        FieldLabel("Title")
        TextInput(b.title, { t -> set { it.copy(title = t) } }, Modifier.fillMaxWidth(), placeholder = "CF Usage")
    }
    Column {
        SwitchRow("Status dot", "Green, amber or red for the whole account", b.statusDot, { v -> set { it.copy(statusDot = v) } })
        SwitchRow("Estimated bill", "Off puts the updated time here instead", b.bill, { v -> set { it.copy(bill = v) } })
        SwitchRow("Days left", "Days left in the cycle and the reset date", b.cycle, { v -> set { it.copy(cycle = v) } })
    }
}

private val IndicatorOptions = listOf(Indicator.Ring to "Ring", Indicator.Dot to "Dot", Indicator.Bar to "Bar", Indicator.None to "None")
private val ValueOptions = listOf(ValueStyle.Percent to "%", ValueStyle.UsedOfTotal to "Used/total", ValueStyle.Used to "Used", ValueStyle.Projected to "Proj.")

/** Toggles [key] in a product selection, keeping catalog order and dropping emptied products. */
internal fun toggleProductMetric(products: List<ProductSel>, key: String): List<ProductSel> {
    val group = MetricCatalog.entry(key)?.group ?: return products
    val order = MetricCatalog.all.map { it.key }
    val existing = products.firstOrNull { it.group == group }
    return if (existing == null) {
        products + ProductSel(group, listOf(key))
    } else {
        val next = if (key in existing.metrics) existing.metrics - key else (existing.metrics + key).sortedBy { order.indexOf(it) }
        if (next.isEmpty()) products.filterNot { it.group == group } else products.map { if (it.group == group) it.copy(metrics = next) else it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionsEditor(b: SectionsBlock, set: ((SectionsBlock) -> SectionsBlock) -> Unit) {
    Column {
        FieldLabel("Products and metrics", "Tap a code to include it. Chosen products show in this order.")
        val chosen = b.products.map { it.group }
        val groups = chosen + MetricCatalog.groups.map { it.name }.filterNot { it in chosen }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (g in groups) {
                val sel = b.products.firstOrNull { it.group == g }?.metrics.orEmpty()
                val idx = chosen.indexOf(g)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g, Modifier.weight(1f), style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = if (sel.isEmpty()) Cf.colors.mutedForeground else Cf.colors.foreground)
                        if (idx > 0) {
                            Text(
                                "Move up",
                                Modifier.clip(ControlShape).clickable {
                                    set { s -> s.copy(products = s.products.toMutableList().apply { add(idx - 1, removeAt(idx)) }) }
                                }.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = Cf.type.caption.copy(fontWeight = FontWeight.Medium),
                                color = Cf.colors.mutedForeground,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (e in MetricCatalog.inGroup(g)) {
                            MetricChip(e, e.key in sel) { set { s -> s.copy(products = toggleProductMetric(s.products, e.key)) } }
                        }
                    }
                }
            }
        }
    }
    Field("Layout", listOf(SectionLayout.Rows to "Rows", SectionLayout.Columns to "Columns", SectionLayout.Tiles to "Tiles"), b.layout,
        hint = "Rows: a product per line. Columns: products side by side. Tiles: a ring per metric.") { v -> set { it.copy(layout = v) } }
    Field("Indicator", IndicatorOptions, b.indicator) { v -> set { it.copy(indicator = v) } }
    Field("Value", ValueOptions, b.value, hint = "Proj. is the end-of-cycle estimate; for storage, the billed average") { v -> set { it.copy(value = v) } }
    Field("Colour", listOf(ColorMode.Status to "Status", ColorMode.Accent to "Accent"), b.colors,
        hint = "Status: green, blue, amber, red. Accent: the accent colour until a warning.") { v -> set { it.copy(colors = v) } }
}

private fun cardContentLabel(c: CardContent) = when (c) {
    CardContent.RingsAndRows -> "Rings + rows"
    CardContent.Rings -> "Rings"
    CardContent.Rows -> "Rows"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardsEditor(b: CardsBlock, summary: Summary?, set: ((CardsBlock) -> CardsBlock) -> Unit) {
    val chosen = b.products.isNotEmpty()
    Column {
        FieldLabel("Products", if (chosen) "Tap to add or remove; cards show in the order you pick them" else "Every product with usage this cycle, as on the Overview")
        SegmentedTabs(listOf(false to "All in use", true to "Choose"), chosen, { pick ->
            set { c ->
                when {
                    pick == c.products.isNotEmpty() -> c
                    pick -> c.copy(products = Templates.productsInUse(summary).ifEmpty { MetricCatalog.groups.map { it.name } })
                    else -> c.copy(products = emptyList())
                }
            }
        }, Modifier.fillMaxWidth())
        if (chosen) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (g in MetricCatalog.groups) {
                    val i = b.products.indexOf(g.name)
                    FilterChip(if (i >= 0) "${i + 1}  ${g.short}" else g.short, i >= 0, {
                        set { c ->
                            when {
                                g.name !in c.products -> c.copy(products = c.products + g.name)
                                c.products.size > 1 -> c.copy(products = c.products - g.name)
                                else -> c // keep at least one; "All in use" is the other tab
                            }
                        }
                    })
                }
            }
        }
    }
    Field(
        "Show",
        CardContent.entries.map { it to cardContentLabel(it) },
        b.content,
        hint = "Rings: a ring per metric in use. Rows: used / included and percent per metric.",
    ) { v -> set { it.copy(content = v) } }
    Field("Density", listOf(Density.Comfortable to "Comfortable", Density.Compact to "Compact"), b.density,
        hint = "Compact: smaller rings, one line per metric, the projection as a short last column") { v -> set { it.copy(density = v) } }
    SwitchRow("Projection", "The end-of-cycle estimate; for storage, the billed average", b.showProjected, { v -> set { it.copy(showProjected = v) } })
}

/** A metric toggle showing its widget code and name: "W  Rows written". */
@Composable
private fun MetricChip(e: MetricCatalog.Entry, selected: Boolean, onClick: () -> Unit) {
    val c = Cf.colors
    Row(
        Modifier.height(32.dp).clip(ControlShape)
            .background(if (selected) c.primary else c.background)
            .border(1.dp, if (selected) c.primary else c.border, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.short, style = Cf.type.small.copy(fontWeight = FontWeight.Bold), color = if (selected) c.primaryForeground else c.foreground)
        Spacer(Modifier.width(6.dp))
        Text(e.name, style = Cf.type.caption, color = if (selected) c.primaryForeground.copy(alpha = 0.8f) else c.mutedForeground, maxLines = 1)
    }
}

/** Metrics grouped by product. Multi-select toggles; single-select replaces. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricPicker(selected: List<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (g in MetricCatalog.groups) {
            Column {
                Text(g.name, style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.mutedForeground)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in MetricCatalog.inGroup(g.name)) MetricChip(e, e.key in selected) { onToggle(e.key) }
                }
            }
        }
    }
}

@Composable
private fun ListEditor(b: ListBlock, set: ((ListBlock) -> ListBlock) -> Unit) {
    val order = MetricCatalog.all.map { it.key }
    Column {
        FieldLabel("Metrics", "One row each. Pick from one product for a product widget, or mix.")
        MetricPicker(b.metrics) { key ->
            set { l -> l.copy(metrics = (if (key in l.metrics) l.metrics - key else l.metrics + key).sortedBy { order.indexOf(it) }) }
        }
    }
    Field("Indicator", IndicatorOptions, b.indicator) { v -> set { it.copy(indicator = v) } }
    Field("Value", ValueOptions, b.value) { v -> set { it.copy(value = v) } }
    Column {
        SwitchRow("Percent", "On the second line when the value isn't already a percent", b.showPercent, { v -> set { it.copy(showPercent = v) } })
        SwitchRow("Projection", "The end-of-cycle estimate; for storage, the billed average", b.showProjected, { v -> set { it.copy(showProjected = v) } })
    }
}

@Composable
private fun MetricEditor(b: MetricBlock, set: ((MetricBlock) -> MetricBlock) -> Unit) {
    Column {
        FieldLabel("Metric")
        MetricPicker(listOf(b.key)) { key -> set { it.copy(key = key) } }
    }
    Field(
        "Visual",
        listOf(MetricVisual.Ring to "Ring", MetricVisual.Daily to "Daily", MetricVisual.Bars to "Bars", MetricVisual.Cumulative to "Total", MetricVisual.None to "None"),
        b.visual,
        hint = "Daily and Bars chart each day of the cycle; Total is the running total against the allowance",
    ) { v -> set { it.copy(visual = v) } }
    SwitchRow("Projection", "The end-of-cycle estimate; for storage, the billed average", b.showProjected, { v -> set { it.copy(showProjected = v) } })
}

@Composable
private fun TopEditor(b: TopBlock, set: ((TopBlock) -> TopBlock) -> Unit) {
    Column {
        FieldLabel("Ranked by", "Workers for Workers metrics, namespaces, databases or buckets for the others")
        MetricPicker(listOf(b.key)) { key -> set { it.copy(key = key) } }
    }
    Field("Rows", listOf(3 to "3", 5 to "5", 8 to "8"), b.count) { v -> set { it.copy(count = v) } }
    Field("Value", listOf(false to "Amount", true to "Share"), b.share) { v -> set { it.copy(share = v) } }
    SwitchRow("Bars", "Shade each row by its size", b.bars, { v -> set { it.copy(bars = v) } })
}

@Composable
private fun BillEditor(b: BillBlock, set: ((BillBlock) -> BillBlock) -> Unit) {
    Column {
        SwitchRow("Cycle progress", "A bar for how much of the cycle has passed", b.cycleProgress, { v -> set { it.copy(cycleProgress = v) } })
        SwitchRow("Overage line", "What's projected beyond the \$5 base", b.overage, { v -> set { it.copy(overage = v) } })
    }
}

@Composable
private fun StatusEditor(b: StatusBlock, set: ((StatusBlock) -> StatusBlock) -> Unit) {
    Column {
        SwitchRow("Alerts", "How many alerts are open", b.alerts, { v -> set { it.copy(alerts = v) } })
        SwitchRow("Kill switches", "How many are on", b.kills, { v -> set { it.copy(kills = v) } })
        SwitchRow("Hide when all clear", "Takes no room until something needs attention", b.hideWhenClear, { v -> set { it.copy(hideWhenClear = v) } })
    }
}

private data class NewBlock(val label: String, val make: () -> Block)

private val NewBlocks = listOf(
    NewBlock("Header") { HeaderBlock(id = newId()) },
    NewBlock("Product sections") { (Templates.overview().blocks[1] as SectionsBlock).copy(id = newId()) },
    NewBlock("Product cards") { CardsBlock(id = newId()) },
    NewBlock("Detail rows") { ListBlock(id = newId(), metrics = Templates.productMetrics("D1", null)) },
    NewBlock("Single metric") { MetricBlock(id = newId()) },
    NewBlock("Top list") { TopBlock(id = newId()) },
    NewBlock("Bill & cycle") { BillBlock(id = newId()) },
    NewBlock("Alerts & kill switches") { StatusBlock(id = newId()) },
)

@Composable
private fun AddBlock(enabled: Boolean, onAdd: (Block) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CfButton(if (enabled) "Add block" else "$MAX_BLOCKS blocks is the most a widget holds", { open = true }, variant = ButtonVariant.Outline, icon = Lucide.Plus, enabled = enabled)
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Cf.colors.card, shape = ControlShape, border = BorderStroke(1.dp, Cf.colors.border)) {
            for (nb in NewBlocks) {
                DropdownMenuItem(
                    text = { Text(nb.label, style = Cf.type.small, color = Cf.colors.foreground) },
                    onClick = {
                        open = false
                        onAdd(nb.make())
                    },
                )
            }
        }
    }
}
