package com.linguareader.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** 同步错误分类：状态码 → 类别，以及 [SyncException] 的缺省类别。 */
class SyncErrorKindTest {

    @Test
    fun statusMapsToKind() {
        assertEquals(SyncErrorKind.AUTH, SyncErrorKind.fromStatus(401))
        assertEquals(SyncErrorKind.NETWORK, SyncErrorKind.fromStatus(0))
        assertEquals(SyncErrorKind.SERVER, SyncErrorKind.fromStatus(403))
        assertEquals(SyncErrorKind.SERVER, SyncErrorKind.fromStatus(404))
        assertEquals(SyncErrorKind.SERVER, SyncErrorKind.fromStatus(500))
    }

    @Test
    fun exceptionDefaultsKindFromStatus() {
        assertEquals(SyncErrorKind.NETWORK, SyncException("offline").kind)
        assertEquals(SyncErrorKind.NETWORK, SyncException("offline", 0).kind)
        assertEquals(SyncErrorKind.AUTH, SyncException("bad credentials", 401).kind)
        assertEquals(SyncErrorKind.SERVER, SyncException("boom", 500).kind)
        // 协议异常：status=0 但不是网络问题，抛出方显式指定
        assertEquals(SyncErrorKind.SERVER, SyncException("缺少 token", 0, SyncErrorKind.SERVER).kind)
    }
}
