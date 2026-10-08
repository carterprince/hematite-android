package io.github.hematite.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hematite.App
import io.github.hematite.SyncController
import io.github.hematite.SyncHost
import io.github.hematite.core.Entry
import io.github.hematite.core.Formatting
import io.github.hematite.core.Hit
import io.github.hematite.core.IndexedNote
import io.github.hematite.core.Lists
import io.github.hematite.core.Search
import io.github.hematite.core.Trashed
import io.github.hematite.core.Vault
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open note: its file and the text it had on disk when opened or last saved. */
data class Document(val file: File, val original: String)

enum class Screen { Notes, Editor, Settings, Trash }

/** A question waiting for the user before an action runs. */
sealed interface Prompt {
    /** The open note has unsaved edits; `next` runs after saving or discarding them. */
    class Unsaved(val next: () -> Unit) : Prompt
    class ConfirmTrash(val file: File, val unsaved: Boolean) : Prompt
    class NewNote(val parent: File, val folder: Boolean) : Prompt
    class Rename(val file: File) : Prompt
    class Move(val file: File) : Prompt
    class Error(val message: String) : Prompt
    object SyncDetails : Prompt
}

class NotesViewModel(application: Application) : AndroidViewModel(application), SyncHost {
    val app = application as App
    val root: File = app.vaultDirectory()
    val sync = SyncController(app, root, this, viewModelScope)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    var screen by mutableStateOf(Screen.Notes)
    var prompt by mutableStateOf<Prompt?>(null)

    // Sidebar

    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var expanded by mutableStateOf<Set<File>>(emptySet()); private set
    private val seenFolders = HashSet<File>()
    var loaded by mutableStateOf(false); private set
    private var populating: Job? = null

    // Search

    var query by mutableStateOf(""); private set
    var hits by mutableStateOf<List<Hit>>(emptyList()); private set
    var indexing by mutableStateOf(true); private set
    var unreadable by mutableStateOf(0); private set
    private var index: List<IndexedNote> = emptyList()
    private var searchJob: Job? = null

    // Editor

    var document by mutableStateOf<Document?>(null); private set
    var editor by mutableStateOf(TextFieldValue("")); private set
    /** Whether the editor should take focus when it appears, as for a new note. */
    var focusEditor by mutableStateOf(false)
    /** An offset to scroll into view, set when a note opens from search. */
    var reveal by mutableStateOf<Int?>(null)
    val dirty get() = document?.let { editor.text != it.original } ?: false
    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastEdit = 0L
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var autosaveJob: Job? = null

    // Trash

    var trashed by mutableStateOf<List<Trashed>>(emptyList()); private set

    init {
        populate()
        sync.restore()
    }

    fun error(message: String) { prompt = Prompt.Error(message) }
    override fun message(text: String) { messages.tryEmit(text) }

    /** Reads the vault folder again, then reindexes its notes for search. */
    fun populate() {
        populating?.cancel()
        populating = viewModelScope.launch {
            val missing = !root.isDirectory
            if (missing) sync.vaultMissing()
            val result = withContext(Dispatchers.IO) { runCatching { root.mkdirs(); Vault.entries(root) } }
            val list = result.getOrElse { error("Could not read vault ${root.path}: ${it.message}"); return@launch }
            val expand = app.settings.foldersStartExpanded
            val next = expanded.filter { it.isDirectory }.toMutableSet()
            for (entry in list.filter { it.directory }) if (seenFolders.add(entry.file) && expand) next += entry.file
            expanded = next
            entries = list
            loaded = true
            indexing = true
            updateSearch()
            val (notes, failures) = withContext(Dispatchers.IO) { Search.index(list, root) }
            index = notes
            unreadable = failures
            indexing = false
            updateSearch()
        }
    }

    fun toggleFolder(folder: File) { expanded = if (folder in expanded) expanded - folder else expanded + folder }

    /** Whether every folder above `entry` is expanded. */
    fun visible(entry: Entry): Boolean {
        var parent = entry.file.parentFile
        while (parent != null && parent != root) {
            if (parent !in expanded) return false
            parent = parent.parentFile
        }
        return true
    }

