package io.github.hematite.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hematite.core.Alignment
import io.github.hematite.core.Dav
import io.github.hematite.core.Markdown
import io.github.hematite.core.MarkdownDocument
import io.github.hematite.core.MarkdownLink
import io.github.hematite.core.Run
import io.github.hematite.core.Span
import io.github.hematite.core.Style
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class TableOverlay(val offset: Int, val cells: List<TextLayoutResult>, val width: Float, val height: Float, val header: Boolean)
private class ImageOverlay(val offset: Int, val end: Int, val bitmap: ImageBitmap, val width: Float, val height: Float)

/** The note as displayed: styled text with hidden markers, and what is drawn over reserved lines. */
private class Rendered(
    val text: AnnotatedString,
    val tables: List<TableOverlay>,
    val images: List<ImageOverlay>,
    val tasks: List<Pair<Int, Boolean>>,
    val links: List<MarkdownLink>,
    val editing: Span?,
)

private class EditorColors(val text: Color, val link: Color, val accent: Color)

private val concealed = SpanStyle(fontSize = 0.1.sp, color = Color.Transparent, letterSpacing = 0.sp)

private fun lineSpan(text: String, offset: Int): Span {
    val start = text.lastIndexOf('\n', offset - 1) + 1
    val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    return Span(start, end)
}

/** Lines touched by the selection, where Markdown source is shown while editing. */
private fun editingSpan(text: String, selection: TextRange): Span {
    val start = text.lastIndexOf('\n', selection.min - 1) + 1
    val end = text.indexOf('\n', selection.max).let { if (it < 0) text.length else it + 1 }
    return Span(start, end)
}

private fun cellText(runs: List<Run>, header: Boolean) = buildAnnotatedString {
    for (run in runs) {
        withStyle(SpanStyle(
            fontWeight = if (run.bold || header) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            textDecoration = if (run.strikethrough) TextDecoration.LineThrough else null,
        )) { append(run.text) }
    }
}

private fun imageFile(directory: File?, destination: String): File? {
    if (directory == null || "://" in destination || destination.startsWith("data:")) return null
    return File(directory, Dav.decode(destination) ?: destination)
}

private fun render(
    text: String, document: MarkdownDocument, editing: Span?, width: Float, density: Density, colors: EditorColors,
    measurer: TextMeasurer, style: TextStyle, images: Map<File, ImageBitmap?>, directory: File?,
): Rendered {
    val chars = text.toCharArray()
    val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    val reservedLines = HashSet<Int>()
    fun visible(span: Span) = editing == null || !span.overlaps(editing)
    fun add(style: SpanStyle, start: Int, end: Int) { if (start < end) spans += AnnotatedString.Range(style, start, end) }
    fun conceal(start: Int, end: Int) {
        if (editing == null) return add(concealed, start, end)
        add(concealed, start, minOf(end, editing.start))
        add(concealed, maxOf(start, editing.end), end)
    }
    // Font size of a glyph whose line is 100 sp tall, for sizing lines in pixels.
    val glyphHeight = measurer.measure("|", style.copy(fontSize = 100.sp)).size.height.toFloat()
    /**
     * Gives the line at `offset` `height` pixels by hiding it, newline included, and enlarging its first
     * (transparent) character, as the desktop does. A table row or image is then drawn over the line.
     */
    fun reserve(offset: Int, height: Float): Boolean {
        val line = lineSpan(text, offset)
        if (!reservedLines.add(line.start)) return false
        add(concealed, line.start, minOf(line.end + 1, text.length))
        add(SpanStyle(fontSize = (100 * height / glyphHeight).sp, color = Color.Transparent), line.start, line.start + 1)
        return true
    }

    for (styled in document.styles) {
        add(when (styled.style) {
            Style.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            Style.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            Style.Heading1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.8.em)
            Style.Heading2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.5.em)
            Style.Heading3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.3.em)
            Style.Heading4 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.15.em)
        }, styled.range.start, styled.range.end)
    }
    for (link in document.links) add(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline), link.label.start, link.label.end)

    // Bullets and checkboxes replace their marker character, so offsets stay the same.
    val bullets = document.bullets.filter { it in chars.indices && (editing == null || it !in editing) }
    val tasks = document.tasks.filter { (offset, _) -> offset + 5 <= chars.size && (editing == null || offset !in editing) }
    for (offset in bullets) chars[offset] = '•'
    for ((offset, checked) in tasks) {
        chars[offset] = if (checked) '☑' else '☐'
        add(SpanStyle(color = colors.accent, fontSize = 1.25.em, fontFamily = FontFamily.Default), offset, offset + 1)
    }
    val replaced = (bullets + tasks.map { it.first }).toHashSet()
    for (marker in document.markers) {
        conceal(if (marker.start in replaced) marker.start + 1 else marker.start, marker.end)
    }

    val imageOverlays = mutableListOf<ImageOverlay>()
    val maxImage = with(density) { 480.dp.toPx() }
    for ((source, destination) in document.images) {
        if (!visible(source)) continue
        val line = lineSpan(text, source.start)
        if (text.substring(line.start, source.start).isNotBlank() || text.substring(source.end, line.end).isNotBlank()) continue
        val bitmap = imageFile(directory, destination)?.let { images[it] } ?: continue
        val imageWidth = minOf(width, maxImage).coerceAtLeast(1f)
        val imageHeight = imageWidth * bitmap.height / bitmap.width
        val margin = with(density) { 8.dp.toPx() }
        if (!reserve(source.start, imageHeight + margin * 2)) continue
        imageOverlays += ImageOverlay(source.start, line.end, bitmap, imageWidth, imageHeight)
    }

    val tableOverlays = mutableListOf<TableOverlay>()
    val padding = with(density) { 10.dp.toPx() }
    for (table in document.tables) {
        val header = table.rows.firstOrNull() ?: continue
        // The separator line under the header.
        val headerLine = lineSpan(text, header.range.start)
        if (headerLine.end < text.length) {
            val separator = lineSpan(text, headerLine.end + 1)
            val range = Span(separator.start, minOf(separator.end + 1, text.length))
            if (visible(range) && separator.start < table.range.end) add(concealed, range.start, range.end)
        }
        val cellWidth = width / maxOf(table.alignments.size, 1)
        for (row in table.rows) {
            if (row.range.isEmpty || !visible(row.range)) continue
            val cells = row.cells.mapIndexed { column, runs ->
                val align = when (table.alignments.getOrNull(column)) {
                    Alignment.Center -> TextAlign.Center
                    Alignment.Right -> TextAlign.End
                    else -> TextAlign.Start
                }
                measurer.measure(cellText(runs, row.header), style.copy(textAlign = align),
                    constraints = Constraints.fixedWidth((cellWidth - padding * 2).toInt().coerceAtLeast(1)))
            }
            val height = (cells.maxOfOrNull { it.size.height } ?: 0) + padding * 1.6f
            if (!reserve(row.range.start, height)) continue
            tableOverlays += TableOverlay(row.range.start, cells, width, height, row.header)
        }
    }
    return Rendered(AnnotatedString(String(chars), spans), tableOverlays, imageOverlays, tasks, document.links, editing)
}

