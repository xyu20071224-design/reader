package com.linguareader.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 行内反馈状态与文案解耦（StatusTone 决定颜色，不靠文案前缀）。 */
class SettingsStatusTest {

    @Test
    fun helpersMapToExpectedTones() {
        assertEquals(StatusTone.SUCCESS, SettingsStatus.success("连接成功").tone)
        assertEquals(StatusTone.DANGER, SettingsStatus.danger("连接失败").tone)
        assertEquals(StatusTone.NEUTRAL, SettingsStatus.info("正在导入…").tone)
    }

    @Test
    fun defaultToneIsNeutral() {
        assertEquals(StatusTone.NEUTRAL, SettingsStatus("任意文案").tone)
    }

    @Test
    fun textIsPreservedVerbatim() {
        assertEquals("已保存", SettingsStatus.success("已保存").text)
    }
}
