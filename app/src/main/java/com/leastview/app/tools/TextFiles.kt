package com.leastview.app.tools

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextFiles {
    const val MAX_BYTES = 4 * 1024 * 1024

    class Loaded(val text: String, val truncated: Boolean)

    /** Reads a text file, honouring BOMs, preferring UTF-8 and falling back to Windows-1252 for legacy files. */
    fun read(file: File, maxBytes: Int = MAX_BYTES): Loaded {
        val all = file.length()
        val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
        val truncated = all > bytes.size
        val (charset, skip) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            // a truncated file may end in the middle of a character, so tolerate that final fragment
            val buf = ByteBuffer.wrap(bytes, skip, bytes.size - skip)
            charset.newDecoder().onMalformedInput(if (truncated) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, skip, bytes.size - skip, Charset.forName("windows-1252"))
        }
        return Loaded(text.replace("\r\n", "\n").replace('\r', '\n'), truncated)
    }
}
