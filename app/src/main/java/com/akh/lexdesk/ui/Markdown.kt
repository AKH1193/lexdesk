package com.akh.lexdesk.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*]\\s+(.*)$")

/** Light Markdown rendering: headings, bullets and **bold**. Enough for legal drafts on a phone. */
fun markdownToAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { index, line ->
        val heading = headingRegex.find(line)
        val bullet = bulletRegex.find(line)
        when {
            heading != null -> {
                val size = if (heading.groupValues[1].length <= 2) 18.sp else 16.sp
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
                    append(heading.groupValues[2].replace("**", ""))
                }
            }
            bullet != null -> {
                append(bullet.groupValues[1])
                append("• ")
                appendInline(bullet.groupValues[2])
            }
            line.trim().matches(Regex("^[-*_]{3,}$")) -> append("")
            else -> appendInline(line)
        }
        if (index < lines.lastIndex) append("\n")
    }
}

private fun AnnotatedString.Builder.appendInline(s: String) {
    s.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}
