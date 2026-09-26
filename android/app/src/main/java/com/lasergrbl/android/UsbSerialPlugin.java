package com.lasergrbl.android;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android 原生 USB 串口通信插件（基于 usb-serial-for-android）。
 *
 * <p>前端通过插件名 {@code UsbSerial} 调用，方法包括：{@code list}、{@code open}、
 * {@code close}、{@code write}；事件包括 {@code data}（串口数据）与 {@code closed}（断开）。</p>
 */
@CapacitorPlugin(name = "UsbSerial")
public class UsbSerialPlugin extends Plugin {

    /** USB 权限广播的唯一 action（需与 PendingIntent 中保持一致）。 */
    private static final String ACTION_USB_PERMISSION = "com.lasergrbl.android.USB_PERMISSION";

    /** 读取缓冲区大小（字节）。 */
    private static final int READ_BUFFER_SIZE = 4096;

    /** 默认波特率。 */
    private static final int DEFAULT_BAUD_RATE = 115200;

    /** 写入阻塞超时（毫秒）。 */
    private static final int WRITE_TIMEOUT_MS = 2000;

    /** 阻塞读超时（0 表示无限等待，直到有数据或连接断开）。 */
    private static final int READ_TIMEOUT_MS = 0;

    /** 等待用户授权的最长时间（毫秒）。 */
    private static final long PERMISSION_TIMEOUT_MS = 20000L;

    private UsbManager usbManager;
    private UsbDeviceConnection usbConnection;
    private UsbSerialPort serialPort;

    /** 读取线程运行标志。 */
    private volatile boolean reading = false;
    private Thread readThread;

    /** 当前已打开的设备 id（-1 表示未打开）。 */
    private volatile int openedDeviceId = -1;

    /** 避免重复发出 closed 事件。 */
    private boolean closedNotified = false;

    /** 串行化写入，避免多线程同时写同一端口。 */
    private final Object writeLock = new Object();

    // --- 权限请求相关 ---
    private CountDownLatch permissionLatch;
    private volatile boolean permissionGranted = false;
    private BroadcastReceiver permissionReceiver;

