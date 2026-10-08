package io.github.hematite.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID
import kotlinx.serialization.Serializable

/** What a file looked like when it was last synced: its content hash and the server's ETag. */
@Serializable
data class Record(val hash: String, val etag: String)

/** Sync history for one vault, in the desktop app's format. */
@Serializable
data class SyncState(val deletions: Set<String> = emptySet(), val files: Map<String, Record> = emptyMap())

/**
 * A local change planned by sync: write `bytes` to `path` (or remove it when null), but only if the
 * file still has the `expected` hash, so a newer local save always wins.
 */
class Change(val modified: Long?, val path: String, val expected: String?, val bytes: ByteArray?, val record: Record?)

class Outcome(var deletions: TreeSet<String>, val files: TreeMap<String, Record>) {
    val changes = mutableListOf<Change>()
    val conflicts = mutableListOf<String>()
    val state get() = SyncState(deletions.toSortedSet(), files.toSortedMap())

    /** Hands a download to `progress`, which applies it immediately when it can; otherwise it waits in `changes`. */
    fun queue(change: Change, progress: (Change) -> Boolean) {
        if (progress(change)) {
            if (change.record != null) files[change.path] = change.record else files.remove(change.path)
        } else changes += change
    }
}

object SyncEngine {
    /** Every visible regular file in the vault, by relative path. */
    fun snapshot(root: File): TreeMap<String, ByteArray> {
        val files = TreeMap<String, ByteArray>()
        fun visit(directory: File) {
            if (!directory.exists()) return
            val children = directory.listFiles() ?: throw IOException("Could not read ${directory.path}")
            for (child in children) {
                if (child.name.startsWith('.')) continue
                val path = child.toPath()
                if (Files.isSymbolicLink(path)) continue
                if (Files.isDirectory(path)) visit(child)
                if (Files.isRegularFile(path)) {
                    val relative = child.relativeTo(root).invariantSeparatorsPath
                    if (!Dav.safePath(relative)) throw SyncException("Unsafe local filename")
                    files[relative] = child.readBytes()
                }
            }
        }
        visit(root)
        return files
    }

    private fun note(path: String) = path.substringAfterLast('/').substringAfterLast('.', "") in setOf("md", "markdown", "txt", "text")

    internal fun conflictPath(path: String, bytes: ByteArray): String {
        val parent = if ('/' in path) path.substringBeforeLast('/') + "/" else ""
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val (stem, suffix) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        return "$parent$stem.conflict-remote-${Dav.hash(bytes).take(12)}$suffix"
    }

    private fun preserveRemote(dav: Dav, path: String, bytes: ByteArray, local: Map<String, ByteArray>, outcome: Outcome, progress: (Change) -> Boolean) {
        var copy = conflictPath(path, bytes)
        // Never reuse a conflicting local filename for different content.
        if (local[copy]?.contentEquals(bytes) == false) copy = "$copy-${UUID.randomUUID()}"
        val response = dav.request("GET", copy)
        val etag = if (response.status == 404) dav.put(copy, bytes, null)
        else if (response.status == 200 && response.body.contentEquals(bytes)) response.etag ?: throw SyncException("Missing conflict-copy ETag")
        else throw SyncException("Could not safely preserve the remote conflict for $path")
        outcome.queue(Change(null, copy, local[copy]?.let { Dav.hash(it) }, bytes, Record(Dav.hash(bytes), etag)), progress)
        outcome.conflicts += copy
    }

    private fun deleted(deletions: Set<String>, path: String) = deletions.any { path == it || path.startsWith("$it/") }

