package io.github.hematite.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hematite.App
import io.github.hematite.SyncKind
import java.io.File
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** The file path of a folder picked in the system's folder chooser, when it is on local storage. */
private fun pickedPath(uri: Uri): File? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val base = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
    return if (path.isEmpty()) base else File(base, path)
}

/** Reopens the app, so a new vault folder takes effect like reopening the desktop app. */
private fun restart(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(intent.component))
    Runtime.getRuntime().exit(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: NotesViewModel, snackbar: SnackbarHostState) {
    val app = viewModel.app
    val context = LocalContext.current
    val status by viewModel.sync.status.collectAsStateWithLifecycle()
    val autosave by app.settings.autosaveFlow.collectAsStateWithLifecycle()
    val expanded by app.settings.foldersStartExpandedFlow.collectAsStateWithLifecycle()
    val external by app.settings.syncExternalDeletionsFlow.collectAsStateWithLifecycle()
    val textSize by app.settings.textSizeFlow.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf(false) }
    var pendingVault by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmVault by rememberSaveable { mutableStateOf<String?>(null) }

    fun chooseVault(path: String) {
        if (path.isEmpty() || File(path).startsWith(app.defaultVault().parentFile!!) || Environment.isExternalStorageManager()) confirmVault = path
        else pendingVault = path
    }
    // Returning from the All files access settings page.
    LifecycleResumeEffect(pendingVault) {
        pendingVault?.let { if (Environment.isExternalStorageManager()) { confirmVault = it; pendingVault = null } }
        onPauseOrDispose { }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = pickedPath(uri)
        if (path == null) viewModel.error("Choose a folder on this device's storage.") else chooseVault(path.path)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { viewModel.screen = Screen.Notes }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Sync")
            ListItem(
                headlineContent = { Text(viewModel.sync.savedProfile()?.url?.takeIf { viewModel.sync.connected || status.kind != SyncKind.Offline } ?: "WebDAV server") },
                supportingContent = { Text(status.detail) },
                trailingContent = { if (status.kind == SyncKind.Busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { connecting = true }) { Text(if (viewModel.sync.savedProfile() != null) "Reconnect" else "Connect") }
                OutlinedButton(onClick = { viewModel.sync.request() }, enabled = viewModel.sync.connected) { Text("Sync now") }
                if (viewModel.sync.savedProfile() != null || viewModel.sync.connected) {
                    TextButton(onClick = { viewModel.sync.disconnect() }) { Text("Disconnect") }
                }
            }
            SwitchRow("Sync files deleted outside Hematite",
                "Remove remote copies when files are deleted in another app. A missing vault is always downloaded again.",
                external) { app.settings.syncExternalDeletions = it }

            Section("Editing")
            SwitchRow("Autosave", "Save notes automatically after you stop typing", autosave) { app.settings.autosave = it }
            ListItem(headlineContent = { Text("Text size") }, supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(value = textSize.toFloat(), onValueChange = { app.settings.textSize = it.toInt() }, valueRange = 8f..48f,
                        steps = 39, modifier = Modifier.weight(1f))
                    Text("  $textSize")
                }
            })

            Section("Notes list")
            SwitchRow("Folders start expanded", "Expand folders when opening the vault", expanded) { app.settings.foldersStartExpanded = it }

            Section("Vault")
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                headlineContent = { Text("Vault folder") },
                supportingContent = { Text(viewModel.root.path) },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Choose folder…") }
                if (app.settings.vaultDirectory.isNotEmpty()) TextButton(onClick = { chooseVault("") }) { Text("Use app storage") }
            }
            Text("Notes in app storage are removed if Hematite is uninstalled. A folder in shared storage, such as Documents, " +
                "can also be opened by other apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Delete, null) },
                headlineContent = { Text("Trash") },
                supportingContent = { Text("Restore notes and folders moved to Trash") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { viewModel.loadTrash(); viewModel.screen = Screen.Trash },
            )

            Section("About")
            ListItem(leadingContent = { Icon(Icons.AutoMirrored.Outlined.StickyNote2, null) },
                headlineContent = { Text("Hematite") }, supportingContent = { Text("A Markdown notes vault, synced with WebDAV") })
            Spacer(Modifier.size(32.dp))
        }
    }

    if (connecting) ConnectDialog(viewModel) { connecting = false }
    pendingVault?.let {
        AlertDialog(
            onDismissRequest = { pendingVault = null },
            title = { Text("Allow access to the folder") },
            text = { Text("To keep notes in $it, Hematite needs All files access. Turn it on for Hematite on the next screen, then come back.") },
            confirmButton = { TextButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { pendingVault = null }) { Text("Cancel") } },
        )
    }
    confirmVault?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmVault = null },
            title = { Text("Open this vault?") },
            text = { Text("Hematite will reopen with the notes in ${path.ifEmpty { app.defaultVault().path }}. " +
                "Notes in the current vault stay where they are.") },
            confirmButton = { TextButton(onClick = {
                viewModel.flush()
                app.settings.vaultDirectory = path
                restart(context)
            }) { Text("Reopen") } },
            dismissButton = { TextButton(onClick = { confirmVault = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectDialog(viewModel: NotesViewModel, onDismiss: () -> Unit) {
    val saved = remember { viewModel.sync.savedProfile() }
    var url by rememberSaveable { mutableStateOf(saved?.url ?: "") }
    var username by rememberSaveable { mutableStateOf(saved?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var remember by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect WebDAV server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync this vault with a WebDAV server. Local saves work offline. Different versions are preserved as conflict copies.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(url, { url = it; error = null }, label = { Text("Server URL") }, placeholder = { Text("https://example.org/vault/") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it; error = null }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password")
                    } },
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Remember password on this device")
                }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Connecting and checking safe-write support…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && url.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    error = viewModel.sync.connect(url, username, password, remember)
                    busy = false
                    if (error == null) { password = ""; onDismiss() }
                }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(viewModel: NotesViewModel, snackbar: SnackbarHostState) {
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadTrash() }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Trash") },
                navigationIcon = { IconButton(onClick = { viewModel.screen = Screen.Settings }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (viewModel.trashed.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (viewModel.trashed.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Trash is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(viewModel.trashed, key = { it.file.path }) { item ->
                ListItem(
                    leadingContent = { Icon(if (item.file.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.StickyNote2, null) },
                    headlineContent = { Text(item.original) },
                    supportingContent = { Text("Trashed ${item.time.replace('.', ':')}") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { viewModel.restore(item) }) { Icon(Icons.Default.Restore, "Restore") }
                            IconButton(onClick = { viewModel.deleteForever(item) }) { Icon(Icons.Default.DeleteForever, "Delete forever") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty Trash?") },
        text = { Text("Everything in Trash will be deleted permanently.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; viewModel.deleteForever(null) }) { Text("Empty Trash", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
    )
}
