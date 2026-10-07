package com.lasergrbl.core.internal

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * JVM 侧的 XML 实现：`javax.xml.parsers.DocumentBuilderFactory` + `org.w3c.dom` 的薄封装
 * （与 Android 侧的 `Xml.android.kt` 逐行同构 —— `javax.xml` 与 `org.w3c.dom` 在两边都是标准件）。
 *
 * 关键决策：
 *   * **关闭命名空间感知**：TS 用的是 `getAttribute('x')` 与完整 `tagName`，
 *     开着 namespaceAware 会改变 `tagName` 的形态，反而不忠实；
 *   * **关闭 DTD / 外部实体**：纯安全考虑（XXE），对黄金样本无影响；
 *   * **按 UTF-8 字节解析**而不是 `StringReader`：XML 声明里的 `encoding="UTF-8"`
 *     只对字节流有意义，喂字符流会踩到编码声明的坑；
 *   * `querySelector` 的选择器匹配（`tag` / `#id` / `.class`）放在 commonMain 的
 *     [matchesSelector] 里，两个 actual 不会各自漂移。
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

