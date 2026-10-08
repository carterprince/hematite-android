package io.github.hematite.core

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A note or folder in the sidebar, `depth` folders below the vault root. */
data class Entry(val file: File, val directory: Boolean, val depth: Int)

/** The result of moving a note: where it went and its text with links rebased. */
data class Moved(val destination: File, val saved: String, val editor: String?)

/** A file in the vault's trash folder and where it came from. */
data class Trashed(val file: File, val original: String, val time: String)

class VaultException(message: String) : IOException(message)

/** Notes are plain files in a folder, as on the desktop. */
object Vault {
    val NOTE_EXTENSIONS = setOf("md", "markdown", "txt", "text")
    /** Hidden, so sync and the sidebar skip it, like Obsidian's vault trash. */
    const val TRASH = ".trash"
    private const val ORIGIN = ".original-path"

    fun isNote(file: File) = file.extension.lowercase() in NOTE_EXTENSIONS

    /** Folders and notes, newest first within each folder. */
    fun entries(root: File): List<Entry> {
        val entries = mutableListOf<Entry>()
        fun visit(directory: File, depth: Int) {
            val children = directory.listFiles() ?: throw IOException("Could not read ${directory.path}")
            val sorted = children.sortedWith(compareByDescending<File> { it.lastModified() }
                .thenBy { it.name.lowercase() }.thenBy { it.name })
            for (child in sorted) {
                if (child.name.startsWith('.')) continue
                val path = child.toPath()
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries += Entry(child, true, depth)
                    visit(child, depth + 1)
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isNote(child)) {
                    entries += Entry(child, false, depth)
                }
            }
        }
        visit(root, 0)
        return entries
    }

    /** Saves `text`, refusing to overwrite a note changed by another app since it was opened. */
    fun save(file: File, original: String, text: String) {
        if (file.readText() != original) {
            throw VaultException("This note changed on disk. Your edits are still in the editor; copy them before reopening the note.")
        }
        SyncEngine.writeAtomically(file, text.toByteArray())
    }

    fun validateEntry(root: File, file: File) {
        val canonicalRoot = root.canonicalFile
        val target = file.canonicalFile
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw VaultException("${file.name} no longer exists.")
        if (target == canonicalRoot || !target.startsWith(canonicalRoot) || Files.isSymbolicLink(path) ||
            !(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            throw VaultException("Choose a note or folder inside this vault.")
        }
    }

    fun validateParent(root: File, parent: File) {
        val relative = parent.relativeToOrNull(root)?.invariantSeparatorsPath
        if (relative == null || relative.startsWith("..")) throw VaultException("Choose a folder inside this vault")
        var current = root
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            if (part == "." || part == "..") throw VaultException("Invalid folder path")
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) throw VaultException("Cannot create notes in a symbolic link")
        }
        if (!parent.isDirectory) throw VaultException("The containing folder no longer exists")
    }

    private fun invalidName(name: String) = name.startsWith('.') || name.any { it in "/\\\n\r" }

    /** Creates an empty note named `name` (`.md` is added when missing), never replacing an existing file. */
    fun createNote(parent: File, name: String): File {
        val trimmed = name.trim()
        val stem = trimmed.removeSuffix(".md")
        if (stem.isBlank() || invalidName(trimmed)) throw VaultException("Enter a filename without folders, such as Ideas.md")
        val file = File(parent, if (trimmed.endsWith(".md")) trimmed else "$trimmed.md")
        try { Files.createFile(file.toPath()) } catch (_: FileAlreadyExistsException) {
            throw VaultException("An item with this name already exists.")
        }
        return file
    }

    fun createFolder(parent: File, name: String): File {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || invalidName(trimmed)) throw VaultException("Enter a folder name without slashes")
        val folder = File(parent, trimmed)
        try { Files.createDirectory(folder.toPath()) } catch (_: FileAlreadyExistsException) {
            throw VaultException("An item with this name already exists.")
        }
        return folder
    }

    /** Renames a note or folder in place. Returns its new location. */
    fun rename(root: File, file: File, name: String): File {
        val trimmed = name.trim()
        val folder = file.isDirectory
        if (trimmed.isEmpty() || invalidName(trimmed) || (!folder && File(trimmed).extension.lowercase() !in NOTE_EXTENSIONS)) {
            throw VaultException(if (folder) "Enter a folder name without slashes" else "Enter a note filename ending in .md, .markdown, .txt, or .text")
        }
        val destination = File(file.parentFile, trimmed)
        if (destination == file) return file
        validateEntry(root, file)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            // Changing only the letter case is a rename on case-insensitive storage.
            if (!destination.name.equals(file.name, ignoreCase = true)) throw VaultException("An item with this name already exists.")
        }
        Files.move(file.toPath(), destination.toPath())
        return destination
    }

    /**
     * Moves a note into `folder`, rebasing its relative links on disk. `editor` is the open note's
     * unsaved text and `original` the text it was opened with, when the note is open.
     */
    fun moveNote(root: File, file: File, folder: File, editor: String?, original: String?): Moved? {
        validateEntry(root, file)
        if (!file.isFile || !isNote(file)) throw VaultException("Only notes can be moved.")
        if (folder != root) validateEntry(root, folder)
        if (!folder.isDirectory) throw VaultException("Choose a folder.")
        val destination = File(folder, file.name)
        if (destination == file) return null
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) throw VaultException("A note with this name already exists in that folder.")
        val text = file.readText()
        if (original != null && original != text) throw VaultException("This note changed on disk. Save or preserve your edits before moving it.")
        val parent = file.parentFile!!
        val disk = Moves.rebase(text, parent, folder)
        val rebasedEditor = editor?.let { Moves.rebase(it, parent, folder) }
        Files.move(file.toPath(), destination.toPath())
        if (disk != text) {
            try { save(destination, text, disk) } catch (error: Exception) {
                val rollback = runCatching { Files.move(destination.toPath(), file.toPath()) }
                throw VaultException(if (rollback.isSuccess) "Could not update relative links; the move was undone: ${error.message}"
                    else "The note is at ${destination.path}. Updating links failed: ${error.message}. Moving it back failed: ${rollback.exceptionOrNull()?.message}")
            }
        }
        return Moved(destination, disk, rebasedEditor)
    }

    /** Saves an image at the vault root and returns its file and the Markdown that shows it in `note`. */
    fun saveImage(root: File, note: File, bytes: ByteArray, extension: String): Pair<File, String> {
        val parent = note.parentFile!!.relativeToOrNull(root)?.invariantSeparatorsPath
        if (parent == null || parent.startsWith("..")) throw VaultException("The note must be inside the vault")
        val depth = parent.split('/').count { it.isNotEmpty() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        for (suffix in 0 until 10_000) {
            val name = if (suffix == 0) "paste-$stamp.$extension" else "paste-$stamp-$suffix.$extension"
            val file = File(root, name)
            try { Files.createFile(file.toPath()) } catch (_: FileAlreadyExistsException) { continue }
            try { file.writeBytes(bytes) } catch (error: IOException) { file.delete(); throw error }
            return file to "![](${"../".repeat(depth)}$name)"
        }
        throw VaultException("Could not choose a unique image filename")
    }

    /** Moves a note or folder into the vault's trash, remembering where it was so it can be restored. */
    fun trash(root: File, file: File) {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))
        var batch = File(File(root, TRASH), stamp)
        var index = 1
        while (batch.exists()) batch = File(File(root, TRASH), "$stamp (${index++})")
        val destination = File(batch, relative)
        destination.parentFile!!.mkdirs()
        File(batch, ORIGIN).writeText(relative)
        Files.move(file.toPath(), destination.toPath())
    }

    /** Trashed notes and folders, newest first. */
    fun trashed(root: File): List<Trashed> =
        File(root, TRASH).listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.name }.mapNotNull { batch ->
            val original = runCatching { File(batch, ORIGIN).readText() }.getOrNull()?.takeIf { Dav.safePath(it) } ?: return@mapNotNull null
            File(batch, original).takeIf { it.exists() }?.let { Trashed(it, original, batch.name.substringBefore(" (")) }
        }

    /** Moves a trashed item back to where it was. */
    fun restore(root: File, item: Trashed) {
        val destination = File(root, item.original)
        if (destination.exists()) throw VaultException("${item.original} already exists in the vault.")
        destination.parentFile!!.mkdirs()
        Files.move(item.file.toPath(), destination.toPath())
        File(root, TRASH).listFiles().orEmpty().filter { File(it, item.original) == item.file }.forEach { it.deleteRecursively() }
    }

    /** Permanently deletes one trashed item. */
    fun delete(root: File, item: Trashed) {
        File(root, TRASH).listFiles().orEmpty().filter { File(it, item.original) == item.file }.forEach { it.deleteRecursively() }
    }

    fun emptyTrash(root: File) { File(root, TRASH).deleteRecursively() }
}
