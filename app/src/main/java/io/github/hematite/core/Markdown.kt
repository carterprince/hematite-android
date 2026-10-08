package io.github.hematite.core

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** A range of UTF-16 offsets in a note, end exclusive. */
data class Span(val start: Int, val end: Int) {
    operator fun contains(offset: Int) = offset in start until end
    val isEmpty get() = start >= end
    fun overlaps(other: Span) = start < other.end && other.start < end
}

enum class Style { Bold, Italic, Strikethrough, Heading1, Heading2, Heading3, Heading4 }

data class Styled(val range: Span, val style: Style)
data class MarkdownLink(val label: Span, val source: Span, val uri: String)

/** Formatted text inside a table cell. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strikethrough: Boolean = false)

enum class Alignment { None, Left, Center, Right }
data class Row(val range: Span, val cells: List<List<Run>>, val header: Boolean)
data class Table(val range: Span, val alignments: List<Alignment>, val rows: List<Row>)

/** Where Markdown formatting is in a note, so the editor can style it and hide its markers. */
class MarkdownDocument {
    val styles = mutableListOf<Styled>()
    /** Source characters hidden except on the line being edited. */
    val markers = mutableListOf<Span>()
    /** Offsets of `-` and `*` bullet markers. */
    val bullets = mutableListOf<Int>()
    /** Offsets of task markers (`- [ ]`) and whether each is checked. */
    val tasks = mutableListOf<Pair<Int, Boolean>>()
    val links = mutableListOf<MarkdownLink>()
    /** Image sources and their destinations. */
    val images = mutableListOf<Pair<Span, String>>()
    val tables = mutableListOf<Table>()
}

object Markdown {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    fun syntaxTree(text: String): Node = parser.parse(text)

    fun span(node: Node): Span? {
        val spans = node.sourceSpans
        if (spans.isEmpty()) return null
        return Span(spans.first().inputIndex, spans.last().let { it.inputIndex + it.length })
    }

    fun supportedUri(uri: String): Boolean {
        val colon = uri.indexOf(':')
        if (colon < 0 || colon == uri.length - 1) return false
        return uri.substring(0, colon).lowercase() in setOf("http", "https", "mailto")
    }

