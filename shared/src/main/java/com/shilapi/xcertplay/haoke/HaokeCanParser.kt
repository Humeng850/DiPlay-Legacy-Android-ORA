package com.shilapi.xcertplay.haoke

/**
 * 好客（HAOKE）车机 UART 回调 CAN 帧解析器。
 *
 * 移植自黑猫助手（com.wuyo.oratool）的 VehicleDataParser，字段映射与帧格式保持一致。
 * 帧格式：0x5A 0xA5 [len] 0xA9 [project] [subcmd] [payload...]
 *   - bArr[0]=0x5A, bArr[1]=0xA5 帧头
 *   - bArr[2] = 长度-3（(bArr[2]&0xff)+3 == 实际长度）
 *   - bArr[3] = 0xA9 地址
 *   - bArr[4] = project（37=空调 / 40=胎压 / 43=车辆状态 / 44=新能源）
 *   - bArr[5] = subcmd
 *   - bArr[6..] = payload
 */
internal object HaokeCanParser {

    const val HEADER_0 = 90            // 0x5A
    const val HEADER_1 = 165           // 0xA5
    const val ADDRESS = 169            // 0xA9
    const val PAYLOAD_OFFSET = 6

    const val PROJECT_AC = 37
    const val PROJECT_TIRE = 40
    const val PROJECT_VEHICLE_STATUS = 43
    const val PROJECT_NEW_ENERGY = 44

    const val AC_SUBCOMMAND = 8
    const val NEW_ENERGY_SUBCOMMAND = 4
    const val TIRE_SUBCOMMAND = 13
    const val VEHICLE_STATUS_SUBCOMMAND = 9

    /**
     * 一次解析得到的最新车辆数据快照。字段与黑猫 Snapshot 对齐，
     * 仅保留 CarPlay 车辆状态上报需要以及驾驶场景可能用到的部分。
     */
    class Snapshot {
        // 车速
        var speedKmh: Int = -1
        var speedValid: Boolean = false

        // 挡位（1=P, 2=R, 3=N, 4/5/6=D）
        var gearRaw: Int = -1
        var gearValid: Boolean = false

        // 电池
        var batteryPercent: Float = -1f
        var remainingRangeKm: Int = -1
        var totalMileageKm: Float = -1f
        var batteryVoltageTenths: Int = -1

        // 充电
        var charging: Boolean = false
        var chargingStatusRaw: Int = -1
        var chargeLimitPercent: Int = -1

        // 能耗
        var instantaneousConsumptionKwhPer100Km: Float = -1f
        var instantaneousPowerKw: Float = -1f

        // 胎压/胎温（raw）
        var tireValid: Boolean = false
        var frontLeftPressureRaw: Int = -1
        var frontRightPressureRaw: Int = -1
        var rearLeftPressureRaw: Int = -1
        var rearRightPressureRaw: Int = -1

        // 空调
        var acValid: Boolean = false
        var acOn: Boolean = false
        var acCompressorOn: Boolean = false
        var windLevel: Int = -1
        var temperatureLevel: Int = -1
        var heatingOn: Boolean = false
        var ventMode: Int = -1

        fun hasSpeed(): Boolean = speedValid && speedKmh in 0..300
        fun hasBattery(): Boolean = batteryPercent in 0f..100f && remainingRangeKm in 0..3000
        fun hasGear(): Boolean = gearValid
    }

    /** 解析一帧。返回 true 表示这一帧被识别并更新了快照。 */
    fun parse(bytes: ByteArray?, snapshot: Snapshot): Boolean {
        if (bytes == null || bytes.size < PAYLOAD_OFFSET) return false
        if ((bytes[0].toInt() and 0xff) != HEADER_0) return false
        if ((bytes[1].toInt() and 0xff) != HEADER_1) return false
        if ((bytes[2].toInt() and 0xff) + 3 != bytes.size) return false
        if ((bytes[3].toInt() and 0xff) != ADDRESS) return false

        val project = bytes[4].toInt() and 0xff
        return when (project) {
            PROJECT_AC -> parseAc(bytes, snapshot)
            PROJECT_TIRE -> parseTire(bytes, snapshot)
            PROJECT_NEW_ENERGY -> parseNewEnergy(bytes, snapshot)
            PROJECT_VEHICLE_STATUS -> parseVehicleStatus(bytes, snapshot)
            else -> false
        }
    }

