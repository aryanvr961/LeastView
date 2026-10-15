package com.leastview.app.ui

import androidx.compose.ui.graphics.Color

fun hexColor(hex: String): Color? {
    if (hex.length != 6) return null
    val v = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or v)
}

fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

/**
 * Document colours were chosen for a white page. On a dark page, near-black text would vanish,
 * so it falls back to the page's default text colour (null) and mid-dark colours are lightened.
 */
fun adaptForPage(hex: String?, darkPage: Boolean): Color? {
    val c = hex?.let { hexColor(it) } ?: return null
    val l = luminance(c)
    return if (darkPage) {
        when {
            l < 0.22f -> null
            l < 0.45f -> androidx.compose.ui.graphics.lerp(c, Color.White, 0.45f)
            else -> c
        }
    } else {
        if (l > 0.9f) null else c
    }
}

/** Black or white, whichever reads better on [background]. */
fun contrastOn(background: Color): Color = if (luminance(background) > 0.55f) Color.Black else Color.White

val highlightColors = mapOf(
    "yellow" to Color(0xFFFFFF00), "green" to Color(0xFF00FF00), "cyan" to Color(0xFF00FFFF),
    "magenta" to Color(0xFFFF00FF), "blue" to Color(0xFF0000FF), "red" to Color(0xFFFF0000),
    "darkBlue" to Color(0xFF000080), "darkCyan" to Color(0xFF008080), "darkGreen" to Color(0xFF008000),
    "darkMagenta" to Color(0xFF800080), "darkRed" to Color(0xFF800000), "darkYellow" to Color(0xFF808000),
    "darkGray" to Color(0xFF808080), "lightGray" to Color(0xFFC0C0C0), "black" to Color(0xFF000000),
)
