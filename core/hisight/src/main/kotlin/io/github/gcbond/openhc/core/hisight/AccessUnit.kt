package io.github.gcbond.openhc.core.hisight

/**
 * 一个已解密的 H.264 访问单元（一帧，Annex-B 格式，带起始码）。
 *
 * **生命周期**：对象本身和 [data] 指向的缓冲区都会被复用，只在 [AccessUnitSink.onAccessUnit]
 * 回调期间有效。回调返回后下一帧会覆盖它们；要留到回调之后用（例如解码器暂时没有空闲输入缓冲），
 * 必须在回调里调用 [copyBytes] 自己保留一份。
 */
class AccessUnit internal constructor() {
    /** 存放这一帧的缓冲区，有效区间是 `[offset, offset + length)`。 */
    var data: ByteArray = ByteArray(0)
        internal set
    var offset: Int = 0
        internal set
    var length: Int = 0
        internal set

    /** 这一帧 RTP 包头里的时间戳，无符号 32 位原值。 */
    var rtpTimestamp: Long = 0
        internal set

    /** PES 头里的 PTS，90kHz 计数。协议只保留了低 32 位（最高位不传）。 */
    var pts90k: Long = 0
        internal set

    /** 这一帧第一个和最后一个 RTP 包的序号。 */
    var firstSequence: Int = 0
        internal set
    var lastSequence: Int = 0
        internal set

    /** [pts90k] 换算成微秒，可直接作为解码器的显示时间戳。 */
    val presentationTimeUs: Long get() = pts90k * 100 / 9

    fun copyBytes(): ByteArray = data.copyOfRange(offset, offset + length)

    /**
     * 按出现顺序列出各 NAL 单元的类型（起始码后一字节的低 5 位），例如 7=SPS、8=PPS、5=IDR。
     * 每次调用都会扫描整帧，不需要时不要调用。
     */
    fun nalTypes(): IntArray {
        val types = ArrayList<Int>()
        val end = offset + length
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val startCodeEnd = when {
                    i + 3 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    data[i + 2].toInt() == 1 -> i + 3
                    else -> -1
                }
                if (startCodeEnd > 0) {
                    if (startCodeEnd < end) types += data[startCodeEnd].toInt() and 0x1F
                    i = startCodeEnd
                    continue
                }
            }
            i++
        }
        return types.toIntArray()
    }
}

/** 接收视频访问单元。在调用 [HiSightReceiver.feedVideo] 的线程上同步回调。 */
fun interface AccessUnitSink {
    fun onAccessUnit(unit: AccessUnit)
}
