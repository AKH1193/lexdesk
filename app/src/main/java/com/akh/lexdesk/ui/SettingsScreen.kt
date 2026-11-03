package com.akh.lexdesk.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.akh.lexdesk.ChatViewModel
import com.akh.lexdesk.data.SettingsStore
import com.akh.lexdesk.ondevice.ModelManager
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings

    var engineMode by remember { mutableStateOf(s.engineMode) }

    var geminiKey by remember { mutableStateOf(s.geminiKey) }
    val savedGemini = remember { s.geminiModel }
    val geminiIsPreset = SettingsStore.GEMINI_MODELS.any { it.first == savedGemini }
    var geminiPreset by remember { mutableStateOf(if (geminiIsPreset) savedGemini else SettingsStore.GEMINI_MODELS.first().first) }
    var geminiCustom by remember { mutableStateOf(if (geminiIsPreset) "" else savedGemini) }

    var onDeviceId by remember { mutableStateOf(vm.models.selected().id) }
    var useGpu by remember { mutableStateOf(s.onDeviceGpu) }

    var claudeKey by remember { mutableStateOf(s.apiKey) }
    val savedClaude = remember { s.model }
    val claudeIsPreset = SettingsStore.MODELS.any { it.first == savedClaude }
    var claudePreset by remember { mutableStateOf(if (claudeIsPreset) savedClaude else SettingsStore.MODELS.first().first) }
    var claudeCustom by remember { mutableStateOf(if (claudeIsPreset) "" else savedClaude) }

    var instructions by remember { mutableStateOf(s.standingInstructions) }

    fun saveAndClose() {
        vm.saveSettings(
            engineMode = engineMode,
            geminiKey = geminiKey,
            geminiModel = geminiCustom.trim().ifBlank { geminiPreset },
            claudeKey = claudeKey,
            claudeModel = claudeCustom.trim().ifBlank { claudePreset },
            instructions = instructions,
            useGpu = useGpu,
        )
        onBack()
    }

    // Leaving the screen by any route saves what was entered.
    BackHandler { saveAndClose() }

    var status by remember { mutableStateOf(vm.models.status(vm.models.byId(onDeviceId))) }
    LaunchedEffect(onDeviceId) {
        while (true) {
            // An imported file selects its own model, so follow the stored choice.
            if (s.onDeviceModel != onDeviceId) onDeviceId = s.onDeviceModel
            status = vm.models.status(vm.models.byId(onDeviceId))
            delay(1000)
        }
    }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { saveAndClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Save and go back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Engine ----
            SectionTitle("Engine")
            SettingsStore.ENGINE_MODES.forEach { (id, label) ->
                RadioRow(label = label, selected = engineMode == id) { engineMode = id }
            }
            Hint("The lock button in any chat forces that chat on-device, whatever is chosen here.")

            HorizontalDivider()

            // ---- On-device model ----
            SectionTitle("On-device model (offline, private)")
            ModelManager.MODELS.forEach { m ->
                RadioRow(label = m.label, selected = onDeviceId == m.id) {
                    onDeviceId = m.id
                    vm.selectOnDeviceModel(m.id)
                }
            }

            when (val st = status) {
                is ModelManager.Status.Ready -> {
                    Text("Downloaded and ready.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { vm.deleteModel() }) { Text("Delete model (frees space)") }
                }
                is ModelManager.Status.Downloading -> {
                    val fraction = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
                    Text("Downloading: ${gb(st.done)} of ${gb(st.total)}")
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { vm.cancelModelDownload() }) { Text("Cancel download") }
                }
                is ModelManager.Status.Failed -> {
                    Text(st.reason, color = MaterialTheme.colorScheme.error)
                    DownloadButtons(vm, onImport = { importModel.launch(arrayOf("*/*")) })
                }
                is ModelManager.Status.NotDownloaded -> {
                    Hint("Not downloaded. Use Wi-Fi: the file is several GB and is stored only on this phone.")
                    DownloadButtons(vm, onImport = { importModel.launch(arrayOf("*/*")) })
                }
            }
            if (vm.isImportingModel) {
                Text("Importing model file…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use GPU (faster)")
                    Hint("Turns off automatically if the GPU fails on this phone.")
                }
                Switch(checked = useGpu, onCheckedChange = { useGpu = it })
            }

            HorizontalDivider()

            // ---- Gemini ----
            SectionTitle("Gemini (free, cloud)")
            KeyField(value = geminiKey, onChange = { geminiKey = it }, placeholder = "AIza…")
            Hint(
                "Get a free key at aistudio.google.com > Get API key. On the free tier Google may use what you " +
                    "send to improve its products, so use the lock (Private mode) for client-confidential material."
            )
            SettingsStore.GEMINI_MODELS.forEach { (id, label) ->
                RadioRow(label = label, selected = geminiCustom.isBlank() && geminiPreset == id) {
                    geminiPreset = id
                    geminiCustom = ""
                }
            }
            OutlinedTextField(
                value = geminiCustom,
                onValueChange = { geminiCustom = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Custom Gemini model ID (optional)") },
            )

            HorizontalDivider()

            // ---- Standing instructions ----
            SectionTitle("Standing instructions")
            Hint("Applied to every chat, on top of the legal-only rules.")
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
            )
            TextButton(onClick = { instructions = SettingsStore.DEFAULT_INSTRUCTIONS }) {
                Text("Restore default instructions")
            }

            HorizontalDivider()

            // ---- Claude (optional) ----
            SectionTitle("Claude (optional, paid)")
            KeyField(value = claudeKey, onChange = { claudeKey = it }, placeholder = "sk-ant-…")
            Hint("Only needed if you choose the Claude engine. Keys from console.anthropic.com.")
            SettingsStore.MODELS.forEach { (id, label) ->
                RadioRow(label = label, selected = claudeCustom.isBlank() && claudePreset == id) {
                    claudePreset = id
                    claudeCustom = ""
                }
            }
            OutlinedTextField(
                value = claudeCustom,
                onValueChange = { claudeCustom = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Custom Claude model ID (optional)") },
            )

            Spacer(Modifier.padding(4.dp))
            Button(onClick = { saveAndClose() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun DownloadButtons(vm: ChatViewModel, onImport: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.downloadModel() }) { Text("Download") }
        OutlinedButton(onClick = onImport) { Text("Import file") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun KeyField(value: String, onChange: (String) -> Unit, placeholder: String) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
        },
    )
}

private fun gb(bytes: Long): String = String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
