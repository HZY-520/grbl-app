package com.lasergrbl.android;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * 后台保活插件：把雕刻任务的开始 / 进度 / 结束转成前台服务（{@link EngraveService}）的启停。
 *
 * <p>前端通过插件名 {@code KeepAlive} 调用，方法包括：{@code start}、{@code update}、{@code stop}。</p>
 *
 * <p>Android 13（API 33）起显示通知需要运行时权限 {@code POST_NOTIFICATIONS}：
 * 未授权时本插件不会中断雕刻流程，而是照常启动前台服务，并在结果中标记
 * {@code notification: "denied"}，由前端决定是否提示用户。</p>
 */
@CapacitorPlugin(
        name = "KeepAlive",
        permissions = {
                @Permission(
                        alias = "notifications",
                        strings = { Manifest.permission.POST_NOTIFICATIONS })
        })
public class KeepAlivePlugin extends Plugin {

    /** 通知是否可见：已显示。 */
    private static final String NOTIFICATION_SHOWN = "shown";

    /** 通知是否可见：缺少运行时权限，通知不会显示（服务仍在保活）。 */
    private static final String NOTIFICATION_DENIED = "denied";

    /** 通知是否可见：系统不支持（无通知管理器）。 */
    private static final String NOTIFICATION_UNSUPPORTED = "unsupported";

    /** 默认通知标题。 */
    private static final String DEFAULT_TITLE = "iGRBL 正在雕刻";

    /**
     * 启动前台服务并显示常驻进度通知（可重复调用，不会产生多条通知）。
     *
     * <p>参数：{@code title}（标题，可选）、{@code text}（正文，可选）、
     * {@code progress}（0~100，可选）。</p>
     */
    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && getPermissionState("notifications") != PermissionState.GRANTED) {
            // 首次调用时向用户申请通知权限，结果在 permissionCallback 中处理
            requestPermissionForAlias("notifications", call, "permissionCallback");
            return;
        }
        doStart(call, true);
    }

    /**
     * 更新通知正文与进度。
     *
     * <p>参数：{@code text}（正文，可选）、{@code progress}（0~100，可选）。
     * 服务未运行（或通知被系统关闭）时静默忽略，不会抛异常。</p>
     */
    @PluginMethod
    public void update(PluginCall call) {
        String text = call.getString("text");
        Double progress = call.getDouble("progress");

        boolean visible = isNotificationVisible();
        EngraveService.update(getContext(), text, toPercent(progress));

        JSObject ret = new JSObject();
        ret.put("running", EngraveService.isRunning());
        ret.put("notification", visible ? NOTIFICATION_SHOWN : NOTIFICATION_DENIED);
        call.resolve(ret);
    }

    /**
     * 停止前台服务并移除通知（幂等：未运行时也不报错）。
     */
    @PluginMethod
    public void stop(PluginCall call) {
        EngraveService.stop(getContext());

        JSObject ret = new JSObject();
        ret.put("running", false);
        ret.put("notification", NOTIFICATION_SHOWN);
        call.resolve(ret);
    }

    @Override
    protected void handleOnDestroy() {
        // 随 Activity 一起销毁时清理前台服务，避免残留僵尸通知
        EngraveService.stop(getContext());
        super.handleOnDestroy();
    }

    // ================= 权限回调 =================

    /**
     * 通知权限申请结果：无论是否授权都启动前台服务（保活优先），
     * 仅通过返回值告知前端通知能否显示。
     */
    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        boolean granted = getPermissionState("notifications") == PermissionState.GRANTED;
        doStart(call, granted);
    }

    // ================= 内部实现 =================

    /** 启动（或刷新）前台服务，并返回当前状态。 */
    private void doStart(PluginCall call, boolean notificationGranted) {
        String title = call.getString("title");
        if (title == null || title.trim().isEmpty()) {
            title = DEFAULT_TITLE;
        }
        String text = call.getString("text");
        if (text == null) {
            text = "";
        }
        Double progress = call.getDouble("progress");

        EngraveService.start(getContext(), title, text, toPercent(progress));

        boolean visible = notificationGranted && isNotificationVisible();
        JSObject ret = new JSObject();
        ret.put("running", true);
        ret.put("foreground", true);
        ret.put("notification", visible ? NOTIFICATION_SHOWN : NOTIFICATION_DENIED);
        if (!visible) {
            ret.put("reason", "未获得通知权限，前台服务已启动但通知不可见");
        }
        call.resolve(ret);
    }

    /**
     * 判断通知当前能否显示：API 33 以下无需权限；
     * 通知管理器缺失（极少数定制系统）时视为不支持。
     */
    private boolean isNotificationVisible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && getPermissionState("notifications") != PermissionState.GRANTED) {
            return false;
        }
        return getContext() != null
                && getContext().getSystemService(Context.NOTIFICATION_SERVICE) != null;
    }

    /** 把前端传入的百分比规整为 0~100 的整数；非法值返回 -1（不确定进度）。 */
    private static Integer toPercent(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        int percent = (int) Math.round(value);
        if (percent < 0) {
            return 0;
        }
        return Math.min(100, percent);
    }
}
