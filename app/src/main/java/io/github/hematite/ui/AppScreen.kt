package io.github.hematite.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hematite.SyncKind
import java.io.File

fun openLink(context: Context, uri: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { }
}

@Composable
fun AppScreen(viewModel: NotesViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val searchFocus = remember { FocusRequester() }

    BackHandler(viewModel.screen != Screen.Notes) {
        when (viewModel.screen) {
            Screen.Editor -> viewModel.close()
            Screen.Trash -> viewModel.screen = Screen.Settings
            else -> viewModel.screen = Screen.Notes
        }
    }
    BackHandler(viewModel.screen == Screen.Notes && viewModel.query.isNotEmpty()) { viewModel.changeQuery("") }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.N -> { viewModel.requestNew(viewModel.root, folder = false); true }
            Key.F -> { viewModel.confirm { viewModel.screen = Screen.Notes }; runCatching { searchFocus.requestFocus() }; true }
            else -> false
        }
    }) {
        when (viewModel.screen) {
            Screen.Notes, Screen.Editor -> TwoPane(viewModel, snackbar, searchFocus)
            Screen.Settings -> SettingsScreen(viewModel, snackbar)
            Screen.Trash -> TrashScreen(viewModel, snackbar)
        }
    }
    Prompts(viewModel)
}

/** The notes list, and the open note beside it on wide screens. */
@Composable
private fun TwoPane(viewModel: NotesViewModel, snackbar: SnackbarHostState, searchFocus: FocusRequester) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(340.dp)) { NotesScreen(viewModel, snackbar, searchFocus) }
                androidx.compose.material3.VerticalDivider()
                Box(Modifier.weight(1f)) {
                    if (viewModel.document != null) EditorScreen(viewModel, snackbar, showBack = false)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Choose a note from the sidebar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (viewModel.document != null) NotesDrawer(viewModel, snackbar, searchFocus)
        else NotesScreen(viewModel, snackbar, searchFocus)
    }
}

/** The open note, with the notes list in a drawer that opens with a swipe right, like Obsidian. */
@Composable
private fun NotesDrawer(viewModel: NotesViewModel, snackbar: SnackbarHostState, searchFocus: FocusRequester) {
    val notes = viewModel.screen == Screen.Notes
    val drawer = rememberDrawerState(if (notes) DrawerValue.Open else DrawerValue.Closed)
    // The Notes screen is the open drawer, so opening a note or Ctrl+F slides it.
    LaunchedEffect(notes) { if (notes) drawer.open() else drawer.close() }
    LaunchedEffect(drawer) {
        snapshotFlow { drawer.currentValue }.collect {
            if (viewModel.screen == Screen.Notes || viewModel.screen == Screen.Editor)
                viewModel.screen = if (it == DrawerValue.Open) Screen.Notes else Screen.Editor
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawer, Modifier.fillMaxWidth(0.85f), windowInsets = WindowInsets(0)) {
                // Its own snackbar host, so messages don't also appear under the scrim.
                NotesScreen(viewModel, remember { SnackbarHostState() }, searchFocus)
            }
        },
    ) { EditorScreen(viewModel, snackbar, showBack = true) }
}

