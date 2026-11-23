package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 控制连接上的一条文本消息（RTSP 风格）：首行、按原顺序的头部、正文。
 *
 * 请求首行形如 `SET_PARAMETER * DMSDP/1.0`，响应首行形如 `DMSDP/1.0 200 OK`。行尾都是 CRLF，
 * 头部之后一个空行，再接正文。正文长度以控制帧长度为准；`Content-Length` 原样留在 [headers] 里，
 * 由具体消息的处理方校验。
 */
class DmsdpMessage(
    val startLine: String,
    /** 头部，保持收到的顺序；名称保留原大小写，名称和值都已去掉首尾空白。可能有重名。 */
    val headers: List<Pair<String, String>>,
    body: ByteArray,
) {
    private val bodyBytes = body.copyOf()

    val body: ByteArray get() = bodyBytes.copyOf()

    val bodySize: Int get() = bodyBytes.size

    /** 首行第一个词是 `DMSDP/1.0` 即为响应。 */
    val isResponse: Boolean get() = firstToken == VERSION

    /** 请求的方法名；响应为 null。 */
    val method: String? get() = if (isResponse) null else firstToken

    /** 响应的状态码；请求或状态码不是数字时为 null。 */
    val statusCode: Int?
        get() {
            if (!isResponse) return null
            val code = startLine.split(' ').getOrNull(1) ?: return null
            return if (code.length == 3 && isAsciiDecimal(code)) code.toInt() else null
        }

    /** 按名称取第一个头部的值，名称不区分大小写。 */
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private val firstToken: String get() = startLine.substringBefore(' ')

    override fun toString() = "DmsdpMessage($startLine, ${headers.size} 个头部, 正文 ${bodyBytes.size} 字节)"

    internal companion object {
        const val VERSION = "DMSDP/1.0"
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        /**
         * 拆一条文本消息：头部区必须是 ASCII、以第一个 CRLFCRLF 结束，每个头部行都要有冒号。
         * 这里不判断方法、版本、重名和 Content-Length。
         */
        fun parse(payload: ByteArray): DmsdpMessage {
            val headerEnd = indexOfBlankLine(payload)
            if (headerEnd < 0) throw MalformedMessageException("控制消息缺少头部结束的空行")
            for (i in 0 until headerEnd) {
                if (payload[i] < 0) throw MalformedMessageException("控制消息头部含非 ASCII 字节")
            }
            val lines = String(payload, 0, headerEnd, Charsets.US_ASCII).split("\r\n")
            val headers = ArrayList<Pair<String, String>>(lines.size - 1)
            for (k in 1 until lines.size) {
                val line = lines[k]
                val colon = line.indexOf(':')
                if (colon < 0) throw MalformedMessageException("控制消息头部第 $k 行缺少冒号")
                headers += line.substring(0, colon).trimControl() to line.substring(colon + 1).trimControl()
            }
            return DmsdpMessage(lines[0], headers, payload.copyOfRange(headerEnd + 4, payload.size))
        }

        /**
         * 看起来是文字消息：首行全是可打印 ASCII、以 CRLF 结束，是「方法名 空格 …」（方法名只有大写字母和下划线）
         * 或以 `DMSDP/1.0 ` 开头，并且有头部结束的空行。控制连接上的二进制块也可能碰巧以大写字母开头、中间含空行，
         * 只看首字节会被当成文字去解析、解析失败断开连接。
         */
        fun looksLikeText(payload: ByteArray): Boolean {
            var end = 0
            while (end < payload.size && payload[end] != CR) {
                if (payload[end] !in PRINTABLE) return false
                end++
            }
            if (end == 0 || end + 1 >= payload.size || payload[end + 1] != LF) return false
            val first = String(payload, 0, end, Charsets.US_ASCII)
            val space = first.indexOf(' ')
            val shaped = first.startsWith("$VERSION ") ||
                (space > 0 && space < first.length - 1 && first.substring(0, space).all { it in 'A'..'Z' || it == '_' })
            return shaped && indexOfBlankLine(payload) >= 0
        }

        private val PRINTABLE = 0x20.toByte()..0x7E.toByte()

        /** 编码一条消息：首行、头部各占一行（CRLF），再一个空行，再正文。 */
        fun encode(startLine: String, headers: List<Pair<String, String>>, body: ByteArray = ByteArray(0)): ByteArray {
            val text = StringBuilder(startLine).append("\r\n")
            for ((name, value) in headers) text.append(name).append(": ").append(value).append("\r\n")
            text.append("\r\n")
            val head = text.toString().toByteArray(Charsets.UTF_8)
            return if (body.isEmpty()) head else head + body
        }

        private fun indexOfBlankLine(data: ByteArray): Int {
            for (i in 0..data.size - 4) {
                if (data[i] == CR && data[i + 1] == LF && data[i + 2] == CR && data[i + 3] == LF) return i
            }
            return -1
        }

        /** 去掉首尾空白和控制字符（与 Java String.trim 相同的范围）。 */
        private fun String.trimControl(): String = trim { it <= ' ' }
    }
}

/** 名称不区分大小写时是否有重名头部。 */
internal fun DmsdpMessage.hasDuplicateHeaders(): Boolean {
    val seen = HashSet<String>()
    for ((name, _) in headers) {
        if (!seen.add(name.lowercase())) return true
    }
    return false
}

/** Content-Length（缺省按 0）与实际正文长度一致。 */
internal fun DmsdpMessage.contentLengthMatches(): Boolean {
    val declared = header("Content-Length") ?: return bodySize == 0
    if (!isAsciiDecimal(declared) || declared.length > 9) return false
    return declared.toInt() == bodySize
}
