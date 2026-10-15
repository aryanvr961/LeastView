package com.leastview.app.office.docx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.Rel
import com.leastview.app.office.kid
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Character formatting, fully resolved (document defaults + paragraph style + run style + direct formatting). */
data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizeHalfPt: Int = 22,
    val color: String? = null,
    val font: String? = null,
    val highlight: String? = null,
    val link: String? = null,
)

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

data class ParaProps(
    val align: Align = Align.LEFT,
    val indentLeftTw: Int = 0,
    val firstLineTw: Int = 0,
    val spaceBeforeTw: Int = 0,
    val spaceAfterTw: Int = 0,
    val lineMult: Float = 1f,
    val marker: String? = null,
    val borderBottom: Boolean = false,
    val borderTop: Boolean = false,
)

class DocImage(val path: String, val widthEmu: Long, val heightEmu: Long)

private val blockIds = java.util.concurrent.atomic.AtomicLong()

sealed interface Block {
    /** Unique for the lifetime of the process; used as a stable list key. */
    val id: Long
}

class TextSnapshot(val text: String, val styles: List<RunStyle>, val props: ParaProps)

class DocParagraph(
    var element: Element?,
    text: String,
    styles: List<RunStyle>,
    var props: ParaProps,
    /** Style a run gets when it has no direct formatting; the writer only emits differences from this. */
    val base: RunStyle,
    /** False for paragraphs holding fields, drawings, content controls, etc. that a plain-text rewrite would destroy. */
    val editable: Boolean,
    val images: List<DocImage>,
) : Block {
    override val id: Long = blockIds.incrementAndGet()

    var text: String = text
        private set
    val styles: ArrayList<RunStyle> = ArrayList(styles)
    var container: SnapshotStateList<Block>? = null
    var dirty = false

    /** Bumped on every change so Compose can re-read text/styles. */
    var version by mutableIntStateOf(0)
        private set

    private fun touch() {
        dirty = true
        version++
    }

    /**
     * Applies a text edit expressed as old text -> [newText]. Inserted characters take [insertStyle],
     * or the style of the character before the insertion point.
     */
    fun applyEdit(newText: String, insertStyle: RunStyle?) {
        val old = text
        if (old == newText) return
        var prefix = 0
        val maxPrefix = minOf(old.length, newText.length)
        while (prefix < maxPrefix && old[prefix] == newText[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(old.length, newText.length) - prefix
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++

        val removedEnd = old.length - suffix
        val insertedLen = newText.length - suffix - prefix
        val style = insertStyle ?: styles.getOrNull(prefix - 1) ?: styles.getOrNull(prefix) ?: base
        if (removedEnd > prefix) styles.subList(prefix, removedEnd).clear()
        if (insertedLen > 0) styles.addAll(prefix, List(insertedLen) { style })
        text = newText
        touch()
    }

    fun restyle(start: Int, end: Int, f: (RunStyle) -> RunStyle) {
        val s = start.coerceIn(0, styles.size)
        val e = end.coerceIn(s, styles.size)
        for (i in s until e) styles[i] = f(styles[i])
        touch()
    }

    /** Style at the caret: the character before it, or the first character, or the paragraph base. */
    fun styleAt(caret: Int): RunStyle = styles.getOrNull(caret - 1) ?: styles.getOrNull(0) ?: base

    fun updateProps(p: ParaProps) {
        props = p
        touch()
    }

    fun snapshot() = TextSnapshot(text, ArrayList(styles), props)

    fun restore(s: TextSnapshot) {
        text = s.text
        props = s.props
        styles.clear()
        styles.addAll(s.styles)
        touch()
    }

    /** Removes and returns everything from [offset] on. */
    fun cut(offset: Int): TextSnapshot {
        val o = offset.coerceIn(0, text.length)
        val tail = TextSnapshot(text.substring(o), ArrayList(styles.subList(o, styles.size)), props)
        text = text.substring(0, o)
        styles.subList(o, styles.size).clear()
        touch()
        return tail
    }

    fun append(s: TextSnapshot) {
        text += s.text
        styles.addAll(s.styles)
        touch()
    }
}

class DocCell(val blocks: SnapshotStateList<Block>, val widthTw: Int, val gridSpan: Int, val shade: String?)

class DocTable(val rows: List<List<DocCell>>, val bordered: Boolean) : Block {
    override val id: Long = blockIds.incrementAndGet()
}

data class PageSetup(
    val widthTw: Int = 12240,
    val heightTw: Int = 15840,
    val marginLeftTw: Int = 1440,
    val marginRightTw: Int = 1440,
    val marginTopTw: Int = 1440,
    val marginBottomTw: Int = 1440,
)

class DocxDocument(
    val pkg: OoxmlPackage,
    val dom: Document,
    val blocks: SnapshotStateList<Block>,
    val page: PageSetup,
    val rels: MutableMap<String, Rel>,
) {
    /** Every paragraph in reading order, including those inside tables. */
    fun allParagraphs(): List<DocParagraph> {
        val out = ArrayList<DocParagraph>()
        fun walk(list: List<Block>) {
            for (b in list) when (b) {
                is DocParagraph -> out += b
                is DocTable -> b.rows.forEach { row -> row.forEach { walk(it.blocks) } }
            }
        }
        walk(blocks)
        return out
    }

    /** Splits [p] at [offset]; the tail becomes a new paragraph right after it. Returns the new paragraph. */
    fun split(p: DocParagraph, offset: Int): DocParagraph? {
        val el = p.element ?: return null
        val list = p.container ?: return null
        val idx = list.indexOf(p)
        if (idx < 0 || !p.editable) return null
        val newEl = el.cloneNode(false) as Element
        el.kid("pPr")?.let { newEl.appendChild(it.cloneNode(true)) }
        el.parentNode.insertBefore(newEl, el.nextSibling)
        val tail = p.cut(offset)
        val np = DocParagraph(newEl, tail.text, tail.styles, p.props, p.base, true, emptyList())
        np.container = list
        np.dirty = true
        list.add(idx + 1, np)
        return np
    }

    /** Merges [p] into the paragraph before it. Returns that paragraph and the caret position at the join. */
    fun mergeIntoPrevious(p: DocParagraph): Pair<DocParagraph, Int>? {
        val list = p.container ?: return null
        val idx = list.indexOf(p)
        if (idx <= 0 || !p.editable) return null
        val prev = list[idx - 1] as? DocParagraph ?: return null
        if (!prev.editable || prev.element == null) return null
        val caret = prev.text.length
        prev.append(p.snapshot())
        p.element?.let { it.parentNode?.removeChild(it) }
        list.removeAt(idx)
        return prev to caret
    }
}
