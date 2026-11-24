package org.mdpeds.logbook.ui.richinput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Checks if a text contains Markdown formatting elements such as tables, headers, lists, or emphasis.
 */
fun hasMarkdown(text: String): Boolean {
    if (text.isBlank()) return false
    return text.contains("|") ||
        text.contains("**") ||
        text.contains("*") ||
        text.contains("~~") ||
        text.contains("#") ||
        text.contains("`") ||
        text.contains("> ") ||
        text.contains("- ") ||
        text.contains("---") ||
        Regex("""^\d+\.\s+""", RegexOption.MULTILINE).containsMatchIn(text)
}

/**
 * Formats inline Markdown (**bold**, *italic*, ~~strikethrough~~, `code`, [link](url)) into an AnnotatedString.
 */
fun parseInlineMarkdown(text: String, primaryColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = text.length

        while (i < len) {
            when {
                // Bold: **text**
                text.startsWith("**", i) && text.indexOf("**", i + 2) != -1 -> {
                    val end = text.indexOf("**", i + 2)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) && text.indexOf("~~", i + 2) != -1 -> {
                    val end = text.indexOf("~~", i + 2)
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
                // Italic: *text* (make sure not followed/preceded by another *)
                text[i] == '*' && text.indexOf('*', i + 1) != -1 && (i == 0 || text[i - 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                }
                // Inline code: `text`
                text[i] == '`' && text.indexOf('`', i + 1) != -1 -> {
                    val end = text.indexOf('`', i + 1)
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = primaryColor.copy(alpha = 0.12f),
                            fontSize = 13.sp,
                        )
                    )
                    append(" ${text.substring(i + 1, end)} ")
                    pop()
                    i = end + 1
                }
                // Link: [text](url)
                text[i] == '[' && text.indexOf(']', i) != -1 &&
                    text.indexOf(']', i) + 1 < len && text[text.indexOf(']', i) + 1] == '(' &&
                    text.indexOf(')', text.indexOf(']', i) + 1) != -1 -> {
                    val titleEnd = text.indexOf(']', i)
                    val urlEnd = text.indexOf(')', titleEnd + 1)
                    val linkTitle = text.substring(i + 1, titleEnd)
                    val linkUrl = text.substring(titleEnd + 2, urlEnd)
                    pushStyle(
                        SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        )
                    )
                    append(linkTitle.ifBlank { linkUrl })
                    pop()
                    i = urlEnd + 1
                }
                // Plain character
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

/**
 * Parsed Markdown blocks.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * Parses a markdown string into structured MarkdownBlocks.
 */
fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    val lines = rawText.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.isBlank() -> {
                i++
            }
            // Divider
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                blocks.add(MarkdownBlock.Divider)
                i++
            }
            // Markdown Table: starts with | and next line has | and ---
            trimmed.startsWith("|") && i + 1 < lines.size &&
                lines[i + 1].trim().startsWith("|") && lines[i + 1].contains("---") -> {
                val headerCells = trimmed.removePrefix("|").removeSuffix("|").split("|")
                    .map { it.trim() }

                i += 2 // Skip header line and delimiter line

                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (rowLine.startsWith("|")) {
                        val cells = rowLine.removePrefix("|").removeSuffix("|").split("|")
                            .map { it.trim() }
                        rows.add(cells)
                        i++
                    } else {
                        break
                    }
                }
                blocks.add(MarkdownBlock.Table(headerCells, rows))
            }
            // Headings
            trimmed.startsWith("### ") -> {
                blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
                i++
            }
            trimmed.startsWith("## ") -> {
                blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
                i++
            }
            trimmed.startsWith("# ") -> {
                blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
                i++
            }
            // Blockquote
            trimmed.startsWith("> ") || trimmed == ">" -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trim().startsWith("> ") || lines[i].trim() == ">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n")))
            }
            // Bullet list
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                blocks.add(MarkdownBlock.BulletItem(trimmed.substring(2).trim()))
                i++
            }
            // Numbered list
            Regex("""^\d+\.\s+""").containsMatchIn(trimmed) -> {
                val match = Regex("""^(\d+\.)\s+(.*)""").find(trimmed)
                if (match != null) {
                    val (num, content) = match.destructured
                    blocks.add(MarkdownBlock.NumberedItem(num, content.trim()))
                } else {
                    blocks.add(MarkdownBlock.Paragraph(line))
                }
                i++
            }
            // Normal paragraph
            else -> {
                blocks.add(MarkdownBlock.Paragraph(line))
                i++
            }
        }
    }

    return blocks
}

/**
 * Rich Markdown View Composable.
 * Renders formatted text, beautiful interactive tables, headers, lists, and quotes.
 */
@Composable
fun RichMarkdownViewer(
    text: String,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = primaryColor)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = primaryColor)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = parseInlineMarkdown(block.text, primaryColor),
                        style = style,
                        modifier = Modifier.padding(top = if (block.level == 1) 6.dp else 4.dp, bottom = 2.dp),
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, primaryColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, primaryColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = block.number,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = primaryColor),
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, primaryColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .background(primaryColor, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(block.text, primaryColor),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is MarkdownBlock.Table -> {
                    MarkdownTableView(table = block, primaryColor = primaryColor)
                }
                MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

/**
 * Beautiful Material 3 Table Component for Markdown Tables.
 * Renders headers, alternating rows, borders, and supports horizontal scrolling.
 */
@Composable
fun MarkdownTableView(
    table: MarkdownBlock.Table,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val altRowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    val colCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Column {
                // Header row
                Row(
                    modifier = Modifier
                        .background(headerBg)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (0 until colCount).forEach { idx ->
                        val headText = table.headers.getOrNull(idx) ?: "Col ${idx + 1}"
                        Box(
                            modifier = Modifier
                                .widthIn(min = if (colCount <= 3) 130.dp else 110.dp, max = 260.dp)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = parseInlineMarkdown(headText.ifBlank { " " }, primaryColor),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                        if (idx < colCount - 1) {
                            Box(modifier = Modifier.width(1.dp).height(18.dp).background(borderColor))
                        }
                    }
                }

                HorizontalDivider(color = borderColor, thickness = 1.5.dp)

                // Data rows
                table.rows.forEachIndexed { rowIndex, row ->
                    val isAlt = rowIndex % 2 == 1
                    Row(
                        modifier = Modifier
                            .background(if (isAlt) altRowBg else Color.Transparent)
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (0 until colCount).forEach { colIdx ->
                            val cellText = row.getOrNull(colIdx) ?: ""
                            Box(
                                modifier = Modifier
                                    .widthIn(min = if (colCount <= 3) 130.dp else 110.dp, max = 260.dp)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = parseInlineMarkdown(cellText.ifBlank { " " }, primaryColor),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (colIdx < colCount - 1) {
                                Box(modifier = Modifier.width(1.dp).height(16.dp).background(borderColor.copy(alpha = 0.5f)))
                            }
                        }
                    }
                    if (rowIndex < table.rows.size - 1) {
                        HorizontalDivider(color = borderColor.copy(alpha = 0.4f), thickness = 0.8.dp)
                    }
                }
            }
        }
    }
}
