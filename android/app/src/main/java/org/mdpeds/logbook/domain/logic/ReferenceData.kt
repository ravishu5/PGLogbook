package org.mdpeds.logbook.domain.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.mdpeds.logbook.domain.model.AcademicDocument
import org.mdpeds.logbook.domain.model.CompetenciesFile
import org.mdpeds.logbook.domain.model.Competency
import org.mdpeds.logbook.domain.model.DocumentsFile
import org.mdpeds.logbook.domain.model.FieldDef
import org.mdpeds.logbook.domain.model.Option
import org.mdpeds.logbook.domain.model.Target
import org.mdpeds.logbook.domain.model.TargetCategory
import org.mdpeds.logbook.domain.model.TargetsFile
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.TemplatesFile

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/** Institutional reference data bundled from /reference-data (the same files seed the database). */
data class ReferenceData(
    val templates: List<Template>,
    val roleOptions: List<Option>,
    val competencies: List<Competency>,
    val targets: List<Target>,
    val targetsDisclaimer: String?,
    val documents: List<AcademicDocument>,
) {
    fun template(code: String): Template? = templates.firstOrNull { it.code == code }

    /** Suggested monthly priorities from the rotation plan appendix (month range → priority text). */
    val monthlyPriorities: List<MonthlyPriority> by lazy { monthlyPrioritiesOf(documents) }

    companion object {
        fun parse(templatesJson: String, competenciesJson: String, targetsJson: String, documentsJson: String): ReferenceData {
            val t = AppJson.decodeFromString(TemplatesFile.serializer(), templatesJson)
            val c = AppJson.decodeFromString(CompetenciesFile.serializer(), competenciesJson)
            val g = AppJson.decodeFromString(TargetsFile.serializer(), targetsJson)
            val d = AppJson.decodeFromString(DocumentsFile.serializer(), documentsJson)
            val targets = g.exposure.mapIndexed { i, x ->
                Target(x.key, TargetCategory.EXPOSURE, x.label, x.target, i + 1, 1, notesOf(x.distribution, x.documentation, x.levelRemarks))
            } + g.procedures.mapIndexed { i, x ->
                Target(x.key, TargetCategory.PROCEDURE, x.label, x.target, 100 + i + 1, 1, notesOf(x.distribution, x.documentation, x.levelRemarks))
            }
            return ReferenceData(
                templates = t.templates.sortedBy { it.sortOrder },
                roleOptions = t.roleOptions,
                competencies = c.competencies.mapIndexed { i, x -> x.copy(sortOrder = i + 1) },
                targets = targets,
                targetsDisclaimer = g.disclaimer,
                documents = d.documents.sortedBy { it.sortOrder }.map {
                    AcademicDocument(it.code, it.title, it.description, it.sortOrder, 1, it.content, null, null, null, "Initial institutional version")
                },
            )
        }

        private fun notesOf(distribution: String?, documentation: String?, levelRemarks: String?): String? =
            listOfNotNull(
                distribution?.let { "Distribution: $it" },
                documentation?.let { "Documentation: $it" },
                levelRemarks?.let { "Level/remarks: $it" },
            ).joinToString("; ").ifEmpty { null }

        fun monthlyPrioritiesOf(documents: List<AcademicDocument>): List<MonthlyPriority> {
            val content = documents.firstOrNull { it.code == "ROTATION_PLAN" }?.content ?: return emptyList()
            val arr = content["monthlyPriorities"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val from = o["fromMonth"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val to = o["toMonth"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                MonthlyPriority(o["year"]?.jsonPrimitive?.content.orEmpty(), from, to, o["priority"]?.jsonPrimitive?.content.orEmpty())
            }
        }
    }
}

data class MonthlyPriority(val year: String, val fromMonth: Int, val toMonth: Int, val priority: String) {
    fun covers(month: Int) = month in fromMonth..toMonth
}

/** Resolves SELECT options including dynamic option sources. */
object OptionsResolver {
    const val OTHER = "OTHER"

    fun options(field: FieldDef, roleOptions: List<Option>, targets: List<Target>): List<Option> = when {
        field.options != null -> field.options
        field.optionsSource == "ROLE" -> roleOptions
        field.optionsSource == "PROCEDURE_TARGETS" ->
            targets.filter { it.category == TargetCategory.PROCEDURE }.sortedBy { it.sortOrder }.map { Option(it.key, it.label) } +
                Option(OTHER, "Other (specify)")
        else -> emptyList()
    }

    fun label(field: FieldDef, value: String?, roleOptions: List<Option>, targets: List<Target>): String? {
        if (value.isNullOrBlank()) return null
        return options(field, roleOptions, targets).firstOrNull { it.value == value }?.label ?: value
    }
}

fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()

fun JsonObject.objectList(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

@Suppress("unused")
internal fun JsonObject.arrayOfArrays(key: String): List<List<String>> =
    (this[key] as? JsonArray)?.map { row -> row.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList()

@Suppress("unused")
internal fun JsonObject.child(key: String): JsonObject? = this[key]?.jsonObject
