package com.leastview.app.office

import org.w3c.dom.Document
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A read-only view over an OOXML zip (docx/xlsx/pptx) plus a set of replaced parts.
 * [writeTo] streams every untouched entry straight from the source, so saving never
 * re-encodes images or other parts the user did not change.
 */
class OoxmlPackage(val file: File) : Closeable {
    private val zip = ZipFile(file)
    private val overrides = LinkedHashMap<String, ByteArray>()

    fun has(name: String): Boolean = overrides.containsKey(name) || zip.getEntry(name) != null

    fun entryNames(): List<String> = zip.entries().asSequence().map { it.name }.toList()

    fun bytes(name: String): ByteArray? {
        overrides[name]?.let { return it }
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun xml(name: String): Document? = bytes(name)?.let { Xml.parse(it) }

    fun put(name: String, data: ByteArray) {
        overrides[name] = data
    }

    fun putXml(name: String, doc: Document) = put(name, Xml.serialize(doc))

    fun writeTo(out: OutputStream) {
        ZipOutputStream(out).use { zo ->
            val seen = HashSet<String>()
            for (entry in zip.entries().asSequence()) {
                seen += entry.name
                zo.putNextEntry(ZipEntry(entry.name))
                val replacement = overrides[entry.name]
                if (replacement != null) zo.write(replacement)
                else zip.getInputStream(entry).use { it.copyTo(zo) }
                zo.closeEntry()
            }
            for ((name, data) in overrides) {
                if (name in seen) continue
                zo.putNextEntry(ZipEntry(name))
                zo.write(data)
                zo.closeEntry()
            }
        }
    }

    override fun close() = zip.close()
}

/** Relationship id -> (target, isExternal). Targets are resolved against [baseDir] (e.g. "word/"). */
fun parseRelationships(pkg: OoxmlPackage, relsPath: String, baseDir: String): Map<String, Rel> {
    val doc = pkg.xml(relsPath) ?: return emptyMap()
    val out = LinkedHashMap<String, Rel>()
    for (e in doc.documentElement.kidsNamed("Relationship")) {
        val id = e.attr("Id") ?: continue
        val target = e.attr("Target") ?: continue
        val external = e.attr("TargetMode").equals("External", ignoreCase = true)
        out[id] = Rel(
            target = if (external) target else resolvePath(baseDir, target),
            external = external,
            type = e.attr("Type").orEmpty(),
        )
    }
    return out
}

data class Rel(val target: String, val external: Boolean, val type: String)

fun resolvePath(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.removePrefix("/")
    val parts = ArrayList<String>()
    (baseDir + target).split('/').forEach {
        when (it) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += it
        }
    }
    return parts.joinToString("/")
}
