package org.mdpeds.logbook.ui.richinput

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rich Formatting Toolbar matching Gmail / Google Workspace compose toolbar.
 * Primary icons:
 * [Aa]  [✨ Sparkle Pencil]  [📎 Paperclip]  [🔗 Link]  [😊 Emoji]  |  [▦ Table]  [👁 Preview]  [⋮]
 */
@Composable
fun RichInputToolbar(
    onWrapSelection: (prefix: String, suffix: String, defaultPlaceholder: String) -> Unit,
    onInsertText: (text: String) -> Unit,
    onClearAll: () -> Unit,
    isPreviewMode: Boolean,
    onTogglePreview: () -> Unit,
    onInsertTableRow: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showFormatBar by remember { mutableStateOf(false) }
    var showTableDialog by remember { mutableStateOf(false) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var showAttachmentDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showEmojiDialog by remember { mutableStateOf(false) }
    var showDriveTablesDialog by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
    ) {
        // Expandable Text Formatting Sub-bar (toggled by Aa button)
        AnimatedVisibility(
            visible = showFormatBar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Bold
                IconButton(onClick = { onWrapSelection("**", "**", "bold text") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.FormatBold, "Bold", modifier = Modifier.size(17.dp))
                }
                // Italic
                IconButton(onClick = { onWrapSelection("*", "*", "italic text") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.FormatItalic, "Italic", modifier = Modifier.size(17.dp))
                }
                // Strikethrough
                IconButton(onClick = { onWrapSelection("~~", "~~", "strikethrough") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.FormatStrikethrough, "Strikethrough", modifier = Modifier.size(17.dp))
                }
                // Heading
                IconButton(onClick = { onInsertText("\n### Heading\n") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.FormatSize, "Heading", modifier = Modifier.size(17.dp))
                }
                // Bullet List
                IconButton(onClick = { onInsertText("\n- ") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list", modifier = Modifier.size(17.dp))
                }
                // Numbered List
                IconButton(onClick = { onInsertText("\n1. ") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.FormatListNumbered, "Numbered list", modifier = Modifier.size(17.dp))
                }
                // Blockquote
                IconButton(onClick = { onInsertText("\n> ") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.FormatQuote, "Quote", modifier = Modifier.size(17.dp))
                }
                // Code
                IconButton(onClick = { onWrapSelection("`", "`", "code") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Code, "Code", modifier = Modifier.size(17.dp))
                }
                // Horizontal Divider
                IconButton(onClick = { onInsertText("\n\n---\n\n") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.HorizontalRule, "Horizontal line", modifier = Modifier.size(17.dp))
                }
            }
        }

        // Main Toolbar Row matching the screenshot exactly
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 1. Aa (Formatting button)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (showFormatBar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { showFormatBar = !showFormatBar },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Aa",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (showFormatBar) MaterialTheme.colorScheme.onPrimaryContainer else iconTint,
                )
            }

            // 2. Sparkle Pencil (with Google-style light blue rounded square #D3E3FD)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFD3E3FD))
                    .clickable { showTemplatesDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "AI & Clinical Note Templates",
                        tint = Color(0xFF041E49),
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF041E49),
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.TopStart)
                            .offset(x = (-3).dp, y = (-3).dp),
                    )
                }
            }

            // 3. Paperclip (Attachment)
            IconButton(
                onClick = { showAttachmentDialog = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Insert attachment reference",
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 4. Link
            IconButton(
                onClick = { showLinkDialog = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = "Insert link",
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 5. Smiley Emoji
            IconButton(
                onClick = { showEmojiDialog = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SentimentSatisfiedAlt,
                    contentDescription = "Insert clinical symbol or emoji",
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // 6. Prominent Table Button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(primaryColor.copy(alpha = 0.12f))
                    .clickable { showTableDialog = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.TableChart,
                    contentDescription = "Insert Table",
                    tint = primaryColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Table",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                )
            }

            // 7. Live Preview / Edit Toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPreviewMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onTogglePreview() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isPreviewMode) Icons.Filled.EditNote else Icons.Filled.Preview,
                    contentDescription = if (isPreviewMode) "Edit Mode" else "Preview Mode",
                    tint = if (isPreviewMode) MaterialTheme.colorScheme.onPrimaryContainer else iconTint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isPreviewMode) "Edit" else "Preview",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (isPreviewMode) MaterialTheme.colorScheme.onPrimaryContainer else iconTint,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // 8. More options (⋮)
            Box {
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.MoreVert, "More options", tint = iconTint, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Insert Table") },
                        leadingIcon = { Icon(Icons.Filled.TableChart, null) },
                        onClick = {
                            showMoreMenu = false
                            showTableDialog = true
                        },
                    )
                    if (onInsertTableRow != null) {
                        DropdownMenuItem(
                            text = { Text("Add Row to Current Table") },
                            leadingIcon = { Icon(Icons.Filled.TableChart, null) },
                            onClick = {
                                showMoreMenu = false
                                onInsertTableRow()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Clinical Scoring Tables (APGAR, Vitals, GCS)") },
                        leadingIcon = { Icon(Icons.Filled.CloudQueue, null) },
                        onClick = {
                            showMoreMenu = false
                            showDriveTablesDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Insert Clinical Image") },
                        leadingIcon = { Icon(Icons.Filled.Image, null) },
                        onClick = {
                            showMoreMenu = false
                            showImageDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Insert Authenticated Timestamp") },
                        leadingIcon = { Icon(Icons.Filled.LockClock, null) },
                        onClick = {
                            showMoreMenu = false
                            onInsertText(buildTimestampString())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Insert Clinician Signature Stamp") },
                        leadingIcon = { Icon(Icons.Filled.Draw, null) },
                        onClick = {
                            showMoreMenu = false
                            onInsertText(buildSignatureStamp())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Insert Horizontal Line (---)") },
                        leadingIcon = { Icon(Icons.Filled.HorizontalRule, null) },
                        onClick = {
                            showMoreMenu = false
                            onInsertText("\n\n---\n\n")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear All Text") },
                        onClick = {
                            showMoreMenu = false
                            onClearAll()
                        },
                    )
                }
            }
        }
    }

    // Dialogs
    if (showTableDialog) {
        TableInsertDialog(
            onDismiss = { showTableDialog = false },
            onInsert = {
                showTableDialog = false
                onInsertText(it)
            },
        )
    }

    if (showTemplatesDialog) {
        ClinicalTemplatesDialog(
            onDismiss = { showTemplatesDialog = false },
            onSelect = {
                showTemplatesDialog = false
                onInsertText(it)
            },
        )
    }

    if (showAttachmentDialog) {
        AttachmentReferenceDialog(
            onDismiss = { showAttachmentDialog = false },
            onInsert = {
                showAttachmentDialog = false
                onInsertText(it)
            },
        )
    }

    if (showLinkDialog) {
        LinkInsertDialog(
            onDismiss = { showLinkDialog = false },
            onInsert = {
                showLinkDialog = false
                onInsertText(it)
            },
        )
    }

    if (showEmojiDialog) {
        EmojiPickerDialog(
            onDismiss = { showEmojiDialog = false },
            onSelectEmoji = {
                showEmojiDialog = false
                onInsertText(it)
            },
        )
    }

    if (showDriveTablesDialog) {
        ReferenceTablesDialog(
            onDismiss = { showDriveTablesDialog = false },
            onSelect = {
                showDriveTablesDialog = false
                onInsertText(it)
            },
        )
    }

    if (showImageDialog) {
        ImageInsertDialog(
            onDismiss = { showImageDialog = false },
            onInsert = {
                showImageDialog = false
                onInsertText(it)
            },
        )
    }
}
