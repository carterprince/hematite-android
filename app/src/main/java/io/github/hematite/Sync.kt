package io.github.hematite

import io.github.hematite.core.Change
import io.github.hematite.core.Dav
import io.github.hematite.core.Outcome
import io.github.hematite.core.SyncEngine
import io.github.hematite.core.SyncException
import io.github.hematite.core.SyncState
import io.github.hematite.core.Vault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The connection settings saved for a vault, in the desktop app's format. The password is kept separately. */
@Serializable
data class Profile(val url: String, val username: String, @SerialName("lock_writes") val lockWrites: Boolean, val remember: Boolean = false)

enum class SyncKind { Offline, Busy, Synced, Waiting, Problem }

data class SyncStatus(val kind: SyncKind, val detail: String)

/** What sync needs from the editor. Called on the main thread. */
interface SyncHost {
    /** The open note and whether it has unsaved edits. */
    fun openNote(): Pair<File, Boolean>?
    /** Files changed on disk; `current` when the open note was among them. */
    fun filesChanged(current: Boolean)
    fun message(text: String)
}

/**
 * Syncs one vault with a WebDAV server, like the desktop app: local saves go to disk first,
 * downloads never replace unsaved edits, and conflicting edits keep both versions.
 * All methods run on the main thread; network and disk work runs in the background.
 */
class SyncController(private val app: App, private val root: File, private val host: SyncHost, private val scope: CoroutineScope) {
    private class Session(val dav: Dav, var state: SyncState)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val directory = File(app.filesDir, "sync/" + Dav.hash(root.path.toByteArray()))
    private val statusState = MutableStateFlow(SyncStatus(SyncKind.Offline, "WebDAV is not connected"))
    val status: StateFlow<SyncStatus> = statusState.asStateFlow()
    private var session: Session? = null
    private var running = false
    private var again = false
    private var generation = 0L
    private var freshCheckout = !root.isDirectory

    val connected get() = session != null

    /** The saved server address and username, to prefill the connection form. */
    fun savedProfile(): Profile? = runCatching { json.decodeFromString(Profile.serializer(), File(directory, "profile.json").readText()) }.getOrNull()

    private fun keyringKey(profile: Profile) = "vault-" + Dav.hash("${root.path}\n${profile.url}\n${profile.username}".toByteArray())

