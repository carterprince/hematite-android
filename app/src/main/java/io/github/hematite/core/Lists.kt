package io.github.hematite.core

/** A replacement of the note's text and where the cursor goes afterwards. */
data class Edit(val text: String, val cursor: Int)

/** Continues Markdown lists on Enter and indents them on Tab, like the desktop editor. */
object Lists {
    /** The length of the line's list prefix and the prefix for the next item. */
    internal fun continuation(line: String): Pair<Int, String>? {
        val indent = line.length - line.trimStart(' ', '\t').length
        val rest = line.substring(indent)
        val (markerEnd, marker) = if (rest.startsWith("- ") || rest.startsWith("* ")) {
            if (rest.length >= 6 && rest[2] == '[' && rest[3] in " xX" && rest[4] == ']' && rest[5] == ' ') 6 to "${rest.substring(0, 2)}[ ] "
            else 2 to rest.substring(0, 2)
        } else {
            val digits = rest.takeWhile { it in '0'..'9' }.length
            if (digits == 0 || !rest.substring(digits).startsWith(". ")) return null
            val number = rest.substring(0, digits).toULongOrNull()?.takeIf { it < ULong.MAX_VALUE } ?: return null
            digits + 2 to "${number + 1u}. "
        }
        return indent + markerEnd to line.substring(0, indent) + marker
    }

    private fun lineBounds(text: String, cursor: Int): Pair<Int, Int> {
        val start = text.lastIndexOf('\n', cursor - 1) + 1
        val end = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        return start to end
    }

    /** Handles Enter at `cursor` in a list item: starts the next item, or ends the list on an empty item. */
    fun enter(text: String, cursor: Int): Edit? {
        val (start, end) = lineBounds(text, cursor)
        val line = text.substring(start, end)
        val (prefix, next) = continuation(line) ?: return null
        if (cursor - start < prefix) return null
        return if (line.substring(prefix).isBlank()) Edit(text.substring(0, start) + text.substring(end), start)
        else Edit(text.substring(0, cursor) + "\n" + next + text.substring(cursor), cursor + 1 + next.length)
    }

    internal fun tabPrefix(line: String, preceding: String, outdent: Boolean): Pair<Int, String>? {
        val (prefix, _) = continuation(line) ?: return null
        val indent = line.length - line.trimStart(' ', '\t').length
        if (outdent) {
            if (indent == 0) return null
            for (previous in preceding.lines().reversed()) {
                val previousIndent = previous.length - previous.trimStart(' ', '\t').length
                if (previous.isBlank() || previousIndent >= indent) continue
                continuation(previous)?.let { (_, next) -> return prefix to next }
                break
            }
            val removed = if (line.startsWith('\t')) 1 else minOf(indent, 2)
            return prefix to line.substring(removed, prefix)
        }
        var width = 2
        for (previous in preceding.lines().reversed().filter { it.isNotBlank() }) {
            val previousIndent = previous.length - previous.trimStart(' ', '\t').length
            if (previousIndent > indent) continue
            if (previousIndent == indent) continuation(previous)?.let { (previousPrefix, _) -> width = previousPrefix - previousIndent }
            break
        }
        val marker = if (line.substring(indent).startsWith('*')) "* " else "- "
        return prefix to line.substring(0, indent) + " ".repeat(width) + marker
    }

    /** Nests the list item at `cursor` under the previous one, or moves it out a level. */
    fun tab(text: String, cursor: Int, outdent: Boolean): Edit? {
        val (start, end) = lineBounds(text, cursor)
        val line = text.substring(start, end)
        val (prefix, replacement) = tabPrefix(line, text.substring(0, start), outdent) ?: return null
        if (cursor - start < prefix) return null
        return Edit(text.substring(0, start) + replacement + text.substring(start + prefix), cursor - prefix + replacement.length)
    }
}

/** A replacement of the note's text with the selection afterwards. */
data class Formatted(val text: String, val start: Int, val end: Int)

object Formatting {
    /** Wraps the selection in `marker` (`**` or `*`), or unwraps it when already wrapped, like Ctrl+B/Ctrl+I on the desktop. */
    fun toggle(text: String, selectionStart: Int, selectionEnd: Int, marker: String): Formatted {
        var begin = minOf(selectionStart, selectionEnd)
        var finish = maxOf(selectionStart, selectionEnd)
        val selected = text.substring(begin, finish)
        val width = marker.length
        val replacement: String
        val start: Int
        val length: Int
        if (selected.length >= width * 2 && selected.startsWith(marker) && selected.endsWith(marker)) {
            replacement = selected.substring(width, selected.length - width)
            start = begin
            length = replacement.length
        } else if (begin >= width && finish + width <= text.length && text.substring(begin - width, begin) == marker &&
            text.substring(finish, finish + width) == marker) {
            begin -= width
            finish += width
            replacement = selected
            start = begin
            length = selected.length
        } else {
            replacement = marker + selected + marker
            start = begin + width
            length = selected.length
        }
        return Formatted(text.substring(0, begin) + replacement + text.substring(finish), start, start + length)
    }

    private val taskPrefix = Regex("^([ \\t]*)[-*] \\[[ xX]\\] ")
    private val bulletPrefix = Regex("^([ \\t]*)[-*] ")

    /** Turns the lines in the selection into tasks (`- [ ] `) or bullets (`- `), or back into plain lines. */
    fun toggleLinePrefix(text: String, selectionStart: Int, selectionEnd: Int, task: Boolean): Formatted {
        val first = text.lastIndexOf('\n', minOf(selectionStart, selectionEnd) - 1) + 1
        val last = text.indexOf('\n', maxOf(selectionStart, selectionEnd)).let { if (it < 0) text.length else it }
        val lines = text.substring(first, last).split('\n')
        val prefix = if (task) taskPrefix else bulletPrefix
        // Remove the prefix when every line already has it; otherwise add it to every line.
        val remove = lines.all { prefix.containsMatchIn(it) && (task || !taskPrefix.containsMatchIn(it)) }
        val changed = lines.map { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val content = line.substring(indent.length)
            when {
                remove -> line.replaceFirst(prefix, "$1")
                task && taskPrefix.containsMatchIn(line) -> line
                task && bulletPrefix.containsMatchIn(line) -> indent + content.substring(0, 2) + "[ ] " + content.substring(2)
                !task && taskPrefix.containsMatchIn(line) -> indent + content.substring(0, 2) + content.substring(6)
                !task && bulletPrefix.containsMatchIn(line) -> line
                else -> indent + (if (task) "- [ ] " else "- ") + content
            }
        }.joinToString("\n")
        val delta = changed.length - (last - first)
        val cursor = (maxOf(selectionStart, selectionEnd) + delta).coerceIn(first, first + changed.length)
        return Formatted(text.substring(0, first) + changed + text.substring(last),
            if (selectionStart == selectionEnd) cursor else first, cursor)
    }
}
