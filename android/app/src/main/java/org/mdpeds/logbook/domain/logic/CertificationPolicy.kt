package org.mdpeds.logbook.domain.logic

import org.mdpeds.logbook.domain.model.CertificationState
import org.mdpeds.logbook.domain.model.CertificationStatus
import java.time.LocalDate

/** Mirrors app.certification_state: the certificate unlocks only after all prerequisites are met. */
object CertificationPolicy {

    fun evaluate(
        approved: Int,
        total: Int,
        completionDate: LocalDate?,
        today: LocalDate,
        requireProgrammeCompletion: Boolean,
        issuedCertificateId: String? = null,
        issuedCertificateNo: String? = null,
    ): CertificationState {
        val complete = completionDate != null && !today.isBefore(completionDate)
        val reasons = buildList {
            if (approved < total) add("${total - approved} of $total final competency sign-offs pending")
            if (requireProgrammeCompletion && !complete) add("Programme completes on ${completionDate?.toString() ?: "an unconfigured date"}")
        }
        val status = when {
            issuedCertificateId != null -> CertificationStatus.CERTIFICATE_ISSUED
            requireProgrammeCompletion && !complete -> CertificationStatus.NOT_ELIGIBLE
            approved < total || total == 0 -> CertificationStatus.AWAITING_FINAL_SIGNOFF
            else -> CertificationStatus.READY_FOR_CERTIFICATION
        }
        return CertificationState(
            status = status,
            approved = approved,
            total = total,
            programmeComplete = complete,
            completionDate = completionDate?.toString(),
            reasons = reasons,
            certificateId = issuedCertificateId,
            certificateNo = issuedCertificateNo,
        )
    }

    fun certificateNumber(prefix: String, year: Int, sequence: Int) = "$prefix/$year/${sequence.toString().padStart(5, '0')}"
}
