package org.mdpeds.logbook.domain.logic

import java.time.LocalDate

/**
 * 36-month programme timeline. Month n is always derived from the original start date
 * (no drift): a 31 Jan start gives month 2 = 28/29 Feb and month 3 = 31 Mar.
 * Mirrors app.month_start / month_end / programme_month / pg_year / completion_date.
 */
object ProgrammeTimeline {
    const val DEFAULT_DURATION = 36

    fun monthStart(start: LocalDate, month: Int): LocalDate = start.plusMonths((month - 1).toLong())

    fun monthEnd(start: LocalDate, month: Int): LocalDate = start.plusMonths(month.toLong()).minusDays(1)

    fun completionDate(start: LocalDate, durationMonths: Int = DEFAULT_DURATION): LocalDate =
        start.plusMonths(durationMonths.toLong()).minusDays(1)

    /** Largest n with monthStart(n) <= date, or 0 before the programme starts. */
    fun programmeMonth(start: LocalDate, on: LocalDate): Int {
        if (on.isBefore(start)) return 0
        val approx = (on.year - start.year) * 12 + (on.monthValue - start.monthValue)
        var n = maxOf(1, approx - 1)
        while (!monthStart(start, n + 1).isAfter(on)) n++
        while (n > 1 && monthStart(start, n).isAfter(on)) n--
        return n
    }

    /** Current month clamped to the programme duration (0 if not started). */
    fun currentMonth(start: LocalDate, on: LocalDate, durationMonths: Int = DEFAULT_DURATION): Int =
        minOf(programmeMonth(start, on), durationMonths)

    fun pgYear(month: Int): Int = if (month <= 0) 0 else minOf(3, (month - 1) / 12 + 1)

    fun isMonthOpen(start: LocalDate, month: Int, today: LocalDate, allowFuture: Boolean = false): Boolean =
        allowFuture || !today.isBefore(monthStart(start, month))

    fun isProgrammeComplete(start: LocalDate, today: LocalDate, durationMonths: Int = DEFAULT_DURATION): Boolean =
        !today.isBefore(completionDate(start, durationMonths))

    fun months(start: LocalDate, durationMonths: Int = DEFAULT_DURATION): List<ProgrammeMonth> =
        (1..durationMonths).map { ProgrammeMonth(it, pgYear(it), monthStart(start, it), monthEnd(start, it)) }
}

data class ProgrammeMonth(val number: Int, val pgYear: Int, val start: LocalDate, val end: LocalDate)
