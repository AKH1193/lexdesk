package com.akh.lexdesk.util

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a reply as a Word document: A4, 1 inch margins, Times New Roman 12,
 * justified, 1.5 line spacing. Markdown headings become bold, ** becomes bold.
 */
object DocxExporter {

    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun write(out: OutputStream, text: String) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("word/_rels/document.xml.rels", DOCUMENT_RELS)
            put("word/styles.xml", STYLES)
            put("word/document.xml", document(text))
        }
    }

    private fun document(text: String): String {
        val body = text.lines()
            .filter { it.isNotBlank() && !it.trim().matches(Regex("^[-*_]{3,}$")) }
            .joinToString("") { paragraph(it) }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
            body +
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
            """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>""" +
            """</w:sectPr></w:body></w:document>"""
    }

    private fun paragraph(raw: String): String {
        var line = raw.trimEnd()
        var headingLevel = 0
        var isBullet = false

        Regex("^(#{1,6})\\s+").find(line)?.let {
            headingLevel = it.groupValues[1].length
            line = line.substring(it.range.last + 1)
        }
        if (headingLevel == 0) {
            Regex("^\\s*[-*]\\s+").find(line)?.let {
                isBullet = true
                line = "•  " + line.substring(it.range.last + 1)
            }
        }

        val pPr = when {
            headingLevel == 1 -> "<w:pPr><w:jc w:val=\"center\"/></w:pPr>"
            headingLevel > 1 -> "<w:pPr><w:keepNext/><w:jc w:val=\"left\"/></w:pPr>"
            isBullet -> "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>"
            else -> ""
        }

        val runs = line.split("**").mapIndexed { i, part ->
            if (part.isEmpty()) "" else run(part, bold = headingLevel > 0 || i % 2 == 1)
        }.joinToString("")

        return "<w:p>$pPr$runs</w:p>"
    }

    private fun run(text: String, bold: Boolean): String {
        val rPr = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        return "<w:r>$rPr<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """</Types>"""

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""

    private const val DOCUMENT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr>""" +
            """<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman" w:cs="Times New Roman"/>""" +
            """<w:sz w:val="24"/><w:szCs w:val="24"/><w:lang w:val="en-IN"/>""" +
            """</w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="360" w:lineRule="auto"/><w:jc w:val="both"/></w:pPr></w:pPrDefault>""" +
            """</w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            """</w:styles>"""
}
