package com.lasergrbl.core.internal

/**
 * 极简 XML 接口 —— 只暴露 v2 里 `DOMParser` 被实际用到的那些成员。
 *
 * 为什么需要它：`src/core/vector/SvgToGcode.ts` 用浏览器 DOM
 * （`documentElement` / `children` / `tagName` / `getAttribute` / `querySelector`）遍历 SVG，
 * 而 commonMain 不能碰 `java.*`；JVM 与 Android 都自带 `javax.xml.parsers`，
 * 所以在这个 expect/actual 后面接一层 `org.w3c.dom` 的薄封装即可。
 *
 * 刻意**不做**的事（保持「镜像 TS 触碰到的子集」这一原则）：
 *   * 不支持 CSS 选择器（`matches` 只认 `tag` / `#id` / `.class`，与黄金样本生成器里的
 *     `MiniElement.matches` 一致，无需拆分组合选择器）；
 *   * 不做命名空间解析（TS 侧用的是 `getAttribute('x')` 与 `tagName`，
 *     `DocumentBuilderFactory` 因此关闭 namespaceAware）；
 *   * 不暴露 `textContent` / `innerHTML` 之类没被用到的成员。
 */

/** 一个已解析的 XML 元素。[tagName] 保留原始大小写与前缀（对应 DOM 的 `tagName`）。 */
internal interface XmlElement {
    val tagName: String

    /** 对应 `getAttribute(name)`：属性不存在时返回 `null`（不是空串）。 */
    fun getAttribute(name: String): String?

    /** 对应 `children`：**仅元素子节点**，不含文本/注释节点。 */
    val children: List<XmlElement>

    /** 对应 `querySelector(selector)`：深度优先、返回第一个命中的后代元素。 */
    fun querySelector(selector: String): XmlElement? {
        for (child in children) {
            if (matchesSelector(child, selector)) return child
            child.querySelector(selector)?.let { return it }
        }
        return null
    }
}

/**
 * 与黄金样本生成器里的 `MiniElement.matches` 同构的选择器匹配：
 * 只认 `tag`（大小写不敏感）/ `#id` / `.class` 三种写法，逗号分组。
 * 放在 commonMain 的默认实现里，保证 JVM / Android 两个 actual 行为一致。
 */
internal fun matchesSelector(el: XmlElement, selector: String): Boolean {
    for (part in selector.split(',')) {
        val sel = part.trim()
        if (sel.isEmpty()) continue
        if (sel.startsWith("#")) {
            if (el.getAttribute("id") == sel.substring(1)) return true
            continue
        }
        if (sel.startsWith(".")) {
            val classes = (el.getAttribute("class") ?: "").split(Regex("\\s+"))
            if (classes.contains(sel.substring(1))) return true
            continue
        }
        if (el.tagName == sel || el.tagName.lowercase() == sel.lowercase()) return true
    }
    return false
}

/**
 * 已解析的 XML 文档：`documentElement` 是根元素。
 *
 * 注意：这里的 `querySelector` 只用于镜像 TS 侧的 `doc.querySelector('parsererror')` 探针。
 * Kotlin 侧解析失败一律返回 `null`（见 [parseXml]），所以它实际总是命中普通元素查询。
 */
internal interface XmlDocument {
    val documentElement: XmlElement?

    /** 对应 `doc.querySelector(selector)`（深度优先，从 `documentElement` 的后代里找）。 */
    fun querySelector(selector: String): XmlElement?
}

/**
 * 解析 XML 文本，对应 `new DOMParser().parseFromString(text, 'image/svg+xml')`。
 *
 * ⚠️ 与浏览器 DOM 的差异：浏览器**从不抛异常**，格式错误时返回一棵含 `<parsererror>` 的树；
 * 而 `javax.xml` 是抛 `SAXException`。这里统一成「**格式错误返回 `null`**」——
 * 调用方（[com.lasergrbl.core.vector.convertSvgToGcode]）据此抛出与 v2 相同的 `Error`。
 * 这样 JVM/Android 两个 actual 的行为完全一致，也不必伪造 `parsererror` 节点的内容。
 */
internal expect fun parseXml(text: String): XmlDocument?
