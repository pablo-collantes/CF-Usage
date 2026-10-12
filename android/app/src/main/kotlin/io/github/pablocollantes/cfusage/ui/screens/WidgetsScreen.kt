package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.ui.HomeTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.components.Badge
import io.github.pablocollantes.cfusage.ui.components.BadgeVariant
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.Callout
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.ControlShape
import io.github.pablocollantes.cfusage.ui.components.IconButton
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SectionLabel
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.widget.Designs
import io.github.pablocollantes.cfusage.widget.Pinning
import io.github.pablocollantes.cfusage.widget.Placement
import io.github.pablocollantes.cfusage.widget.Templates
import io.github.pablocollantes.cfusage.widget.WidgetDesign
import io.github.pablocollantes.cfusage.widget.WidgetPlacements
import io.github.pablocollantes.cfusage.widget.WidgetStage
import io.github.pablocollantes.cfusage.widget.WidgetUpdater
import io.github.pablocollantes.cfusage.widget.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WidgetsViewModel(private val c: AppContainer) : ViewModel() {
    val designs = c.designStore.designs
    val summary = c.summaryStore.summary
    private val _placements = MutableStateFlow<List<Placement>>(emptyList())
    val placements = _placements.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _placements.value = runCatching { WidgetPlacements.all(c.appContext) }.getOrDefault(emptyList()) }
    }

    /** Saves a new design and returns its id, for the editor. */
    fun create(design: WidgetDesign): String {
        c.designStore.save(design)
        return design.id
    }

    fun duplicate(d: WidgetDesign) = create(Designs.duplicate(d))

    fun delete(d: WidgetDesign) {
        c.designStore.delete(d.id)
        WidgetUpdater.designsChanged(c.appContext)
        refresh()
    }

    fun pin(d: WidgetDesign) {
        _message.value = when {
            !Pinning.supported(c.appContext) -> "This launcher can't add widgets for apps. Long-press the home screen → Widgets → CF Usage instead."
            Pinning.pin(c.appContext, d) -> null
            else -> "The launcher refused the request. Add it from the home screen's widget list instead."
        }
    }

    /**
     * One design per product in use that doesn't have one yet, ready to stack: detail rows, or the
     * product's Overview card.
     */
    fun productStack(summary: Summary?, cards: Boolean) {
        val existing = c.designStore.designs.value.map { it.name }.toSet()
        val made = Templates.productsInUse(summary)
            .map { if (cards) Templates.productCard(it, summary, id = newId()) else Templates.product(it, summary, id = newId()) }
            .filter { it.name !in existing }
        made.forEach { c.designStore.save(it) }
        _message.value = if (made.isEmpty()) {
            "Every product in use already has a widget. Add them to the home screen, then stack them."
        } else {
            "Added ${made.joinToString(", ") { it.name }}. Add each to the home screen, then stack them."
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

@Composable
fun WidgetsScreen(nav: Nav) {
    val vm = appViewModel { WidgetsViewModel(it) }
    val designs by vm.designs.collectAsState()
    val summary by vm.summary.collectAsState()
    val placements by vm.placements.collectAsState()
    val message by vm.message.collectAsState()
    var templates by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WidgetDesign?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    Column(Modifier.fillMaxSize()) {
        HomeTopBar("Widgets", nav, summary)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Callout(
                    "Design your widgets here",
                    "Each design can go on the home screen any number of times. To swipe between widgets, " +
                        "long-press one on the home screen, choose Create stack, and add another CF Usage widget to it.",
                    icon = Lucide.Layers,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CfButton("New widget", { templates = true }, icon = Lucide.Plus)
                    ProductStackButton { cards -> vm.productStack(summary, cards) }
                }
            }
            message?.let { m ->
                item { Callout(m, null, icon = Lucide.Info, onClick = vm::dismissMessage) }
            }
            item { SectionLabel("Your widgets") { Text("${designs.size}", style = Cf.type.caption, color = Cf.colors.mutedForeground) } }
            items(designs, key = { it.id }) { d ->
                DesignCard(
                    d,
                    summary,
                    placed = placements.count { it.designId == d.id },
                    onEdit = { nav.widget(d.id) },
                    onPin = { vm.pin(d) },
                    onDuplicate = { nav.widget(vm.duplicate(d)) },
                    onDelete = { deleting = d },
                )
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    if (templates) {
        TemplateSheet(summary, onDismiss = { templates = false }) { d ->
            templates = false
            nav.widget(vm.create(d))
        }
    }
    deleting?.let { d ->
        DeleteDesignDialog(d, placements.count { it.designId == d.id }, onDismiss = { deleting = null }) {
            vm.delete(d)
            deleting = null
        }
    }
}

/** "Product stack", then which style: the detail rows or the Overview's cards. */
@Composable
private fun ProductStackButton(onPick: (cards: Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CfButton("Product stack", { open = true }, variant = ButtonVariant.Outline, icon = Lucide.Layers)
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Cf.colors.card, shape = ControlShape, border = BorderStroke(1.dp, Cf.colors.border)) {
            for ((cards, title, body) in listOf(
                Triple(false, "Detail rows", "Each metric with used / included and the projection"),
                Triple(true, "Overview cards", "Each product's card, as on the Overview"),
            )) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(title, style = Cf.type.small.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.foreground)
                            Text(body, style = Cf.type.caption, color = Cf.colors.mutedForeground)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(cards)
                    },
                )
            }
        }
    }
}

