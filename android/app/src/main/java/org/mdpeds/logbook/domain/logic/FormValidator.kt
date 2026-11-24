package org.mdpeds.logbook.domain.logic

import org.mdpeds.logbook.domain.model.FieldDef
import org.mdpeds.logbook.domain.model.FieldType
import org.mdpeds.logbook.domain.model.Template
import java.time.LocalDate

/** Client-side mirror of app.validate_fields: the server re-validates every submission. */
object FormValidator {

    fun wordCount(text: String?): Int =
        if (text.isNullOrBlank()) 0 else text.trim().split(Regex("\\s+")).size

    fun isVisible(field: FieldDef, data: Map<String, String>): Boolean =
        field.visibleWhen?.all { (k, v) -> data[k] == v } ?: true

    /** Returns field key → error message for the fields owned by [owner]. */
    fun validate(template: Template, owner: String, data: Map<String, String>): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for (f in template.fieldsFor(owner)) {
            val error = validateField(f, data)
            if (error != null) errors[f.key] = error
        }
        return errors
    }

    fun validateField(f: FieldDef, data: Map<String, String>): String? {
        if (f.type == FieldType.ATTACHMENT) return null
        if (!isVisible(f, data)) return null
        val v = data[f.key]?.trim().orEmpty()
        if (v.isEmpty()) return if (f.required) "${f.label} is required" else null
        return when (f.type) {
            FieldType.RATING_9 -> if (!Regex("^[1-9]$").matches(v)) "${f.label} must be between 1 and 9" else null
            FieldType.BME -> if (v !in setOf("B", "M", "E")) "${f.label} must be B, M or E" else null
            FieldType.DATE -> if (runCatching { LocalDate.parse(v) }.isFailure) "${f.label} must be a valid date" else null
            FieldType.BOOLEAN -> if (v != "true" && v != "false") "${f.label} must be yes or no" else null
            FieldType.WORDS -> {
                val n = wordCount(v)
                when {
                    f.minWords != null && n < f.minWords -> "${f.label} needs at least ${f.minWords} words (currently $n)"
                    f.maxWords != null && n > f.maxWords -> "${f.label} allows at most ${f.maxWords} words (currently $n)"
                    else -> null
                }
            }
            FieldType.SELECT -> {
                val opts = f.options
                if (opts != null && opts.none { it.value == v }) "${f.label} has an invalid value" else null
            }
            else -> null
        }
    }

    /** Removes values of fields hidden by visibleWhen and keys not owned by [owner]. */
    fun sanitize(template: Template, owner: String, data: Map<String, String>): Map<String, String> {
        val owned = template.fieldsFor(owner).associateBy { it.key }
        return data.filterKeys { it in owned }
    }
}
