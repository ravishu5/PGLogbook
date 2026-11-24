package org.mdpeds.logbook.ui.richinput

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
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
 * Rich Formatting Toolbar matching Gmail / Google Workspace design.
 * Features: Aa (Formatting), Sparkle (Clinical Templates), Paperclip (Attachment),
 * Link, Emoji, Drive (Tables), Image, Lock (Timestamp), Pen (Signature), More (⋮).
 */
@Composable
fun RichInputToolbar(
    onWrapSelection: (prefix: String, suffix: String, defaultPlaceholder: String) -> Unit,
    onInsertText: (text: String) -> Unit,
    onClearAll: () -> Unit,
    isPreviewMode: Boolean,
    onTogglePreview: () -> Unit,
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

    val scrollState = rememberScrollState()
    val activeColor = MaterialTheme.colorScheme.primaryContainer
    val onActiveColor = MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            // Main icon toolbar row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 1. Aa (Typography & Formatting)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (showFormatBar) activeColor else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { showFormatBar = !showFormatBar }) {
                        Text(
                            text = "Aa",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (showFormatBar) onActiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 2. Sparkle (AI / Clinical note templates)
                IconButton(
                    onClick = { showTemplatesDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "Clinical templates & notes")
                }

                // 3. Paperclip (Attachment reference)
                IconButton(
                    onClick = { showAttachmentDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Insert attachment reference")
                }

                // 4. Link
                IconButton(
                    onClick = { showLinkDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.Link, contentDescription = "Insert link")
                }

                // 5. Emoji
                IconButton(
                    onClick = { showEmojiDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.SentimentSatisfiedAlt, contentDescription = "Insert clinical emoji or symbol")
                }

                // 6. Drive / Clinical Reference Tables
                IconButton(
                    onClick = { showDriveTablesDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.CloudQueue, contentDescription = "Clinical reference scoring tables")
                }

                // 7. Photo / Image
                IconButton(
                    onClick = { showImageDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = "Insert clinical image")
                }

                // 8. Lock / Authenticated timestamp
                IconButton(
                    onClick = { onInsertText(buildTimestampString()) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.LockClock, contentDescription = "Insert verified timestamp")
                }

                // 9. Pen / Clinician signature stamp
                IconButton(
                    onClick = { onInsertText(buildSignatureStamp()) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.Draw, contentDescription = "Insert clinician sign-off stamp")
                }

                // Direct Table Insert shortcut button
                IconButton(
                    onClick = { showTableDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Filled.TableChart, contentDescription = "Insert Table")
                }

                // Live Preview / Edit Toggle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isPreviewMode) activeColor else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onTogglePreview) {
                        Icon(
                            imageVector = if (isPreviewMode) Icons.Filled.EditNote else Icons.Filled.Preview,
                            contentDescription = if (isPreviewMode) "Edit text" else "Preview formatted text and tables",
                            tint = if (isPreviewMode) onActiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // 10. More (⋮)
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More formatting options")
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
                        DropdownMenuItem(
                            text = { Text("Insert Horizontal Line") },
                            leadingIcon = { Icon(Icons.Filled.HorizontalRule, null) },
                            onClick = {
                                showMoreMenu = false
                                onInsertText("\n\n---\n\n")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isPreviewMode) "Switch to Editor" else "Preview Rendered Tables & Text") },
                            leadingIcon = { Icon(if (isPreviewMode) Icons.Filled.EditNote else Icons.Filled.Preview, null) },
                            onClick = {
                                showMoreMenu = false
                                onTogglePreview()
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

            // Sub-row: Text formatting options (toggled by Aa button)
            AnimatedVisibility(
                visible = showFormatBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Bold
                    IconButton(
                        onClick = { onWrapSelection("**", "**", "bold text") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.FormatBold, "Bold", modifier = Modifier.size(18.dp))
                    }

                    // Italic
                    IconButton(
                        onClick = { onWrapSelection("*", "*", "italic text") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.FormatItalic, "Italic", modifier = Modifier.size(18.dp))
                    }

                    // Strikethrough
                    IconButton(
                        onClick = { onWrapSelection("~~", "~~", "strikethrough text") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.FormatStrikethrough, "Strikethrough", modifier = Modifier.size(18.dp))
                    }

                    // Headings
                    IconButton(
                        onClick = { onInsertText("\n### Heading\n") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.FormatSize, "Heading", modifier = Modifier.size(18.dp))
                    }

                    // Bullet List
                    IconButton(
                        onClick = { onInsertText("\n- ") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list", modifier = Modifier.size(18.dp))
                    }

                    // Numbered List
                    IconButton(
                        onClick = { onInsertText("\n1. ") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.FormatListNumbered, "Numbered list", modifier = Modifier.size(18.dp))
                    }

                    // Blockquote
                    IconButton(
                        onClick = { onInsertText("\n> ") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.FormatQuote, "Quote", modifier = Modifier.size(18.dp))
                    }

                    // Monospace Code
                    IconButton(
                        onClick = { onWrapSelection("`", "`", "code") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.Code, "Code", modifier = Modifier.size(18.dp))
                    }

                    // Table inside format bar
                    IconButton(
                        onClick = { showTableDialog = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.TableChart, "Insert Table", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }

                    // Divider
                    IconButton(
                        onClick = { onInsertText("\n\n---\n\n") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.HorizontalRule, "Horizontal Line", modifier = Modifier.size(18.dp))
                    }
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
