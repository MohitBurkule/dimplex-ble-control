package com.mohit.dimplex.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HeaterConfig(
    val label: String,
    val address: String,
    val pin: String,
    /** Floorplan position (future feature, see docs/roadmap.md). */
    val x: Float? = null,
    val y: Float? = null,
) {
    /** Stable id used as foreign key in Room tables. */
    val id: String get() = address.uppercase()
}

@Serializable
data class HeaterFile(val heaters: List<HeaterConfig> = emptyList())

/** Heater list (incl. PINs) kept in an app-private file (not backed up: allowBackup=false). */
class HeaterStore(context: Context) {
    private val file = File(context.filesDir, "heaters.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<HeaterConfig>> = _list

    private fun load(): List<HeaterConfig> =
        runCatching { json.decodeFromString(HeaterFile.serializer(), file.readText()).heaters }.getOrDefault(emptyList())

    private fun save(l: List<HeaterConfig>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(HeaterFile.serializer(), HeaterFile(l)))
        tmp.renameTo(file)
        _list.value = l
    }

    fun byLabel(label: String) = _list.value.firstOrNull { it.label.equals(label, ignoreCase = true) }
    fun byId(id: String) = _list.value.firstOrNull { it.id == id.uppercase() }

    /** Parse an imported config; merges by address (imported wins). Returns count imported. */
    fun import(text: String): Int {
        val imported = json.decodeFromString(HeaterFile.serializer(), text).heaters.map { validate(it) }
        val merged = _list.value.filterNot { e -> imported.any { it.id == e.id } } + imported
        save(merged)
        return imported.size
    }

    fun upsert(h: HeaterConfig, previousId: String? = null) {
        val v = validate(h)
        save(_list.value.filterNot { it.id == v.id || it.id == previousId } + v)
    }

    fun delete(id: String) = save(_list.value.filterNot { it.id == id })

    companion object {
        private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        fun validate(h: HeaterConfig): HeaterConfig {
            require(h.label.isNotBlank()) { "label is empty" }
            require(MAC.matches(h.address.trim())) { "bad address '${h.address}'" }
            require(h.pin.trim().matches(Regex("^\\d{6}$"))) { "PIN must be 6 digits" }
            return h.copy(label = h.label.trim(), address = h.address.trim().uppercase(), pin = h.pin.trim())
        }
    }
}
