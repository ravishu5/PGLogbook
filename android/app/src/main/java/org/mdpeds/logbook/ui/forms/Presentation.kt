package org.mdpeds.logbook.ui.forms

import org.mdpeds.logbook.domain.logic.OptionsResolver
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.model.FieldDef
import org.mdpeds.logbook.domain.model.FieldType
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.TemplateCategory
import org.mdpeds.logbook.ui.common.formatDate

/** Everything needed to render or label field values. */
data class FieldContext(
    val reference: ReferenceData,
    /** Faculty that may be chosen for FACULTY fields (id → name). */
    val facultyOptions: List<Pair<String, String>> = emptyList(),
    /** All known staff names for display (id → name). */
    val staffNames: Map<String, String> = emptyMap(),
)

fun ratingBand(v: Int): String = when (v) {
    in 1..3 -> "Unsatisfactory"
    in 4..6 -> "Satisfactory"
    in 7..9 -> "Superior"
    else -> ""
}

val bmeOptions = listOf("B" to "Below expectations", "M" to "Meets expectations", "E" to "Exceeds expectations")

fun displayValue(f: FieldDef, value: String?, ctx: FieldContext): String {
    if (value.isNullOrBlank()) return "—"
    return when (f.type) {
        FieldType.DATE -> formatDate(value)
        FieldType.SELECT -> OptionsResolver.label(f, value, ctx.reference.roleOptions, ctx.reference.targets) ?: value
        FieldType.FACULTY -> ctx.staffNames[value] ?: ctx.facultyOptions.firstOrNull { it.first == value }?.second ?: "Assigned faculty"
        FieldType.BOOLEAN -> if (value == "true") "Yes" else if (value == "false") "No" else value
        FieldType.BME -> bmeOptions.firstOrNull { it.first == value }?.second ?: value
        FieldType.RATING_9 -> value.toIntOrNull()?.let { "$it / 9 · ${ratingBand(it)}" } ?: value
        FieldType.COMPETENCIES -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { code ->
            val c = ctx.reference.competencies.firstOrNull { it.code == code }
            if (c != null) "$code — ${c.title}" else code
        }
        else -> value
    }
}

fun slotLabel(t: Template, slot: Int): String = t.slots.getOrNull(slot - 1) ?: "${t.title} $slot"

/** Card title for a record, following the template's title field (e.g. diagnosis, procedure, topic). */
fun recordTitle(t: Template, s: Submission, ref: ReferenceData): String {
    val data = s.allData
    return when {
        t.category == TemplateCategory.MONTHLY -> "Month ${s.periodNo ?: "?"}" + (data["posting_unit"]?.let { " · $it" } ?: "")
        t.category == TemplateCategory.ANNUAL -> "PG Year ${s.periodNo ?: "?"} annual review"
        else -> {
            val tf = t.titleField?.let { key -> t.fields.firstOrNull { it.key == key } }
            val v = tf?.let { f -> data[f.key]?.takeIf { it.isNotBlank() }?.let { displayValue(f, it, FieldContext(ref)) } }
            v ?: s.slotNo?.let { slotLabel(t, it) } ?: "Untitled ${t.title.lowercase()}"
        }
    }
}

fun recordSubtitle(t: Template, s: Submission, ref: ReferenceData): String {
    val data = s.allData
    val ctx = FieldContext(ref)
    val parts = t.subtitleFields.mapNotNull { key ->
        val f = t.fields.firstOrNull { it.key == key } ?: return@mapNotNull null
        data[key]?.takeIf { it.isNotBlank() }?.let { displayValue(f, it, ctx).lineSequence().first() }
    }
    val date = s.entryDate?.let { formatDate(it) }
    return (listOfNotNull(date) + parts).joinToString(" · ")
}

/** Categories shown on the student's Logs and Forms tabs. */
fun categoriesFor(group: String): List<String> = when (group) {
    "LOG" -> listOf(TemplateCategory.LOG)
    "FORM" -> listOf(TemplateCategory.FORM, TemplateCategory.REFLECTION)
    else -> listOf(group)
}

fun groupTitle(group: String): String = when (group) {
    "LOG" -> "Clinical logs"
    "FORM" -> "Assessments & forms"
    else -> group
}
