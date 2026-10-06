package com.lasergrbl.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * 雕刻任务前台服务：在雕刻运行期间把本应用提升为前台服务，避免被系统冻结 / 回收，
 * 并显示一条不可划掉的常驻进度通知。
 *
 * <p>服务本身不做任何串口通信（通信仍在 WebView 与插件中完成），只负责「保活 + 通知」：
 * <ul>
 *     <li>{@link #ACTION_START}：启动服务并显示通知（重复调用只更新，不会产生多条通知）；</li>
 *     <li>{@link #ACTION_UPDATE}：更新通知文字与进度；</li>
 *     <li>{@link #ACTION_STOP}：停止前台、移除通知并结束服务。</li>
 * </ul>
 * 由 {@code KeepAlivePlugin} 通过 {@link #start(Context, String, String, int)} /
 * {@link #update(Context, String, Integer)} / {@link #stop(Context)} 驱动。</p>
 */
public class EngraveService extends Service {

    /** 通知渠道 id。 */
    private static final String CHANNEL_ID = "igrbl_engrave";

    /** 通知渠道名称（系统设置中显示）。 */
    private static final String CHANNEL_NAME = "雕刻进度";

    /** 默认通知标题。 */
    private static final String DEFAULT_TITLE = "iGRBL 正在雕刻";

    /** 通知 id（固定值：同一任务始终复用一条通知）。 */
    private static final int NOTIFICATION_ID = 1001;

    /** 进度为未知时的取值（对应 {@code NotificationCompat.Builder#setProgress} 的 indeterminate）。 */
    private static final int PROGRESS_UNKNOWN = -1;

    /** 启动并显示通知。 */
    public static final String ACTION_START = "com.lasergrbl.android.action.ENGRAVE_START";

    /** 更新通知内容。 */
    public static final String ACTION_UPDATE = "com.lasergrbl.android.action.ENGRAVE_UPDATE";

    /** 停止服务并移除通知。 */
    public static final String ACTION_STOP = "com.lasergrbl.android.action.ENGRAVE_STOP";

    /** 通知标题（可由前端覆盖，默认「iGRBL 正在雕刻」）。 */
    public static final String EXTRA_TITLE = "title";

    /** 通知正文（形如「文件名 · 42%」）。 */
    public static final String EXTRA_TEXT = "text";

    /** 进度百分比（0~100，{@link #PROGRESS_UNKNOWN} 表示不确定进度）。 */
    public static final String EXTRA_PROGRESS = "progress";

    /** 当前存活的实例（null 表示服务未运行）；所有调用都发生在主线程，故无需加锁。 */
    private static EngraveService instance;

    /** 是否已进入前台（进入前台后才能安全地更新 / 移除通知）。 */
    private volatile boolean foreground = false;

    /** 当前通知标题。 */
    private String title = "";

    /** 当前通知正文。 */
    private String text = "";

    /** 当前进度百分比。 */
    private int progress = PROGRESS_UNKNOWN;

    // ================= 对外入口（供 KeepAlivePlugin 调用） =================

    /**
     * 启动（或重复启动）前台服务并显示雕刻进度通知。
     *
     * @param context  调用方上下文
     * @param title    通知标题，为空时使用默认标题
     * @param text     通知正文
     * @param progress 进度百分比：0~100，负数表示不确定进度
     */
    public static void start(Context context, String title, String text, int progress) {
        Intent intent = new Intent(context, EngraveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_TEXT, text);
        intent.putExtra(EXTRA_PROGRESS, progress);
        startServiceSafely(context, intent, true);
    }

    /**
     * 更新通知内容。
     *
     * @param text     新的正文（null 表示保持不变）
     * @param progress 新的进度百分比（null 表示保持不变）
     */
    public static void update(Context context, String text, Integer progress) {
        EngraveService current = instance;
        // 服务未运行时无需（也无法）更新通知，直接忽略
        if (current == null || !current.foreground) {
            return;
        }
        current.applyUpdate(text, progress);
    }

    /** 停止前台、移除通知并结束服务（幂等）。 */
    public static void stop(Context context) {
        EngraveService current = instance;
        if (current != null) {
            current.stopForegroundInternal();
            return;
        }
        // 服务已不在运行：兜底再发一次停止指令，确保不残留通知
        Intent intent = new Intent(context, EngraveService.class);
        intent.setAction(ACTION_STOP);
        startServiceSafely(context, intent, false);
    }

    /** 前台服务是否正在运行（供插件返回状态使用）。 */
    public static boolean isRunning() {
        EngraveService current = instance;
        return current != null && current.foreground;
    }

    /**
     * 发送服务指令。
     *
     * @param startForegroundService 为 true 时使用 {@code startForegroundService}（服务需在 5 秒内进入前台）
     */
    private static void startServiceSafely(Context context, Intent intent, boolean startForegroundService) {
        if (context == null || intent == null) {
            return;
        }
        try {
            if (startForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // 系统限制后台启动服务或服务被禁止时忽略，避免拖垮前端逻辑
            e.printStackTrace();
        }
    }

    // ================= Service 生命周期 =================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopForegroundInternal();
            return START_NOT_STICKY;
        }

        // ACTION_START / ACTION_UPDATE：更新字段后统一刷新通知
        if (ACTION_START.equals(action)) {
            String newTitle = intent.getStringExtra(EXTRA_TITLE);
            if (newTitle != null && !newTitle.trim().isEmpty()) {
                title = newTitle;
            }
            text = safeText(intent.getStringExtra(EXTRA_TEXT), text);
            progress = intent.getIntExtra(EXTRA_PROGRESS, progress);
        } else if (ACTION_UPDATE.equals(action)) {
            if (intent.hasExtra(EXTRA_TEXT)) {
                text = safeText(intent.getStringExtra(EXTRA_TEXT), text);
            }
            if (intent.hasExtra(EXTRA_PROGRESS)) {
                progress = intent.getIntExtra(EXTRA_PROGRESS, progress);
            }
        }

        if (ACTION_START.equals(action)) {
            // 首次启动（或重复 start）：声明前台服务并显示通知
            startForegroundInternal();
        } else if (ACTION_UPDATE.equals(action)) {
            // 未进入前台时更新通知没有意义（前台服务通知由 startForeground 统一发布）
            notifyInternal();
        } else {
            // 服务被系统重建但未收到 START（intent 为 null）：补一次前台声明，避免 5 秒内未进入前台被判定 ANR
            startForegroundInternal();
        }

        // 不粘性：任务的生命周期完全由前端控制，避免系统重启服务后留下僵尸通知
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        foreground = false;
        // 兜底：确保通知被移除（例如系统直接销毁服务）
        cancelNotification();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 纯保活服务，不提供绑定
        return null;
    }

    // ================= 内部实现 =================

    /** 进入前台并显示通知。 */
    private void startForegroundInternal() {
        if (foreground) {
            // 已在运行：只刷新内容，避免重复 startForeground
            notifyInternal();
            return;
        }
        NotificationManager manager = getManager();
        if (manager == null) {
            // 没有通知管理器（极少数定制系统）：退化为普通服务，至少保留保活能力
            return;
        }
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14（API 34）起必须显式声明前台服务类型，且需配套 FOREGROUND_SERVICE_DATA_SYNC 权限
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
        } catch (Exception e) {
            // 前台服务被系统拒绝（例如缺少权限）时不崩溃：仅记录，前端仍可正常工作
            e.printStackTrace();
        }
    }

    /** 应用一次增量更新。 */
    private void applyUpdate(String newText, Integer newProgress) {
        if (newText != null) {
            text = newText;
        }
        if (newProgress != null) {
            progress = newProgress;
        }
        notifyInternal();
    }

    /** 刷新通知（仅在前台状态下有效）。 */
    private void notifyInternal() {
        if (!foreground) {
            return;
        }
        NotificationManager manager = getManager();
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            // 通知被系统限制时忽略
            e.printStackTrace();
        }
    }

    /** 停止前台、移除通知并结束服务（幂等）。 */
    private void stopForegroundInternal() {
        foreground = false;
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            // 未进入前台时部分系统会抛异常，忽略即可
        }
        cancelNotification();
        stopSelf();
    }

    /** 取消通知（幂等）。 */
    private void cancelNotification() {
        NotificationManager manager = getManager();
        if (manager == null) {
            return;
        }
        try {
            manager.cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            // 忽略
        }
    }

    /** 构建常驻进度通知（无任何操作按钮，不可划掉）。 */
    private Notification buildNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_engrave)
                .setContentTitle(title != null && !title.isEmpty() ? title : DEFAULT_TITLE)
                .setContentText(text)
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        if (progress >= 0) {
            builder.setProgress(100, Math.min(100, progress), false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    /** 点击通知回到主界面。 */
    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        // requestCode 固定：同一任务的 PendingIntent 复用即可
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    /** 创建通知渠道（Android 8.0 及以上必需）。 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getManager();
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // 低重要性：常驻通知不发声、不弹横幅，只在状态栏展示进度
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("雕刻任务运行期间的常驻进度通知");
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private NotificationManager getManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 取新正文；为 null 时沿用旧值（避免一次更新把正文清空）。 */
    private static String safeText(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
