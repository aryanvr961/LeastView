package com.leastview.app.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.tools.LineEditSpec

/** A line of the PDF as the user has changed it so far (an immutable snapshot; edits replace it). */
data class LineEdit(
    val line: TextLine,
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val sizePt: Float,
    /** 0xAARRGGBB of the text, and of the page behind the line. */
    val color: Int,
    val baseColor: Int,
    val highlight: String?,
    val cover: Int,
) {
    /** False while the line still looks and reads exactly like the original, so untouched lines are never rewritten. */
    val changed: Boolean
        get() = text != line.text || bold != line.bold || italic != line.italic || underline || strike ||
            sizePt != line.sizePt || color != baseColor || highlight != null
}

/**
 * The text edits made in this session: which line is being typed in, what every touched line now says and
 * looks like, with undo/redo. Nothing touches the PDF until it is saved.
 */
class PdfTextEdit(private val highlightColor: (String) -> Int?) {
    val edits = mutableStateMapOf<LineKey, LineEdit>()
    var active by mutableStateOf<LineKey?>(null)

    val activeEdit: LineEdit? get() = active?.let { edits[it] }
    val dirty: Boolean get() = edits.values.any { it.changed }

    private class Step(val key: LineKey, val before: LineEdit?, var after: LineEdit?, var time: Long)

    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var revision by mutableIntStateOf(0)

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        revision++
    }

    /** Starts editing [line]. [cover] is the page colour behind it and [ink] the colour its text currently has. */
    fun activate(line: TextLine, cover: Int, ink: Int) {
        if (line.key !in edits) {
            edits[line.key] = LineEdit(line, line.text, line.bold, line.italic, false, false, line.sizePt, ink, ink, null, cover)
        }
        active = line.key
    }

    fun deactivate() {
        active = null
    }

    /** Applies [change] to the active line as one undo step, or merges it into the previous step when [merge] is set. */
    private fun apply(merge: Boolean = false, change: (LineEdit) -> LineEdit) {
        val key = active ?: return
        val before = edits[key] ?: return
        val after = change(before)
        if (after == before) return
        edits[key] = after
        val now = System.currentTimeMillis()
        val top = undoStack.lastOrNull()
        if (merge && top != null && top.key == key && now - top.time < 1200) {
            top.after = after
            top.time = now
        } else {
            undoStack += Step(key, before, after, now)
        }
        redoStack.clear()
        refresh()
    }

    fun typing(text: String) = apply(merge = true) { it.copy(text = text) }
    fun toggleBold() = apply { it.copy(bold = !it.bold) }
    fun toggleItalic() = apply { it.copy(italic = !it.italic) }
    fun toggleUnderline() = apply { it.copy(underline = !it.underline) }
    fun toggleStrike() = apply { it.copy(strike = !it.strike) }
    fun changeSize(deltaPt: Float) = apply { it.copy(sizePt = (it.sizePt + deltaPt).coerceIn(4f, 96f)) }

    /** [argb] null goes back to the line's original colour. */
    fun setColor(argb: Int?) = apply { it.copy(color = argb ?: it.baseColor) }
    fun setHighlight(name: String?) = apply { it.copy(highlight = name) }

    fun undo() {
        val step = undoStack.removeLastOrNull() ?: return
        restore(step.key, step.before)
        redoStack += step
        refresh()
    }

    fun redo() {
        val step = redoStack.removeLastOrNull() ?: return
        restore(step.key, step.after)
        undoStack += step
        refresh()
    }

    private fun restore(key: LineKey, state: LineEdit?) {
        if (state == null) edits.remove(key) else edits[key] = state
        active = key
    }

    fun clear() {
        edits.clear()
        active = null
        undoStack.clear()
        redoStack.clear()
        refresh()
    }

    /** What to write into the PDF: only lines that really changed. */
    fun specs(): List<LineEditSpec> = edits.values.filter { it.changed }.map { e ->
        LineEditSpec(
            page = e.line.page, x = e.line.x, baseline = e.line.baseline, origWidth = e.line.width, origSizePt = e.line.sizePt,
            text = e.text, family = e.line.family, bold = e.bold, italic = e.italic, underline = e.underline, strike = e.strike,
            sizePt = e.sizePt, color = e.color, highlight = e.highlight?.let(highlightColor), cover = e.cover,
        )
    }
}