    fun changeQuery(text: String) {
        query = text
        updateSearch()
    }

    private fun updateSearch() {
        searchJob?.cancel()
        if (query.isBlank()) { hits = emptyList(); return }
        val notes = index
        val current = document?.let { it.file to editor.text }
        val text = query
        searchJob = viewModelScope.launch {
            hits = withContext(Dispatchers.Default) { Search.find(notes, text, current) }
        }
    }

    // Opening and saving

    /** Runs `next` once the open note's edits are saved or discarded. */
    fun confirm(next: () -> Unit) {
        if (!dirty) return next()
        if (app.settings.autosave) { if (save()) next(); return }
        prompt = Prompt.Unsaved(next)
    }

    fun open(file: File, revealQuery: String? = null) {
        if (document?.file == file) {
            screen = Screen.Editor
            revealQuery?.let { query -> matchOffset(editor.text, query)?.let { reveal = it.first; editor = editor.copy(selection = TextRange(it.first, it.last + 1)) } }
            return
        }
        confirm {
            val text = try { file.readText() } catch (error: Exception) { error("${file.path}: ${error.message}"); return@confirm }
            document = Document(file, text)
            val match = revealQuery?.let { matchOffset(text, it) }
            editor = TextFieldValue(text, match?.let { TextRange(it.first, it.last + 1) } ?: TextRange(0))
            reveal = match?.first
            clearHistory()
            screen = Screen.Editor
        }
    }

    /** The first match of the whole query, or else of its earliest word. */
    private fun matchOffset(text: String, query: String): IntRange? {
        val whole = query.trim()
        text.indexOf(whole, ignoreCase = true).takeIf { whole.isNotEmpty() && it >= 0 }?.let { return it until it + whole.length }
        return query.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .mapNotNull { word -> text.indexOf(word, ignoreCase = true).takeIf { it >= 0 }?.let { it until it + word.length } }
            .minByOrNull { it.first }
    }

    fun close() {
        confirm {
            document = null
            editor = TextFieldValue("")
            clearHistory()
            screen = Screen.Notes
        }
    }

    fun save(): Boolean {
        val current = document ?: return true
        val text = editor.text
        if (text == current.original) return true
        try { Vault.save(current.file, current.original, text) } catch (error: Exception) {
            error(error.message ?: "Could not save ${current.file.name}")
            return false
        }
        document = current.copy(original = text)
        populate()
        sync.request()
        return true
    }

    fun discard() {
        val current = document ?: return
        edit(TextFieldValue(current.original, TextRange(editor.selection.start.coerceAtMost(current.original.length))), checkpoint = true)
    }

    /** Saves when leaving the app, if autosave is on. */
    fun flush() { if (dirty && app.settings.autosave) save() }

    // Editing

    private fun clearHistory() {
        undo.clear(); redo.clear()
        canUndo = false; canRedo = false
        autosaveJob?.cancel()
    }

    /** Applies an edit, recording undo history; typing within a second groups into one step. */
    fun edit(value: TextFieldValue, checkpoint: Boolean = false) {
        val previous = editor
        if (value.text != previous.text) {
            val now = System.currentTimeMillis()
            if (checkpoint || now - lastEdit > 1000 || undo.isEmpty()) {
                undo.addLast(previous)
                if (undo.size > 500) undo.removeFirst()
            }
            lastEdit = if (checkpoint) 0 else now
            redo.clear()
            canUndo = true; canRedo = false
            editor = value
            scheduleAutosave()
            if (query.isNotBlank()) updateSearch()
        } else editor = value
    }

