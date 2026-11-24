package org.mdpeds.logbook.ui.richinput

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dialog to configure and insert a Markdown table.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableInsertDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var selectedPreset by remember { mutableStateOf("Custom Grid") }
    var cols by remember { mutableIntStateOf(3) }
    var rows by remember { mutableIntStateOf(3) }

    val presets = listOf(
        "Custom Grid",
        "Vitals Table",
        "APGAR Score",
        "Lab Investigations",
        "Medications",
        "Growth / Anthropometry",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TableChart, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Insert Table")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Choose a preset or build a custom table:", style = MaterialTheme.typography.bodySmall)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(preset) },
                        )
                    }
                }

                if (selectedPreset == "Custom Grid") {
                    HorizontalDivider()

                    // Columns controller
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Columns: $cols", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (cols > 1) cols-- }, enabled = cols > 1) {
                                Icon(Icons.Filled.Remove, "Decrease columns")
                            }
                            Text("$cols", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if (cols < 6) cols++ }, enabled = cols < 6) {
                                Icon(Icons.Filled.Add, "Increase columns")
                            }
                        }
                    }

                    // Rows controller
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Rows: $rows", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (rows > 1) rows-- }, enabled = rows > 1) {
                                Icon(Icons.Filled.Remove, "Decrease rows")
                            }
                            Text("$rows", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if (rows < 10) rows++ }, enabled = rows < 10) {
                                Icon(Icons.Filled.Add, "Increase rows")
                            }
                        }
                    }

                    // Quick visual preview of grid
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Preview ($cols columns × $rows rows):", style = MaterialTheme.typography.labelSmall)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..cols).forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Col $c", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            (1..minOf(rows, 3)).forEach { r ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    (1..cols).forEach { _ ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                                .padding(vertical = 4.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("—", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            if (rows > 3) {
                                Text("+ ${rows - 3} more row(s)...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    // Show description for selected preset
                    val foundPreset = when (selectedPreset) {
                        "Vitals Table" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Vitals") }
                        "APGAR Score" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("APGAR") }
                        "Lab Investigations" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Lab") }
                        "Medications" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Medication") }
                        "Growth / Anthropometry" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Anthropometry") }
                        else -> null
                    }
                    if (foundPreset != null) {
                        Text(foundPreset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val tableMd = when (selectedPreset) {
                    "Vitals Table" -> ClinicalReferenceTables.allTables.first { it.title.contains("Vitals") }.tableMarkdown
                    "APGAR Score" -> ClinicalReferenceTables.allTables.first { it.title.contains("APGAR") }.tableMarkdown
                    "Lab Investigations" -> ClinicalReferenceTables.allTables.first { it.title.contains("Lab") }.tableMarkdown
                    "Medications" -> ClinicalReferenceTables.allTables.first { it.title.contains("Medication") }.tableMarkdown
                    "Growth / Anthropometry" -> ClinicalReferenceTables.allTables.first { it.title.contains("Anthropometry") }.tableMarkdown
                    else -> generateCustomTable(cols, rows)
                }
                onInsert("\n\n$tableMd\n\n")
            }) {
                Text("Insert Table")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to select and insert clinical structures and notes (Sparkle ✨ button).
 */
@Composable
fun ClinicalTemplatesDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Clinical Note Templates")
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ClinicalTemplates.allTemplates) { t ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect("\n\n${t.content}\n\n") },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Dialog to select and insert clinical reference scoring tables (Drive/Cloud 📁 button).
 */
@Composable
fun ReferenceTablesDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Clinical Reference Tables")
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ClinicalReferenceTables.allTables) { t ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect("\n\n${t.tableMarkdown}\n\n") },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Dialog to insert an attachment / document reference (Paperclip 📎 button).
 */
@Composable
fun AttachmentReferenceDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }

    val quickExamples = listOf(
        "Chest X-Ray (AP View)",
        "Arterial Blood Gas (ABG)",
        "Echocardiogram Report",
        "Complete Blood Count (CBC)",
        "CSF Analysis Report",
        "Head Ultrasound (HUS)",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Insert Attachment Reference")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Add a reference to an attached clinical document or investigation:", style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Document / Investigation Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details / Lab ID / Date (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Quick suggestions:", style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    quickExamples.forEach { ex ->
                        FilterChip(
                            selected = title == ex,
                            onClick = { title = ex },
                            label = { Text(ex, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val full = if (details.isNotBlank()) "$title ($details)" else title
                    onInsert(" 📎 **[Attachment: $full]** ")
                },
                enabled = title.isNotBlank(),
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to insert a Markdown link (Link 🔗 button).
 */
@Composable
fun LinkInsertDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var linkText by remember { mutableStateOf(initialText) }
    var linkUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Insert Link")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text("Text to display") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    label = { Text("URL (e.g. https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val url = if (!linkUrl.startsWith("http://") && !linkUrl.startsWith("https://")) "https://$linkUrl" else linkUrl
                    val text = linkText.ifBlank { url }
                    onInsert("[$text]($url)")
                },
                enabled = linkUrl.isNotBlank(),
            ) {
                Text("Insert Link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to insert an Image reference (Photo 🖼️ button).
 */
@Composable
fun ImageInsertDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Insert Clinical Image")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Image Caption / Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text("Image URL or Path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cap = caption.ifBlank { "Clinical Photo" }
                    val url = imageUrl.ifBlank { "attachment://image" }
                    onInsert("![$cap]($url)")
                },
                enabled = caption.isNotBlank() || imageUrl.isNotBlank(),
            ) {
                Text("Insert Image")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Emoji picker dialog (Smiley 😊 button).
 */
@Composable
fun EmojiPickerDialog(
    onDismiss: () -> Unit,
    onSelectEmoji: (String) -> Unit,
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val categories = listOf("Medical", "Status & Review", "Symbols")

    val medicalEmojis = listOf(
        "🩺", "👶", "🏥", "💉", "💊", "🩹", "🫀", "🫁", "🌡️",
        "🧬", "🩸", "🍼", "🔬", "🩻", "🦷", "🧠", "👁️", "🚑",
    )

    val statusEmojis = listOf(
        "✅", "⚠️", "❌", "⏳", "📌", "💡", "🎯", "📊", "📝",
        "🔍", "⭐", "💯", "🏆", "🚩", "🔔", "🕒", "📅", "✍️",
    )

    val generalEmojis = listOf(
        "👍", "👎", "👏", "🤝", "👋", "❓", "❗", "ℹ️", "🔒",
        "🏷️", "📎", "🔗", "📖", "🎓", "📋", "📂", "✨", "🛡️",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Insert Clinical Symbol & Emoji")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = tabIndex) {
                    categories.forEachIndexed { idx, cat ->
                        Tab(
                            selected = tabIndex == idx,
                            onClick = { tabIndex = idx },
                            text = { Text(cat, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                val currentEmojis = when (tabIndex) {
                    0 -> medicalEmojis
                    1 -> statusEmojis
                    else -> generalEmojis
                }

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    currentEmojis.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .clickable { onSelectEmoji(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
