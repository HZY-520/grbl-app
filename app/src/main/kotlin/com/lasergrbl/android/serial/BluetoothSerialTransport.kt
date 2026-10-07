package com.lasergrbl.android.serial

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.lasergrbl.core.serial.PortConnectionFactory
import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.SerialPortBase
import com.lasergrbl.core.serial.TransportKind
import java.io.IOException
import java.util.UUID

// =====================================================================================
// 错误/事件文案（冻结）：逐字对齐 v2 `android/app/src/main/java/com/lasergrbl/android/BluetoothSerialPlugin.java`
//
// 单测会把下面每个常量与 v2 的字面量再比一遍（BluetoothV2ParityConstantsTest），
// 这样"改文案"这种最容易被顺手做掉的回归会被挡住。
// =====================================================================================

/** v2 `BluetoothSerialPlugin.java:247` / `:294`。 */
internal const val MESSAGE_BLUETOOTH_UNSUPPORTED = "设备不支持蓝牙"

/** v2 `BluetoothSerialPlugin.java:251` / `:298`。 */
internal const val MESSAGE_BLUETOOTH_DISABLED = "蓝牙未开启，请先开启蓝牙"

/** v2 `BluetoothSerialPlugin.java:259` / `:341`（前缀）。 */
internal const val PREFIX_BLUETOOTH_PERMISSION_MISSING = "缺少蓝牙权限: "

/** v2 `BluetoothSerialPlugin.java:344`（前缀）。 */
internal const val PREFIX_BLUETOOTH_CONNECT_FAILED = "连接蓝牙设备失败: "

/** v2 `BluetoothSerialPlugin.java:309`（前缀）。 */
internal const val PREFIX_BLUETOOTH_INVALID_ADDRESS = "无效的蓝牙地址: "

/** v2 `BluetoothSerialPlugin.java:185`（前缀）。 */
internal const val PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED = "无法打开蓝牙设置: "

/** 外部断开时写进 `lastError` 的原因（v2 只发事件不带文案；这是 3.0 新增的可诊断信息）。 */
internal const val MESSAGE_BLUETOOTH_DISCONNECTED = "蓝牙连接已断开"

/**
 * 系统返回的一台**已配对**设备（v2 遍历 `Set<BluetoothDevice>` 时的两个取值点）。
 *
 * 之所以单独抽一个快照类型：`BluetoothDevice` 是 final 且 `getAddress()` / `getName()` 无法在
 * JVM 单测里 stub，把「系统对象 → 两个字段」这一步和「字段 → [SerialDeviceInfo]」的映射规则分开，
 * 后者就成了可离线验证的纯函数 [bondedDevicesToSerialInfos]。
 */
internal data class BondedDeviceSnapshot(
    /** `BluetoothDevice.getAddress()`（MAC，作为稳定标识）。 */
    val address: String,
    /** `BluetoothDevice.getName()`；读取抛 `SecurityException` 时与 v2 一样退化为 null。 */
    val name: String?
)

/**
 * 「已配对设备快照 → [SerialDeviceInfo]」的映射规则 —— 逐行对应 v2 `doList`
 * （`BluetoothSerialPlugin.java:263-278`）：
 *
 *  * `id` = MAC 地址（`:core` 的稳定标识，同时写进 `address` 字段）；
 *  * `name` = 设备名，**为空（null 或空串）时退化成 MAC 地址**（v2 的三元表达式）；
 *  * `kind` = [TransportKind.Bluetooth]；
 *  * 按 `address` **去重并保留系统返回顺序**（对应 v2 拿到的 `Set<BluetoothDevice>`：同一台设备
 *    不会出现两次，顺序即系统顺序）；
 *  * USB 专有字段（deviceId / vendorId / productId / vendor / product）全部为 null。
 *
 * 注意 v2 判的是 `isEmpty()` 而不是 `isBlank()`：纯空白名字会原样保留（单测固定这一条）。
 */
internal fun bondedDevicesToSerialInfos(devices: List<BondedDeviceSnapshot>): List<SerialDeviceInfo> {
    val seenAddresses = LinkedHashSet<String>(devices.size)
    val result = ArrayList<SerialDeviceInfo>(devices.size)
    for (device in devices) {
        if (!seenAddresses.add(device.address)) continue
        val name = device.name
        val displayName = if (name != null && name.isNotEmpty()) name else device.address
        result += SerialDeviceInfo(
            id = device.address,
            kind = TransportKind.Bluetooth,
            name = displayName,
            deviceId = null,
            address = device.address,
            vendor = null,
            product = null,
            vendorId = null,
            productId = null
        )
    }
    return result
}