    /** Handles typed text, continuing lists when Enter is pressed in one. */
    fun type(value: TextFieldValue) {
        val previous = editor
        val cursor = previous.selection.start
        if (previous.selection.collapsed && value.text.length == previous.text.length + 1 && value.selection.collapsed &&
            value.selection.start == cursor + 1 && value.text[cursor] == '\n' &&
            value.text.regionMatches(0, previous.text, 0, cursor) && value.text.regionMatches(cursor + 1, previous.text, cursor, previous.text.length - cursor)) {
            Lists.enter(previous.text, cursor)?.let { result ->
                edit(TextFieldValue(result.text, TextRange(result.cursor)), checkpoint = true)
                return
            }
        }
        edit(value)
    }

    fun tab(outdent: Boolean): Boolean {
        if (!editor.selection.collapsed) return false
        val result = Lists.tab(editor.text, editor.selection.start, outdent) ?: return false
        edit(TextFieldValue(result.text, TextRange(result.cursor)), checkpoint = true)
        return true
    }

    fun toggleMarker(marker: String) {
        val result = Formatting.toggle(editor.text, editor.selection.start, editor.selection.end, marker)
        edit(TextFieldValue(result.text, TextRange(result.start, result.end)), checkpoint = true)
    }

    fun toggleLinePrefix(task: Boolean) {
        val result = Formatting.toggleLinePrefix(editor.text, editor.selection.start, editor.selection.end, task)
        edit(TextFieldValue(result.text, TextRange(result.start, result.end)), checkpoint = true)
    }

    /** Checks or unchecks the task whose marker starts at `offset`, without moving the cursor. */
    fun toggleTask(offset: Int, checked: Boolean) {
        val text = editor.text
        if (offset + 3 >= text.length) return
        val next = text.substring(0, offset + 3) + (if (checked) " " else "X") + text.substring(offset + 4)
        edit(editor.copy(text = next), checkpoint = true)
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(editor)
        editor = previous
        lastEdit = 0
        canUndo = undo.isNotEmpty(); canRedo = true
        scheduleAutosave()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(editor)
        editor = next
        lastEdit = 0
        canUndo = true; canRedo = redo.isNotEmpty()
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        if (!app.settings.autosave) return
        val file = document?.file ?: return
        autosaveJob = viewModelScope.launch {
            delay(1000)
            if (app.settings.autosave && document?.file == file && dirty) save()
        }
    }

