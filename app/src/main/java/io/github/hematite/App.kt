package io.github.hematite

import android.app.Application
import android.content.Context
import android.os.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings private set
    lateinit var secrets: Secrets private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        secrets = Secrets(this)
    }

    /** The vault the user chose, or the app's own Vault folder. */
    fun vaultDirectory(): File = settings.vaultDirectory.takeIf { it.isNotEmpty() }?.let(::File) ?: defaultVault()

    fun defaultVault(): File = File(getExternalFilesDir(null) ?: filesDir, "Vault")

    /** Shared storage, where a chosen vault can be read by other apps. Needs All files access. */
    fun sharedStorage(): File = Environment.getExternalStorageDirectory()
}

/** Preferences, with the desktop app's defaults. */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val autosaveState = MutableStateFlow(preferences.getBoolean("autosave", true))
    private val expandedState = MutableStateFlow(preferences.getBoolean("folders-start-expanded", true))
    private val externalState = MutableStateFlow(preferences.getBoolean("sync-external-deletions", false))
    private val textSizeState = MutableStateFlow(preferences.getInt("text-size", DEFAULT_TEXT_SIZE))

    val autosaveFlow: StateFlow<Boolean> = autosaveState.asStateFlow()
    val foldersStartExpandedFlow: StateFlow<Boolean> = expandedState.asStateFlow()
    val syncExternalDeletionsFlow: StateFlow<Boolean> = externalState.asStateFlow()
    val textSizeFlow: StateFlow<Int> = textSizeState.asStateFlow()

    var autosave: Boolean
        get() = autosaveState.value
        set(value) { autosaveState.value = value; preferences.edit().putBoolean("autosave", value).apply() }
    var foldersStartExpanded: Boolean
        get() = expandedState.value
        set(value) { expandedState.value = value; preferences.edit().putBoolean("folders-start-expanded", value).apply() }
    var syncExternalDeletions: Boolean
        get() = externalState.value
        set(value) { externalState.value = value; preferences.edit().putBoolean("sync-external-deletions", value).apply() }
    /** Editor text size in sp, 8 to 48 like the desktop's zoom. */
    var textSize: Int
        get() = textSizeState.value
        set(value) { val size = value.coerceIn(8, 48); textSizeState.value = size; preferences.edit().putInt("text-size", size).apply() }
    /** An empty value uses the app's own Vault folder. */
    var vaultDirectory: String
        get() = preferences.getString("vault-directory", "") ?: ""
        set(value) { preferences.edit().putString("vault-directory", value).commit() }

    companion object {
        const val DEFAULT_TEXT_SIZE = 16
    }
}
