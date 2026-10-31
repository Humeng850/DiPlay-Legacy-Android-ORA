package com.shilapi.xcertplay.haoke

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 好客（HAOKE）CarService 连接层。
 *
 * 移植自黑猫助手（com.wuyo.oratool）的 VehicleDataMonitor：
 * bindService("action.haoke.CARSERVICE") → transact(REGISTER_WITH_UART=2, mode=32561)
 * → ICarUartCallBack 回调原始 CAN 帧字节。带 linkToDeath + watchdog + 指数退避重连。
 *
 * mode=32561 是黑猫逆向得出的未公开值，不保证跨车/跨固件稳定；见 README 说明。
 */
internal class HaokeVehicleClient(
    context: Context,
    private val onUartData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "HaokeVehicleClient"
        private const val ACTION = "action.haoke.CARSERVICE"
        private const val PACKAGE = "com.haoke.carmanager"
        private const val SERVICE = "com.haoke.service.CarService"
        private const val SERVICE_DESCRIPTOR = "com.haoke.aidl.ICarService"
        private const val CALLBACK_DESCRIPTOR = "com.haoke.aidl.ICarCallBack"
        private const val UART_CALLBACK_DESCRIPTOR = "com.haoke.aidl.ICarUartCallBack"

        private const val MODE_ID = 32561
        private const val REGISTER_WITH_UART = 2
        private const val UNREGISTER = 3

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val UART_TIMEOUT_MS = 60_000L
        private const val BIND_FLAGS = 1 // Context.BIND_AUTO_CREATE
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var connection: Connection? = null
    private var retryDelayMs = 1_000L

    private val uartListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()

    init {
        uartListeners += onUartData
        statusListeners += onStatus
    }

    fun addUartListener(listener: (ByteArray) -> Unit) { uartListeners += listener }
    fun removeUartListener(listener: (ByteArray) -> Unit) { uartListeners -= listener }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val gen = generation + 1
            generation = gen
            if (thread == null) {
                thread = HandlerThread("HaokeVehicleMonitor").also { it.start() }
                handler = Handler(thread!!.looper)
            }
            val h = handler!!
            h.post { if (active(gen)) { retryDelayMs = 1_000L; bind(gen, h) } }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            val h = handler ?: return
            h.post {
                connection?.let { close(it) }
                synchronized(lock) {
                    if (!running && handler == h) {
                        thread?.quit()
                        thread = null
                        handler = null
                    }
                }
            }
        }
    }

    private fun active(gen: Long): Boolean = running && generation == gen
    private fun current(c: Connection): Boolean = connection == c && active(c.token)

    private fun bind(gen: Long, h: Handler) {
        if (!active(gen)) return
        val c = Connection(gen, h)
        connection = c
        status(c, "正在连接车辆数据服务")
        try {
            var bound = appContext.bindService(
                Intent(ACTION).setPackage(PACKAGE), c, BIND_FLAGS,
            )
            if (!bound) {
                bound = appContext.bindService(
                    Intent().setComponent(ComponentName(PACKAGE, SERVICE)), c, BIND_FLAGS,
                )
            }
            if (!bound) {
                recover(c, "未连接车辆数据服务")
            } else {
                c.worker.postDelayed(c.timeout, CONNECT_TIMEOUT_MS)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "bind failed", e)
            recover(c, "车辆数据绑定失败")
        }
    }

    private fun register(c: Connection, binder: IBinder) {
        if (!current(c)) return
        clearBinder(c)
        c.worker.removeCallbacks(c.timeout)
        c.worker.removeCallbacks(c.retry)
        if (!binder.pingBinder()) {
            recover(c, "车辆数据服务不可用")
            return
        }
        c.binder = binder
        val death = IBinder.DeathRecipient {
            c.worker.post {
                if (current(c) && c.binder == binder) recover(c, "车辆数据 Binder 已断开")
            }
        }
        c.death = death
        c.callback = CallbackBinder()
        c.uartCallback = UartCallbackBinder(c)
        c.lastUartMillis = SystemClock.elapsedRealtime()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            binder.linkToDeath(death, 0)
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeInt(MODE_ID)
            data.writeStrongBinder(c.callback)
            data.writeStrongBinder(c.uartCallback)
            c.accepting = true
            c.registrationAttempted = true
            val handled = binder.transact(REGISTER_WITH_UART, data, reply, 0)
            Log.i(TAG, "register transaction=2 handled=$handled generation=${c.token}")
            if (!handled) throw RemoteException("registration transaction rejected")
            reply.readException()
            val result = reply.readInt()
            Log.i(TAG, "register result=$result generation=${c.token}")
            if (result == 0) throw RemoteException("registration returned false")
            c.registered = true
            status(c, "车辆数据已连接")
            c.worker.postDelayed(c.watchdog, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "register failed", e)
            recover(c, "车辆数据注册失败")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun recover(c: Connection, message: String) {
        if (!current(c)) return
        c.accepting = false
        c.worker.removeCallbacks(c.timeout)
        c.worker.removeCallbacks(c.watchdog)
        c.worker.removeCallbacks(c.retry)
        status(c, "$message，${retryDelayMs / 1000}秒后重试")
        c.worker.postDelayed(c.retry, retryDelayMs)
        retryDelayMs = minOf(MAX_RETRY_MS, retryDelayMs * 2)
    }

    private fun clearBinder(c: Connection) {
        c.accepting = false
        c.registered = false
        val binder = c.binder
        val death = c.death
        if (binder != null && death != null) {
            try { binder.unlinkToDeath(death, 0) } catch (_: RuntimeException) {}
        }
        c.death = null
        c.binder = null
        c.callback = null
        c.uartCallback = null
        c.registrationAttempted = false
        c.worker.removeCallbacks(c.watchdog)
    }

    private fun close(c: Connection) {
        c.accepting = false
        c.worker.removeCallbacks(c.timeout)
        c.worker.removeCallbacks(c.retry)
        c.worker.removeCallbacks(c.watchdog)
        val binder = c.binder
        if (c.registrationAttempted && binder != null && binder.isBinderAlive) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeInt(MODE_ID)
                if (binder.transact(UNREGISTER, data, reply, 0)) {
                    reply.readException()
                    Log.i(TAG, "unregister result=${reply.readInt()}")
                }
            } catch (_: Exception) {
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        clearBinder(c)
        if (c.bound) {
            try { appContext.unbindService(c) } catch (_: RuntimeException) {}
            c.bound = false
        }
        if (connection == c) connection = null
    }

    private fun status(c: Connection, message: String) {
        Log.i(TAG, "generation=${c.token} $message")
        if (current(c)) statusListeners.forEach { it(message) }
    }

    private fun dispatchUart(bytes: ByteArray) {
        uartListeners.forEach { listener -> runCatching { listener(bytes) } }
    }

    private inner class Connection(val token: Long, val worker: Handler) : ServiceConnection {
        @Volatile var accepting = false
        var binder: IBinder? = null
        var bound = false
        var callback: Binder? = null
        var death: IBinder.DeathRecipient? = null
        @Volatile var lastUartMillis = 0L
        var loggedUartMillis = 0L
        var registered = false
        var registrationAttempted = false
        @Volatile var uartCallback: UartCallbackBinder? = null
        val uartCount = java.util.concurrent.atomic.AtomicLong()

        val retry = Runnable {
            if (current(this)) { close(this); bind(token, worker) }
        }
        val timeout = Runnable {
            if (!registered) recover(this, "车辆数据连接超时")
        }
        val watchdog = Runnable {
            if (!current(this) || !registered) return@Runnable
            val silentMs = SystemClock.elapsedRealtime() - lastUartMillis
            Log.i(TAG, "UART count=${uartCount.get()} silentMs=$silentMs")
            if (binder == null || !binder!!.pingBinder()) {
                recover(this, "车辆数据服务不可用")
            } else if (silentMs >= UART_TIMEOUT_MS) {
                recover(this, "车辆数据回调超时")
            } else {
                retryDelayMs = 1_000L
                worker.postDelayed(watchdog, CONNECT_TIMEOUT_MS)
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            worker.post { register(this, service) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            accepting = false
            worker.post {
                if (current(this)) { clearBinder(this); recover(this, "车辆数据连接已断开") }
            }
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    private class CallbackBinder : Binder() {
        init { attachInterface(null, CALLBACK_DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1598968902) { // INTERFACE_TRANSACTION
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code == 1 || code == REGISTER_WITH_UART) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                data.readInt()
                data.readInt()
                if (code == 1) data.readInt() else data.readString()
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private inner class UartCallbackBinder(private val owner: Connection) : Binder() {
        init { attachInterface(null, UART_CALLBACK_DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1598968902) {
                reply?.writeString(UART_CALLBACK_DESCRIPTOR)
                return true
            }
            if (code == 1) {
                data.enforceInterface(UART_CALLBACK_DESCRIPTOR)
                data.readInt()
                data.readInt()
                val bytes = data.createByteArray()
                if (current(owner) && owner.accepting && owner.uartCallback == this &&
                    bytes != null && bytes.isNotEmpty()
                ) {
                    val now = SystemClock.elapsedRealtime()
                    owner.lastUartMillis = now
                    val count = owner.uartCount.incrementAndGet()
                    if (count == 1L || now - owner.loggedUartMillis >= MAX_RETRY_MS) {
                        owner.loggedUartMillis = now
                        Log.i(TAG, "UART received count=$count len=${bytes.size}")
                    }
                    dispatchUart(bytes)
                }
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
