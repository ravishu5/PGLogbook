package org.mdpeds.logbook.ui.richinput

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Enhanced Text Input with unified rich text formatting toolbar and table generation.
 * Features a docked toolbar at the bottom of the input container for all input fields.
 */
@Composable
fun RichTextInput(
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
    enableRichToolbar: Boolean = false,
) {
    if (!enableRichToolbar) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            enabled = enabled,
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = modifier
                .fillMaxWidth()
                .testTag("input:$label"),
        )
        return
    }

    // Internal TextFieldValue state keeps track of text and cursor/selection
    var tfv by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    // Keep tfv in sync when value changes externally
    LaunchedEffect(value) {
        if (value != tfv.text) {
            val newCursor = tfv.selection.start.coerceIn(0, value.length)
            tfv = TextFieldValue(text = value, selection = TextRange(newCursor))
        }
    }

    var isPreviewMode by remember { mutableStateOf(false) }

    fun updateTextAndSelection(newText: String, newCursor: Int) {
        val safeCursor = newCursor.coerceIn(0, newText.length)
        tfv = TextFieldValue(text = newText, selection = TextRange(safeCursor))
        onChange(newText)
    }

    fun handleWrapSelection(prefix: String, suffix: String, defaultPlaceholder: String) {
        val current = tfv.text
        val sel = tfv.selection
        val safeMin = sel.min.coerceIn(0, current.length)
        val safeMax = sel.max.coerceIn(0, current.length)
        if (!sel.collapsed && safeMin < safeMax) {
            val selected = current.substring(safeMin, safeMax)
            val wrapped = "$prefix$selected$suffix"
            val newText = current.replaceRange(safeMin, safeMax, wrapped)
            val newCursor = safeMin + wrapped.length
            updateTextAndSelection(newText, newCursor)
        } else {
            val safeStart = sel.start.coerceIn(0, current.length)
            val wrapped = "$prefix$defaultPlaceholder$suffix"
            val newText = current.replaceRange(safeStart, safeStart, wrapped)
            val newCursor = safeStart + prefix.length + defaultPlaceholder.length
            updateTextAndSelection(newText, newCursor)
        }
    }

    fun handleInsertText(insert: String) {
        val current = tfv.text
        val sel = tfv.selection
        val safeMin = sel.min.coerceIn(0, current.length)
        val safeMax = sel.max.coerceIn(0, current.length)
        val newText = current.replaceRange(safeMin, safeMax, insert)
        val newCursor = safeMin + insert.length
        updateTextAndSelection(newText, newCursor)
    }

    fun handleInsertTableRow() {
        val current = tfv.text
        val tableLines = current.lines().filter { it.trim().startsWith("|") && !it.contains("---") }
        val cols = tableLines.firstOrNull()?.split("|")?.drop(1)?.dropLast(1)?.size?.coerceIn(1, 8) ?: 3
        val newRow = "\n| " + (1..cols).joinToString(" | ") { "" } + " |"
        handleInsertText(newRow)
    }

    val borderColor = when {
        error != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, borderColor),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                if (isPreviewMode) {
                    // Live Rendered Markdown & Table Preview
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "$label (Rendered Table & Text Preview)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = { isPreviewMode = false }) {
                                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit")
                            }
                        }

                        if (value.isBlank()) {
                            Text(
                                text = "No content entered yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp),
                            ) {
                                RichMarkdownViewer(text = value)
                            }
                        }
                    }
                } else {
                    // Text Input Area
                    OutlinedTextField(
                        value = tfv,
                        onValueChange = { newTfv ->
                            tfv = newTfv
                            onChange(newTfv.text)
                        },
                        label = { Text(label) },
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else minLines,
                        enabled = enabled,
                        isError = error != null,
                        supportingText = null, // supporting text displayed below the card
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            errorBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input:$label"),
                    )
                }

                if (enableRichToolbar) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.8.dp,
                    )
                    RichInputToolbar(
                        onWrapSelection = { prefix, suffix, placeholder -> handleWrapSelection(prefix, suffix, placeholder) },
                        onInsertText = { text -> handleInsertText(text) },
                        onClearAll = { updateTextAndSelection("", 0) },
                        isPreviewMode = isPreviewMode,
                        onTogglePreview = { isPreviewMode = !isPreviewMode },
                        onInsertTableRow = { handleInsertTableRow() },
                    )
                }
            }
        }

        // Supporting and error text
        val message = error ?: supporting
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