// =====================================================================================
// 真机触碰层（保持"薄到看一眼就知道对不对"）：适配器查询、建连、建连失败清理。
// 这些函数只把 Android API 翻译成接缝里的阻塞调用，所有状态机语义都在 SerialPortBase。
// =====================================================================================

/** `getSystemService(BLUETOOTH_SERVICE).adapter`（v2 的 `getAdapter()`，`BluetoothSerialPlugin.java:424-427`）。 */
private fun bluetoothAdapterOf(context: Context): BluetoothAdapter? {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter
}

/**
 * `BluetoothAdapter.isEnabled` 在 API 31+ 也需要 `BLUETOOTH_CONNECT`（框架标了
 * `@RequiresPermission`）；与 v2 一样把权限问题映射成 `缺少蓝牙权限: ` 文案。
 */
private fun isAdapterEnabled(adapter: BluetoothAdapter): Boolean = try {
    adapter.isEnabled
} catch (e: SecurityException) {
    throw SecurityException(PREFIX_BLUETOOTH_PERMISSION_MISSING + e.message)
}

private fun closeQuietly(socket: BluetoothSocket?) {
    if (socket == null) return
    runCatching { socket.close() }
}

/**
 * SPP 连接工厂 —— **蓝牙的建连就发生在这里**（接缝把「设备查找 / 权限 / 打开」收进了
 * `PortConnectionFactory.create(device, baudRate)`，v2 则是在 `doOpen` 的后台线程里做）。
 *
 * 顺序逐条对齐 v2（`BluetoothSerialPlugin.java:315-346`）：
 * 1. 适配器存在 & 已开启（否则 [MESSAGE_BLUETOOTH_UNSUPPORTED] / [MESSAGE_BLUETOOTH_DISABLED]）；
 * 2. `getRemoteDevice(address)`，`IllegalArgumentException` → [PREFIX_BLUETOOTH_INVALID_ADDRESS]；
 * 3. `cancelDiscovery()`，`SecurityException` 忽略（部分系统需要额外的扫描权限）；
 * 4. `createRfcommSocketToServiceRecord(`[BluetoothSerialTransport.SPP_UUID]`)` + `connect()`；
 * 5. 失败**必须先把 socket 关掉再抛**（v2 的 `closeQuietly(socket)`，也是接缝对工厂的硬性要求）：
 *    `SecurityException` → [PREFIX_BLUETOOTH_PERMISSION_MISSING]，
 *    `IOException` → [PREFIX_BLUETOOTH_CONNECT_FAILED]；
 * 6. 成功 → 交出包裹该 socket 的 [BluetoothSocketConnection]。
 *
 * `baudRate` 被忽略：SPP 不区分波特率（v2 的 `DEFAULT_BAUD_RATE` 也只是常量）。
 *
 * @param appContext application context（工厂是长生命周期对象，不能持有 Activity）。
 */
private fun bluetoothSppConnectionFactory(appContext: Context): PortConnectionFactory =
    PortConnectionFactory { device, _ ->
        val adapter = bluetoothAdapterOf(appContext)
            ?: throw IllegalStateException(MESSAGE_BLUETOOTH_UNSUPPORTED)
        if (!isAdapterEnabled(adapter)) throw IllegalStateException(MESSAGE_BLUETOOTH_DISABLED)

        // list() 同时写了 id 与 address（都是 MAC）；这里优先用 address，取不到再退回 id
        val address = device.address?.takeIf { it.isNotEmpty() } ?: device.id
        val remote = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(PREFIX_BLUETOOTH_INVALID_ADDRESS + address)
        }

        try {
            adapter.cancelDiscovery()
        } catch (ignored: SecurityException) {
            // v2：取消发现失败不影响连接，忽略
        }

        var socket: BluetoothSocket? = null
        try {
            val created = remote.createRfcommSocketToServiceRecord(BluetoothSerialTransport.SPP_UUID)
            socket = created
            created.connect()
            BluetoothSocketConnection(created)
        } catch (e: SecurityException) {
            closeQuietly(socket)
            throw SecurityException(PREFIX_BLUETOOTH_PERMISSION_MISSING + e.message)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw IOException(PREFIX_BLUETOOTH_CONNECT_FAILED + e.message)
        }
    }

