package com.leastview.app.ui.docx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.leastview.app.office.docx.Align
import com.leastview.app.office.docx.DocParagraph
import com.leastview.app.office.docx.DocxDocument
import com.leastview.app.office.docx.RunStyle
import com.leastview.app.office.docx.TextSnapshot

class FocusRequest(val paragraph: DocParagraph, val caret: Int)

/**
 * Editing state for one open document: which paragraph has focus, the selection in it, pending
 * formatting for the next typed characters, and undo/redo.
 * Splitting or merging paragraphs resets the history (those changes aren't undoable yet).
 */
class DocEditor(val doc: DocxDocument) {
    var focused by mutableStateOf<DocParagraph?>(null)
        private set
    var selection by mutableStateOf(TextRange.Zero)
        private set
    var focusRequest by mutableStateOf<FocusRequest?>(null)
    var pending by mutableStateOf<RunStyle?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Increases with every edit, so other features (search) can tell the document changed. */
    var changeCount by mutableIntStateOf(0)
        private set
    private var savedCount = 0
    val dirty: Boolean get() = changeCount != savedCount
    fun markSaved() {
        savedCount = changeCount
    }

    private class Entry(val p: DocParagraph, val before: TextSnapshot, var after: TextSnapshot, var time: Long)

    private val undoStack = ArrayList<Entry>()
    private val redoStack = ArrayList<Entry>()

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
    }

    fun onFocus(p: DocParagraph, sel: TextRange) {
        if (focused !== p) pending = null
        focused = p
        selection = sel
    }

    /**
     * Handles a change coming from [p]'s text field and returns the value the field should now show.
     * [old] is what the field showed before.
     */
    fun onEdit(p: DocParagraph, new: TextFieldValue, old: TextFieldValue): TextFieldValue {
        if (!p.editable) return old
        if (new.text == p.text) {
            if (new.selection != selection) pending = null
            selection = new.selection
            return new
        }

        // Enter was pressed: the field inserted a newline at the caret, which becomes a paragraph break.
        val at = new.selection.start - 1
        val typedNewline = new.text.count { it == '\n' } > p.text.count { it == '\n' } &&
            at >= 0 && new.text.getOrNull(at) == '\n'
        if (typedNewline) {
            val without = new.text.removeRange(at, at + 1)
            if (without != p.text) p.applyEdit(without, pending)
            val np = doc.split(p, at)
            if (np != null) {
                pending = null
                clearHistory()
                changeCount++
                focusRequest = FocusRequest(np, 0)
                return TextFieldValue(p.text, TextRange(p.text.length))
            }
            return TextFieldValue(p.text, TextRange(at.coerceAtMost(p.text.length)))
        }

        val before = p.snapshot()
        p.applyEdit(new.text, pending)
        pending = null
        selection = new.selection
        pushTyping(p, before)
        return new
    }

    private fun pushTyping(p: DocParagraph, before: TextSnapshot) {
        val now = System.currentTimeMillis()
        val top = undoStack.lastOrNull()
        if (top != null && top.p === p && now - top.time < 1200) {
            top.after = p.snapshot()
            top.time = now
        } else {
            undoStack += Entry(p, before, p.snapshot(), now)
        }
        redoStack.clear()
        changeCount++
        refreshFlags()
    }

    /** Runs [block] on [p] as a single undoable step. */
    private fun mutate(p: DocParagraph, block: () -> Unit) {
        val before = p.snapshot()
        block()
        undoStack += Entry(p, before, p.snapshot(), 0L)
        redoStack.clear()
        changeCount++
        refreshFlags()
    }

    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        e.p.restore(e.before)
        redoStack += e
        focusRequest = FocusRequest(e.p, e.before.text.length)
        changeCount++
        refreshFlags()
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        e.p.restore(e.after)
        undoStack += e
        focusRequest = FocusRequest(e.p, e.after.text.length)
        changeCount++
        refreshFlags()
    }

    /** Backspace at the very start of [p]: join it onto the previous paragraph. */
    fun mergeBack(p: DocParagraph): Boolean {
        val (prev, caret) = doc.mergeIntoPrevious(p) ?: return false
        pending = null
        clearHistory()
        changeCount++
        focusRequest = FocusRequest(prev, caret)
        return true
    }

    // ---- formatting ----

    private fun applyToSelection(set: (RunStyle) -> RunStyle) {
        val p = focused ?: return
        val sel = selection
        if (sel.collapsed) {
            pending = set(pending ?: p.styleAt(sel.start))
            return
        }
        mutate(p) { p.restyle(sel.min, sel.max, set) }
    }

    private fun toggleFlag(get: (RunStyle) -> Boolean, set: (RunStyle, Boolean) -> RunStyle) {
        val p = focused ?: return
        val sel = selection
        val on = if (sel.collapsed) get(pending ?: p.styleAt(sel.start))
        else (sel.min until sel.max.coerceAtMost(p.styles.size)).all { get(p.styles[it]) }
        applyToSelection { set(it, !on) }
    }

    fun isActive(get: (RunStyle) -> Boolean): Boolean {
        val p = focused ?: return false
        p.version // re-evaluate when styles change
        val sel = selection
        if (sel.collapsed) return get(pending ?: p.styleAt(sel.start))
        val hi = sel.max.coerceAtMost(p.styles.size)
        return hi > sel.min && (sel.min until hi).all { get(p.styles[it]) }
    }

    fun toggleBold() = toggleFlag({ it.bold }) { s, v -> s.copy(bold = v) }
    fun toggleItalic() = toggleFlag({ it.italic }) { s, v -> s.copy(italic = v) }
    fun toggleUnderline() = toggleFlag({ it.underline }) { s, v -> s.copy(underline = v) }
    fun toggleStrike() = toggleFlag({ it.strike }) { s, v -> s.copy(strike = v) }

    fun changeSize(deltaHalfPts: Int) = applyToSelection { it.copy(sizeHalfPt = (it.sizeHalfPt + deltaHalfPts).coerceIn(2, 400)) }
    fun setColor(hex: String?) = applyToSelection { it.copy(color = hex) }
    fun setHighlight(name: String?) = applyToSelection { it.copy(highlight = name) }

    fun alignment(): Align? = focused?.let { it.version; it.props.align }

    fun setAlign(a: Align) {
        val p = focused ?: return
        if (p.props.align == a) return
        mutate(p) { p.updateProps(p.props.copy(align = a)) }
    }
}