    private fun parseAc(b: ByteArray, s: Snapshot): Boolean {
        if (b.size < 10 || (b[5].toInt() and 0xff) != AC_SUBCOMMAND) return false
        val i2 = b[6].toInt() and 0xff
        val i3 = b[7].toInt() and 0xff
        val i4 = b[8].toInt() and 0xff
        if ((i4 and 0x80) != 0) return false
        s.acValid = true
        s.acOn = ((i2 shr 3) and 1) != 0
        s.acCompressorOn = ((i2 shr 2) and 1) != 0
        s.windLevel = i3 and 0x0f
        s.temperatureLevel = i4 and 0x1f
        s.heatingOn = (i4 and 0x40) != 0
        val loop = (i2 shr 6) and 3
        val vent = (i3 shr 4) and 7
        s.ventMode = when (vent) {
            0 -> 1
            1 -> 3
            2 -> 2
            3 -> 4
            else -> 0
        }
        return true
    }

    private fun parseTire(b: ByteArray, s: Snapshot): Boolean {
        if (b.size < 19 || (b[5].toInt() and 0xff) != TIRE_SUBCOMMAND) return false
        s.frontLeftPressureRaw = b[6].toInt() and 0xff
        s.frontRightPressureRaw = b[7].toInt() and 0xff
        s.rearLeftPressureRaw = b[8].toInt() and 0xff
        s.rearRightPressureRaw = b[9].toInt() and 0xff
        s.tireValid = true
        return true
    }

    private fun parseNewEnergy(b: ByteArray, s: Snapshot): Boolean {
        if (b.size < 21 || (b[5].toInt() and 0xff) != NEW_ENERGY_SUBCOMMAND) return false
        val i = b[6].toInt() and 0xff
        val i2 = b[7].toInt() and 0xff
        val i3 = b[8].toInt() and 0xff
        val i4 = b[11].toInt() and 0xff
        val i5 = b[12].toInt() and 0xff
        val i6 = b[14].toInt() and 0xff
        val i7 = b[18].toInt() and 0xff

        if (b.size == 37 && validChecksum(b)) {
            s.gearRaw = (i6 shr 4) and 0x0f
            // 与黑猫 GearState.isKnown 一致：仅 R=2 / N=3 / D=4 视为已知挡位
            s.gearValid = s.gearRaw == 2 || s.gearRaw == 3 || s.gearRaw == 4
        }

        s.batteryPercent = ((i4 shr 6) or (i5 shl 2)) / 10.0f
        s.remainingRangeKm = (i7 shl 2) or ((i6 shr 2) and 3)
        s.totalMileageKm = (((i shl 16) or (i2 shl 8)) or i3) / 10.0f
        s.batteryVoltageTenths = b[19].toInt() and 0xff

        s.speedValid = b.size > 27
        if (s.speedValid) {
            s.speedKmh = ((b[25].toInt() and 0xff) shl 8) or (b[26].toInt() and 0xff)
        }

        if (b.size > 23) {
            val i8 = b[20].toInt() and 0xff
            val i9 = b[21].toInt() and 0xff and 3
            s.instantaneousConsumptionKwhPer100Km = ((i8 shl 2) or i9) / 10.0f
        }
        if (b.size > 29) {
            s.instantaneousPowerKw = ((b[28].toInt() shr 3) and 15) * 0.5f
        }
        return true
    }

    private fun parseVehicleStatus(b: ByteArray, s: Snapshot): Boolean {
        if (b.size < 16 || (b[5].toInt() and 0xff) != VEHICLE_STATUS_SUBCOMMAND) return false
        val i = b[8].toInt() and 0xff
        val i2 = b[11].toInt() and 0xff
        s.chargeLimitPercent = ((i2 shr 6) and 1) or ((i2 and 0x3f) shl 1)
        s.chargingStatusRaw = i
        s.charging = (i and 20) == 20
        return true
    }

    private fun validChecksum(b: ByteArray): Boolean {
        var sum = 0
        for (idx in 2 until b.size) sum = (sum + (b[idx].toInt() and 0xff)) and 0xff
        return sum == 0
    }
}
