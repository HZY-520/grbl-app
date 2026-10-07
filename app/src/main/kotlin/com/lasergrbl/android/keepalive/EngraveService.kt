package com.lasergrbl.android.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lasergrbl.android.MainActivity
import com.lasergrbl.android.R

/**
 * 雕刻任务前台服务：雕刻运行期间把应用提升为前台服务（避免被系统冻结/回收），
 * 并显示一条不可划掉的常驻进度通知。**行为逐条照搬 v2 `EngraveService.java`**：
 *
 *  * 通知渠道 `igrbl_engrave`、固定通知 id **1001**、无任何操作按钮、不可划掉（`setOngoing`）；
 *  * `IMPORTANCE_LOW` + `setOnlyAlertOnce` + `setShowWhen(false)`：常驻进度不发声、不弹横幅；
 *  * 进度用 `setProgress(100, percent, false)`，百分比 < 0 表示不确定进度；
 *  * `START_NOT_STICKY`；intent 为 null（服务被系统重建）时**补一次前台声明**，
 *    避免 5 秒内未进入前台被判 ANR；
 *  * `onDestroy` 兜底取消通知，杜绝僵尸通知。
 *
 * 3.0 的架构差异（与 v2 的差异仅此三点，且都不改变可观察行为）：
 *  1. 驱动方从 Capacitor 的 `KeepAlivePlugin` 换成了 [AndroidNativeKeepAlive]（实现
 *     `:core` 的 `NativeKeepAlive` 接缝）→ 节流策略在 `:core` 里可单测；
 *  2. 通知权限（API 33+ 的 `POST_NOTIFICATIONS`）由 [NotificationPermissionGate] 负责，
 *     服务本身**不**申请权限：没权限也照样进前台（保活优先），只是通知不可见；
 *  3. 任务名截断（24 字符）与 `文件名 · 百分比%` 文案由 `:core` 的
 *     `KeepAliveController` + `formatText` 产出，本服务只负责显示。
 */
class EngraveService : Service() {

    /** 通知渠道 id（v2 `CHANNEL_ID`）。 */
    private val channelId = CHANNEL_ID

    /** 当前存活的实例（null 表示服务未运行）。所有调用都发生在主线程，故无需加锁。 */
    private var foreground = false

    private var title = ""
    private var text = ""
    private var progress = PROGRESS_UNKNOWN

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (ACTION_STOP == action) {
            stopForegroundInternal()
            return START_NOT_STICKY
        }

        if (ACTION_START == action) {
            val newTitle = intent.getStringExtra(EXTRA_TITLE)
            if (!newTitle.isNullOrBlank()) title = newTitle
            text = intent.getStringExtra(EXTRA_TEXT) ?: text
            progress = intent.getIntExtra(EXTRA_PROGRESS, progress)
        } else if (ACTION_UPDATE == action) {
            if (intent.hasExtra(EXTRA_TEXT)) text = intent.getStringExtra(EXTRA_TEXT) ?: text
            if (intent.hasExtra(EXTRA_PROGRESS)) progress = intent.getIntExtra(EXTRA_PROGRESS, progress)
        }

        when (action) {
            ACTION_START -> startForegroundInternal()
            ACTION_UPDATE -> notifyInternal()
            // 服务被系统重建但没有 START（intent == null）：补一次前台声明（v2 行为）
            else -> startForegroundInternal()
        }

        // 不粘性：任务生命周期完全由应用层控制，避免系统重启服务后留下僵尸通知
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        foreground = false
        cancelNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= 内部实现 =================

    private fun startForegroundInternal() {
        if (foreground) {
            // 已在运行：只刷新内容，避免重复 startForeground
            notifyInternal()
            return
        }
        val manager = getManager() ?: return
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14（API 34）起必须显式声明前台服务类型，且需 FOREGROUND_SERVICE_DATA_SYNC 权限
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (t: Throwable) {
            // 前台服务被系统拒绝（例如缺权限）时不崩溃：仅记录，应用仍可正常工作
            t.printStackTrace()
        }
    }

    private fun notifyInternal() {
        if (!foreground) return
        val manager = getManager() ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun stopForegroundInternal() {
        foreground = false
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
            // 未进入前台时部分系统会抛异常，忽略
        }
        cancelNotification()
        stopSelf()
    }

