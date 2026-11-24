package org.mdpeds.logbook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.mdpeds.logbook.ui.richinput.RichTextInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import org.mdpeds.logbook.domain.model.CertificationStatus
import org.mdpeds.logbook.domain.model.SignoffStatus
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.display
import org.mdpeds.logbook.ui.common.parseDate
import org.mdpeds.logbook.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ------------------------------------------------------------------ status

enum class Tone { NOT_STARTED, DRAFT, PENDING, APPROVED, RETURNED }

fun SubmissionStatus.tone(): Tone = when (this) {
    SubmissionStatus.NOT_STARTED -> Tone.NOT_STARTED
    SubmissionStatus.DRAFT -> Tone.DRAFT
    SubmissionStatus.RETURNED -> Tone.RETURNED
    SubmissionStatus.VERIFIED, SubmissionStatus.COMPLETED -> Tone.APPROVED
    else -> Tone.PENDING
}

fun SignoffStatus.tone(): Tone = when (this) {
    SignoffStatus.NOT_STARTED -> Tone.NOT_STARTED
    SignoffStatus.IN_PROGRESS -> Tone.DRAFT
    SignoffStatus.AWAITING_APPROVAL -> Tone.PENDING
    SignoffStatus.APPROVED -> Tone.APPROVED
    SignoffStatus.RETURNED -> Tone.RETURNED
}

fun CertificationStatus.tone(): Tone = when (this) {
    CertificationStatus.NOT_ELIGIBLE -> Tone.NOT_STARTED
    CertificationStatus.AWAITING_FINAL_SIGNOFF -> Tone.PENDING
    CertificationStatus.READY_FOR_CERTIFICATION -> Tone.DRAFT
    CertificationStatus.CERTIFICATE_ISSUED -> Tone.APPROVED
}

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val s = AppTheme.status
    return when (tone) {
        Tone.NOT_STARTED -> s.notStarted to s.notStartedContainer
        Tone.DRAFT -> s.draft to s.draftContainer
        Tone.PENDING -> s.pending to s.pendingContainer
        Tone.APPROVED -> s.approved to s.approvedContainer
        Tone.RETURNED -> s.returned to s.returnedContainer
    }
}

@Composable
fun StatusChip(label: String, tone: Tone, modifier: Modifier = Modifier) {
    val (fg, bg) = toneColors(tone)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(fg, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun StatusChip(status: SubmissionStatus, modifier: Modifier = Modifier) = StatusChip(status.label, status.tone(), modifier)

@Composable
fun StatusChip(status: SignoffStatus, modifier: Modifier = Modifier) = StatusChip(status.label, status.tone(), modifier)

// ------------------------------------------------------------------ scaffolding pieces

@Composable
fun BackTopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("loading"), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
fun ErrorBox(message: String, offline: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MessageBox(
            icon = if (offline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
            title = if (offline) "You're offline" else "Couldn't load",
            body = message,
            modifier = Modifier.testTag("error"),
        ) { OutlinedButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Try again") } }
    }
}

@Composable
fun EmptyState(title: String, body: String? = null, action: (@Composable () -> Unit)? = null) =
    MessageBox(Icons.Filled.Inbox, title, body, Modifier.testTag("empty"), action)

@Composable
fun OfflineBanner(text: String = "Offline — showing saved data. Changes to drafts will sync automatically.") {
    val s = AppTheme.status
    Surface(color = s.pendingContainer, contentColor = s.pending, modifier = Modifier.fillMaxWidth().testTag("offline")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Renders loading / error / ready states uniformly; [content] receives the ready value. */
@Composable
fun <T> StateContent(state: UiState<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        UiState.Loading -> LoadingBox()
        is UiState.Error -> ErrorBox(state.message, state.offline, onRetry)
        is UiState.Ready -> Column(Modifier.fillMaxSize()) {
            if (state.offline) OfflineBanner()
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) { content(state.value) }
        }
    }
}

/** A centred, width-limited list suitable for phones and tablets. */
@Composable
fun ScreenList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun CollectMessages(messages: Flow<String>, show: suspend (String) -> Unit) {
    LaunchedEffect(messages) { messages.collect { show(it) } }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 4.dp))
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
    }
}

@Composable
fun Banner(text: String, tone: Tone, title: String? = null, actions: (@Composable () -> Unit)? = null) {
    val (fg, bg) = toneColors(tone)
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
fun KeyValue(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, tone: Tone? = null, onClick: (() -> Unit)? = null) {
    val fg = tone?.let { toneColors(it).first } ?: MaterialTheme.colorScheme.primary
    InfoCard(modifier = modifier, onClick = onClick) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = fg)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
fun ProgressLine(label: String, done: Int, total: Int, detail: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$done / $total", style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (total > 0 && done >= total) AppTheme.status.approved else MaterialTheme.colorScheme.primary,
        )
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ inputs

/** Read-only text field that opens a dropdown menu. */
@Composable
fun DropdownField(
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    allowClear: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value.orEmpty()
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) Box(Modifier.matchParentSize().clickable { open = true }.testTag("dropdown:$label"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowClear) DropdownMenuItem(text = { Text("— None —") }, onClick = { open = false; onSelect("") })
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(key) })
            }
        }
    }
}

/** Date input backed by the Material date picker; value is ISO yyyy-MM-dd. */
@Composable
fun DateField(
    label: String,
    value: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    supporting: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val date = parseDate(value)
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.display() ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) Box(Modifier.matchParentSize().clickable { open = true }.testTag("date:$label"))
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    error: String? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    enableRichToolbar: Boolean = true,
) {
    RichTextInput(
        label = label,
        value = value,
        onChange = onChange,
        modifier = modifier,
        singleLine = singleLine,
        error = error,
        supporting = supporting,
        enabled = enabled,
        keyboardType = keyboardType,
        minLines = minLines,
        enableRichToolbar = enableRichToolbar,
    )
}

// ------------------------------------------------------------------ dialogs

/** Asks for a mandatory (or optional) reason/comment before an action. */
@Composable
fun ReasonDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    required: Boolean = true,
    message: String? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
                TextInput(
                    label = label,
                    value = text,
                    onChange = { text = it },
                    singleLine = false,
                    minLines = 3,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = !required || text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Small row of filter chips for list screens. */
@Composable
fun <T> FilterRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            androidx.compose.material3.FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}