    // --- USB 设备拔出监听相关 ---
    private boolean detachReceiverRegistered = false;
    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                if (device.getDeviceId() != openedDeviceId) {
                    return;
                }
            } else if (openedDeviceId < 0) {
                return;
            }
            // 设备被拔出 -> 清理并通知前端
            closePortInternal();
            notifyClosedOnce();
        }
    };

    /**
     * 枚举当前可识别的 USB 串口设备。
     */
    @PluginMethod
    public void list(PluginCall call) {
        UsbManager manager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            call.reject("无法获取 UsbManager");
            return;
        }

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        JSArray devices = new JSArray();
        for (UsbSerialDriver driver : drivers) {
            UsbDevice device = driver.getDevice();
            if (device == null) {
                continue;
            }
            JSObject info = new JSObject();
            info.put("deviceId", device.getDeviceId());

            String productName = device.getProductName();
            String manufacturer = device.getManufacturerName();
            String name = (productName != null && !productName.isEmpty()) ? productName : device.getDeviceName();
            if (manufacturer != null && !manufacturer.isEmpty()) {
                name = manufacturer + " " + name;
            }
            info.put("name", name);
            info.put("vendor", manufacturer != null ? manufacturer : String.format("0x%04X", device.getVendorId()));
            info.put("product", productName != null ? productName : "");
            info.put("vendorId", device.getVendorId());
            info.put("productId", device.getProductId());
            devices.put(info);
        }

        JSObject ret = new JSObject();
        ret.put("devices", devices);
        call.resolve(ret);
    }

    /**
     * 打开指定 USB 串口；如无权限会先弹窗请求权限。
     */
    @PluginMethod
    public void open(PluginCall call) {
        Integer deviceIdOption = call.getInt("deviceId");
        if (deviceIdOption == null) {
            call.reject("缺少 deviceId 参数");
            return;
        }
        int deviceId = deviceIdOption;
        Integer baudOption = call.getInt("baudRate", DEFAULT_BAUD_RATE);
        int baudRate = baudOption != null ? baudOption : DEFAULT_BAUD_RATE;

        // 打开新设备前先释放已有连接
        closePortInternal();

        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            call.reject("无法获取 UsbManager");
            return;
        }

        UsbSerialDriver driver = findDriver(deviceId);
        if (driver == null) {
            call.reject("未找到可用的串口驱动 (deviceId=" + deviceId + ")");
            return;
        }
        UsbDevice device = driver.getDevice();

        // 检查/申请 USB 权限
        if (!acquirePermission(usbManager, device)) {
            call.reject("未获得 USB 设备权限");
            return;
        }

        List<UsbSerialPort> ports = driver.getPorts();
        if (ports == null || ports.isEmpty()) {
            call.reject("该 USB 设备没有可用串口");
            return;
        }
        UsbSerialPort port = ports.get(0);

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            call.reject("打开 USB 设备连接失败");
            return;
        }

        try {
            port.open(connection);
            port.setParameters(
                    baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE);
            // GRBL 常见需要 DTR/RTS；部分芯片不支持，忽略异常
            try {
                port.setDTR(true);
                port.setRTS(true);
            } catch (IOException ignored) {
                // 芯片不支持 DTR/RTS 控制时忽略
            }
        } catch (IOException e) {
            try {
                port.close();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                connection.close();
            } catch (Exception ignored) {
                // ignore
            }
            call.reject("初始化串口失败: " + e.getMessage());
            return;
        }

        serialPort = port;
        usbConnection = connection;
        openedDeviceId = deviceId;
        closedNotified = false;
        registerDetachReceiver();

        // 启动后台读取线程
        reading = true;
        readThread = new Thread(this::readLoop, "UsbSerial-Read");
        readThread.setDaemon(true);
        readThread.start();

        call.resolve();
    }

    /**
     * 关闭当前串口连接。
     */
    @PluginMethod
    public void close(PluginCall call) {
        closePortInternal();
        call.resolve();
    }

    /**
     * 向串口写入数据。
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

        UsbSerialPort port = serialPort;
        if (port == null || !reading) {
            call.reject("串口未打开");
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
                port.write(bytes, WRITE_TIMEOUT_MS);
            } catch (IOException e) {
                call.reject("写入串口失败: " + e.getMessage());
                return;
            }
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        closePortInternal();
        super.handleOnDestroy();
    }

    /** 根据 deviceId 查找对应的串口驱动。 */
    private UsbSerialDriver findDriver(int deviceId) {
        if (usbManager == null) {
            return null;
        }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        for (UsbSerialDriver driver : drivers) {
            UsbDevice device = driver.getDevice();
            if (device != null && device.getDeviceId() == deviceId) {
                return driver;
            }
        }
        return null;
    }

    /**
     * 检查并（在需要时）请求 USB 权限，阻塞等待广播结果。
     *
     * @return 是否已获得权限
     */
    private boolean acquirePermission(UsbManager manager, UsbDevice device) {
        if (manager.hasPermission(device)) {
            return true;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        permissionLatch = latch;
        permissionGranted = false;

        permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    return;
                }
                UsbDevice received = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                // 只处理目标设备的结果
                if (received == null || received.getDeviceId() == device.getDeviceId()) {
                    permissionGranted = granted;
                }
                latch.countDown();
            }
        };

        Context ctx = getContext();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(permissionReceiver, filter);
        }

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
        permissionIntent.setPackage(ctx.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                ctx,
                0,
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.requestPermission(device, pendingIntent);

        try {
            latch.await(PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                ctx.unregisterReceiver(permissionReceiver);
            } catch (IllegalArgumentException ignored) {
                // 未注册或已注销时忽略
            }
            permissionReceiver = null;
            permissionLatch = null;
        }

        return permissionGranted || manager.hasPermission(device);
    }

    /** 后台线程：持续阻塞读取串口数据并转发给前端。 */
    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        UsbSerialPort port = serialPort;
        try {
            while (reading && port != null) {
                int len = port.read(buffer, READ_TIMEOUT_MS);
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
            if (reading) {
                closePortInternal();
                notifyClosedOnce();
            }
        }
    }

    /** 关闭串口、结束读取线程并清理资源。 */
    private synchronized void closePortInternal() {
        reading = false;
        openedDeviceId = -1;

        Thread thread = readThread;
        readThread = null;

        UsbSerialPort port = serialPort;
        serialPort = null;
        if (port != null) {
            try {
                port.close();
            } catch (Exception ignored) {
                // ignore
            }
        }

        UsbDeviceConnection connection = usbConnection;
        usbConnection = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // ignore
            }
        }

        unregisterDetachReceiver();

        // 注意：读取线程自身触发清理时不能 join 自己，否则会死锁
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

    private void registerDetachReceiver() {
        if (detachReceiverRegistered) {
            return;
        }
        Context ctx = getContext();
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(detachReceiver, filter);
        }
        detachReceiverRegistered = true;
    }

    private synchronized void unregisterDetachReceiver() {
        if (!detachReceiverRegistered) {
            return;
        }
        detachReceiverRegistered = false;
        try {
            getContext().unregisterReceiver(detachReceiver);
        } catch (IllegalArgumentException ignored) {
            // 未注册或已注销时忽略
        }
    }
}