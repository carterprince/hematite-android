package io.github.hematite.core

import java.io.File

/** A note's searchable text. */
class IndexedNote(val file: File, root: File, val text: String, internal var modified: Long = file.lastModified()) {
    val title: String = file.nameWithoutExtension
    val relative: String = file.relativeTo(root).invariantSeparatorsPath
    internal val titleLower = title.lowercase()
    internal val pathLower = relative.lowercase()
    internal val textLower = text.lowercase()
}

class Hit(val file: File, val title: String, val relative: String, val preview: String?, internal val score: Int, internal val modified: Long)

/** Finds notes by title, path, and content, like the desktop sidebar search. */
object Search {
    /** Reads every note; returns the index and how many notes could not be read. */
    fun index(entries: List<Entry>, root: File): Pair<List<IndexedNote>, Int> {
        var unreadable = 0
        val notes = entries.filter { !it.directory }.map { entry ->
            val text = try { entry.file.readText() } catch (_: Exception) { unreadable++; "" }
            IndexedNote(entry.file, root, text)
        }
        return notes to unreadable
    }

    internal fun preview(text: String, tokens: List<String>): String? {
        val line = text.lines().filter { it.isNotBlank() }
            .maxByOrNull { line -> line.lowercase().let { lower -> tokens.count { it in lower } } } ?: return null
        // Find the first match in the original line, so case-folding never shifts the excerpt.
        val first = tokens.mapNotNull { token -> line.indexOf(token, ignoreCase = true).takeIf { it >= 0 } }.minOrNull() ?: return null
        val codePoints = line.codePoints().toArray()
        val firstPoint = line.codePointCount(0, first)
        val start = maxOf(0, firstPoint - 35)
        val end = minOf(start + 140, codePoints.size)
        val excerpt = String(codePoints, start, end - start).trim()
        return (if (start > 0) "…" else "") + excerpt + (if (end < codePoints.size) "…" else "")
    }

    /** Notes matching every word of `query`, best first. `current` is the open note's unsaved text. */
    fun find(notes: List<IndexedNote>, query: String, current: Pair<File, String>? = null): List<Hit> {
        val normalized = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
        if (normalized.isEmpty()) return emptyList()
        val tokens = normalized.split(' ')
        val hits = mutableListOf<Hit>()
        for (note in notes) {
            val currentText = current?.takeIf { it.first == note.file }?.second
            val body = currentText?.lowercase() ?: note.textLower
            if (!tokens.all { it in note.titleLower || it in note.pathLower || it in body }) continue
            var score = when {
                note.titleLower == normalized -> 1000
                note.titleLower.startsWith(normalized) -> 600
                normalized in note.titleLower -> 400
                else -> 0
            }
            for (token in tokens) score += if (token in note.titleLower) 80 else if (token in note.pathLower) 20 else 8
            if (normalized in body) score += 5
            hits += Hit(note.file, note.title, note.relative, preview(currentText ?: note.text, tokens), score, note.modified)
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.modified }.thenBy { it.relative.lowercase() })
    }
}
