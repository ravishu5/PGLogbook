package org.mdpeds.logbook.domain.logic

import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Target
import org.mdpeds.logbook.domain.model.TargetProgress
import org.mdpeds.logbook.domain.model.Template

/**
 * Computes target progress from stored records only (mirrors v_submission_targets / v_target_progress).
 * A record counts at most once per target; NOT_STARTED placeholders never count.
 */
object TargetEngine {

    fun targetKeys(template: Template, data: Map<String, String>): Set<String> =
        template.targetRules.mapNotNull { rule ->
            val matches = rule.whenMatch?.all { (k, v) -> data[k] == v } ?: true
            if (!matches) null else rule.targetKey ?: rule.targetFromField?.let { data[it] }?.takeIf { it.isNotBlank() }
        }.toSet()

    fun progress(
        studentId: String,
        targets: List<Target>,
        templates: List<Template>,
        submissions: List<Submission>,
    ): List<TargetProgress> {
        val byCode = templates.associateBy { it.code }
        val hits = HashMap<String, MutableList<SubmissionStatus>>()
        for (s in submissions) {
            if (s.studentId != studentId || s.status == SubmissionStatus.NOT_STARTED) continue
            val tpl = byCode[s.templateCode] ?: continue
            for (key in targetKeys(tpl, s.studentData)) hits.getOrPut(key) { mutableListOf() }.add(s.status)
        }
        return targets.sortedBy { it.sortOrder }.map { t ->
            val st = hits[t.key].orEmpty()
            val verified = st.count { it.isVerified }
            TargetProgress(
                studentId = studentId,
                targetKey = t.key,
                category = t.category,
                label = t.label,
                targetValue = t.targetValue,
                targetVersion = t.version,
                sortOrder = t.sortOrder,
                recorded = st.size,
                submitted = st.count { it != SubmissionStatus.DRAFT && it != SubmissionStatus.RETURNED },
                verified = verified,
                verifiedPercent = percent(verified, t.targetValue),
            )
        }
    }

    fun percent(count: Int, target: Int): Int =
        if (target <= 0) 0 else minOf(100, Math.round(100.0 * count / target).toInt())
}