@Composable
fun SyncButton(viewModel: NotesViewModel) {
    val status by viewModel.sync.status.collectAsStateWithLifecycle()
    IconButton(onClick = { viewModel.prompt = Prompt.SyncDetails }) {
        when (status.kind) {
            SyncKind.Busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            SyncKind.Offline -> Icon(Icons.Outlined.CloudOff, status.detail, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            SyncKind.Synced -> Icon(Icons.Outlined.CloudDone, status.detail, tint = MaterialTheme.colorScheme.primary)
            SyncKind.Waiting -> Icon(Icons.Outlined.Schedule, status.detail, tint = MaterialTheme.colorScheme.tertiary)
            SyncKind.Problem -> Icon(Icons.Outlined.ErrorOutline, status.detail, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** A name field with the given part selected, so typing replaces it. */
@Composable
private fun NameField(initial: String, selection: TextRange, label: String, error: String?, onDone: (String) -> Unit, onChange: () -> Unit): () -> String {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onChange() },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    return { value.text }
}

@Composable
private fun Prompts(viewModel: NotesViewModel) {
    val dismiss = { viewModel.prompt = null }
    when (val prompt = viewModel.prompt) {
        null -> Unit
        is Prompt.Error -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Could not complete the operation") },
            text = { Text(prompt.message) },
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
        )
        is Prompt.Unsaved -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Save your changes?") },
            text = { Text("This note has unsaved changes.") },
            confirmButton = { TextButton(onClick = { dismiss(); if (viewModel.save()) prompt.next() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = dismiss) { Text("Cancel") }
                    TextButton(onClick = { dismiss(); viewModel.discard(); prompt.next() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
        is Prompt.ConfirmTrash -> {
            val folder = prompt.file.isDirectory
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(if (folder) "Move folder to Trash?" else "Move note to Trash?") },
                text = {
                    Text(viewModel.relative(prompt.file) + "\n\n" +
                        (if (folder) "This includes all notes and files inside the folder. You can restore them from Trash in Settings."
                        else "You can restore it from Trash in Settings.") +
                        (if (prompt.unsaved) "\n\nThis note has unsaved edits. Moving it to Trash will discard those edits." else ""))
                },
                confirmButton = { TextButton(onClick = { viewModel.trash(prompt.file) }) { Text("Move to Trash", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            )
        }
        is Prompt.NewNote -> {
            var error by remember(prompt) { mutableStateOf<String?>(null) }
            lateinit var text: () -> String
            val create = { name: String -> error = viewModel.create(prompt.parent, name, prompt.folder) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(if (prompt.folder) "New folder" else "New note") },
                text = {
                    Column {
                        if (prompt.parent != viewModel.root) Text("In ${viewModel.relative(prompt.parent)}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        // The caret starts before ".md", so typing names the note.
                        text = NameField(if (prompt.folder) "" else ".md", TextRange(0), if (prompt.folder) "Folder name" else "Note name", error,
                            onDone = { create(it) }, onChange = { error = null })
                    }
                },
                confirmButton = { TextButton(onClick = { create(text()) }) { Text("Create") } },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            )
        }
        is Prompt.Rename -> {
            var error by remember(prompt) { mutableStateOf<String?>(null) }
            lateinit var text: () -> String
            val name = prompt.file.name
            val stem = if (prompt.file.isDirectory) name.length else name.substringBeforeLast('.', name).length
            val rename = { value: String -> error = viewModel.rename(prompt.file, value) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(if (prompt.file.isDirectory) "Rename folder" else "Rename note") },
                text = { text = NameField(name, TextRange(0, stem), "Name", error, onDone = { rename(it) }, onChange = { error = null }) },
                confirmButton = { TextButton(onClick = { rename(text()) }) { Text("Rename") } },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            )
        }
        is Prompt.Move -> {
            val parent = prompt.file.parentFile
            val folders = viewModel.folders().filter { it != parent }
            var chosen by remember(prompt) { mutableStateOf<File?>(null) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Move ${prompt.file.nameWithoutExtension} to…") },
                text = {
                    if (folders.isEmpty()) Text("Create a folder first.")
                    else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(folders, key = { it.path }) { folder ->
                            Row(Modifier.fillMaxWidth().clickable { chosen = folder }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = chosen == folder, onClick = { chosen = folder })
                                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(if (folder == viewModel.root) "Vault (top level)" else viewModel.relative(folder))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(enabled = chosen != null, onClick = { chosen?.let { viewModel.move(prompt.file, it) } }) { Text("Move") } },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            )
        }
        Prompt.SyncDetails -> {
            val status by viewModel.sync.status.collectAsStateWithLifecycle()
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Vault sync") },
                text = { Column { Text(status.detail); HorizontalDivider(Modifier.padding(top = 16.dp)) } },
                confirmButton = {
                    if (viewModel.sync.connected) TextButton(onClick = { dismiss(); viewModel.sync.request() }) { Text("Sync now") }
                    else TextButton(onClick = { dismiss(); viewModel.confirm { viewModel.screen = Screen.Settings } }) { Text("Set up sync") }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
            )
        }
    }
}
