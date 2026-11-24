package org.mdpeds.logbook.util

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.mdpeds.logbook.domain.model.Certificate
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders documents with Android's built-in PdfDocument (no third-party PDF library).
 * Signatures are rendered as authenticated electronic verification text — never as fabricated signature images.
 */
object PdfRenderer {
    private const val A4_W = 595
    private const val A4_H = 842
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH)

    private fun date(value: String?): String =
        value?.take(10)?.let { runCatching { LocalDate.parse(it).format(dateFmt) }.getOrNull() } ?: "—"

    private fun paint(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SERIF, when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
    }

    private fun layout(text: String, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(align).setLineSpacing(0f, 1.15f).build()

    private fun android.graphics.Canvas.draw(l: StaticLayout, x: Float, y: Float): Float {
        save()
        translate(x, y)
        l.draw(this)
        restore()
        return y + l.height
    }

    /** A4 landscape completion certificate built from the immutable issue snapshot. */
    fun certificate(cert: Certificate): ByteArray {
        val s = cert.snapshot
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_H, A4_W, 1).create())
        val c = page.canvas
        val w = A4_H
        val h = A4_W
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(0, 77, 64); strokeWidth = 3f }
        c.drawRect(18f, 18f, w - 18f, h - 18f, border)
        border.strokeWidth = 1f
        c.drawRect(26f, 26f, w - 26f, h - 26f, border)

        val inner = w - 120
        var y = 50f
        y = c.draw(layout(s.institutionName.orEmpty(), paint(20f, bold = true), inner), 60f, y)
        y = c.draw(layout(listOfNotNull(s.departmentName, s.universityName).joinToString(" · "), paint(12f), inner), 60f, y + 2)
        y = c.draw(layout("CERTIFICATE OF COMPLETION OF TRAINING", paint(22f, bold = true, color = Color.rgb(0, 77, 64)), inner), 60f, y + 22)
        y = c.draw(layout(s.course ?: "MD Pediatrics", paint(13f, italic = true), inner), 60f, y + 4)

        val body = s.statement ?: (
            "This is to certify that Dr. ${s.studentName.orEmpty()}" +
                (s.universityRegNo?.let { ", University Registration No. $it" } ?: "") +
                ", has completed the postgraduate training programme in ${s.course ?: "MD Pediatrics"}" +
                " from ${date(s.programmeStartDate)} to ${date(s.programmeCompletionDate)}, and has maintained the competency logbook" +
                " with ${s.competenciesApproved ?: 0} competencies signed off by the faculty of the department."
            )
        y = c.draw(layout(body, paint(13.5f), inner - 60), 90f, y + 26)

        val details = listOfNotNull(
            s.batch?.let { "Batch: $it" },
            s.guideName?.let { "Guide: $it" },
            "Certificate No.: ${s.certificateNo ?: cert.certificateNo}",
            "Date of issue: ${date(s.issueDate ?: cert.issuedAt)}",
        ).joinToString("    ")
        y = c.draw(layout(details, paint(11f), inner), 60f, y + 18)

        // Signatory blocks: name + verification statement, evenly spaced along the bottom.
        val sigs = s.signatories.ifEmpty { listOf(org.mdpeds.logbook.domain.model.Signatory("Head of Department", cert.issuedByName, null)) }
        val colW = (w - 120) / sigs.size
        val sigTop = h - 150f
        sigs.forEachIndexed { i, sig ->
            val x = 60f + i * colW
            val line = Paint().apply { color = Color.DKGRAY; strokeWidth = 0.8f }
            c.drawLine(x + 20, sigTop, x + colW - 20, sigTop, line)
            var yy = c.draw(layout(sig.name ?: "—", paint(11.5f, bold = true), colW - 20), x + 10, sigTop + 6)
            yy = c.draw(layout(sig.role, paint(10.5f), colW - 20), x + 10, yy)
            c.draw(layout(sig.verification ?: "Signature pending", paint(8.5f, italic = true, color = Color.DKGRAY), colW - 20), x + 10, yy + 2)
        }
        val foot = s.disclaimer ?: "Electronically generated from the departmental PG logbook. Verification entries are authenticated user actions recorded in the audit trail."
        c.draw(layout(foot, paint(8f, italic = true, color = Color.GRAY), inner), 60f, h - 58f)

        doc.finishPage(page)
        return doc.toBytes()
    }

    /** Simple multi-page A4 portrait table report. */
    fun table(title: String, subtitle: String, headers: List<String>, rows: List<List<String>>): ByteArray {
        val doc = PdfDocument()
        val margin = 36f
        val usable = A4_W - 2 * margin
        val colW = usable / headers.size
        val cell = paint(8.5f)
        val head = paint(8.5f, bold = true)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let(doc::finishPage)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNo).create())
            val c = page!!.canvas
            y = margin
            if (pageNo == 1) {
                y = c.draw(layout(title, paint(15f, bold = true), usable.toInt(), Layout.Alignment.ALIGN_NORMAL), margin, y)
                y = c.draw(layout(subtitle, paint(9.5f, color = Color.DKGRAY), usable.toInt(), Layout.Alignment.ALIGN_NORMAL), margin, y + 2) + 8
            }
            var maxH = 0
            headers.forEachIndexed { i, hd ->
                val l = layout(hd, head, colW.toInt() - 4, Layout.Alignment.ALIGN_NORMAL)
                c.draw(l, margin + i * colW + 2, y)
                maxH = maxOf(maxH, l.height)
            }
            y += maxH + 3
            c.drawLine(margin, y, A4_W - margin, y, Paint().apply { strokeWidth = 0.8f })
            y += 3
            c.drawText("Page $pageNo", A4_W - margin - 40, A4_H - 18f, paint(8f, color = Color.GRAY))
        }

        newPage()
        rows.forEach { r ->
            val layouts = headers.indices.map { i -> layout(r.getOrElse(i) { "" }, cell, colW.toInt() - 4, Layout.Alignment.ALIGN_NORMAL) }
            val rowH = layouts.maxOf { it.height } + 4
            if (y + rowH > A4_H - margin) newPage()
            val c = page!!.canvas
            layouts.forEachIndexed { i, l -> c.draw(l, margin + i * colW + 2, y) }
            y += rowH
            c.drawLine(margin, y - 2, A4_W - margin, y - 2, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.4f })
        }
        page?.let(doc::finishPage)
        return doc.toBytes()
    }

    private fun PdfDocument.toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(out)
        close()
        return out.toByteArray()
    }
}
