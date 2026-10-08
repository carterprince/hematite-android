package io.github.hematite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hematite.SyncKind
import io.github.hematite.core.Entry
import io.github.hematite.core.Hit
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel, snackbar: SnackbarHostState, searchFocus: FocusRequester) {
    val status by viewModel.sync.status.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    Scaffold(
        topBar = { SearchBar(viewModel, searchFocus) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { viewModel.requestNew(viewModel.root, folder = false) },
                icon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) }, text = { Text("New note") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = status.kind == SyncKind.Busy && viewModel.sync.connected,
            onRefresh = { viewModel.populate(); viewModel.sync.request() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (viewModel.query.isBlank()) Tree(viewModel) else Results(viewModel) { focus.clearFocus() }
        }
    }
}

@Composable
private fun SearchBar(viewModel: NotesViewModel, searchFocus: FocusRequester) {
    var menu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = viewModel.query,
            onValueChange = viewModel::changeQuery,
            placeholder = { Text("Search notes…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (viewModel.query.isNotEmpty()) IconButton(onClick = { viewModel.changeQuery(""); focus.clearFocus() }) { Icon(Icons.Default.Close, "Clear search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Enter opens the best match, like the desktop search.
            keyboardActions = KeyboardActions(onSearch = { viewModel.hits.firstOrNull()?.let { viewModel.open(it.file, viewModel.query) } }),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        SyncButton(viewModel)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New folder") }, onClick = { menu = false; viewModel.requestNew(viewModel.root, folder = true) })
                DropdownMenuItem(text = { Text("Sync now") }, enabled = viewModel.sync.connected, onClick = { menu = false; viewModel.sync.request() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; viewModel.confirm { viewModel.screen = Screen.Settings } })
            }
        }
    }
}

@Composable
private fun Tree(viewModel: NotesViewModel) {
    val visible = viewModel.entries.filter { viewModel.visible(it) }
    if (viewModel.loaded && viewModel.entries.none { !it.directory }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("No .md or .txt notes found in this vault", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (visible.isEmpty()) return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(visible, key = { it.file.path }) { entry -> TreeRow(viewModel, entry) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(viewModel: NotesViewModel, entry: Entry) {
    var menu by remember { mutableStateOf(false) }
    val active = viewModel.document?.file == entry.file
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { if (entry.directory) viewModel.toggleFolder(entry.file) else viewModel.open(entry.file) },
                    onLongClick = { menu = true },
                )
                .padding(start = 12.dp + 20.dp * entry.depth, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            if (entry.directory) {
                Icon(if (entry.file in viewModel.expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = tint)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Folder, null, tint = tint, modifier = Modifier.size(20.dp))
            } else {
                Spacer(Modifier.width(28.dp))
                Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (entry.directory) entry.file.name else entry.file.nameWithoutExtension,
                fontWeight = if (entry.directory) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ItemMenu(viewModel, entry.file, entry.directory, menu) { menu = false }
    }
}

@Composable
fun ItemMenu(viewModel: NotesViewModel, file: File, directory: Boolean, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (directory) {
            DropdownMenuItem(text = { Text("New note here") }, onClick = { onDismiss(); viewModel.requestNew(file, folder = false) })
            DropdownMenuItem(text = { Text("New folder here") }, onClick = { onDismiss(); viewModel.requestNew(file, folder = true) })
        }
        DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); viewModel.prompt = Prompt.Rename(file) })
        if (!directory) DropdownMenuItem(text = { Text("Move to…") }, onClick = { onDismiss(); viewModel.prompt = Prompt.Move(file) })
        DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { onDismiss(); viewModel.requestTrash(file) })
    }
}

@Composable
private fun Results(viewModel: NotesViewModel, onOpen: () -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(viewModel.query) { state.scrollToItem(0) }
    val count = viewModel.hits.size
    val message = when {
        viewModel.indexing -> "Indexing contents… · $count matches"
        count == 0 -> "No matching notes"
        else -> "$count matching ${if (count == 1) "note" else "notes"}"
    } + if (viewModel.unreadable > 0) " · ${viewModel.unreadable} notes searchable by name only" else ""
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
        item { Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        items(viewModel.hits, key = { it.file.path }) { hit -> ResultRow(viewModel, hit, onOpen) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(viewModel: NotesViewModel, hit: Hit, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(if (viewModel.document?.file == hit.file) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { onOpen(); viewModel.open(hit.file, viewModel.query) }, onLongClick = { menu = true })
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(hit.relative, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            hit.preview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
        }
        ItemMenu(viewModel, hit.file, false, menu) { menu = false }
    }
}
