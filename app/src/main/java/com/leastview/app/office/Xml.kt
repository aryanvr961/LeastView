package com.leastview.app.office

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
const val NS_XML = "http://www.w3.org/XML/1998/namespace"
private const val NS_XMLNS = "http://www.w3.org/2000/xmlns/"

/** DOM helpers that match on local names only, so transitional and strict namespaces both work. */
object Xml {
    fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // Documents are untrusted input: no DTDs, no external entities.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { f.isXIncludeAware = false }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun serialize(doc: Document): ByteArray {
        doc.xmlStandalone = true
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val out = ByteArrayOutputStream()
        t.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }
}

fun Element.kids(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element) out += n
        n = n.nextSibling
    }
    return out
}

fun Element.kid(local: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n is Element && n.localName == local) return n
        n = n.nextSibling
    }
    return null
}

fun Element.kidsNamed(local: String): List<Element> = kids().filter { it.localName == local }

fun Element.attr(local: String): String? {
    val a = attributes
    for (i in 0 until a.length) {
        val n = a.item(i)
        if (n.namespaceURI == NS_XMLNS) continue
        if ((n.localName ?: n.nodeName) == local) return n.nodeValue
    }
    return null
}

/** Namespaced attribute lookup, for names that exist in two namespaces (e.g. p:sldId has both id and r:id). */
fun Element.attrNs(ns: String, local: String): String? = getAttributeNS(ns, local).takeIf { it.isNotEmpty() }

fun Element.descendants(local: String, out: MutableList<Element> = ArrayList()): List<Element> {
    var n = firstChild
    while (n != null) {
        if (n is Element) {
            if (n.localName == local) out += n
            n.descendants(local, out)
        }
        n = n.nextSibling
    }
    return out
}

fun Element.firstDescendant(local: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n is Element) {
            if (n.localName == local) return n
            n.firstDescendant(local)?.let { return it }
        }
        n = n.nextSibling
    }
    return null
}

fun Document.el(ns: String, prefix: String, local: String): Element = createElementNS(ns, "$prefix:$local")

fun Element.setNs(ns: String, prefix: String, local: String, value: String) =
    setAttributeNS(ns, "$prefix:$local", value)
