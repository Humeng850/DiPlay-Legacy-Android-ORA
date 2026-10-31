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
 *
 * 帧布局（与黑猫 VehicleDataParser 一致，索引为最终帧 bArr[]）：
 *   [0]=0x5A  [1]=0xA5  [2]=长度-3  [3]=0xA9  [4]=project  [5]=subcmd  [6..]=payload
 */
class HaokeCanParserTest {

    /** 构造一个指定长度的帧，帧头/地址/project/subcmd 已填好，其余为 0 */
    private fun frame(totalLen: Int, project: Int, subcmd: Int): ByteArray {
        val b = ByteArray(totalLen)
        b[0] = 0x5A.toByte()
        b[1] = 0xA5.toByte()
        b[2] = (totalLen - 3).toByte()
        b[3] = 0xA9.toByte()
        b[4] = project.toByte()
        b[5] = subcmd.toByte()
        return b
    }

    /** 调整 b[2..end] 使累加和 == 0（黑猫 validChecksum 规则：从 index 2 累加到末尾 == 0） */
    private fun fixChecksum(b: ByteArray) {
        var sum = 0
        for (i in 2 until b.size) sum = (sum + (b[i].toInt() and 0xff)) and 0xff
        // 校验和字节通常放在 payload 末尾；这里用最后一个字节补齐使总和归零
        b[b.size - 1] = ((-sum) and 0xff).toByte()
    }

    @Test
    fun `解析新能源帧 - 车速`() {
        // 37 字节帧，speedValid = size>27 成立；车速在 b[25]<<8 | b[26]
        val b = frame(37, HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND)
        b[25] = 0x00
        b[26] = 0x3C  // 60 km/h
        fixChecksum(b)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.hasSpeed())
        assertEquals(60, s.speedKmh)
    }

    @Test
    fun `解析新能源帧 - 电量与续航`() {
        val b = frame(37, HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND)
        // 电量：batteryPercent = ((b[11]>>6) | (b[12]<<2)) / 10
        // 目标 80.0% => raw=800 => b[11]>>6=0, b[12]<<2=800 => b[12]=200
        b[11] = 0x00
        b[12] = 200.toByte()
        // 续航：remainingRangeKm = (b[18]<<2) | ((b[14]>>2)&3)
        // 目标 250 => b[18]=62 (62<<2=248), b[14] bit2..3=2 => b[14]=2<<2=8
        b[18] = 62
        b[14] = 8
        fixChecksum(b)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.hasBattery())
        assertEquals(80.0f, s.batteryPercent, 0.01f)
        assertEquals(250, s.remainingRangeKm)
    }

    @Test
    fun `解析新能源帧 - 挡位 D`() {
        val b = frame(37, HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND)
        // gearRaw = (b[14] >> 4) & 0x0f；D 挡 = 4
        b[14] = (4 shl 4).toByte()
        fixChecksum(b)

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.hasGear())
        assertEquals(4, s.gearRaw)
    }

    @Test
    fun `解析车辆状态帧 - 充电中`() {
        // project=43, subcmd=9，长度需 >= 16
        val b = frame(18, HaokeCanParser.PROJECT_VEHICLE_STATUS, HaokeCanParser.VEHICLE_STATUS_SUBCOMMAND)
        // charging = (b[8] & 20) == 20 => b[8] = 20
        b[8] = 20
        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        assertTrue(s.charging)
    }

    @Test
    fun `解析胎压帧 - 四轮压力`() {
        // project=40, subcmd=13，长度需 >= 19
        val b = frame(21, HaokeCanParser.PROJECT_TIRE, HaokeCanParser.TIRE_SUBCOMMAND)
        b[6] = 230.toByte()   // 前左
        b[7] = 235.toByte()   // 前右
        b[8] = 225.toByte()   // 后左
        b[9] = 228.toByte()   // 后右
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
        val b = frame(37, HaokeCanParser.PROJECT_NEW_ENERGY, HaokeCanParser.NEW_ENERGY_SUBCOMMAND)
        b[14] = (4 shl 4).toByte()
        // 故意破坏校验和（不调用 fixChecksum，并确保末尾字节使累加不为 0）
        b[36] = 1

        val s = HaokeCanParser.Snapshot()
        assertTrue(HaokeCanParser.parse(b, s))
        // 电量/续航/车速仍然解析，但挡位因校验和失败而不更新
        assertFalse(s.hasGear())
    }
}
