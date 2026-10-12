package io.github.pablocollantes.cfusage.widget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The user's widget designs, persisted as JSON next to the cached summary. The first run writes
 * the seed designs. A file that no longer parses is kept aside as .bad rather than overwritten.
 */
class WidgetDesignStore(context: Context) {
    private val file = File(context.filesDir, "widget_designs.json")
    private val _designs = MutableStateFlow<List<WidgetDesign>>(emptyList())
    val designs: StateFlow<List<WidgetDesign>> = _designs.asStateFlow()

    init {
        _designs.value = load()
    }

    private fun load(): List<WidgetDesign> {
        if (file.exists()) {
            runCatching { return Designs.decode(file.readText()) }.onFailure {
                Log.w("CfUsage", "widget designs unreadable, starting over", it)
                file.renameTo(File(file.parentFile, "widget_designs.json.bad"))
            }
        }
        return Templates.seeds().also { write(it) }
    }

    fun get(id: String) = _designs.value.firstOrNull { it.id == id }

    /** Insert or replace by id, keeping the list order. */
    @Synchronized
    fun save(design: WidgetDesign) {
        val cur = _designs.value
        val next = if (cur.any { it.id == design.id }) cur.map { if (it.id == design.id) design else it } else cur + design
        _designs.value = next
        write(next)
    }

    @Synchronized
    fun delete(id: String) {
        val next = _designs.value.filterNot { it.id == id }
        _designs.value = next
        write(next)
    }

    private fun write(designs: List<WidgetDesign>) {
        val tmp = File(file.parentFile, "widget_designs.json.tmp")
        runCatching {
            tmp.writeText(Designs.encode(designs))
            tmp.renameTo(file)
        }.onFailure { Log.w("CfUsage", "could not save widget designs", it) }
    }
}
