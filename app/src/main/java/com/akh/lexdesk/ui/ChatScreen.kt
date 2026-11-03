package com.akh.lexdesk.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akh.lexdesk.ChatViewModel
import com.akh.lexdesk.data.Attachment
import com.akh.lexdesk.data.ChatMessage
import com.akh.lexdesk.data.Conversation
import com.akh.lexdesk.util.AttachmentReader
import com.akh.lexdesk.util.DocxExporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PICK_TYPES = arrayOf("application/pdf", "image/*", "text/plain", AttachmentReader.DOCX_MIME)

private val SUGGESTIONS = listOf(
    "Draft a statutory notice under Section 138 of the NI Act for a dishonoured cheque",
    "List the documents to call for in a Legal Scrutiny Report for a BDA-allotted site",
    "Summarise the attached judgment: facts, issues, ratio and relevance",
    "Prepare a list of dates and events from the attached documents",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var exportText by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addAttachment(uri)
    }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocxExporter.MIME)) { uri ->
        val text = exportText
        if (uri != null && text != null) vm.exportDocx(uri, text)
        exportText = null
    }

    val notice = vm.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.clearNotice()
        }
    }

    val messages = vm.current.messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    pendingDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text(c.title.ifBlank { "Untitled chat" }) },
            confirmButton = {
                TextButton(onClick = { vm.delete(c); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "LexDesk",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("New chat") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    selected = false,
                    onClick = { vm.newChat(); scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; onOpenSettings() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Chats",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.conversations, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title.ifBlank { "Untitled chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = c.id == vm.current.id,
                            onClick = { vm.open(c); scope.launch { drawerState.close() } },
                            badge = {
                                IconButton(onClick = { pendingDelete = c }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                vm.current.title.ifBlank { "LexDesk" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                vm.routeLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (vm.current.privateMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chats")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.togglePrivate() }, enabled = !vm.isStreaming) {
                            if (vm.current.privateMode) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "Private mode on",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(Icons.Outlined.Lock, contentDescription = "Turn on private mode")
                            }
                        }
                        IconButton(onClick = { vm.newChat() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                InputBar(
                    vm = vm,
                    onAttach = { pickFile.launch(PICK_TYPES) },
                )
            },
        ) { padding ->
            if (messages.isEmpty()) {
                EmptyState(Modifier.padding(padding)) { vm.input = it }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        val isLast = m.id == messages.last().id
                        MessageItem(
                            message = m,
                            streaming = vm.isStreaming && isLast,
                            onCopy = { clipboard.setText(AnnotatedString(m.text)) },
                            onSaveDocx = {
                                exportText = m.text
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                saveDocx.launch("LexDesk-$stamp.docx")
                            },
                            onShare = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, m.text)
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    streaming: Boolean,
    onCopy: () -> Unit,
    onSaveDocx: () -> Unit,
    onShare: () -> Unit,
) {
    if (message.role == "user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            message.attachments.forEach { AttachmentLabel(it) }
            if (message.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    SelectionContainer {
                        Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (message.text.isEmpty() && streaming) {
                Text(
                    if (message.engine.startsWith("On-device")) "Thinking on the phone… (first answer loads the model)"
                    else "Working on it…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val rendered = remember(message.text) { markdownToAnnotated(message.text) }
                SelectionContainer {
                    Text(rendered, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (message.engine.isNotBlank()) {
                Text(
                    message.engine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!streaming && message.text.isNotBlank()) {
                Row {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onSaveDocx) { Text("Save .docx") }
                    TextButton(onClick = onShare) { Text("Share") }
                }
            }
        }
    }
}

@Composable
private fun AttachmentLabel(a: Attachment) {
    val kind = when (a.kind) {
        Attachment.PDF -> "PDF"
        Attachment.IMAGE -> "Image"
        else -> "Text"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp),
    ) {
        Text(
            "$kind · ${a.name}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InputBar(vm: ChatViewModel, onAttach: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (vm.pending.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    vm.pending.forEachIndexed { index, a ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeAttachment(index) },
                            label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                            },
                            modifier = Modifier.padding(end = 6.dp).widthIn(max = 220.dp),
                        )
                    }
                }
            }
            if (vm.isReadingFile) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !vm.isStreaming) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach a document")
                }
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask, or attach a document") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                )
                if (vm.isStreaming) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = { vm.send() },
                        enabled = (vm.input.isNotBlank() || vm.pending.isNotEmpty()) && !vm.isReadingFile,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyState(modifier: Modifier, onPick: (String) -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LexDesk", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Legal work only. Ask a question, or attach a PDF, Word file or photo of a document. " +
                "Tap the lock for Private mode: answers stay on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SUGGESTIONS.forEach { s ->
            OutlinedCard(
                onClick = { onPick(s) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(s, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
