package io.github.gcbond.openhc.core.dmsdp.audio

/**
 * 加密控制连接上送来的声音（不走音频数据连接）：鸿蒙 3.0 / 4.x 的手机建不起音频数据会话时，整段声音都这样送；
 * 纯血鸿蒙在数据连接连上之前也会先送一点。
 *
 * 一片的格式（多字节整数大端）：
 * - 0：`#`；1～4：流号（手机的一个音频数据会话号，每次连接都变）。同时放两路声音（例如导航和音乐）时两路的流号不同、
 *   各片交替到达，按流号分开拼；
 * - 5～8：固定 [MAGIC]；
 * - 9：这一片在组里的位置：1 = 首片，2 = 中间片，3 = 末片（一组 3 片时是 1、2、3，6 片时是 1、2、2、2、2、3）；
 * - 10～13：组号，同一组的各片相同；14～15：片序号，每组从 0 起逐片加一；
 * - 16～17：本片数据长度，等于整片长度 − [HEADER]；之后是数据。
 *
 * 同一组的各片按片序号依次拼起来是一组：开头是组头，组头最后 4 字节等于整组长度，之后是 PCM
 * （16 位小端交错，采样参数和手机在声音请求里写的一样，2026-11 实机为 48 kHz 双声道）。组头的长度各版本不同：
 * 鸿蒙 3.0 是 14 字节（一组 1024 帧），鸿蒙 4.2 是 18 字节（一组 2048 帧），见 [GROUP_HEADERS]。
 *
 * 结构对不上的载荷不认（[feed] 返回 false），交给调用方按别的消息处理；认得出但拼不成一组的（缺片、乱序、组头不对）丢掉。
 * 纯逻辑，非线程安全。
 */
internal class ControlChannelAudio {
    /** 一路声音正在拼的组。 */
    private class Assembly {
        var sequence = -1L
        var count = 0
        val buffer = java.io.ByteArrayOutputStream()

        fun reset() {
            sequence = -1
            count = 0
            buffer.reset()
        }
    }

    /** 各路正在拼的组，键是流号；最多 [MAX_STREAMS] 路，再多丢掉最早出现的那路。 */
    private val assemblies = LinkedHashMap<Long, Assembly>()

    /** 认出过的片数、拼成的组数、丢掉的组数（诊断用）。 */
    var fragments = 0L
        private set
    var groups = 0L
        private set
    var dropped = 0L
        private set

    /**
     * 喂入一条控制载荷。是这种声音片就返回 true；某一路拼成一组时先调 [onPcm]（参数是流号和 PCM，数据只在回调期间有效）。
     */
    fun feed(payload: ByteArray, onPcm: (stream: Long, data: ByteArray, offset: Int, length: Int) -> Unit): Boolean {
        if (payload.size < HEADER || u32(payload, MAGIC_AT) != MAGIC) return false
        val position = payload[POSITION_AT].toInt() and 0xFF
        val length = u16(payload, 16)
        if (position !in FIRST..LAST || length != payload.size - HEADER) return false
        fragments++
        val stream = u32(payload, STREAM_AT)
        val a = assemblies[stream] ?: Assembly().also {
            if (assemblies.size >= MAX_STREAMS) assemblies.remove(assemblies.keys.first())
            assemblies[stream] = it
        }
        val seq = u32(payload, 10)
        val index = u16(payload, 14)
        if (index == 0) {
            if (a.count > 0) dropped++
            a.reset()
            a.sequence = seq
        } else if (a.count == 0 || seq != a.sequence || index != a.count) {
            if (a.count > 0) dropped++
            a.reset()
            return true
        }
        a.buffer.write(payload, HEADER, length)
        a.count++
        if (a.count > MAX_FRAGMENTS || a.buffer.size() > MAX_GROUP_BYTES) {
            dropped++
            a.reset()
            return true
        }
        if (position != LAST) return true
        val group = a.buffer.toByteArray()
        a.reset()
        val header = GROUP_HEADERS.firstOrNull { it < group.size && u32(group, it - 4) == group.size.toLong() }
        val pcm = if (header == null) 0 else group.size - header
        if (header == null || pcm % 2 != 0) {
            dropped++
            return true
        }
        groups++
        onPcm(stream, group, header, pcm)
        return true
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        ((u16(b, at).toLong()) shl 16) or u16(b, at + 2).toLong()

    companion object {
        /** 每片的头长度。 */
        const val HEADER = 18

        /** 片头 5～8 字节的固定值。 */
        const val MAGIC = 0xFEDCBA03L

        /** 组头的几种长度（组头最后 4 字节是整组长度），按顺序试。 */
        val GROUP_HEADERS = intArrayOf(14, 18)

        /** 一组最多几片、最多多少字节（实机一组 3 片约 4 KB、6 片约 8 KB）。 */
        const val MAX_FRAGMENTS = 32
        const val MAX_GROUP_BYTES = 64 * 1024

        /** 同时拼的声音最多几路。 */
        const val MAX_STREAMS = 4

        private const val STREAM_AT = 1
        private const val MAGIC_AT = 5
        private const val POSITION_AT = 9
        private const val FIRST = 1
        private const val LAST = 3
    }
}
