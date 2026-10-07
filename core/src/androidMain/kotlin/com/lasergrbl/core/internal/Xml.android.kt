package com.lasergrbl.core.internal

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Android 侧的 XML 实现 —— 与 JVM 侧的 `Xml.jvm.kt` 逐行同构：`javax.xml.parsers` + `org.w3c.dom`
 * 在两端都是标准件，所以刻意**不**用 Android 特有的 `XmlPullParser`（流式 API 要自己维护栈，
 * 而且 `Xml.newPullParser()` 是 android.* 依赖，commonMain 侧的语义更难对齐）。
 *
 * 选择器匹配放在 commonMain 的 [matchesSelector]，两个 actual 只负责 DOM 适配。
 */
internal actual fun parseXml(text: String): XmlDocument? {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isValidating = false
    factory.isExpandEntityReferences = false
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }

    // 手工剥掉 BOM：字节流里 U+FEFF 出现在 XML 声明之前是合法的，但并非所有实现都认
    val body = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
    val bytes = body.toByteArray(Charsets.UTF_8)

    val doc: Document = try {
        val builder = factory.newDocumentBuilder()
        // 解析期间不要往 stderr 打印任何东西
        builder.setErrorHandler(null)
        builder.parse(ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        // 对应浏览器 DOM 的 parsererror 情形：JS 侧返回一棵含 <parsererror> 的树，
        // Kotlin 侧统一成 null，由调用方抛出与 v2 逐字相同的 Error 消息
        return null
    }
    return DocumentImpl(doc)
}

private class DocumentImpl(private val doc: Document) : XmlDocument {
    override val documentElement: XmlElement? = doc.documentElement?.let { ElementImpl(it) }

    override fun querySelector(selector: String): XmlElement? = documentElement?.querySelector(selector)
}

private class ElementImpl(private val el: Element) : XmlElement {
    override val tagName: String = el.tagName

    override fun getAttribute(name: String): String? {
        if (!el.hasAttribute(name)) return null
        return el.getAttribute(name)
    }

    override val children: List<XmlElement> by lazy {
        val out = ArrayList<XmlElement>()
        var node: Node? = el.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) out.add(ElementImpl(node as Element))
            node = node.nextSibling
        }
        out
    }

    // querySelector 用 XmlElement 的默认实现（commonMain 的 matchesSelector + 深度优先）
}