    /** Saves a picked image in the vault and links it from the open note. */
    fun insertImage(uri: Uri) {
        val note = document?.file ?: return
        val source = editor.text
        val selection = editor.selection
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val (bytes, extension) = readImage(uri)
                    Vault.saveImage(root, note, bytes, extension)
                }
            }
            val (file, markdown) = saved.getOrElse { error("Could not save the image: ${it.message}"); return@launch }
            // Recheck after disk I/O, before using the captured selection.
            if (editor.text != source || document?.file != note) {
                file.delete()
                message("Note changed while saving the image. Insert it again.")
                return@launch
            }
            val start = minOf(selection.start, selection.end)
            val end = maxOf(selection.start, selection.end)
            val before = if (start == 0 || source[start - 1] == '\n') "" else "\n"
            val after = if (end == source.length || source[end] == '\n') "" else "\n"
            val inserted = before + markdown + after
            edit(TextFieldValue(source.substring(0, start) + inserted + source.substring(end), TextRange(start + inserted.length)), checkpoint = true)
            sync.request()
        }
    }

    /** The image's bytes in a format the desktop can show: PNG, JPEG, GIF, and WebP are kept, others become JPEG. */
    private fun readImage(uri: Uri): Pair<ByteArray, String> {
        val resolver = app.contentResolver
        val type = resolver.getType(uri) ?: ""
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Could not read the image")
        val kept = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp")
        kept[type]?.let { return bytes to it }
        val bitmap = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes))
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("Unsupported image")
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        return output.toByteArray() to "jpg"
    }

    // Notes and folders

    fun create(parent: File, name: String, folder: Boolean): String? {
        val created = try {
            Vault.validateParent(root, parent)
            if (folder) Vault.createFolder(parent, name) else Vault.createNote(parent, name)
        } catch (error: Exception) { return error.message ?: "Could not create $name" }
        prompt = null
        changeQuery("")
        if (parent != root) expanded = expanded + parent
        if (folder) expanded = expanded + created
        populate()
        if (!folder) {
            document = Document(created, "")
            editor = TextFieldValue("")
            clearHistory()
            focusEditor = true
            screen = Screen.Editor
        }
        sync.request()
        return null
    }

    fun requestNew(parent: File, folder: Boolean) = confirm { prompt = Prompt.NewNote(parent, folder) }

    fun rename(file: File, name: String): String? {
        val destination = try { Vault.rename(root, file, name) } catch (error: Exception) { return error.message ?: "Could not rename ${file.name}" }
        prompt = null
        if (destination != file) {
            sync.recordDeletion(file)
            fun moved(path: File) = if (path == file) destination else File(destination, path.relativeTo(file).path)
            document?.let { if (it.file.startsWith(file)) document = it.copy(file = moved(it.file)) }
            expanded = expanded.map { if (it.startsWith(file)) moved(it) else it }.toSet()
            populate()
            sync.request()
        }
        return null
    }

    fun move(file: File, folder: File) {
        prompt = null
        val current = document?.takeIf { it.file == file }
        val moved = try {
            Vault.moveNote(root, file, folder, current?.let { editor.text }, current?.original)
        } catch (error: Exception) { error(error.message ?: "Could not move ${file.name}"); return } ?: return
        if (current != null) {
            document = Document(moved.destination, moved.saved)
            moved.editor?.let { text -> editor = TextFieldValue(text, TextRange(editor.selection.start.coerceAtMost(text.length))) }
        }
        if (folder != root) expanded = expanded + folder
        populate()
        sync.recordDeletion(file)
        sync.request()
        message("Moved ${file.name} to ${if (folder == root) "the vault" else folder.name}")
    }

    fun requestTrash(file: File) {
        val unsaved = document?.file?.let { it == file || it.startsWith(file) } == true && dirty
        prompt = Prompt.ConfirmTrash(file, unsaved)
    }

    fun trash(file: File) {
        prompt = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Vault.validateEntry(root, file); Vault.trash(root, file) } }
            result.exceptionOrNull()?.let {
                error("Could not move ${file.name} to Trash: ${it.message}\n\nYour note and editor contents have been kept.")
                return@launch
            }
            sync.recordDeletion(file)
            if (document?.file?.let { it == file || it.startsWith(file) } == true) {
                document = null
                editor = TextFieldValue("")
                clearHistory()
                if (screen == Screen.Editor) screen = Screen.Notes
            }
            populate()
            sync.request()
            message("${file.name} moved to Trash")
        }
    }

    /** Folders a note can move to: the vault root and every folder. */
    fun folders(): List<File> = listOf(root) + entries.filter { it.directory }.map { it.file }

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    // Trash screen

    fun loadTrash() { viewModelScope.launch { trashed = withContext(Dispatchers.IO) { Vault.trashed(root) } } }

    fun restore(item: Trashed) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Vault.restore(root, item) } }
            result.exceptionOrNull()?.let { error(it.message ?: "Could not restore ${item.original}") }
            loadTrash()
            populate()
            sync.request()
        }
    }

    fun deleteForever(item: Trashed?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (item == null) Vault.emptyTrash(root) else Vault.delete(root, item) }
            loadTrash()
        }
    }

    // SyncHost

    override fun openNote(): Pair<File, Boolean>? = document?.let { it.file to dirty }

    override fun filesChanged(current: Boolean) {
        populate()
        if (!current) return
        val open = document ?: return
        if (dirty) return
        if (open.file.exists()) {
            val text = runCatching { open.file.readText() }.getOrNull() ?: return
            document = Document(open.file, text)
            val cursor = editor.selection.start.coerceAtMost(text.length)
            editor = TextFieldValue(text, TextRange(cursor))
            clearHistory()
        } else {
            document = null
            editor = TextFieldValue("")
            clearHistory()
            if (screen == Screen.Editor) screen = Screen.Notes
            message("This note was deleted on another device.")
        }
    }
}