@Composable
private fun DesignCard(
    d: WidgetDesign,
    summary: Summary?,
    placed: Int,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    CfCard(Modifier.fillMaxWidth(), onClick = onEdit) {
        Box(Modifier.padding(12.dp)) {
            WidgetStage(d, summary, d.size.widthDp, d.size.heightDp, maxHeight = 220.dp, pad = 14.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = Cf.type.title, color = Cf.colors.foreground, maxLines = 1)
                Text("${d.size.label} · ${d.blocks.size} block${if (d.blocks.size == 1) "" else "s"}", style = Cf.type.caption, color = Cf.colors.mutedForeground)
            }
            if (placed > 0) {
                Badge("On home screen${if (placed > 1) " ×$placed" else ""}", BadgeVariant.Outline, dot = Cf.colors.chart2)
            } else {
                Badge("Not placed", BadgeVariant.Secondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CfButton("Edit", onEdit, variant = ButtonVariant.Outline, small = true, icon = Lucide.Pencil)
            Spacer(Modifier.width(8.dp))
            CfButton("Add to home screen", onPin, small = true, icon = Lucide.HomeAdd)
            Spacer(Modifier.weight(1f))
            IconButton(Lucide.Copy, "Duplicate", onDuplicate, tint = Cf.colors.mutedForeground)
            IconButton(Lucide.Trash, "Delete", onDelete, tint = Cf.colors.mutedForeground)
        }
    }
}

@Composable
fun DeleteDesignDialog(d: WidgetDesign, placed: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cf.colors.card,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Delete “${d.name}”?", style = Cf.type.h2, color = Cf.colors.foreground) },
        text = {
            Text(
                if (placed > 0) {
                    "$placed widget${if (placed == 1) "" else "s"} on your home screen show this design. They'll switch to their default design."
                } else {
                    "It isn't on your home screen."
                },
                style = Cf.type.small,
                color = Cf.colors.mutedForeground,
            )
        },
        confirmButton = { CfButton("Delete", onConfirm, variant = ButtonVariant.Destructive, small = true) },
        dismissButton = { CfButton("Cancel", onDismiss, variant = ButtonVariant.Outline, small = true) },
    )
}

private data class TemplateOption(val title: String, val body: String, val icon: ImageVector, val make: (Summary?) -> WidgetDesign)

private val TemplateOptions = listOf(
    TemplateOption("Overview cards", "The Overview's product cards: status, a ring per metric and detail rows", Lucide.Layers) {
        Templates.overviewCards()
    },
    TemplateOption("Overview rings", "Products side by side: R, W, rq and S with a small ring and percent", Lucide.LayoutGrid) {
        Templates.overview(id = newId())
    },
    TemplateOption("Single metric", "One metric large, with its daily chart", Lucide.Activity) { s ->
        val key = s?.metrics?.maxByOrNull { it.pct }?.key ?: "workers_requests"
        Templates.spotlight(key, id = newId())
    },
    TemplateOption("Top list", "The Workers, databases or buckets using the most", Lucide.Server) {
        Templates.topWorkers(id = newId())
    },
    TemplateOption("Bill & status", "Estimated bill, cycle progress, alerts and kill switches", Lucide.Zap) {
        Templates.billAndStatus()
    },
    TemplateOption("Blank", "Just a header; add blocks yourself", Lucide.Plus) { Templates.blank() },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TemplateSheet(summary: Summary?, onDismiss: () -> Unit, onPick: (WidgetDesign) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Cf.colors.card,
        contentColor = Cf.colors.foreground,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Cf.colors.border))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("New widget", style = Cf.type.h2, color = Cf.colors.foreground)
            Text("Start from a template, then change anything.", style = Cf.type.small, color = Cf.colors.mutedForeground)
            Spacer(Modifier.height(14.dp))
            Text("Product detail", style = Cf.type.small.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.foreground)
            Text("Every metric of one product with used / included and the projection", style = Cf.type.caption, color = Cf.colors.mutedForeground)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in MetricCatalog.groups) {
                    CfButton(g.short, { onPick(Templates.product(g.name, summary, id = newId())) }, variant = ButtonVariant.Outline, small = true)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Product card", style = Cf.type.small.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.foreground)
            Text("One product's card from the Overview", style = Cf.type.caption, color = Cf.colors.mutedForeground)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in MetricCatalog.groups) {
                    CfButton(g.short, { onPick(Templates.productCard(g.name, summary)) }, variant = ButtonVariant.Outline, small = true)
                }
            }
            Spacer(Modifier.height(10.dp))
            for (t in TemplateOptions) {
                Row(
                    Modifier.fillMaxWidth().clip(ControlShape).clickable { onPick(t.make(summary)) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(ControlShape).background(Cf.colors.muted), contentAlignment = Alignment.Center) {
                        Icon(t.icon, null, Modifier.size(18.dp), tint = Cf.colors.foreground)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = Cf.type.small.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.foreground)
                        Text(t.body, style = Cf.type.caption, color = Cf.colors.mutedForeground)
                    }
                    Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = Cf.colors.mutedForeground)
                }
            }
        }
    }
}
