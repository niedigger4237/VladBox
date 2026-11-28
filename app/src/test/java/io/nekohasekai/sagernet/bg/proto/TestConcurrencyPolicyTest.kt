package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test

class TestConcurrencyPolicyTest {
    @Test fun respectsUserLimitAndDeviceCap() {
        assertEquals(5, TestConcurrencyPolicy.workerCount(5, 100, false))
        assertEquals(8, TestConcurrencyPolicy.workerCount(100, 100, false))
        assertEquals(3, TestConcurrencyPolicy.workerCount(8, 100, true))
        assertEquals(2, TestConcurrencyPolicy.workerCount(8, 2, false))
        assertEquals(0, TestConcurrencyPolicy.workerCount(5, 0, false))
        assertEquals(1, TestConcurrencyPolicy.workerCount(0, 10, false))
    }

    @Test fun recognizesHeavyGeneratedConfigurations() {
        assertFalse(TestConcurrencyPolicy.isHeavy(listOf("vless", "direct", "direct"), false, false))
        assertTrue(TestConcurrencyPolicy.isHeavy(listOf("naive", "direct"), false, false))
        assertTrue(TestConcurrencyPolicy.isHeavy(listOf("vless", "naive", "direct"), false, false))
        assertTrue(TestConcurrencyPolicy.isHeavy(listOf("vless", "wireguard", "direct"), false, false))
        assertTrue(TestConcurrencyPolicy.isHeavy(listOf("socks", "direct"), true, false))
        assertTrue(TestConcurrencyPolicy.isHeavy(listOf("vless"), false, true))
    }
}