    private fun cancelNotification() {
        val manager = getManager() ?: return
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
            // 忽略
        }
    }

    /** 构建常驻进度通知（无操作按钮、不可划掉、不发声）。 */
    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_engrave)
            .setContentTitle(title.ifEmpty { DEFAULT_TITLE })
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (progress >= 0) {
            builder.setProgress(100, minOf(100, progress), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 点击通知回到主界面（v2 用 FLAG_ACTIVITY_SINGLE_TOP | CLEAR_TOP + IMMUTABLE）。 */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        // requestCode 固定：同一任务的 PendingIntent 复用
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    /** 创建通知渠道（Android 8.0+ 必需）。 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getManager() ?: return
        val channel = NotificationChannel(channelId, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        channel.description = "雕刻任务运行期间的常驻进度通知"
        channel.setShowBadge(false)
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    private fun getManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        /** 通知渠道 id（v2 固定值，改名会让老用户的通知设置失效）。 */
        const val CHANNEL_ID = "igrbl_engrave"

        /** 通知渠道名称（系统设置里显示）。 */
        const val CHANNEL_NAME = "雕刻进度"

        /** 默认通知标题。 */
        const val DEFAULT_TITLE = "iGRBL 正在雕刻"

        /** 通知 id（固定值：同一任务始终复用一条通知）。 */
        const val NOTIFICATION_ID = 1001

        /** 进度未知（对应 `setProgress` 的 indeterminate）。 */
        const val PROGRESS_UNKNOWN = -1

        /** 启动并显示通知。 */
        const val ACTION_START = "com.lasergrbl.android.action.ENGRAVE_START"

        /** 更新通知内容。 */
        const val ACTION_UPDATE = "com.lasergrbl.android.action.ENGRAVE_UPDATE"

        /** 停止服务并移除通知。 */
        const val ACTION_STOP = "com.lasergrbl.android.action.ENGRAVE_STOP"

        /** 通知标题。 */
        const val EXTRA_TITLE = "title"

        /** 通知正文（形如「文件名 · 42%」）。 */
        const val EXTRA_TEXT = "text"

        /** 进度百分比（0~100，[PROGRESS_UNKNOWN] 表示不确定）。 */
        const val EXTRA_PROGRESS = "progress"

        /** 当前存活的实例。 */
        @Volatile
        private var instance: EngraveService? = null

        /** 前台服务是否正在运行。 */
        fun isRunning(): Boolean = instance?.foreground == true

        /**
         * 启动（或重复启动）前台服务并显示通知。
         *
         * @param title    标题，空则用 [DEFAULT_TITLE]
         * @param text     正文
         * @param progress 0~100；负数表示不确定进度
         */
        fun start(context: Context?, title: String, text: String, progress: Int) {
            val ctx = context ?: return
            val intent = Intent(ctx, EngraveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
            }
            startServiceSafely(ctx, intent, startForegroundService = true)
        }

        /**
         * 更新通知内容（服务未运行或未进入前台时静默忽略）。
         *
         * @param text     新正文（null 保持不变）
         * @param progress 新进度（null 保持不变）
         */
        fun update(text: String?, progress: Int?) {
            val current = instance ?: return
            if (!current.foreground) return
            current.applyUpdate(text, progress)
        }

        /** 停止前台、移除通知并结束服务（**幂等**）。 */
        fun stop(context: Context?) {
            val current = instance
            if (current != null) {
                current.stopForegroundInternal()
                return
            }
            // 服务已不在运行：兜底再发一次停止指令，确保不残留通知
            val ctx = context ?: return
            val intent = Intent(ctx, EngraveService::class.java).apply { action = ACTION_STOP }
            startServiceSafely(ctx, intent, startForegroundService = false)
        }

        private fun startServiceSafely(context: Context, intent: Intent, startForegroundService: Boolean) {
            try {
                if (startForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // 系统限制后台启动服务或服务被禁止时忽略，避免拖垮前端逻辑
                t.printStackTrace()
            }
        }
    }

    private fun applyUpdate(newText: String?, newProgress: Int?) {
        if (newText != null) text = newText
        if (newProgress != null) progress = newProgress
        notifyInternal()
    }
}
