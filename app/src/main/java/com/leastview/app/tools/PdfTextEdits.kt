package com.leastview.app.tools

/** Rough typeface family of a PDF font, used to pick a matching built-in font for edited text. */
enum class LineFamily { SANS, SERIF, MONO }

/**
 * One line of a PDF page whose text the user changed. Positions are fractions (0..1) of the page as displayed,
 * y pointing down; sizes are PDF points.
 */
class LineEditSpec(
    val page: Int,
    /** Left edge and baseline of the original line, and how wide it was (it is covered up before the new text is drawn). */
    val x: Float,
    val baseline: Float,
    val origWidth: Float,
    val origSizePt: Float,
    /** Empty text deletes the line. */
    val text: String,
    val family: LineFamily,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val sizePt: Float,
    /** 0xAARRGGBB; alpha is ignored. */
    val color: Int,
    val highlight: Int?,
    /** Colour of the page behind the line, used to hide the original text. */
    val cover: Int,
)
