package org.mdpeds.logbook.ui.richinput

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Enhanced Text Input with full rich text, table generation, and formatting toolbar support.
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
    enableRichToolbar: Boolean = true,
) {
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
    var singleLineToolbarOpen by remember { mutableStateOf(false) }

    fun updateTextAndSelection(newText: String, newCursor: Int) {
        val safeCursor = newCursor.coerceIn(0, newText.length)
        tfv = TextFieldValue(text = newText, selection = TextRange(safeCursor))
        onChange(newText)
    }

    fun handleWrapSelection(prefix: String, suffix: String, defaultPlaceholder: String) {
        val current = tfv.text
        val sel = tfv.selection
        if (!sel.collapsed) {
            val selected = current.substring(sel.min, sel.max)
            val wrapped = "$prefix$selected$suffix"
            val newText = current.replaceRange(sel.min, sel.max, wrapped)
            val newCursor = sel.min + wrapped.length
            updateTextAndSelection(newText, newCursor)
        } else {
            val wrapped = "$prefix$defaultPlaceholder$suffix"
            val newText = current.replaceRange(sel.start, sel.start, wrapped)
            val newCursor = sel.start + prefix.length + defaultPlaceholder.length
            updateTextAndSelection(newText, newCursor)
        }
    }

    fun handleInsertText(insert: String) {
        val current = tfv.text
        val sel = tfv.selection
        val newText = current.replaceRange(sel.min, sel.max, insert)
        val newCursor = sel.min + insert.length
        updateTextAndSelection(newText, newCursor)
    }

    val showToolbar = enableRichToolbar && (!singleLine || singleLineToolbarOpen)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Toolbar displayed above input for easy accessibility
        if (enableRichToolbar && !singleLine) {
            RichInputToolbar(
                onWrapSelection = { prefix, suffix, placeholder -> handleWrapSelection(prefix, suffix, placeholder) },
                onInsertText = { text -> handleInsertText(text) },
                onClearAll = { updateTextAndSelection("", 0) },
                isPreviewMode = isPreviewMode,
                onTogglePreview = { isPreviewMode = !isPreviewMode },
            )
        } else if (enableRichToolbar && singleLine) {
            AnimatedVisibility(
                visible = singleLineToolbarOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                RichInputToolbar(
                    onWrapSelection = { prefix, suffix, placeholder -> handleWrapSelection(prefix, suffix, placeholder) },
                    onInsertText = { text -> handleInsertText(text) },
                    onClearAll = { updateTextAndSelection("", 0) },
                    isPreviewMode = isPreviewMode,
                    onTogglePreview = { isPreviewMode = !isPreviewMode },
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        // Editor or Live Rendered Preview
        if (isPreviewMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview:$label"),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                ),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$label (Rendered Preview)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        ) {
                            RichMarkdownViewer(text = value)
                        }
                    }
                }
            }
        } else {
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
                supportingText = (error ?: supporting)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                trailingIcon = {
                    if (enableRichToolbar && singleLine) {
                        IconButton(
                            onClick = { singleLineToolbarOpen = !singleLineToolbarOpen },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Text(
                                text = "Aa",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (singleLineToolbarOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input:$label"),
            )
        }
    }
}
