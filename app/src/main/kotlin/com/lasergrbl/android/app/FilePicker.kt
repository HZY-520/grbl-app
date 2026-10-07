package com.lasergrbl.android.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 文件选择接缝 —— 对应 v2 `src/ui/utils.ts` 里的隐藏 `<input type="file">` + `pickFile()`。
 *
 * ### 为什么不用 `Intent.ACTION_GET_CONTENT` 手写
 * `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` 已经处理了
 * Activity 结果注册、配置变更后的回调重放、以及 `onActivityResult` 的分发；
 * 手写 `startActivityForResult` 反而要自己管 requestCode 与生命周期。
 *
 * ### 与 v2 `pickFile` 的逐条对应
 * | v2 | 这里 |
 * | --- | --- |
 * | 隐藏的 `input` 元素（`type=file`、图片 accept）+ `click()` | `launch` 传图片 MIME（调用方传 `image` 前缀通配） |
 * | `accept=".svg,image/svg+xml"` | `launch` 传全通配，提交后按 MIME / 后缀校验（见 [PickedFile]） |
 * | `readAsDataURL`（图片） | [PickedFile.bitmap]（`BitmapFactory.decodeStream`，按尺寸自动降采样） |
 * | `readAsText`（SVG 源码） | [PickedFile.text] |
 * | `file.name` | [PickedFile.name]（`OpenableColumns.DISPLAY_NAME`，取不到时回退 URI 末段） |
 *
 * ### 为什么不直接返回 `Uri`
 * 屏幕拿到 URI 后**不能**在主线程上 `openInputStream` + 解码大图（ANR），
 * 也不能忘记关闭流；这里把「打开流 → 读取 → 解码 → 关流」一次做完，
 * 屏幕只拿到不可变的 [PickedFile]。
 */
data class PickedFile(
    /** 显示名（v2 的 `file.name`）。 */
    val name: String,
    /** 内容 URI 字符串（用于日志 / 二次读取）。 */
    val uri: String,
    /** ContentResolver 报告的 MIME，可能为空串。 */
    val mimeType: String,
    /** 文本内容（SVG 源码）；非文本时为 null。 */
    val text: String? = null,
    /** 解码后的位图；非图片或解码失败时为 null。 */
    val bitmap: Bitmap? = null
) {
    /** 是否像 SVG（MIME 或后缀）。 */
    val looksLikeSvg: Boolean
        get() = mimeType.contains("svg", ignoreCase = true) || name.endsWith(".svg", ignoreCase = true)
}

/**
 * 记住一个文件选择入口，并用挂起函数拉起系统选择器。
 *
 * 用法（屏幕里）：
 * ```
 * val picker = rememberFilePicker()
 * // 在协程里：
 * val file = picker.pick(IMAGE_MIME)
 * ```
 *
 * ⚠️ 一次只允许一个在途请求：同一屏里没有并发的选择入口，第二个请求会取消第一个
 * （返回 null），避免出现两个回调写到同一个结果槽。
 */
class FilePickerHandle internal constructor() {

    private var launch: ((mime: String, onResult: (Uri?) -> Unit) -> Unit)? = null
    private var pending: ((Uri?) -> Unit)? = null

    /** 读取文件所需的 `Context`，由 [rememberFilePicker] 在组合期注入。 */
    internal var context: android.content.Context? = null

    /** 由 [rememberFilePicker] 注入 launcher 调用（每次重组刷新）。 */
    internal fun bindLauncher(block: (mime: String) -> Unit) {
        launch = { mime, _ -> block(mime) }
    }

    /**
     * 拉起系统选择器并读取所选文件。
     *
     * @return 选中并读取成功时返回 [PickedFile]；用户取消或打开失败时返回 null。
     */
    suspend fun pick(mime: String = ANY_MIME): PickedFile? {
        val uri = awaitUri(mime) ?: return null
        val ctx = context ?: return null
        return readPickedFile(ctx, uri)
    }

    /** 只取 URI（不读取内容）。 */
    suspend fun pickUri(mime: String = ANY_MIME): Uri? = awaitUri(mime)

    private suspend fun awaitUri(mime: String): Uri? {
        pending?.invoke(null)
        return suspendCancellableCoroutine { cont ->
            pending = { uri -> if (cont.isActive) cont.resume(uri) }
            cont.invokeOnCancellation { pending = null }
            val start = launch
            if (start == null) {
                cont.resume(null)
            } else {
                start(mime) { uri ->
                    val result = pending
                    pending = null
                    result?.invoke(uri)
                }
            }
        }
    }

    /** 由 launcher 回调调用（主线程）。 */
    internal fun deliver(uri: Uri?) {
        val result = pending
        pending = null
        result?.invoke(uri)
    }
}

/** 见 [FilePickerHandle] 的说明。 */
@Composable
fun rememberFilePicker(): FilePickerHandle {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 结果回到 UI 线程；真正的读取在 pick() 的协程里做（那里会切 IO）。
        // 全应用同时只有一个屏幕在组合、每个屏幕只有一个选择入口，所以一个槽就够。
        activePicker?.deliver(uri)
    }
    // handle 用 remember 固定身份；launch 目标与 context 每次组合都刷新（launcher 会随重组更新）
    val handle = remember { FilePickerHandle() }
    handle.context = context
    handle.bindLauncher { mime -> launcher.launch(mime) }
    // 把当前 handle 记进槽：launcher 的回调 lambda 在组合期创建，用它才能拿到最新实例
    activePicker = handle
    return handle
}

