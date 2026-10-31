package com.shilapi.xcertplay.haoke

import android.content.Context
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

/**
 * 好客（HAOKE）车机车辆数据的对外入口，对标 [com.shilapi.xcertplay.hud.BydNavigationOutputs]。
 *
 * 提供电量 [VehicleStatusProvider] 与车速 [VehicleSpeedSource]，供 CarPlayHostActivity
 * 在装配 iAP2 车辆状态上报与死区推算（$PASCD）时使用。
 */
object HaokeOutputs {
    private const val HAOKE_CARMANAGER = "com.haoke.carmanager"

    private const val DEFAULT_LOW_CHARGE_PERCENT = 20

    @Volatile private var batteryProvider: HaokeBatteryStatus? = null
    @Volatile private var wheelSpeedSource: HaokeWheelSpeedSource? = null

    /** 当前车机是否安装了好客 CarManager（好客车机的判定依据）。 */
    fun available(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(HAOKE_CARMANAGER, 0)
        }.isSuccess

    /**
     * 好客电量 provider。首次调用会启动好客数据源（bind CarService + UART 回调）。
     * 低电量告警阈值默认 20%，后续可接入设置页。
     */
    fun batteryStatus(context: Context): VehicleStatusProvider {
        HaokeVehicleData.start(context)
        return batteryProvider ?: HaokeBatteryStatus(DEFAULT_LOW_CHARGE_PERCENT).also {
            batteryProvider = it
        }
    }

    /** 好客车速 + 挡位 source，用于 iPhone 死区推算。 */
    fun wheelSpeed(context: Context): VehicleSpeedSource {
        HaokeVehicleData.start(context)
        return wheelSpeedSource ?: HaokeWheelSpeedSource().also { wheelSpeedSource = it }
    }

    /** App 打开时预热好客数据源，让电量读数在 CarPlay 识别前就绪（对标 BydNavigationOutputs.onAppOpened）。 */
    fun onAppOpened(context: Context) {
        if (available(context)) HaokeVehicleData.start(context)
    }

    /** 会话结束/关闭时释放好客连接。 */
    fun endNow() {
        HaokeVehicleData.stop()
    }
}