    private fun writeJson(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, ".write-${UUID.randomUUID()}")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) { temporary.delete(); throw SyncException("Could not save ${file.name}") }
    }

    private fun writeState(state: SyncState) = writeJson(File(directory, "state.json"), json.encodeToString(SyncState.serializer(), state))

    private fun loadState(): SyncState {
        val file = File(directory, "state.json")
        if (!file.exists()) return SyncState()
        return try { json.decodeFromString(SyncState.serializer(), file.readText()) } catch (_: Exception) {
            throw SyncException("Sync history is damaged. Disconnect and reconnect to reconcile safely.")
        }
    }

    private fun busy(message: String) { statusState.value = SyncStatus(SyncKind.Busy, message) }
    private fun status(kind: SyncKind, detail: String) { statusState.value = SyncStatus(kind, detail) }

    private fun pendingDeletion(state: SyncState, path: String) = state.deletions.any { path == it || path.startsWith("$it/") }

    /** Applies a download as soon as it arrives, so notes appear while a long sync is still running. */
    private fun receiveDownload(change: Change): Boolean {
        val session = session ?: return false
        if (pendingDeletion(session.state, change.path)) return false
        val file = File(root, change.path)
        val open = host.openNote()
        val current = open?.first == file
        if (current && open!!.second) return false
        val applied = try { SyncEngine.apply(root, change) { Vault.trash(root, it) } } catch (_: Exception) { false }
        if (!applied) return false
        // Persist each accepted download so a later network failure doesn't lose
        // its baseline and make it look like a new local upload on the retry.
        val files = session.state.files.toMutableMap()
        if (change.record != null) files[change.path] = change.record else files.remove(change.path)
        session.state = session.state.copy(files = files)
        try { writeState(session.state) } catch (error: Exception) { host.message(error.message ?: "Could not save sync history") }
        host.filesChanged(current)
        return true
    }

    /** Starts a sync pass, or queues another one if a pass is running. */
    fun request() {
        val session = session ?: return
        if (running) { again = true; return }
        running = true
        busy("Syncing vault…")
        val initialDeletions = session.state.deletions
        val syncExternal = app.settings.syncExternalDeletions
        val fresh = freshCheckout || !root.isDirectory
        val generation = generation
        val dav = session.dav
        val state = session.state
        scope.launch {
            val work = runCatching {
                withContext(Dispatchers.IO) {
                    val missingVault = fresh || !root.isDirectory
                    val local = SyncEngine.snapshot(root)
                    SyncEngine.cycle(dav, local, state, syncExternal, missingVault) { change ->
                        runBlocking(Dispatchers.Main) { generation == this@SyncController.generation && receiveDownload(change) }
                    }
                }
            }
            running = false
            if (generation != this@SyncController.generation) {
                if (again) { again = false; request() }
                return@launch
            }
            work.fold({ finish(it, initialDeletions) }, {
                status(SyncKind.Problem, "Sync failed. Local saves are safe and pending changes will retry.\n\n${it.message ?: it.javaClass.simpleName}")
            })
            if (again) { again = false; request() }
        }
    }

    private fun finish(outcome: Outcome, initialDeletions: Set<String>) {
        var waiting = false
        var changed = false
        var currentChanged = false
        val errors = mutableListOf<String>()
        freshCheckout = false
        val pendingDeletions = session?.state?.deletions.orEmpty() - initialDeletions
        outcome.deletions.addAll(pendingDeletions)
        val open = host.openNote()
        for (change in outcome.changes) {
            val file = File(root, change.path)
            if (pendingDeletions.any { change.path == it || change.path.startsWith("$it/") }) { waiting = true; continue }
            val current = open?.first == file
            if (current && open!!.second) { waiting = true; continue }
            try {
                if (SyncEngine.apply(root, change) { Vault.trash(root, it) }) {
                    changed = true
                    currentChanged = currentChanged || current
                    if (change.record != null) outcome.files[change.path] = change.record else outcome.files.remove(change.path)
                } else waiting = true
            } catch (error: Exception) {
                errors += error.message ?: error.javaClass.simpleName
            }
        }
        session?.state = outcome.state
        try { writeState(outcome.state) } catch (error: Exception) { errors += error.message ?: "Could not save sync history" }
        if (changed) host.filesChanged(currentChanged)
        when {
            errors.isNotEmpty() -> status(SyncKind.Problem, errors.joinToString("\n"))
            outcome.conflicts.isNotEmpty() -> {
                status(SyncKind.Problem, "Sync completed with conflicts. Both versions were preserved:\n${outcome.conflicts.joinToString("\n")}")
                host.message("Sync conflict: both versions have been preserved.")
            }
            waiting -> status(SyncKind.Waiting, "Remote changes are waiting. Save or discard your edits to continue syncing.")
            else -> status(SyncKind.Synced, "Vault synced")
        }
    }

    fun disconnect() {
        generation++
        val profile = savedProfile()
        session = null
        again = false
        profile?.let { app.secrets.remove(keyringKey(it)) }
        val file = File(directory, "profile.json")
        if (file.exists() && !file.delete()) {
            status(SyncKind.Problem, "Disconnected for this session, but could not remove saved connection settings.")
            return
        }
        status(SyncKind.Offline, "WebDAV disconnected. Local files have been kept.")
    }

    /** Connects to a server after checking it supports safe writes. Returns an error message on failure. */
    suspend fun connect(typedUrl: String, username: String, password: String, remember: Boolean): String? {
        val normalized = try { Dav.normalizeUrl(typedUrl) } catch (error: SyncException) { return error.message }
        if (username.any { it in ":\n\r" } || password.any { it in "\n\r" }) {
            return "Username and password cannot contain newlines; username cannot contain a colon."
        }
        val bareDomain = '/' !in typedUrl.trim() && "://" !in typedUrl
        generation++
        val generation = generation
        session = null
        busy("Connecting and checking safe-write support…")
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val dav = Dav(normalized, username, password)
                if (runCatching { dav.list() }.isFailure && bareDomain) dav.url += "vault/"
                dav.list()
                dav.probe()
                val profile = Profile(dav.url, dav.username, dav.lockWrites, remember)
                val previous = savedProfile()
                val state = if (previous != null && previous.url == profile.url && previous.username == profile.username) loadState() else SyncState()
                if (remember) app.secrets.put(keyringKey(profile), password)
                Triple(dav, state, profile)
            }
        }
        if (generation != this.generation) return null
        val (dav, state, profile) = result.getOrElse {
            val message = it.message ?: "Could not connect"
            status(SyncKind.Problem, message)
            return message
        }
        try {
            writeJson(File(directory, "profile.json"), json.encodeToString(Profile.serializer(), profile))
            writeState(state)
        } catch (error: Exception) {
            status(SyncKind.Problem, error.message ?: "Could not save connection settings")
            return error.message
        }
        session = Session(dav, state)
        request()
        return null
    }

    /** Reconnects with the saved profile and password when the app starts. */
    fun restore() {
        val profile = savedProfile() ?: return
        if (!profile.remember) {
            status(SyncKind.Offline, "Reconnect WebDAV to enter your password. Local changes have been kept.")
            return
        }
        val generation = generation
        busy("Connecting…")
        scope.launch {
            val restored = runCatching {
                withContext(Dispatchers.IO) {
                    val password = app.secrets.get(keyringKey(profile))
                    if (password.isNullOrEmpty()) throw SyncException("Reconnect your WebDAV server to unlock or enter its password.")
                    val dav = Dav(profile.url, profile.username, password, profile.lockWrites)
                    dav.list()
                    dav.probe()
                    Session(dav, loadState())
                }
            }
            if (generation != this@SyncController.generation) return@launch
            restored.fold({ session = it; request() }, { status(SyncKind.Problem, it.message ?: "Could not restore WebDAV connection") })
        }
    }

    /** The vault folder disappeared: download everything again rather than deleting it remotely. */
    fun vaultMissing() {
        freshCheckout = true
        generation++
        if (running) again = true
        // Reset on disk before the vault is recreated. Otherwise a restart after an
        // interrupted download could reuse deletion history.
        val state = SyncState()
        session?.state = state
        if (directory.exists()) {
            try { writeState(state) } catch (error: Exception) { host.message("Could not reset missing-vault sync history: ${error.message}") }
        }
    }

    /**
     * Remembers a note or folder removed inside the app, so sync deletes the server copy. Recorded
     * even while offline, and for the old paths of renames and moves.
     */
    fun recordDeletion(file: File) {
        val relative = file.relativeToOrNull(root)?.invariantSeparatorsPath?.takeUnless { it.startsWith("..") || it.isEmpty() } ?: return
        if (session == null && savedProfile() == null) return
        val state = session?.state ?: runCatching { loadState() }.getOrDefault(SyncState())
        val paths = state.files.keys.filter { it == relative || it.startsWith("$relative/") }
        val next = state.copy(deletions = state.deletions + paths + relative)
        try { writeState(next) } catch (error: Exception) { host.message("Could not remember deletion for sync: ${error.message}") }
        session?.state = next
    }
}