    /**
     * One sync pass. Files removed from the vault are deleted remotely only when they were trashed
     * inside the app (`state.deletions`) or `syncExternalDeletions` is set, and never when the whole
     * vault is missing. Conflicting edits keep both versions.
     */
    fun cycle(dav: Dav, local: Map<String, ByteArray>, state: SyncState, syncExternalDeletions: Boolean, missingVault: Boolean,
              progress: (Change) -> Boolean = { false }): Outcome {
        val start = if (missingVault) SyncState() else state
        val remote = dav.list() // Never infer deletion from a failed/partial listing.
        val outcome = Outcome(TreeSet(start.deletions), TreeMap(start.files))
        // Attachments before the notes that reference them.
        val paths = (local.keys + remote.keys + start.files.keys).toSortedSet()
            .sortedWith(compareBy<String>({ it !in local }, { note(it) }, { it }))
        for (path in paths) {
            if (!Dav.safePath(path)) throw SyncException("Invalid path in sync history")
            val bytes = local[path]
            val remoteFile = remote[path]
            val base = outcome.files[path]
            val localHash = bytes?.let { Dav.hash(it) }
            when {
                bytes != null && remoteFile != null -> {
                    val localChanged = base == null || base.hash != localHash
                    val remoteChanged = base == null || base.etag != remoteFile.etag
                    if (!localChanged && !remoteChanged) continue
                    if (remoteChanged) {
                        val (remoteBytes, etag) = dav.get(path)
                        val remoteHash = Dav.hash(remoteBytes)
                        if (remoteHash == localHash) {
                            outcome.files[path] = Record(remoteHash, etag)
                            continue
                        }
                        if (!localChanged) {
                            outcome.queue(Change(remoteFile.modified.takeIf { etag == remoteFile.etag }, path, localHash, remoteBytes,
                                Record(remoteHash, etag)), progress)
                            continue
                        }
                        // Both changed: preserve the remote version in BOTH vaults
                        // before publishing the local version at its original name.
                        preserveRemote(dav, path, remoteBytes, local, outcome, progress)
                        outcome.files[path] = Record(localHash!!, dav.put(path, bytes, etag))
                    } else {
                        outcome.files[path] = Record(localHash!!, dav.put(path, bytes, remoteFile.etag))
                    }
                }
                bytes != null && remoteFile == null && base != null && localHash == base.hash -> {
                    outcome.queue(Change(null, path, localHash, null, null), progress)
                }
                bytes != null && remoteFile == null -> {
                    if (base != null) outcome.conflicts += "$path (local edit kept after remote deletion)"
                    outcome.files[path] = Record(Dav.hash(bytes), dav.put(path, bytes, null))
                }
                bytes == null && remoteFile != null && base != null && base.etag == remoteFile.etag &&
                    (syncExternalDeletions || deleted(outcome.deletions, path)) -> {
                    dav.delete(path, base.etag)
                    outcome.files.remove(path)
                }
                bytes == null && remoteFile != null -> {
                    val (remoteBytes, etag) = dav.get(path)
                    if (base != null && (syncExternalDeletions || deleted(outcome.deletions, path))) {
                        outcome.conflicts += "$path (remote edit kept after local deletion)"
                    }
                    outcome.queue(Change(remoteFile.modified.takeIf { etag == remoteFile.etag }, path, null, remoteBytes,
                        Record(Dav.hash(remoteBytes), etag)), progress)
                }
                else -> outcome.files.remove(path)
            }
            outcome.deletions.remove(path)
        }
        outcome.deletions.clear()
        return outcome
    }

    /**
     * Applies a planned change unless the local file changed since it was planned. Removed files go
     * to `trash`. Returns whether the change was applied.
     */
    fun apply(root: File, change: Change, trash: (File) -> Unit): Boolean {
        if (!Dav.safePath(change.path)) throw SyncException("Unsafe sync destination")
        root.mkdirs()
        val file = File(root, change.path)
        // Reject symlinks in every component, including the target file.
        var ancestor = root
        for (part in change.path.split('/')) {
            ancestor = File(ancestor, part)
            if (Files.isSymbolicLink(ancestor.toPath())) throw SyncException("Sync destination contains a symbolic link")
        }
        val actual = if (file.exists()) Dav.hash(file.readBytes()) else null
        if (actual != change.expected) return false // A newer local save wins.
        if (change.bytes != null) {
            file.parentFile!!.mkdirs()
            writeAtomically(file, change.bytes)
            if (change.modified != null && !file.setLastModified(change.modified)) {
                throw SyncException("Could not preserve modification time for ${change.path}")
            }
        } else if (actual != null) {
            try { trash(file) } catch (error: Exception) {
                throw SyncException("Could not safely trash remotely deleted note: ${error.message}")
            }
        }
        return true
    }

    /** Replaces `file` through a temporary file, so a failed write never truncates the original. */
    fun writeAtomically(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }
}