private fun DrawScope.drawOverlays(rendered: Rendered, layout: TextLayoutResult, colors: EditorColors) {
    val border = colors.text.copy(alpha = 0.25f)
    val padding = 10.dp.toPx()
    for (row in rendered.tables) {
        if (row.offset > layout.layoutInput.text.length) continue
        val line = layout.getLineForOffset(row.offset)
        val top = layout.getLineTop(line)
        val height = layout.getLineBottom(line) - top
        if (row.header) drawRect(colors.text.copy(alpha = 0.08f), Offset(0f, top), Size(row.width, height))
        val cellWidth = row.width / maxOf(row.cells.size, 1)
        for ((column, cell) in row.cells.withIndex()) {
            val left = column * cellWidth
            drawText(cell, color = colors.text, topLeft = Offset(left + padding, top + padding * 0.8f))
            drawRect(border, Offset(left, top), Size(1f, height))
        }
        drawRect(border, Offset(row.width - 1f, top), Size(1f, height))
        drawRect(border, Offset(0f, top), Size(row.width, 1f))
        drawRect(border, Offset(0f, top + height - 1f), Size(row.width, 1f))
    }
    for (image in rendered.images) {
        if (image.offset > layout.layoutInput.text.length) continue
        val line = layout.getLineForOffset(image.offset)
        val top = layout.getLineTop(line) + 8.dp.toPx()
        val left = (size.width - image.width) / 2
        drawImage(image.bitmap, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(image.width.toInt(), image.height.toInt()))
    }
}

/** What a tap on the rendered note does instead of placing the cursor. */
private sealed interface Target {
    class Task(val offset: Int, val checked: Boolean) : Target
    class OpenLink(val uri: String) : Target
    /** Reveals a table row's or image's source by moving the cursor to the end of its line. */
    class Reveal(val offset: Int) : Target
}

