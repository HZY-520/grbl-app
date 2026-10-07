package com.lasergrbl.android.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 统一**G 代码 / 文本文件选择器** —— v2 `src/ui/utils.ts:14-18` 的隐藏 `<input type=file>` 的
 * Android 对应物。
 *
 * 与 `FilePicker.kt`（图片 / SVG 用）的关系：那个走 `GetContent("image/*")`，这个走
 * `OpenDocument(arrayOf("*/*"))` 并且**按 UTF-8 优先、失败回退 GBK** 解码 —— 雕刻机导出的
 * G 代码里有相当一部分是 GBK（Windows 下的 LaserGRBL 默认），
 * 直接用 `readText()` 会得到替换字符。
 *
 * 用法：
 * ```kotlin
 * val picker = rememberGcodeFilePicker(context) { name, text -> grbl.loadGcodeText(name, text) }
 * // 在按钮的 onClick 里：
 * picker.launch()
 * ```
 */
class GcodeFilePicker internal constructor(
    private val launchFn: () -> Unit
) {
    /** 打开系统文件选择器。 */
    fun launch() = launchFn()
}

@Composable
fun rememberGcodeFilePicker(
    context: Context,
    onLoaded: (name: String, text: String) -> Unit
): GcodeFilePicker {
    // 回调里要读最新 context，用 rememberUpdatedState 语义（这里 onLoaded 由调用方每次组合重建，
    // 所以直接用 remember 缓存 launcher、在 lambda 里引用最新 onLoaded 会拿到旧值 ——
    // 因此把 onLoaded 也放进 remember 的 key 里，简单且正确）
    val currentContext = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = currentContext.contentResolver
        val name = queryDisplayName(currentContext, uri)
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            onLoaded(name, "")
            return@rememberLauncherForActivityResult
        }
        onLoaded(name, decodeText(bytes))
    }
    return remember(launcher) { GcodeFilePicker { launcher.launch(arrayOf("*/*")) } }
}

/** 用 UTF-8 严格解码；失败（含替换字符）回退 GBK。 */
private fun decodeText(bytes: ByteArray): String {
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (utf8 != null) return utf8
    return runCatching { String(bytes, charset("GBK")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
}

/** 从 `content://` URI 取显示名（取不到时用时间戳兜底）。 */
private fun queryDisplayName(context: Context, uri: android.net.Uri): String {
    var cursor: android.database.Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, null, null, null, null)
        val idx = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) ?: -1
        if (cursor != null && idx >= 0 && cursor.moveToFirst()) {
            cursor.getString(idx) ?: fallbackName()
        } else {
            uri.lastPathSegment?.substringAfterLast('/') ?: fallbackName()
        }
    } catch (_: Throwable) {
        fallbackName()
    } finally {
        cursor?.close()
    }
}

private fun fallbackName(): String = "imported-${System.currentTimeMillis()}.gcode"

/** 行读取（GBK 回退路径用；保留给将来的流式大文件读取）。 */
internal fun readLines(context: Context, uri: android.net.Uri): List<String>? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readLines()
        }
    }.getOrNull()
