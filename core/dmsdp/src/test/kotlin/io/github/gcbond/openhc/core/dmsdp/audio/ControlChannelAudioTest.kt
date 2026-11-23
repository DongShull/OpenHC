package io.github.gcbond.openhc.core.dmsdp.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 控制连接上的声音：分片的识别、按组拼接、组头校验。数据都是合成的。 */
class ControlChannelAudioTest {

    /** 一组：[headerSize] 字节组头（最后 4 字节是整组长度，其余是合成值）+ [pcm]。 */
    private fun group(pcm: ByteArray, headerSize: Int = 14): ByteArray {
        val total = headerSize + pcm.size
        val head = ByteArray(headerSize) { (it + 1).toByte() }
        for (k in 0 until 4) head[headerSize - 4 + k] = (total shr (24 - 8 * k)).toByte()
        return head + pcm
    }

    /** 把一组切成 [parts] 片，每片带 18 字节片头；位置标记首片 1、末片 3、其余 2。流号用合成值。 */
    private fun fragments(seq: Long, whole: ByteArray, parts: Int, stream: Long = 0x51525354): List<ByteArray> {
        val size = (whole.size + parts - 1) / parts
        return (0 until parts).map { i ->
            val data = whole.copyOfRange(i * size, minOf(whole.size, (i + 1) * size))
            val h = ByteArray(ControlChannelAudio.HEADER)
            h[0] = '#'.code.toByte()
            for (k in 0 until 4) h[1 + k] = (stream shr (24 - 8 * k)).toByte()
            for (k in 0 until 4) h[5 + k] = (ControlChannelAudio.MAGIC shr (24 - 8 * k)).toByte()
            h[9] = when (i) {
                parts - 1 -> 3
                0 -> 1
                else -> 2
            }
            for (k in 0 until 4) h[10 + k] = (seq shr (24 - 8 * k)).toByte()
            h[14] = (i shr 8).toByte()
            h[15] = i.toByte()
            h[16] = (data.size shr 8).toByte()
            h[17] = data.size.toByte()
            h + data
        }
    }

    private fun pcm(frames: Int, seed: Int) = ByteArray(frames * 4) { ((it * 7 + seed) and 0xFF).toByte() }

    private fun collect(audio: ControlChannelAudio, input: List<ByteArray>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (f in input) assertTrue(audio.feed(f) { _, d, o, l -> out += d.copyOfRange(o, o + l) })
        return out
    }

    @Test
    fun threeFragmentsWithShortGroupHeaderMakeOneGroupOfPcm() {
        val audio = ControlChannelAudio()
        val expected = listOf(pcm(1024, 1), pcm(1024, 2))
        val out = collect(audio, expected.withIndex().flatMap { (seq, samples) -> fragments(seq.toLong(), group(samples), 3) })
        assertEquals(2, out.size)
        assertContentEquals(expected[0], out[0])
        assertContentEquals(expected[1], out[1])
        assertEquals(2, audio.groups)
        assertEquals(0, audio.dropped)
    }

    /** 一组 6 片（中间 4 片的位置标记都是 2），组头 18 字节。 */
    @Test
    fun sixFragmentsWithLongGroupHeaderMakeOneGroupOfPcm() {
        val audio = ControlChannelAudio()
        val samples = pcm(2048, 3)
        val out = collect(audio, fragments(40, group(samples, headerSize = 18), 6) + fragments(41, group(pcm(2048, 4), 18), 6))
        assertEquals(2, out.size)
        assertContentEquals(samples, out[0])
        assertContentEquals(pcm(2048, 4), out[1])
        assertEquals(0, audio.dropped)
    }

    /** 两路声音（流号不同）的片交替到达：各拼各的，都不丢。 */
    @Test
    fun interleavedStreamsAreAssembledSeparately() {
        val audio = ControlChannelAudio()
        val music = fragments(5, group(pcm(2048, 7), 18), 6, stream = 0x11111111)
        val guide = fragments(9, group(pcm(1024, 8)), 3, stream = 0x22222222)
        val order = listOf(music[0], guide[0], music[1], music[2], guide[1], music[3], guide[2], music[4], music[5])
        val out = ArrayList<Pair<Long, ByteArray>>()
        for (f in order) assertTrue(audio.feed(f) { stream, d, o, l -> out += stream to d.copyOfRange(o, o + l) })
        assertEquals(listOf(0x22222222L, 0x11111111L), out.map { it.first })
        assertContentEquals(pcm(1024, 8), out[0].second)
        assertContentEquals(pcm(2048, 7), out[1].second)
        assertEquals(0, audio.dropped)
    }

    @Test
    fun missingOrShuffledFragmentsDropTheGroupButKeepGoing() {
        val audio = ControlChannelAudio()
        val out = ArrayList<ByteArray>()
        val sink: (Long, ByteArray, Int, Int) -> Unit = { _, d, o, l -> out += d.copyOfRange(o, o + l) }
        val first = fragments(7, group(pcm(256, 3)), 3)
        val second = fragments(8, group(pcm(256, 4)), 3)
        // 第 7 组缺中间一片：认得出，但拼不成。
        assertTrue(audio.feed(first[0], sink))
        assertTrue(audio.feed(first[2], sink))
        // 第 8 组完整。
        for (f in second) audio.feed(f, sink)
        assertEquals(1, out.size)
        assertContentEquals(pcm(256, 4), out[0])
        assertEquals(1, audio.dropped)
        // 组头里的总长对不上：丢掉。
        val bad = group(pcm(64, 5)).also { it[13] = (it[13] + 1).toByte() }
        for (f in fragments(9, bad, 2)) assertTrue(audio.feed(f, sink))
        assertEquals(1, out.size)
        assertEquals(2, audio.dropped)
        // 末片的组号和前面不一样：丢掉。
        val mixed = fragments(10, group(pcm(64, 6)), 2).toMutableList()
        mixed[1] = fragments(11, group(pcm(64, 6)), 2)[1]
        for (f in mixed) assertTrue(audio.feed(f, sink))
        assertEquals(1, out.size)
        assertEquals(3, audio.dropped)
    }

    @Test
    fun otherPayloadsAreNotRecognized() {
        val audio = ControlChannelAudio()
        val sink: (Long, ByteArray, Int, Int) -> Unit = { _, _, _, _ -> error("不该交出") }
        val ok = fragments(1, group(pcm(16, 6)), 1).single()
        assertFalse(audio.feed(ok.copyOf(ControlChannelAudio.HEADER - 1), sink), "太短")
        assertFalse(audio.feed(ok.copyOf().also { it[17] = (it[17] + 1).toByte() }, sink), "长度字段对不上")
        assertFalse(audio.feed(ok.copyOf().also { it[6] = 0 }, sink), "固定值不对")
        assertFalse(audio.feed(ok.copyOf().also { it[9] = 0 }, sink), "位置标记为 0")
        assertFalse(audio.feed(ok.copyOf().also { it[9] = 4 }, sink), "位置标记超出")
        assertFalse(audio.feed("{\"a\":1}".toByteArray(), sink))
        assertEquals(0, audio.fragments)
    }
}