private fun targetAt(position: Offset, rendered: Rendered, layout: TextLayoutResult, slop: Float): Target? {
    val length = layout.layoutInput.text.length
    for ((offset, checked) in rendered.tasks) {
        if (offset >= length) continue
        val box = layout.getBoundingBox(offset)
        if (Rect(box.left - slop, box.top - slop / 2, box.right + slop, box.bottom + slop / 2).contains(position)) return Target.Task(offset, checked)
    }
    for (row in rendered.tables) {
        if (row.offset > length) continue
        val line = layout.getLineForOffset(row.offset)
        if (position.y >= layout.getLineTop(line) && position.y < layout.getLineBottom(line)) return Target.Reveal(layout.getLineEnd(line, true))
    }
    for (image in rendered.images) {
        if (image.offset > length) continue
        val line = layout.getLineForOffset(image.offset)
        if (position.y >= layout.getLineTop(line) && position.y < layout.getLineBottom(line)) return Target.Reveal(image.end)
    }
    val offset = layout.getOffsetForPosition(position)
    val line = layout.getLineForOffset(offset)
    if (position.y < layout.getLineTop(line) || position.y > layout.getLineBottom(line)) return null
    for (link in rendered.links) {
        if (rendered.editing != null && link.source.overlaps(rendered.editing)) continue
        if (offset !in link.label && offset != link.label.end) continue
        // Only when the tap is on the label's glyphs, not past the end of the line.
        val first = layout.getBoundingBox(link.label.start)
        val last = layout.getBoundingBox((link.label.end - 1).coerceAtLeast(link.label.start))
        if (layout.getLineForOffset(link.label.start) == layout.getLineForOffset(link.label.end - 1) &&
            (position.x < first.left - slop || position.x > last.right + slop)) continue
        return Target.OpenLink(link.uri)
    }
    return null
}

/**
 * A Markdown editor that shows formatting in place: markers are hidden except on the line being
 * edited, bullets and checkboxes are drawn, and tables and images appear as previews.
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    noteDirectory: File?,
    textSize: Int,
    editable: Boolean,
    focusRequester: FocusRequester,
    scrollState: ScrollState,
    minHeight: androidx.compose.ui.unit.Dp,
    onOpenLink: (String) -> Unit,
    onToggleTask: (Int, Boolean) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onFocusChange: (Boolean) -> Unit,
    reveal: Int?,
    onRevealed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) { EditorColors(scheme.onSurface, scheme.primary, scheme.primary) }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = textSize.sp, color = scheme.onSurface)
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val document = remember(value.text) { Markdown.parse(value.text) }
    val images = remember(noteDirectory) { mutableStateMapOf<File, ImageBitmap?>() }

    // Load images in the background; the note re-renders when each one is ready.
    val wanted = remember(document, noteDirectory) { document.images.mapNotNull { imageFile(noteDirectory, it.second) }.distinct() }
    LaunchedEffect(wanted) {
        for (file in wanted) {
            if (images.containsKey(file)) continue
            images[file] = withContext(Dispatchers.IO) { decodeImage(file, with(density) { 960.dp.roundToPx() }) }
        }
    }

    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.toPx() }
        val editing = if (focused && editable) editingSpan(value.text, value.selection) else null
        val rendered = remember(value.text, document, editing, width, textSize, colors, images.toMap()) {
            render(value.text, document, editing, width, density, colors, measurer, style, images, noteDirectory)
        }
        val current by rememberUpdatedState(rendered)
        // A new transformation whenever the rendering changes, so the field re-reads it.
        val transformation = remember(rendered) { VisualTransformation { TransformedText(rendered.text, OffsetMapping.Identity) } }
        val currentLayout by rememberUpdatedState(layout)
        val currentValue by rememberUpdatedState(value)

        LaunchedEffect(reveal, layout) {
            val offset = reveal ?: return@LaunchedEffect
            val result = layout ?: return@LaunchedEffect
            if (offset > result.layoutInput.text.length) return@LaunchedEffect
            val top = result.getLineTop(result.getLineForOffset(offset))
            scrollState.animateScrollTo((top - with(density) { 96.dp.toPx() }).toInt().coerceAtLeast(0))
            onRevealed()
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editable,
            textStyle = style,
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
                .onPreviewKeyEvent(onKey),
            decorationBox = { inner ->
                Box(Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val result = currentLayout ?: return@awaitEachGesture
                            val target = targetAt(down.position, current, result, 12.dp.toPx()) ?: return@awaitEachGesture
                            if (target is Target.Task && !editable) return@awaitEachGesture
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                if (!change.pressed) {
                                    // Claim the tap so the field doesn't also move the cursor.
                                    change.consume()
                                    when (target) {
                                        is Target.Task -> onToggleTask(target.offset, target.checked)
                                        is Target.OpenLink -> onOpenLink(target.uri)
                                        is Target.Reveal -> if (editable) {
                                            onValueChange(currentValue.copy(selection = TextRange(target.offset)))
                                            focusRequester.requestFocus()
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                                if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
                            }
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        currentLayout?.let { drawOverlays(current, it, colors) }
                    }) { inner() }
            },
        )
    }
}

/** Decodes an image scaled down to at most `maxWidth` pixels wide. */
private fun decodeImage(file: File, maxWidth: Int): ImageBitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
