package io.github.gcbond.openhc.core.dmsdp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 控制连接上的载荷是不是文字消息。数据都是合成的。 */
class TextPayloadTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { acc, p ->
        acc + when (p) {
            is String -> p.toByteArray(Charsets.US_ASCII)
            is ByteArray -> p
            else -> error("不支持的类型")
        }
    }

    @Test
    fun requestsAndResponsesAreText() {
        assertTrue(DmsdpMessage.looksLikeText(bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n")))
        assertTrue(DmsdpMessage.looksLikeText(bytes("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 4\r\n\r\nbody")))
        assertTrue(DmsdpMessage.looksLikeText(bytes("DMSDP/1.0 200 OK\r\nCSeq: 3\r\n\r\n")))
    }

    /** 二进制块碰巧以大写字母开头、中间含空行：不当文字。 */
    @Test
    fun binaryBlocksStartingWithCapitalsAreNotText() {
        assertFalse(DmsdpMessage.looksLikeText(bytes("R", byteArrayOf(0, 1, 2), "\r\n\r\n", byteArrayOf(9))))
        assertFalse(DmsdpMessage.looksLikeText(bytes("VX", byteArrayOf(-1, 7), " a\r\n\r\n")))
        assertFalse(DmsdpMessage.looksLikeText(bytes("GABC\r\n\r\n")), "首行没有空格")
        assertFalse(DmsdpMessage.looksLikeText(bytes("Get x DMSDP/1.0\r\n\r\n")), "方法名有小写")
        assertFalse(DmsdpMessage.looksLikeText(bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n")), "没有头部结束的空行")
        assertFalse(DmsdpMessage.looksLikeText(bytes("#", ByteArray(17), "\r\n\r\n")))
        assertFalse(DmsdpMessage.looksLikeText(ByteArray(0)))
    }
}
