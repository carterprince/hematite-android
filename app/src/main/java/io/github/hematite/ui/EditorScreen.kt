package io.github.hematite.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hematite.core.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: NotesViewModel, snackbar: SnackbarHostState, showBack: Boolean) {
    val document = viewModel.document ?: return
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scroll = remember(document.file) { androidx.compose.foundation.ScrollState(0) }
    var focused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val textSize by viewModel.app.settings.textSizeFlow.collectAsStateWithLifecycle()
    val autosave by viewModel.app.settings.autosaveFlow.collectAsStateWithLifecycle()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::insertImage) }
    val folder = document.file.parentFile!!.let { if (it == viewModel.root) "Vault" else viewModel.relative(it) }

    LaunchedEffect(document.file, viewModel.focusEditor) {
        if (viewModel.focusEditor) {
            viewModel.focusEditor = false
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val control = event.isCtrlPressed
        if (event.key == Key.Tab && !control && !event.isAltPressed && !event.isMetaPressed) return viewModel.tab(event.isShiftPressed)
        if (!control) return false
        when (event.key) {
            Key.B -> viewModel.toggleMarker("**")
            Key.I -> viewModel.toggleMarker("*")
            Key.S -> viewModel.save()
            Key.Z -> if (event.isShiftPressed) viewModel.redo() else viewModel.undo()
            Key.Y -> viewModel.redo()
            Key.Equals, Key.Plus, Key.NumPadAdd -> viewModel.app.settings.textSize = textSize + 1
            Key.Minus, Key.NumPadSubtract -> viewModel.app.settings.textSize = textSize - 1
            Key.Zero, Key.NumPad0 -> viewModel.app.settings.textSize = io.github.hematite.Settings.DEFAULT_TEXT_SIZE
            Key.Enter, Key.NumPadEnter -> {
                // Opens the link at the cursor, even while its source is shown.
                val cursor = viewModel.editor.selection.start
                val link = Markdown.parse(viewModel.editor.text).links.firstOrNull { cursor in it.source || cursor == it.source.end } ?: return false
                openLink(context, link.uri)
            }
            else -> return false
        }
        return true
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = { if (showBack) IconButton(onClick = viewModel::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to notes") } },
                title = {
                    Column {
                        Text((if (viewModel.dirty) "•  " else "") + document.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (!autosave) IconButton(onClick = { viewModel.save() }, enabled = viewModel.dirty) { Icon(Icons.Default.Save, "Save") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; viewModel.prompt = Prompt.Rename(document.file) })
                            DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; viewModel.prompt = Prompt.Move(document.file) })
                            DropdownMenuItem(text = { Text("Insert image") }, onClick = {
                                menu = false
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("Larger text") }, onClick = { viewModel.app.settings.textSize = textSize + 1 })
                            DropdownMenuItem(text = { Text("Smaller text") }, onClick = { viewModel.app.settings.textSize = textSize - 1 })
                            if (!autosave && viewModel.dirty) DropdownMenuItem(text = { Text("Discard changes") }, onClick = { menu = false; viewModel.discard() })
                            DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; viewModel.requestTrash(document.file) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider()
                    if (focused) FormatBar(viewModel, onImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onHide = { focus.clearFocus() })
                    else Footer(viewModel)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val height = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                MarkdownEditor(
                    value = viewModel.editor,
                    onValueChange = viewModel::type,
                    noteDirectory = document.file.parentFile,
                    textSize = textSize,
                    editable = true,
                    focusRequester = focusRequester,
                    scrollState = scroll,
                    minHeight = height - 32.dp,
                    onOpenLink = { openLink(context, it) },
                    onToggleTask = viewModel::toggleTask,
                    onKey = ::onKey,
                    onFocusChange = { focused = it },
                    reveal = viewModel.reveal,
                    onRevealed = { viewModel.reveal = null },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Footer(viewModel: NotesViewModel) {
    val words = remember(viewModel.editor.text) { viewModel.editor.text.split(Regex("\\s+")).count { it.isNotEmpty() } }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (words == 1) "1 word" else "$words words", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        SyncButton(viewModel)
    }
}

@Composable
private fun FormatBar(viewModel: NotesViewModel, onImage: () -> Unit, onHide: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            IconButton(onClick = { viewModel.toggleMarker("**") }) { Icon(Icons.Default.FormatBold, "Bold") }
            IconButton(onClick = { viewModel.toggleMarker("*") }) { Icon(Icons.Default.FormatItalic, "Italic") }
            IconButton(onClick = { viewModel.toggleMarker("~~") }) { Icon(Icons.Default.FormatStrikethrough, "Strikethrough") }
            IconButton(onClick = { viewModel.toggleLinePrefix(task = true) }) { Icon(Icons.Default.Checklist, "Checklist") }
            IconButton(onClick = { viewModel.toggleLinePrefix(task = false) }) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list") }
            IconButton(onClick = { viewModel.tab(outdent = true) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Move list item out") }
            IconButton(onClick = { viewModel.tab(outdent = false) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Nest list item") }
            IconButton(onClick = onImage) { Icon(Icons.Outlined.Image, "Insert image") }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onHide) { Icon(Icons.Default.KeyboardHide, "Hide keyboard") }
    }
}
