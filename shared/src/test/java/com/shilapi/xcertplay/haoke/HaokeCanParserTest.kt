package com.shilapi.xcertplay.haoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 好客 CAN 帧解析器单元测试。
 *
 * 用黑猫（com.wuyo.oratool）源码中已确认的帧格式与字段偏移，
 * 手工构造带校验和的 CAN 帧，验证 HaokeCanParser 的字节级解析是否正确。
 * 这是实车验证前的"软模拟"——它不依赖真实车辆，但能覆盖绝大多数移植偏移错误。
 */
class HaokeCanParserTest {

    // ---- 帧构造工具 ----

    /** 构造帧：帧头 0x5A 0xA5 + len 字节 + 0xA9 + project + subcmd + payload，自动填校验和 */
    private fun frame(project: Int, subcmd: Int, payload: ByteArray): ByteArray {
        // 总长 = 6 头 + payload；b[2] = 长度 - 3
        val total = 6 + payload.size
        val b = ByteArray(total)
        b[0] = 0x5A.toByte()
        b[1] = 0xA5.toByte()
        b[2] = (total - 3).toByte()
        b[3] = 0xA9.toByte()
        b[4] = project.toByte()
        b[5] = subcmd.toByte()
        payload.copyInto(b, 6)
        return b
    }

    /** 调整 b[2..end] 的校验和，使累加 == 0（黑猫 validChecksum 规则） */
    private fun fixChecksum(b: ByteArray) {
        var sum = 0
        for (i in 2 until b.size) sum = (sum + (b[i].toInt() and 0xff)) and 0xff
        // 在最后一个字节上补齐，使总和为 0
        b[b.size - 1] = ((b[b.size - 1].toInt() and 0xff) - sum and 0xff).toByte()
    }

    // ---- 测试用例 ----

    @Test
    fun `解析 37 字节新能源帧 - 车速`() {
        // payload 大小 = 31（总长 37），这样 b[25]/b[26] 才有意义且 speedValid=true
        val payload = ByteArray(31)
        // 车速 = 0x30 0x32 = 12338 ？ 直接用高位/低位设值：b[25]=0x00, b[26]=0x3C => 60 km/h
        payload[25] = 0x00
        payload[26] = 0x3C  // 60
        val b = frame(HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND, payload)
        fixChecksum(b)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.hasSpeed())
        assertEquals(60, s.speedKmh)
    }

    @Test
    fun `解析新能源帧 - 电量与续航`() {
        val payload = ByteArray(31)
        // 电量：batteryPercent = ((b[11]>>6) | (b[12]<<2)) / 10
        // 设 b[11] = 0x40（>>6 = 1），b[12] = 0x13（<<2 = 76） => (1|76)=77 => 7.7%
        // 更直观：目标 80.0% => 需要 raw = 800 => b[11]>>6 贡献 0..3, b[12]<<2 贡献
        // 让 b[11]=0x00, b[12]=200 => 200<<2=800 => 80.0%
        payload[11] = 0x00
        payload[12] = 200.toByte()
        // 续航：remainingRangeKm = (b[18]<<2) | ((b[14]>>2)&3)
        // 目标 250 => 250>>2=62 放 b[18], 余 2 放 b[14] 的 bit2..3 => b[14]=2<<2=8
        payload[18] = 62
        payload[14] = 8
        val b = frame(HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND, payload)
        fixChecksum(b)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.hasBattery())
        assertEquals(80.0f, s.batteryPercent, 0.01f)
        assertEquals(250, s.remainingRangeKm)
    }

    @Test
    fun `解析 37 字节新能源帧 - 挡位 D`() {
        val payload = ByteArray(31)
        // gearRaw = (b[14] >> 4) & 0x0f，D 挡在黑猫 GearState 里通常是 4
        payload[14] = (4 shl 4).toByte()
        val b = frame(HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND, payload)
        fixChecksum(b)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.hasGear())
        assertEquals(4, s.gearRaw)
    }

    @Test
    fun `解析车辆状态帧 - 充电中`() {
        // project=43, subcmd=9，长度需 >= 16
        val payload = ByteArray(12) // 总长 18 >= 16
        // charging = (b[8] & 20) == 20 => b[8] = 20
        payload[8] = 20
        val b = frame(HaokeCanParser.PROJECT_VEHICLE_STATUS, HaokeCanParser.VEHICLE_STATUS_SUBCOMMAND, payload)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.charging)
    }

    @Test
    fun `解析胎压帧 - 四轮压力`() {
        // project=40, subcmd=13，长度需 >= 19
        val payload = ByteArray(15) // 总长 21 >= 19
        payload[6] = 230.toByte()  // 前左
        payload[7] = 235.toByte()  // 前右
        payload[8] = 225.toByte()  // 后左
        payload[9] = 228.toByte()  // 后右
        val b = frame(HaokeCanParser.PROJECT_TIRE, HaokeCanParser.TIRE_SUBCOMMAND, payload)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.tireValid)
        assertEquals(230, s.frontLeftPressureRaw)
        assertEquals(235, s.frontRightPressureRaw)
        assertEquals(225, s.rearLeftPressureRaw)
        assertEquals(228, s.rearRightPressureRaw)
    }

    @Test
    fun `非法帧头被拒绝`() {
        val b = ByteArray(10)
        b[0] = 0x00
        assertFalse(HaokeCanParser.parse(b, HaokeCanParser.Snapshot()))
    }

    @Test
    fun `长度字段不符被拒绝`() {
        val b = ByteArray(10)
        b[0] = 0x5A.toByte()
        b[1] = 0xA5.toByte()
        b[2] = 100  // 声明长度 103，实际只有 10 字节
        b[3] = 0xA9.toByte()
        assertFalse(HaokeCanParser.parse(b, HaokeCanParser.Snapshot()))
    }

    @Test
    fun `校验和错误时挡位不更新`() {
        val payload = ByteArray(31)
        payload[14] = (4 shl 4).toByte()
        val b = frame(HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND, payload)
        // 故意不修校验和（破坏它）
        b[36] = ((b[36].toInt() + 1) and 0xff).toByte()

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        // 电量/续航/车速仍然解析，但挡位因校验和失败而不更新
        assertFalse(s.hasGear())
    }
}