    fun walk(node: Node, visit: (Node) -> Unit) {
        visit(node)
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            walk(child, visit)
            child = next
        }
    }

    private fun cell(node: Node): List<Run> {
        val runs = mutableListOf<Run>()
        fun visit(node: Node, bold: Boolean, italic: Boolean, strike: Boolean) {
            when (node) {
                is Text -> runs += Run(node.literal, bold, italic, strike)
                is Code -> runs += Run(node.literal, bold, italic, strike)
                is SoftLineBreak, is HardLineBreak -> runs += Run("\n", bold, italic, strike)
                else -> {
                    val b = bold || node is StrongEmphasis
                    val i = italic || node is Emphasis
                    val s = strike || node is Strikethrough
                    var child = node.firstChild
                    while (child != null) { visit(child, b, i, s); child = child.next }
                }
            }
        }
        visit(node, false, false, false)
        return runs
    }

    fun parse(text: String): MarkdownDocument {
        val document = MarkdownDocument()
        val protected = mutableListOf<Span>()
        walk(syntaxTree(text)) { node ->
            val range = span(node) ?: return@walk
            when (node) {
                is TableBlock -> {
                    protected += range
                    val alignments = mutableListOf<Alignment>()
                    val rows = mutableListOf<Row>()
                    walk(node) { part ->
                        if (part !is TableRow) return@walk
                        val source = span(part) ?: return@walk
                        val line = text.substring(source.start, source.end).trimEnd('\r', '\n')
                        val cells = mutableListOf<List<Run>>()
                        var child = part.firstChild
                        while (child != null) {
                            if (child is TableCell) {
                                cells += cell(child)
                                if (rows.isEmpty()) alignments += when (child.alignment) {
                                    TableCell.Alignment.LEFT -> Alignment.Left
                                    TableCell.Alignment.CENTER -> Alignment.Center
                                    TableCell.Alignment.RIGHT -> Alignment.Right
                                    else -> Alignment.None
                                }
                            }
                            child = child.next
                        }
                        rows += Row(Span(source.start, source.start + line.length), cells, part.parent is TableHead)
                    }
                    document.tables += Table(range, alignments, rows)
                }
                is Heading -> {
                    val source = text.substring(range.start, range.end).trimEnd('\r', '\n')
                    val line = source.trimStart(' ')
                    val width = line.takeWhile { it == '#' }.length
                    if (node.level <= 4 && width == node.level) {
                        val start = range.start + source.length - line.length
                        val prefix = width + line.length - width - line.substring(width).trimStart(' ', '\t').length
                        document.markers += Span(start, start + prefix)
                        document.styles += Styled(Span(range.start, range.start + source.length), Style.entries[2 + node.level])
                    }
                }
                is Image -> {
                    document.images += range to node.destination
                    protected += range
                }
                is Emphasis, is StrongEmphasis, is Strikethrough -> {
                    val strong = node is StrongEmphasis
                    val strike = node is Strikethrough
                    val width = if (strong || strike) 2 else 1
                    val source = text.substring(range.start, range.end)
                    if (source.length >= width * 2 && (source.startsWith('*') || source.startsWith('_') || source.startsWith("~~"))) {
                        document.markers += Span(range.start, range.start + width)
                        document.markers += Span(range.end - width, range.end)
                        document.styles += Styled(Span(range.start + width, range.end - width),
                            if (strike) Style.Strikethrough else if (strong) Style.Bold else Style.Italic)
                    }
                }
                is ListItem -> {
                    val line = text.substring(range.start, range.end).substringBefore('\n')
                    val indent = line.length - line.trimStart(' ', '\t').length
                    val marker = range.start + indent
                    val rest = line.substring(indent)
                    if (listOf("- ", "-\t", "* ", "*\t").any { rest.startsWith(it) }) {
                        if (rest.length >= 6 && rest[2] == '[' && rest[4] == ']' && rest[3] in " xX" && rest[5] in " \t") {
                            document.markers += Span(marker, marker + 5)
                            document.tasks += marker to (rest[3] != ' ')
                        } else {
                            document.markers += Span(marker, marker + 1)
                            document.bullets += marker
                        }
                    }
                }
                is Link -> {
                    protected += range
                    var label: Span? = null
                    var child = node.firstChild
                    while (child != null) {
                        if (child is Text || child is Code || child is Emphasis || child is StrongEmphasis || child is Strikethrough || child is Image) {
                            span(child)?.let { label = label?.let { old -> Span(minOf(old.start, it.start), maxOf(old.end, it.end)) } ?: it }
                        }
                        child = child.next
                    }
                    val labelSpan = label
                    if (labelSpan != null && supportedUri(node.destination)) {
                        if (range.start < labelSpan.start) document.markers += Span(range.start, labelSpan.start)
                        if (labelSpan.end < range.end) document.markers += Span(labelSpan.end, range.end)
                        document.links += MarkdownLink(labelSpan, range, node.destination)
                    }
                }
                is Code, is FencedCodeBlock, is IndentedCodeBlock, is HtmlBlock, is HtmlInline -> protected += range
                is LinkReferenceDefinition -> Unit
                else -> Unit
            }
        }

        // CommonMark parses explicit links; also recognize familiar bare web URLs.
        var consumed = 0
        var start = 0
        while (start < text.length) {
            val index = start
            start++
            if (index < consumed || text[index] != 'h' && text[index] != 'H') continue
            val rest = text.substring(index)
            if (!rest.startsWith("http://", true) && !rest.startsWith("https://", true)) continue
            if (index > 0) {
                val previous = Character.codePointBefore(text, index)
                if (Character.isLetterOrDigit(previous) || previous == '_'.code) continue
            }
            val length = rest.indexOfFirst { it.isWhitespace() || it in "<>\"`" }.let { if (it < 0) rest.length else it }
            var uri = rest.substring(0, length).trimEnd('.', ',', ';', ':', '!', '?')
            while (uri.endsWith(')') && uri.count { it == ')' } > uri.count { it == '(' }) uri = uri.dropLast(1)
            for ((open, close) in listOf('[' to ']', '{' to '}')) {
                while (uri.endsWith(close) && uri.count { it == close } > uri.count { it == open }) uri = uri.dropLast(1)
            }
            val range = Span(index, index + uri.length)
            consumed = range.end
            if (protected.any { it.overlaps(range) }) continue
            document.links += MarkdownLink(range, range, uri)
        }
        return document
    }
}
