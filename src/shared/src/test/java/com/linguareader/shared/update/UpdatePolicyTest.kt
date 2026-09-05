package com.linguareader.shared.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较：tag 前缀、多段、畸形输入。 */
class UpdatePolicyTest {

    @Test
    fun `newer tag is detected with and without v prefix`() {
        assertTrue(UpdatePolicy.isNewer("v1.6.0", "1.5.0"))
        assertTrue(UpdatePolicy.isNewer("1.6.0", "1.5.0"))
        assertTrue(UpdatePolicy.isNewer("V1.6.0", "1.5.0"))
    }

    @Test
    fun `same or older version is not newer`() {
        assertFalse(UpdatePolicy.isNewer("v1.5.0", "1.5.0"))
        assertFalse(UpdatePolicy.isNewer("v1.4.9", "1.5.0"))
        assertFalse(UpdatePolicy.isNewer("v1.5.0-rc", "1.5.0")) // 畸形（带后缀）宁可漏报
    }

    @Test
    fun `numeric comparison not lexicographic`() {
        assertTrue(UpdatePolicy.isNewer("v1.10.0", "v1.9.0"))
        assertFalse(UpdatePolicy.isNewer("v1.9.0", "v1.10.0"))
    }

    @Test
    fun `shorter version padded with zeros`() {
        assertTrue(UpdatePolicy.isNewer("v2", "1.5.0"))
        assertFalse(UpdatePolicy.isNewer("v1", "1.5.0"))
        assertTrue(UpdatePolicy.isNewer("v1.5.1", "1.5"))
    }

    @Test
    fun `malformed input never reports newer`() {
        assertFalse(UpdatePolicy.isNewer("", "1.5.0"))
        assertFalse(UpdatePolicy.isNewer("v", "1.5.0"))
        assertFalse(UpdatePolicy.isNewer("v1.x.0", "1.5.0"))
        assertFalse(UpdatePolicy.isNewer("v1.6.0", ""))
        assertFalse(UpdatePolicy.isNewer("v1.6.0", "nonsense"))
        assertFalse(UpdatePolicy.isNewer("v1.-6.0", "1.5.0"))
    }
}
