package com.lasergrbl.android;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Android 原生蓝牙串口通信插件（经典蓝牙 SPP）。
 *
 * <p>前端通过插件名 {@code BluetoothSerial} 调用，方法包括：{@code list}（已配对设备）、
 * {@code open}、{@code close}、{@code write}、{@code openSettings}；事件包括
 * {@code data}（串口数据）与 {@code closed}（断开）。</p>
 *
 * <p>GRBL 蓝牙模块（如 HC-05 / HC-06）使用经典蓝牙串口协议（SPP），需先在系统设置中完成配对。</p>
 */
@CapacitorPlugin(
        name = "BluetoothSerial",
        permissions = {
                @Permission(
                        alias = "bluetoothConnect",
                        strings = { Manifest.permission.BLUETOOTH_CONNECT })
        })
public class BluetoothSerialPlugin extends Plugin {

    /** 经典蓝牙串口（SPP）服务的标准 UUID。 */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** 读取缓冲区大小（字节）。 */
    private static final int READ_BUFFER_SIZE = 4096;

    /** 默认波特率（SPP 实际不区分波特率，仅保持接口一致）。 */
    private static final int DEFAULT_BAUD_RATE = 115200;

    private BluetoothSocket bluetoothSocket;

    /** 读取线程运行标志。 */
    private volatile boolean reading = false;
    private Thread readThread;

    /** 当前已打开的蓝牙设备地址（null 表示未打开）。 */
    private volatile String openedAddress = null;

    /** 避免重复发出 closed 事件。 */
    private boolean closedNotified = false;

    /** 串行化写入，避免多线程同时写同一端口。 */
    private final Object writeLock = new Object();

    // --- 权限请求续接 ---
    private PluginCall pendingCall;
    private String pendingAction;

