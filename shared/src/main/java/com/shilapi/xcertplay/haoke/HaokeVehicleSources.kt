package com.shilapi.xcertplay.haoke

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 好客（HAOKE）车辆数据的唯一数据源聚合点。
 *
 * 持有 [HaokeVehicleClient]（bind + UART 回调）与 [HaokeCanParser.Snapshot]（最新解析结果），
 * 向上层（车速 [VehicleSpeedSource]、电量 [VehicleStatusProvider]）提供一致的读取视图。
 */
internal object HaokeVehicleData {
    private const val TAG = "HaokeVehicleData"

    private val snapshot = HaokeCanParser.Snapshot()
    private val lock = Any()

    @Volatile private var client: HaokeVehicleClient? = null
    @Volatile private var started = false

    /** 最新一帧到达的单调时钟时间。 */
    @Volatile private var lastFrameMillis = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
            val c = HaokeVehicleClient(app, onUartData = { bytes -> onFrame(bytes) })
            client = c
            c.start()
        }
        Log.i(TAG, "haoke vehicle data started")
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            client?.stop()
            client = null
        }
        Log.i(TAG, "haoke vehicle data stopped")
    }

    private fun onFrame(bytes: ByteArray) {
        synchronized(lock) {
            HaokeCanParser.parse(bytes, snapshot)
            lastFrameMillis = SystemClock.elapsedRealtime()
        }
    }

    /** 最近一次 CAN 帧是否仍在时效内（超过 3 秒视为失效）。 */
    private fun fresh(): Boolean =
        lastFrameMillis != 0L && SystemClock.elapsedRealtime() - lastFrameMillis < 3_000L

    fun currentSpeedMetersPerSecond(): Double? = synchronized(lock) {
        if (!fresh() || !snapshot.hasSpeed()) return null
        (snapshot.speedKmh / 3.6).takeIf { it in 0.0..83.3 } // 0..300 km/h
    }

    fun currentGear(): VehicleGear? = synchronized(lock) {
        if (!snapshot.hasGear()) return null
        when (snapshot.gearRaw) {
            1 -> VehicleGear.PARK
            2 -> VehicleGear.REVERSE
            3 -> VehicleGear.NEUTRAL
            4, 5, 6 -> VehicleGear.DRIVE
            else -> null
        }
    }

    fun currentSnapshot(): VehicleStatusSnapshot? = synchronized(lock) {
        if (!fresh() || !snapshot.hasBattery()) return null
        val percent = snapshot.batteryPercent.toDouble()
        val range = snapshot.remainingRangeKm
        if (percent !in 0.0..100.0 || range !in 0..3000) return null
        VehicleStatusSnapshot(
            rangeKm = range,
            rangeWarning = false, // 低电量告警阈值由上层设置决定，这里给默认值
            batteryPercent = percent,
            currentChargeWh = null,
            maxChargeWh = null,
            maxRangeKm = range,
            charging = snapshot.charging,
        )
    }
}

/**
 * 好客车速 + 挡位数据源，实现 [VehicleSpeedSource]。
 * 直接读取 [HaokeVehicleData] 的缓存，iAP2 循环永不阻塞。
 */
internal class HaokeWheelSpeedSource : VehicleSpeedSource {
    private val TAG = "HaokeWheelSpeed"
    private val MAX_SAMPLES = 40

    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var executor: ScheduledExecutorService? = null
    private val lock = Any()

    private fun sample() {
        val speed = HaokeVehicleData.currentSpeedMetersPerSecond() ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += VehicleSpeedSample(now, speed)
        }
        HaokeVehicleData.currentGear()?.let { next ->
            val changed = synchronized(lock) { (gear != next).also { gear = next } }
            if (changed) Log.i(TAG, "gear $next")
        }
    }

    override fun start() {
        if (executor != null) return
        synchronized(lock) {
            samples.clear()
            gear = null
        }
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "haoke-wheel-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::sample, 0, 250L, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "wheel speed started")
    }

    override fun stop() {
        val e = synchronized(lock) { executor.also { executor = null } } ?: return
        e.shutdown()
        Log.i(TAG, "wheel speed stopped")
    }

    override fun drain(): VehicleSpeedReading? {
        val current = synchronized(lock) { gear } ?: return null
        val list = synchronized(lock) {
            if (samples.isEmpty()) return null
            samples.toList().also { samples.clear() }
        }
        return VehicleSpeedReading(current, list)
    }
}

/**
 * 好客电量 + 续航 + 充电状态，实现 [VehicleStatusProvider]。
 * 读取 [HaokeVehicleData] 缓存；声明为 EV 的条件由上层 withVehicleStatusFrom 决定。
 */
internal class HaokeBatteryStatus(private val lowChargePercent: Int) : VehicleStatusProvider {
    override fun snapshot(): VehicleStatusSnapshot? {
        val base = HaokeVehicleData.currentSnapshot() ?: return null
        return base.copy(rangeWarning = base.batteryPercent <= lowChargePercent)
    }

    fun currentReadingOrNull(): VehicleStatusSnapshot? = snapshot()

    /** 仅供诊断：把当前百分比/续航取整后返回人类可读信息。 */
    fun describe(): String {
        val s = snapshot() ?: return "no reading"
        return "soc=${s.batteryPercent.roundToInt()}% range=${s.rangeKm}km charging=${s.charging}"
    }
}
