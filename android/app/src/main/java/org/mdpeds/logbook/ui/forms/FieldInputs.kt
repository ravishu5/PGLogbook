package org.mdpeds.logbook.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.mdpeds.logbook.domain.logic.FormValidator
import org.mdpeds.logbook.domain.logic.OptionsResolver
import org.mdpeds.logbook.domain.model.FieldDef
import org.mdpeds.logbook.domain.model.FieldType
import org.mdpeds.logbook.ui.components.DateField
import org.mdpeds.logbook.ui.components.DropdownField
import org.mdpeds.logbook.ui.components.TextInput

private fun FieldDef.caption() = if (required) "$label *" else label

/** Editable input for one template field. ATTACHMENT fields are rendered by the editor itself. */
@Composable
fun FieldInput(f: FieldDef, value: String, onChange: (String) -> Unit, error: String?, ctx: FieldContext) {
    val help = f.help
    when (f.type) {
        FieldType.DATE -> DateField(f.caption(), value, onChange, error = error, supporting = help)
        FieldType.SELECT -> {
            val opts = OptionsResolver.options(f, ctx.reference.roleOptions, ctx.reference.targets)
            DropdownField(f.caption(), value, opts.map { it.value to it.label }, onChange, error = error, allowClear = !f.required)
            if (help != null && error == null) Help(help)
        }
        FieldType.FACULTY -> {
            DropdownField(f.caption(), value, ctx.facultyOptions, onChange, error = error, allowClear = !f.required)
            if (ctx.facultyOptions.isEmpty()) Help("No faculty are assigned to you yet. Ask the HOD to assign your guide or unit faculty.")
            else if (help != null && error == null) Help(help)
        }
        FieldType.MULTILINE -> TextInput(f.caption(), value, onChange, singleLine = false, minLines = 3, error = error, supporting = help)
        FieldType.WORDS -> {
            val n = FormValidator.wordCount(value)
            val range = when {
                f.minWords != null && f.maxWords != null -> " (${f.minWords}–${f.maxWords} words)"
                f.minWords != null -> " (min ${f.minWords} words)"
                f.maxWords != null -> " (max ${f.maxWords} words)"
                else -> ""
            }
            TextInput(f.caption(), value, onChange, singleLine = false, minLines = 6, error = error, supporting = "$n words$range" + (help?.let { " · $it" } ?: ""))
        }
        FieldType.RATING_9 -> RatingInput(f, value, onChange, error)
        FieldType.BME -> ChoiceRow(f, value, bmeOptions.map { it.first to it.first }, onChange, error, caption = bmeOptions.joinToString(" · ") { "${it.first} = ${it.second}" })
        FieldType.BOOLEAN -> ChoiceRow(f, value, listOf("true" to "Yes", "false" to "No"), onChange, error, caption = help)
        FieldType.COMPETENCIES -> CompetencyInput(f, value, onChange, error, ctx)
        else -> TextInput(f.caption(), value, onChange, error = error, supporting = help)
    }
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun FieldLabel(f: FieldDef, error: String?) {
    Text(
        f.caption(),
        style = MaterialTheme.typography.bodyLarge,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RatingInput(f: FieldDef, value: String, onChange: (String) -> Unit, error: String?) {
    Column(Modifier.fillMaxWidth().testTag("rating:${f.key}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(f, error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..9).forEach { n ->
                FilterChip(
                    selected = value == "$n",
                    onClick = { onChange(if (value == "$n" && !f.required) "" else "$n") },
                    label = { Text("$n") },
                )
            }
        }
        val v = value.toIntOrNull()
        Text(
            if (v != null) "$v · ${ratingBand(v)}" else "1–3 Unsatisfactory · 4–6 Satisfactory · 7–9 Superior",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        f.help?.let { Help(it) }
        ErrorText(error)
    }
}

@Composable
private fun ChoiceRow(f: FieldDef, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, error: String?, caption: String?) {
    Column(Modifier.fillMaxWidth().testTag("choice:${f.key}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(f, error)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = value == key,
                    onClick = { onChange(if (value == key && !f.required) "" else key) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label) }
            }
        }
        caption?.let { Help(it) }
        ErrorText(error)
    }
}

@Composable
private fun CompetencyInput(f: FieldDef, value: String, onChange: (String) -> Unit, error: String?, ctx: FieldContext) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(f, error)
        if (selected.isEmpty()) Help("No competencies linked yet.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { code -> AssistChip(onClick = { open = true }, label = { Text(code) }) }
        }
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("competencies:${f.key}")) { Text(if (selected.isEmpty()) "Link competencies" else "Edit competencies") }
        f.help?.let { Help(it) }
        ErrorText(error)
    }
    if (open) {
        var working by remember { mutableStateOf(selected.toSet()) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Competencies") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ctx.reference.competencies.filter { it.active }, key = { it.code }) { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = c.code in working, onCheckedChange = { on -> working = if (on) working + c.code else working - c.code })
                            Column(Modifier.weight(1f)) {
                                Text(c.code, style = MaterialTheme.typography.labelLarge)
                                Text(c.title, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    val order = ctx.reference.competencies.map { it.code }
                    onChange(working.sortedBy { order.indexOf(it) }.joinToString(","))
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Read-only presentation of a field value. */
@Composable
fun FieldValue(f: FieldDef, value: String?, ctx: FieldContext) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(displayValue(f, value, ctx), style = MaterialTheme.typography.bodyLarge)
    }
}