/**
 * 蓝牙经典串口（SPP）传输层 —— `:core` 的 [com.lasergrbl.core.serial.SerialTransport] 契约的
 * Kotlin 原生实现，取代 v2 的 Capacitor 插件 `BluetoothSerialPlugin.java`（456 行）。
 *
 * ### 与 v2 的逐条对应
 * | 行为 | v2（`BluetoothSerialPlugin.java`） | 这里 |
 * | --- | --- | --- |
 * | 设备枚举 | `adapter.getBondedDevices()`，`Set` 顺序 | [list] + [bondedDevicesToSerialInfos] |
 * | 不支持蓝牙 | reject `设备不支持蓝牙`（:247） | [IllegalStateException] 同文案 |
 * | 蓝牙未开 | reject `蓝牙未开启，请先开启蓝牙`（:251） | 同文案 |
 * | 权限门禁 | `withPermission`：`< S \|\| GRANTED` 放行，否则弹框（:202） | [BluetoothPermissionGate] |
 * | 打开前先释放旧连接 | `closePortInternal()`（:303） | [SerialPortBase.open] 第一步就是 teardown |
 * | 无效地址 | `getRemoteDevice` 的 `IllegalArgumentException` → `无效的蓝牙地址: `（:309） | 同 |
 * | 取消发现 | `cancelDiscovery()`，`SecurityException` 忽略（:319-323） | 同 |
 * | 建连 | `createRfcommSocketToServiceRecord(SPP_UUID)` + `connect()`（:325-326） | 同（[SPP_UUID]） |
 * | 建连失败 | 关 socket 后 reject `连接蓝牙设备失败: ` / `缺少蓝牙权限: `（:339-345） | 关 socket 后抛同文案的异常 |
 * | 断开监听 | `ACTION_ACL_DISCONNECTED` + `ACTION_ACL_DISCONNECT_REQUESTED`（:429-443） | 同（应用上下文注册） |
 * | 断开过滤 | 广播里的 device 地址与当前地址不同则忽略（:90） | 同 |
 * | 读循环 / 4096 缓冲 | `readLoop()`（:349-376） | [SerialPortBase] 的读线程（含增量 UTF-8 解码） |
 * | 写锁串行化 | `synchronized (writeLock)`（:161-170） | [SerialPortBase] 的单消费者写队列 |
 * | `closed` 只通知一次 | `notifyClosedOnce()`（:413-422） | [SerialPortBase] 的 `notifyClosedOnce()` |
 * | 关闭幂等 / 释放 socket | `closePortInternal()` + `closeQuietly()` | [SerialPortBase.teardown] + [BluetoothSocketConnection.close] |
 * | 打开系统蓝牙设置 | `openSettings`（:177-187） | [openSettings] |
 *
 * ### 本类**不做**什么
 * 写队列、写线程、读线程、增量 UTF-8、`closed` 只通知一次、关闭幂等全部由 [SerialPortBase] 负责
 * （v2 把同一套语义在 USB / 蓝牙两个插件里各抄了一遍，3.0 只留一份）；建连在
 * [bluetoothSppConnectionFactory] 里（接缝的 `PortConnectionFactory` 就是这个位置）。
 * 本类只做三件事：设备枚举、ACL 断开广播、系统设置的跳转。
 *
 * ### 线程
 * * [list] 是 `suspend`，可在任意调度器上调用（内部可能弹权限框）；
 * * 工厂的 `create()` 由 [SerialPortBase.open] **在调用方的线程上同步执行**，其中的
 *   `socket.connect()` 是阻塞调用（实测可长达数秒）。因此 Phase 4 的连接页应当在 IO 调度器上
 *   `open()`（`SerialPortBase` 本身不切线程）；
 * * [openSettings] 与广播接收器不限线程。
 *
 * ### 与 v2 的有意差异（都有理由）
 * 1. 广播接收器注册在 **application context** 上（v2 用插件 context）：传输层可能比 Activity 活得久，
 *    用 Activity context 会泄漏；注册仍用 `RECEIVER_NOT_EXPORTED`（API 33+），与 v2 相同。
 * 2. `list()` 里读取 `adapter.isEnabled` 时也捕获了 `SecurityException` 并映射为
 *    `缺少蓝牙权限: `（v2 只包了 `getBondedDevices`）—— 避免把系统异常原样抛到 UI。
 * 3. 外部断开由 [SerialPortBase.handleExternalDisconnect] 兜底「已关闭时再收到广播不会重复通知」，
 *    因此不需要 v2 里手工维护的 `closedNotified`。
 *
 * @param context 任意 context；内部只用 `applicationContext` 做系统调用（[openSettings] 用
 *   `FLAG_ACTIVITY_NEW_TASK` 启动系统设置页，所以传 Activity 或 Application 都可以）。
 * @param permissionGate 权限门禁；默认按 context 新建（见 [BluetoothPermissionGate] 的 Activity 取舍）。
 */
