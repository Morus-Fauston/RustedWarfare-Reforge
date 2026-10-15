package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.reject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal fun readXml(path: Path): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    val builder = factory.newDocumentBuilder()
    builder.setErrorHandler(object : DefaultHandler() {
        override fun error(exception: SAXParseException) { throw exception }
        override fun fatalError(exception: SAXParseException) { throw exception }
    })
    return try {
        Files.newInputStream(path).use { builder.parse(it).documentElement }
    } catch (failure: SAXException) {
        reject(ContentErrorCode.MALFORMED_SYNTAX, path, reason = failure.message ?: "Malformed XML")
    } catch (failure: IOException) {
        reject(ContentErrorCode.BAD_REFERENCE, path, reason = failure.message ?: "Cannot read XML")
    }
}

internal fun resolveReference(file: Path, value: String, section: String, field: String): Path {
    // P001 resolves each reference relative to the referencing file, as an experimental project rule.
    val resolved = try { file.parent.resolve(value).toAbsolutePath().normalize() } catch (failure: InvalidPathException) {
        reject(ContentErrorCode.BAD_REFERENCE, file, section, field, value, "Invalid reference path")
    }
    if (!Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
        reject(ContentErrorCode.BAD_REFERENCE, file, section, field, value, "Reference is not a readable file: $resolved")
    }
    return resolved
}

internal fun attributes(element: Element, path: Path, allowed: Set<String>) {
    for (index in 0 until element.attributes.length) {
        val name = element.attributes.item(index).nodeName
        if (name !in allowed) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, element.tagName, name, reason = "Unsupported XML attribute")
    }
}

internal fun elements(element: Element, path: Path, allowText: Boolean = false): List<Element> {
    val result = mutableListOf<Element>()
    for (index in 0 until element.childNodes.length) {
        val node = element.childNodes.item(index)
        when (node.nodeType) {
            Node.ELEMENT_NODE -> result.add(node as Element)
            Node.COMMENT_NODE -> Unit
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> if (!allowText && node.textContent.isNotBlank()) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, element.tagName, reason = "Unexpected element text")
            }
            else -> reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, element.tagName, reason = "Unsupported XML node")
        }
    }
    return result
}

internal fun onlyChild(element: Element, path: Path, name: String): Element {
    val children = elements(element, path)
    children.firstOrNull { it.tagName != name }?.let {
        reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, it.tagName, reason = "Unsupported child of ${element.tagName}")
    }
    if (children.isEmpty()) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, element.tagName, name, reason = "Missing $name element")
    if (children.size != 1) reject(ContentErrorCode.MALFORMED_SYNTAX, path, element.tagName, name, reason = "Expected exactly one $name element")
    return children.single()
}

internal fun required(element: Element, path: Path, field: String): String = element.getAttribute(field).takeIf { it.isNotBlank() }
    ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, element.tagName, field, reason = "Required attribute is absent or empty")

internal fun integer(element: Element, path: Path, field: String, minimum: Int, default: Int? = null): Int {
    if (!element.hasAttribute(field) && default != null) return default
    val value = required(element, path, field).toIntOrNull()
    if (value == null || value < minimum) reject(ContentErrorCode.MALFORMED_SYNTAX, path, element.tagName, field, reason = "Expected integer >= $minimum")
    return value
}
