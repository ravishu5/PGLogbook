package org.mdpeds.logbook.ui.richinput

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichInputTest {

    @Test
    fun testBuildMarkdownTableGeneratesValidMarkdown() {
        val headers = listOf("Test", "Result", "Normal")
        val rows = listOf(
            listOf("Hb", "12.5", "11.5-15.5"),
            listOf("TLC", "8200", "4000-11000"),
        )
        val table = buildMarkdownTable(headers, rows)
        val expected = """
            | Test | Result | Normal |
            | --- | --- | --- |
            | Hb | 12.5 | 11.5-15.5 |
            | TLC | 8200 | 4000-11000 |
        """.trimIndent()
        assertEquals(expected, table)
    }

    @Test
    fun testGenerateCustomTableConstraints() {
        val table = generateCustomTable(2, 3)
        assertTrue(table.contains("| Column 1 | Column 2 |"))
        assertTrue(table.contains("| --- | --- |"))
        val lines = table.lines()
        // 1 header + 1 delimiter + 3 data rows = 5 lines
        assertEquals(5, lines.size)
    }

    @Test
    fun testHasMarkdownDetection() {
        assertTrue(hasMarkdown("| Col 1 | Col 2 |"))
        assertTrue(hasMarkdown("**important clinical sign**"))
        assertTrue(hasMarkdown("*italic note*"))
        assertTrue(hasMarkdown("~~strikethrough~~"))
        assertTrue(hasMarkdown("### Heading 3"))
        assertTrue(hasMarkdown("`lab_value`"))
        assertTrue(hasMarkdown("> SBAR Handover"))
        assertTrue(hasMarkdown("- Bullet item"))
        assertTrue(hasMarkdown("1. First step"))
        assertFalse(hasMarkdown("Normal plain clinical note with no special formatting."))
    }

    @Test
    fun testParseMarkdownBlocks() {
        val raw = """
            # Case Summary

            > Important clinical caution

            - Fever x 3 days
            - Poor feeding

            1. Order CBC
            2. Start IV fluids

            | Parameter | Value |
            | --- | --- |
            | HR | 130 |
            | RR | 32 |

            ---
        """.trimIndent()

        val blocks = parseMarkdownBlocks(raw)
        assertEquals(8, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading && (blocks[0] as MarkdownBlock.Heading).level == 1)
        assertTrue(blocks[1] is MarkdownBlock.Blockquote)
        assertTrue(blocks[2] is MarkdownBlock.BulletItem)
        assertTrue(blocks[3] is MarkdownBlock.BulletItem)
        assertTrue(blocks[4] is MarkdownBlock.NumberedItem && (blocks[4] as MarkdownBlock.NumberedItem).number == "1.")
        assertTrue(blocks[5] is MarkdownBlock.NumberedItem && (blocks[5] as MarkdownBlock.NumberedItem).number == "2.")
        assertTrue(blocks[6] is MarkdownBlock.Table)
        assertTrue(blocks[7] is MarkdownBlock.Divider)

        val tableBlock = blocks[6] as MarkdownBlock.Table
        assertEquals(listOf("Parameter", "Value"), tableBlock.headers)
        assertEquals(2, tableBlock.rows.size)
        assertEquals(listOf("HR", "130"), tableBlock.rows[0])
        assertEquals(listOf("RR", "32"), tableBlock.rows[1])
    }

    @Test
    fun testParseInlineMarkdown() {
        val text = "Patient has **tachypnea** and *mild subcostal retractions* with `SpO2 94%`."
        val annotated = parseInlineMarkdown(text, Color.Blue)
        assertEquals(
            "Patient has tachypnea and mild subcostal retractions with  SpO2 94% .",
            annotated.text,
        )
        // Check that style spans were added
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun testClinicalTemplatesAndScoreTablesExist() {
        assertTrue(ClinicalTemplates.allTemplates.size >= 5)
        assertTrue(ClinicalTemplates.allTemplates.any { it.title.contains("SOAP") })
        assertTrue(ClinicalTemplates.allTemplates.any { it.title.contains("SBAR") })

        assertTrue(ClinicalReferenceTables.allTables.size >= 5)
        assertTrue(ClinicalReferenceTables.allTables.any { it.title.contains("APGAR") })
        assertTrue(ClinicalReferenceTables.allTables.any { it.title.contains("Vitals") })
        assertTrue(ClinicalReferenceTables.allTables.any { it.title.contains("Glasgow") })
    }

    @Test
    fun testTimestampAndSignatureStamps() {
        val ts = buildTimestampString()
        assertTrue(ts.contains("Recorded:"))
        assertTrue(ts.contains("IST"))

        val sig = buildSignatureStamp()
        assertTrue(sig.contains("Electronically Verified:"))
    }

    @Test
    fun testPresetTableMappings() {
        val presets = listOf(
            "Vitals Table",
            "APGAR Score",
            "Lab Investigations",
            "Medications",
            "Growth / Anthropometry",
        )
        presets.forEach { preset ->
            val tableMd = when (preset) {
                "Vitals Table" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Vitals") }?.tableMarkdown
                "APGAR Score" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("APGAR") }?.tableMarkdown
                "Lab Investigations" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Lab") }?.tableMarkdown
                "Medications" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Medication") }?.tableMarkdown
                "Growth / Anthropometry" -> ClinicalReferenceTables.allTables.firstOrNull { it.title.contains("Anthropometry") }?.tableMarkdown
                else -> null
            }
            assertTrue("Preset $preset should resolve to non-null table markdown", tableMd != null)
            assertTrue("Preset $preset markdown should contain table delimiter", tableMd!!.contains("---"))
        }
    }

    @Test
    fun testTableParsingWithFlexibleWhitespaceAndEmptyCells() {
        val raw = """
            | Item | Quantity | Note |
            | --- | --- | --- |
            | Amikacin | 15 mg/kg | IV OD |
            | | | |
        """.trimIndent()

        val blocks = parseMarkdownBlocks(raw)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Table)

        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(listOf("Item", "Quantity", "Note"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Amikacin", "15 mg/kg", "IV OD"), table.rows[0])
        assertEquals(listOf("", "", ""), table.rows[1])
    }

    @Test
    fun testInlineLinkAndAttachmentParsing() {
        val text = "Refer to [NRP Guidelines](https://nrp.org) and 📎 **[Attachment: CXR Report]**."
        val annotated = parseInlineMarkdown(text, Color.Blue)
        assertTrue(annotated.text.contains("Refer to NRP Guidelines and 📎 [Attachment: CXR Report]."))
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun testCasePresentationMandatoryAndRichFields() {
        val jsonText = java.io.File("../../reference-data/templates.json").readText()
        val ref = org.mdpeds.logbook.domain.logic.ReferenceData.parse(jsonText, """{"competencies":[]}""", """{"exposure":[],"procedures":[]}""", """{"documents":[]}""")
        val clinicalCase = ref.templates.first { it.code == "CLINICAL_CASE" }
        
        // 1. Verify Case presentation is mandatory
        val presField = clinicalCase.fields.first { it.key == "presentation" }
        assertTrue("Case presentation must be mandatory (required=true)", presField.required)
        assertEquals("Case presentation", presField.label)

        // 2. Verify presentation_detail exists right below presentation
        val presIndex = clinicalCase.fields.indexOfFirst { it.key == "presentation" }
        val detailField = clinicalCase.fields[presIndex + 1]
        assertEquals("presentation_detail", detailField.key)
        assertEquals("Presentation detail", detailField.label)
        assertEquals("MULTILINE", detailField.type)
        assertTrue("presentation_detail should be richText", detailField.richText)

        // 3. Verify key_learning is richText
        val keyLearning = clinicalCase.fields.first { it.key == "key_learning" }
        assertTrue("key_learning should be richText", keyLearning.richText)
    }
}
