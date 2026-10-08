package io.github.hematite.core

import java.io.File
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition

/** Rewrites relative links and images when a note moves to another folder. */
object Moves {
    private fun relativeUrl(uri: String, from: File, to: File): String? {
        if (uri.isEmpty() || uri.startsWith('/') || uri.startsWith('#') || ':' in uri.substringBefore('/')) return null
        val suffix = uri.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) uri.length else it }
        val path = Dav.decode(uri.substring(0, suffix))?.takeUnless { it.startsWith('/') } ?: return null
        val target = ArrayList(from.invariantSeparatorsPath.split('/').filter { it.isNotEmpty() })
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (target.isNotEmpty()) target.removeAt(target.lastIndex)
                else -> target += part
            }
        }
        val source = to.invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }
        val common = source.zip(target).takeWhile { (a, b) -> a == b }.size
        val relative = List(source.size - common) { ".." } + target.drop(common)
        return Dav.encode(relative.joinToString("/")) + uri.substring(suffix)
    }

    private fun destinationRange(source: String, from: Int): IntRange? {
        val start = from + source.length - from - source.substring(from).trimStart().length
        if (source.getOrNull(start) == '<') {
            val close = source.indexOf('>', start + 1).takeIf { it >= 0 } ?: return null
            return start + 1 until close
        }
        var depth = 0
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (escaped) { escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (char == '(') depth++
            if (char == ')') {
                if (depth == 0) return start until index
                depth--
            }
            if (char.isWhitespace()) return start until index
        }
        return start until source.length
    }

    private fun inlineDestination(source: String): IntRange? {
        val start = source.indexOf('[').takeIf { it >= 0 } ?: return null
        var depth = 1
        var escaped = false
        for (index in start + 1 until source.length) {
            val char = source[index]
            if (escaped) { escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (char == '[') depth++
            if (char == ']') {
                depth--
                if (depth == 0) {
                    val end = index + 1
                    return if (source.getOrNull(end) == '(') destinationRange(source, end + 1) else null
                }
            }
        }
        return null
    }

    internal fun linkChanges(text: String, from: File, to: File): List<Pair<IntRange, String>> {
        val changes = mutableListOf<Pair<IntRange, String>>()
        Markdown.walk(Markdown.syntaxTree(text)) { node ->
            val span = Markdown.span(node) ?: return@walk
            val source = text.substring(span.start, span.end)
            when (node) {
                is LinkReferenceDefinition -> {
                    val colon = source.indexOf("]:").takeIf { it >= 0 } ?: return@walk
                    val range = destinationRange(source, colon + 2) ?: return@walk
                    val uri = relativeUrl(node.destination, from, to) ?: return@walk
                    changes += (span.start + range.first..span.start + range.last) to uri
                }
                // Reference-style links are rewritten at their definition, not at each use.
                is Link, is Image -> {
                    val destination = inlineDestination(source) ?: return@walk
                    val uri = relativeUrl(if (node is Link) node.destination else (node as Image).destination, from, to) ?: return@walk
                    changes += (span.start + destination.first..span.start + destination.last) to uri
                }
                else -> Unit
            }
        }
        return changes.sortedBy { it.first.first }.distinctBy { it.first }
    }

    internal fun rewritten(text: String, changes: List<Pair<IntRange, String>>): String {
        val result = StringBuilder(text)
        for ((range, value) in changes.asReversed()) result.replace(range.first, range.last + 1, value)
        return result.toString()
    }

    /** `text` with its relative links rebased from folder `from` to folder `to`. */
    fun rebase(text: String, from: File, to: File) = rewritten(text, linkChanges(text, from, to))
}