/**
 * 当前在途的选择入口。
 *
 * 为什么用模块级槽：`rememberLauncherForActivityResult` 的回调 lambda 在**组合期**创建，
 * 而用户可能在选择器打开期间触发重组（甚至切屏）。把「当前 handle」放进槽里，
 * 回调就能拿到最新的实例；用闭包捕获 `handle` 会在重组后指向旧实例。
 *
 * ⚠️ 已知限制：**同一屏里不要挂两个 [rememberFilePicker]** —— 槽只保留最后一个，
 * 第一个的回调会被派发到第二个 handle 上。现有 5 个屏幕每个只有一个选择入口，
 * 所以这条限制不会触发。
 */
private var activePicker: FilePickerHandle? = null

/**
 * 打开内容 URI 并读成 [PickedFile]（IO 线程）。
 *
 * 对应 v2 `pickFile()` 的 `readAsDataURL` / `readAsText` 分支：
 *  * 文件名像 `.svg`、MIME 为 `image/svg+xml`、或 MIME 是 `text/` 前缀 → 读文本；
 *  * 文件名像 `.txt` / `.nc` / `.gcode` 且 MIME 不是 `image/` → 按文本读（UTF-8 优先、GBK 兜底，
 *    与 `GcodeFilePicker` 的容错一致：雕刻机导出的 `.nc` 常见 GBK）；
 *  * 其余 → 解码位图（`BitmapFactory.decodeStream`，超大图二次尝试降采样）。
 *
 * 文本读取有 16 MB 上限：用户若把视频/压缩包改后缀（或 MIME 上报失败）误选进来，
 * 不会把整个文件读成字符串把内存打爆。
 *
 * 解码失败（文件损坏、非图片）时返回带 `bitmap = null` 的 [PickedFile]，
 * 由屏幕决定是提示"请选择图片文件"还是"图片加载失败"。
 */
suspend fun readPickedFile(context: android.content.Context, uri: Uri): PickedFile? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val displayName = queryDisplayName(context, uri)
        val name = displayName.lowercase()

        val looksSvg = mime.contains("svg", ignoreCase = true) || name.endsWith(".svg")
        val looksImage = mime.startsWith("image/") && !looksSvg
        val looksText = looksSvg ||
            mime.startsWith("text/") ||
            name.endsWith(".txt") ||
            name.endsWith(".nc") ||
            name.endsWith(".gcode")

        if (looksText && !looksImage) {
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.size > MAX_TEXT_BYTES) return@withContext null
            return@withContext PickedFile(
                name = displayName,
                uri = uri.toString(),
                mimeType = mime,
                text = decodeText(bytes)
            )
        }

        PickedFile(
            name = displayName,
            uri = uri.toString(),
            mimeType = mime,
            bitmap = decodeBitmap(context, uri)
        )
    }

/**
 * 解码位图（流式，避免把整张原图读进内存）。
 *
 * 1. 第一遍 `inJustDecodeBounds` 拿尺寸，按 [MAX_DECODE_EDGE] 算 `inSampleSize`
 *    （一张 8000×6000 的 ARGB_8888 是 192 MB，真机上必 OOM）；
 * 2. 第二遍真正解码；若仍 OOM，退一步用 2 倍采样再试一次。
 *
 * 降采样只影响"原图预览"与线性模式的处理分辨率（线性另有 1600 px 上限），
 * 不改变光栅模式的算法参数。
 */
private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longEdge / sample > MAX_DECODE_EDGE) sample *= 2

    fun decodeWith(inSample: Int, config: Bitmap.Config): Bitmap? = runCatching {
        BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = config
        }.let { opts ->
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }.getOrNull()

    decodeWith(sample, Bitmap.Config.ARGB_8888)?.let { return it }
    return decodeWith(sample * 2, Bitmap.Config.RGB_565)
}

/**
 * 文本解码：UTF-8 优先，失败回退 GBK（与 `GcodeFilePicker` 的容错策略一致）。
 *
 * 「失败」的判据是严格解码抛异常，或解出的字符串里出现 U+FFFD 替换字符
 * （说明有非法 UTF-8 序列）。
 */
private fun decodeText(bytes: ByteArray): String {
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (utf8 != null && !utf8.contains('\uFFFD')) return utf8
    return runCatching { String(bytes, charset("GBK")) }
        .getOrElse { String(bytes, Charsets.ISO_8859_1) }
}

/** 读取显示名（`OpenableColumns.DISPLAY_NAME` 取不到时回退 URI 末段）。 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val value = cursor.getString(idx)
                if (!value.isNullOrEmpty()) return value
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件"
}

/** 解码长边上限（像素）。 */
private const val MAX_DECODE_EDGE = 4096

/** 文本读取上限（字节）—— 参见 [readPickedFile] 的说明。 */
private const val MAX_TEXT_BYTES = 16 * 1024 * 1024

/** 图片 MIME 通配（等价 v2 里 `accept` 的图片匹配；用加号拼接以免踩到注释嵌套）。 */
const val IMAGE_MIME: String = "image" + "/" + "*"

/** 全通配 MIME（SVG 等无法只靠 MIME 过滤的场合）。 */
const val ANY_MIME: String = "*" + "/" + "*"