class BluetoothSerialTransport(
    private val context: Context,
    private val permissionGate: BluetoothPermissionGate = BluetoothPermissionGate(context)
) : SerialPortBase(
    TransportKind.Bluetooth,
    bluetoothSppConnectionFactory(context.applicationContext)
) {

    /** 注册广播/查询系统服务用的长生命周期 context。 */
    private val appContext: Context = context.applicationContext

    /** 当前已打开设备的 MAC（null 表示未打开），对应 v2 的 `openedAddress`。 */
    @Volatile
    private var openedAddress: String? = null

    private val receiverLock = Any()

    /** 复用同一个接收器实例，便于注销（v2 同样只 new 一次）。 */
    private var disconnectReceiver: BroadcastReceiver? = null

    private var disconnectReceiverRegistered = false

    // ================= SerialTransport =================

    /**
     * 枚举**已配对**的蓝牙设备（GRBL 蓝牙模块需先在系统设置里配对，与 v2 相同）。
     *
     * 顺序与 v2 一致：先过权限门禁（[BluetoothPermissionGate.ensureGranted]，
     * 未授权时会弹系统权限框），再判断适配器存在/已开启，最后读已配对列表。
     */
    override suspend fun list(): List<SerialDeviceInfo> {
        // v2 的 list 同样走 withPermission（未授权时先申请，被拒才失败）
        permissionGate.ensureGranted()

        val adapter = bluetoothAdapterOf(appContext)
            ?: throw IllegalStateException(MESSAGE_BLUETOOTH_UNSUPPORTED)
        if (!isAdapterEnabled(adapter)) throw IllegalStateException(MESSAGE_BLUETOOTH_DISABLED)

        val bonded = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            throw SecurityException(PREFIX_BLUETOOTH_PERMISSION_MISSING + e.message)
        }

        val snapshots = ArrayList<BondedDeviceSnapshot>(bonded?.size ?: 0)
        bonded?.forEach { device ->
            val address = device.address
            // 没有地址的设备既无法展示也无法打开，直接跳过（正常已配对设备不会出现）
            if (address.isNullOrEmpty()) return@forEach
            val name = try {
                device.name
            } catch (ignored: SecurityException) {
                // v2：读名字没有权限时退化为 null（随后用地址当名字）
                null
            }
            snapshots += BondedDeviceSnapshot(address = address, name = name)
        }
        return bondedDevicesToSerialInfos(snapshots)
    }

    /**
     * 供 Phase 4 连接页在 `open()` **之前**调用：v2 的 `open` 也先过 `withPermission`
     * （`BluetoothSerialPlugin.java:111-113`）。[list] 内部已经会调用它，所以只要列表是同一个
     * transport 拉的，这一步通常已经是「已授权」的快速返回。
     *
     * 之所以不把权限申请塞进工厂：工厂的 `create()` 是**阻塞**调用，而权限申请必须挂起等待
     * 用户操作，在阻塞调用里等待会卡住调用线程。
     *
     * @throws SecurityException 未获得权限时（文案同 v2 的 `未获得蓝牙权限`）。
     */
    suspend fun ensureBluetoothPermission() {
        permissionGate.ensureGranted()
    }

    /**
     * **推荐 Phase 4 使用的连接入口**：先过权限门禁再 [open]。
     *
     * 为什么不能只 [open]：v2 的 `open` 自己会先 `withPermission(call, "open")`
     * （`BluetoothSerialPlugin.java:111-113`），**未授权时先弹权限框、回调里再 dispatch**；
     * 而 3.0 的 [open] 继承自 [com.lasergrbl.core.serial.SerialPortBase] 且是 `final`
     * （基类不关心权限），所以"绕过列表直接连"的场景会与 v2 行为不同：
     *  * **自动重连上次设备**、深链/快捷方式直达连接、以及"用户在系统设置里撤销了
     *    `BLUETOOTH_CONNECT` 后重进应用" —— v2 会立刻弹框让用户恢复，只调 [open] 只会抛
     *    `缺少蓝牙权限: …`，用户无法自助恢复。
     *
     * 先 `listBluetoothDevices()` 再连的正常流程不受影响（列表那一步已经弹过框）。
     * 所以 Phase 4 的连接动作请走本方法（或先调 [ensureBluetoothPermission]），不要直接 [open]。
     *
     * 注意 [open] 内部会**阻塞调用线程**（`socket.connect()`），调用方必须放在 IO 调度器上
     * （见 `docs/PHASE3-PERIPHERALS.md` §6 的记录；v2 是把建连放进独立线程的）。
     */
    suspend fun connect(device: SerialDeviceInfo, baudRate: Int) {
        ensureBluetoothPermission()
        open(device, baudRate)
    }

    /**
     * 打开系统蓝牙设置界面，便于用户配对雕刻机的蓝牙模块（v2 `openSettings`）。
     *
     * @throws IllegalStateException 启动失败时，文案 [PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED] + 原因。
     */
    fun openSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            throw IllegalStateException(PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED + e.message)
        }
    }

    // ================= 子类扩展点：ACL 断开广播 =================

    /**
     * 连接成功之后：记下当前地址并注册 ACL 断开广播。
     *
     * [SerialPortBase] 保证本方法在**读线程启动之前**调用（否则读线程可能立刻 EOF 并注销掉
     * 一个还没注册的接收器），与 v2「先 `registerDisconnectReceiver()` 再启动读线程」一致。
     */
    protected override fun onOpened(device: SerialDeviceInfo) {
        openedAddress = device.address?.takeIf { it.isNotEmpty() } ?: device.id
        registerDisconnectReceiver()
    }

    /** 连接已关闭（含读线程自行断开）：清掉地址并注销广播（幂等）。 */
    protected override fun onClosed() {
        openedAddress = null
        unregisterDisconnectReceiver()
    }

    // ================= 内部 =================

    private fun registerDisconnectReceiver() {
        synchronized(receiverLock) {
            if (disconnectReceiverRegistered) return
            val receiver = disconnectReceiver ?: createDisconnectReceiver().also { disconnectReceiver = it }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            disconnectReceiverRegistered = true
        }
    }

    private fun unregisterDisconnectReceiver() {
        val receiver: BroadcastReceiver?
        synchronized(receiverLock) {
            if (!disconnectReceiverRegistered) return
            disconnectReceiverRegistered = false
            receiver = disconnectReceiver
        }
        if (receiver != null) {
            // 未注册/已注销时系统会抛 IllegalArgumentException，与 v2 一样忽略
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /**
     * ACL 断开接收器 —— 逐行对应 v2 的 `disconnectReceiver`（`BluetoothSerialPlugin.java:81-97`）。
     *
     * 只做「取广播里的设备地址」这一件事，判定与断开动作都在 [onAclDisconnect] 里：
     * 一是为了可读，二是 `handleExternalDisconnect` 是基类的 `protected` 成员，
     * 在类自己的成员函数里调用（而不是匿名对象内部）语义最明确。
     */
    private fun createDisconnectReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) = onAclDisconnect(intent)
    }

    /**
     * 处理 ACL 断开广播：广播里的设备地址与当前打开的地址不一致时忽略；
     * 其余情况一律走 [SerialPortBase.handleExternalDisconnect]（关连接 + **只通知一次** `closed`）。
     *
     * 若广播到达时连接已经关掉，[SerialPortBase.handleExternalDisconnect] 会是空操作（不会重复通知），
     * 所以这里不需要 v2 那样的额外标记。
     */
    private fun onAclDisconnect(intent: Intent?) {
        val action = intent?.action
        if (action != BluetoothDevice.ACTION_ACL_DISCONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED
        ) {
            return
        }
        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java
        )
        val current = openedAddress
        // v2：device 为 null（广播没带 EXTRA_DEVICE）或地址与当前设备一致时都算断开
        if (device != null && current != null && current != device.address) return
        handleExternalDisconnect(MESSAGE_BLUETOOTH_DISCONNECTED)
    }

    companion object {

        /** 经典蓝牙串口（SPP）服务的标准 UUID（v2 的 `SPP_UUID`，`BluetoothSerialPlugin.java:52`）。 */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** v2 的 `DEFAULT_BAUD_RATE`：SPP 实际不区分波特率，仅保持接口一致。 */
        const val DEFAULT_BAUD_RATE: Int = 115200

        /** 创建 Phase 4 可直接使用的传输层（用 application context，避免持有 Activity）。 */
        fun create(context: Context): BluetoothSerialTransport =
            BluetoothSerialTransport(context.applicationContext)
    }
}