    // --- 蓝牙断开监听相关 ---
    private boolean disconnectReceiverRegistered = false;
    private final BroadcastReceiver disconnectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                    && !BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action)) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null && openedAddress != null && !openedAddress.equals(device.getAddress())) {
                return;
            }
            // 设备断开 -> 清理并通知前端
            closePortInternal();
            notifyClosedOnce();
        }
    };

    /**
     * 枚举已配对的蓝牙设备。
     */
    @PluginMethod
    public void list(PluginCall call) {
        withPermission(call, "list");
    }

    /**
     * 打开指定蓝牙串口。
     */
    @PluginMethod
    public void open(PluginCall call) {
        withPermission(call, "open");
    }

    /**
     * 关闭当前蓝牙串口连接。
     */
    @PluginMethod
    public void close(PluginCall call) {
        closePortInternal();
        call.resolve();
    }

    /**
     * 向蓝牙串口写入数据。
     *
     * <p>{@code encoding} 为 {@code base64} 时先做 base64 解码；否则按 UTF-8 文本写入。</p>
     */
    @PluginMethod
    public void write(PluginCall call) {
        String data = call.getString("data");
        if (data == null) {
            call.reject("缺少 data 参数");
            return;
        }
        String encoding = call.getString("encoding", "utf8");

        BluetoothSocket socket = bluetoothSocket;
        if (socket == null || !reading) {
            call.reject("蓝牙串口未打开");
            return;
        }

        byte[] bytes;
        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                bytes = Base64.decode(data, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                call.reject("base64 解码失败: " + e.getMessage());
                return;
            }
        } else {
            bytes = data.getBytes(StandardCharsets.UTF_8);
        }

        if (bytes.length == 0) {
            call.resolve();
            return;
        }

        synchronized (writeLock) {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                call.reject("写入蓝牙失败: " + e.getMessage());
                return;
            }
        }
        call.resolve();
    }

    /**
     * 打开系统蓝牙设置界面，便于用户配对雕刻机蓝牙模块。
     */
    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("无法打开蓝牙设置: " + e.getMessage());
        }
    }

    @Override
    protected void handleOnDestroy() {
        closePortInternal();
        super.handleOnDestroy();
    }

    // ================= 权限处理 =================

    /**
     * Android 12（API 31）起访问蓝牙需要运行时权限 {@code BLUETOOTH_CONNECT}。
     * 低版本无需该权限，直接执行。
     */
    private void withPermission(PluginCall call, String action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || getPermissionState("bluetoothConnect") == PermissionState.GRANTED) {
            dispatch(call, action);
            return;
        }
        pendingCall = call;
        pendingAction = action;
        requestPermissionForAlias("bluetoothConnect", call, "permissionCallback");
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        PluginCall saved = pendingCall;
        String action = pendingAction;
        pendingCall = null;
        pendingAction = null;
        if (saved == null || action == null) {
            call.reject("蓝牙权限请求状态丢失");
            return;
        }
        if (getPermissionState("bluetoothConnect") == PermissionState.GRANTED) {
            dispatch(saved, action);
        } else {
            saved.reject("未获得蓝牙权限");
        }
    }

    private void dispatch(PluginCall call, String action) {
        switch (action) {
            case "list":
                doList(call);
                break;
            case "open":
                doOpen(call);
                break;
            default:
                call.reject("未知操作: " + action);
        }
    }

    // ================= 具体实现 =================

    private void doList(PluginCall call) {
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null) {
            call.reject("设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            call.reject("蓝牙未开启，请先开启蓝牙");
            return;
        }

        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            call.reject("缺少蓝牙权限: " + e.getMessage());
            return;
        }

        JSArray devices = new JSArray();
        if (bonded != null) {
            for (BluetoothDevice device : bonded) {
                String address = device.getAddress();
                String name;
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                JSObject info = new JSObject();
                info.put("address", address);
                info.put("name", (name != null && !name.isEmpty()) ? name : address);
                devices.put(info);
            }
        }

        JSObject ret = new JSObject();
        ret.put("devices", devices);
        call.resolve(ret);
    }

    private void doOpen(PluginCall call) {
        String address = call.getString("address");
        if (address == null) {
            call.reject("缺少 address 参数");
            return;
        }

        BluetoothAdapter adapter = getAdapter();
        if (adapter == null) {
            call.reject("设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            call.reject("蓝牙未开启，请先开启蓝牙");
            return;
        }

        // 打开新设备前先释放已有连接
        closePortInternal();

        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            call.reject("无效的蓝牙地址: " + address);
            return;
        }

        // 连接是阻塞操作，放到后台线程执行，避免阻塞主线程
        final PluginCall openCall = call;
        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                // 发现过程会显著拖慢连接，尝试取消（部分系统需要额外的扫描权限，失败则忽略）
                try {
                    adapter.cancelDiscovery();
                } catch (SecurityException ignored) {
                    // ignore
                }

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                bluetoothSocket = socket;
                openedAddress = address;
                closedNotified = false;
                registerDisconnectReceiver();

                reading = true;
                readThread = new Thread(this::readLoop, "BluetoothSerial-Read");
                readThread.setDaemon(true);
                readThread.start();

                openCall.resolve();
            } catch (SecurityException se) {
                closeQuietly(socket);
                openCall.reject("缺少蓝牙权限: " + se.getMessage());
            } catch (IOException e) {
                closeQuietly(socket);
                openCall.reject("连接蓝牙设备失败: " + e.getMessage());
            }
        }, "BluetoothSerial-Connect").start();
    }

    /** 后台线程：持续阻塞读取蓝牙串口数据并转发给前端。 */
    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        BluetoothSocket socket = bluetoothSocket;
        try {
            InputStream in = socket.getInputStream();
            while (reading && socket != null) {
                int len = in.read(buffer);
                if (len < 0) {
                    break;
                }
                if (len > 0) {
                    // GRBL 输出为 ASCII，逐块按 UTF-8 解码即可
                    String text = new String(buffer, 0, len, StandardCharsets.UTF_8);
                    JSObject payload = new JSObject();
                    payload.put("data", text);
                    notifyListeners("data", payload);
                }
            }
        } catch (IOException e) {
            // 读取异常一般意味着设备断开或连接失效
        } finally {
            if (reading) {
                closePortInternal();
                notifyClosedOnce();
            }
        }
    }

    /** 关闭串口、结束读取线程并清理资源。 */
    private synchronized void closePortInternal() {
        reading = false;
        openedAddress = null;

        Thread thread = readThread;
        readThread = null;

        BluetoothSocket socket = bluetoothSocket;
        bluetoothSocket = null;
        closeQuietly(socket);

        unregisterDisconnectReceiver();

        // 注意：读取线程自身触发清理时不能 join 自己，否则会死锁
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 只发出一次 closed 事件。 */
    private void notifyClosedOnce() {
        synchronized (this) {
            if (closedNotified) {
                return;
            }
            closedNotified = true;
        }
        notifyListeners("closed", new JSObject());
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager manager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }

    private void registerDisconnectReceiver() {
        if (disconnectReceiverRegistered) {
            return;
        }
        Context ctx = getContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(disconnectReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(disconnectReceiver, filter);
        }
        disconnectReceiverRegistered = true;
    }

    private synchronized void unregisterDisconnectReceiver() {
        if (!disconnectReceiverRegistered) {
            return;
        }
        disconnectReceiverRegistered = false;
        try {
            getContext().unregisterReceiver(disconnectReceiver);
        } catch (IllegalArgumentException ignored) {
            // 未注册或已注销时忽略
        }
    }
}
