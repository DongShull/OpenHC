package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** USB 那一头：一次 bulk 传输收发一整块 NTB。 */
interface NtbPort {
    /** 一次接收的最大字节数，不小于手机单块 NTB 的上限。 */
    val receiveSize: Int

    /** 出向 NTB 的布局。 */
    val outLayout: NtbOutLayout

    /** 手机能收的单块 NTB 上限（dwNtbOutMaxSize），超过的块直接丢掉。 */
    val maxOutBlockLength: Int

    /** 发送端点的最大包长，见 [Ntb16.blockLength]。 */
    val zlpPacketSize: Int

    /** 单块出向 NTB 允许打包的数据报上限（默认 1，表示单帧发送）。 */
    val maxOutDatagrams: Int get() = 1

    /** 读一块到 `buffer[0, receiveSize)`。返回字节数（>0）；0 表示这段时间没有数据；负数表示链路出错。 */
    fun receive(buffer: ByteArray, timeoutMs: Int): Int

    /** 发 `buffer[0, length)` 这一块，整块发出才返回 true。 */
    fun send(buffer: ByteArray, length: Int, timeoutMs: Int): Boolean
}

/** TUN 那一头：一次读写一个 IP 包。 */
interface PacketTunnel {
    /**
     * 阻塞读一个包到 `buffer[offset, offset + maxLength)`，返回长度（>0）；
     * 0 表示暂时没有，接着读；负数表示隧道已关闭或已被 [wakeup] 唤醒，不要再读。
     */
    fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int

    /**
     * 非阻塞尝试再读一个已经就绪的包（用于零等待出向合并）；没有就绪包返回 0，隧道关闭或已唤醒返回负数。
     */
    fun tryRead(buffer: ByteArray, offset: Int, maxLength: Int): Int = 0

    /** 写一个包；写不进去返回 false（包被丢弃）。 */
    fun write(buffer: ByteArray, offset: Int, length: Int): Boolean

    /** 让正在阻塞和以后的 [read] 尽快返回负数。可在任意线程、重复调用。 */
    fun wakeup()
}

/**
 * USB 网卡数据面的两个搬运线程：
 * - 收：bulk-IN 读 NTB → 拆出以太网帧 → [EthernetEndpoint]（ARP 当场应答，IPv4 写进 TUN）；
 * - 发：TUN 读 IPv4 包 → 补以太网头 → 组 NTB → bulk-OUT。
 *
 * 本类不拥有也不关闭 [port] 和 [tunnel]。[stop] 等两个线程都退出后才返回，调用方之后再关资源，
 * 保证关的时候没有线程还在用它们（老系统上 USB 连接的关闭与传输并发不安全，文件描述符关早了还可能被复用）。
 */
class NcmDataPath(
    private val port: NtbPort,
    private val tunnel: PacketTunnel,
    private val endpoint: EthernetEndpoint,
    private val listener: Listener,
    private val timing: Timing = Timing(),
) {
    /** 数据面自己发现的断开原因。 */
    enum class LinkDown {
        /** USB 读持续出错（设备没了、权限被收回等）。 */
        USB_LINK_LOST,

        /** TUN 被关闭（VPN 被撤销、被别的 VPN 顶掉等）。 */
        TUNNEL_CLOSED,

        /** 搬运线程遇到意外异常（程序缺陷）。 */
        INTERNAL_ERROR,
    }

    /** 只用于日志与排查的里程碑，每种只报一次。 */
    enum class Milestone {
        FIRST_NTB_FROM_PHONE,
        FIRST_ARP_REPLY,
        FIRST_PACKET_TO_TUNNEL,
        FIRST_PACKET_TO_PHONE,

        /** 手机在找别的车机地址：多半是它按另一条 carInfo（例如车机自带的投屏应用发的）在找车机。 */
        ARP_FOR_OTHER_ADDRESS,
        FIRST_MALFORMED_NTB,
    }

    interface Listener {
        /**
         * 数据面自己发现链路断了（不是 [stop] 引起的）。在搬运线程上调用，只调一次。
         * 可以在这里调 [stop]，但更好的做法是转到自己的线程再收尾。
         */
        fun onLinkDown(reason: LinkDown)

        /** 在搬运线程上调用，不要做耗时操作。 */
        fun onMilestone(milestone: Milestone, detail: String) {}
    }

    /** 时序参数；测试里可以调小。 */
    class Timing(
        /** 每次 USB 读的超时；也是 [stop] 后收线程最慢多久退出。 */
        val receiveTimeoutMs: Int = 500,
        val sendTimeoutMs: Int = 1000,
        /** USB 读出错后歇多久再读，免得设备没了时空转。 */
        val errorBackoffMs: Long = 20,
        /** 连续出错多少次算链路断开。 */
        val maxConsecutiveErrors: Int = 25,
        /** [stop] 等线程退出的最长时间。 */
        val joinTimeoutMs: Long = 3000,
    )

    /** 计数快照。 */
    data class Stats(
        val ntbFromPhone: Long,
        val bytesFromPhone: Long,
        val malformedNtb: Long,
        val arpReplies: Long,
        val arpForOtherAddress: Long,
        val packetsToTunnel: Long,
        val packetsToPhone: Long,
        val bytesToPhone: Long,
        val droppedFromPhone: Long,
        val droppedToPhone: Long,
        val usbReceiveErrors: Long,
        val usbSendFailures: Long,
    )

    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val sendLock = Any()
    private val reported = AtomicInteger(0)

    private val ntbFromPhone = AtomicLong()
    private val bytesFromPhone = AtomicLong()
    private val malformedNtb = AtomicLong()
    private val arpReplies = AtomicLong()
    private val arpForOtherAddress = AtomicLong()
    private val packetsToTunnel = AtomicLong()
    private val packetsToPhone = AtomicLong()
    private val bytesToPhone = AtomicLong()
    private val droppedFromPhone = AtomicLong()
    private val droppedToPhone = AtomicLong()
    private val usbReceiveErrors = AtomicLong()
    private val usbSendFailures = AtomicLong()

    private class RxSlot(val buffer: ByteArray) {
        var length: Int = 0
    }

    private val rxEndSentinel = RxSlot(ByteArray(0))
    private val rxFree = ArrayBlockingQueue<RxSlot>(RX_POOL_SIZE + 1)
    private val rxPending = ArrayBlockingQueue<RxSlot>(RX_POOL_SIZE + 1)

    @Volatile
    private var receiver: Thread? = null

    @Volatile
    private var deliverer: Thread? = null

    @Volatile
    private var transmitter: Thread? = null

    val isRunning: Boolean get() = running.get()

    /** 起收包、解包投递与发送三条流水线线程。只能调一次。 */
    fun start() {
        check(started.compareAndSet(false, true)) { "数据面只能启动一次" }
        running.set(true)
        rxFree.clear()
        rxPending.clear()
        repeat(RX_POOL_SIZE) { rxFree.add(RxSlot(ByteArray(port.receiveSize))) }
        deliverer = Thread({ deliverLoop() }, "OpenHC-NcmDeliver").apply {
            isDaemon = true
            start()
        }
        receiver = Thread({ receiveLoop() }, "OpenHC-NcmRx").apply {
            isDaemon = true
            start()
        }
        transmitter = Thread({ transmitLoop() }, "OpenHC-NcmTx").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 停止并等全部搬运线程退出（最多 [Timing.joinTimeoutMs]）。可在任意线程、重复调用；
     * 在搬运线程上调时不等它自己。返回全部线程是否都已退出。
     */
    fun stop(): Boolean {
        running.set(false)
        tunnel.wakeup()
        rxPending.offer(rxEndSentinel)
        rxFree.offer(rxEndSentinel)
        val deadline = System.nanoTime() + timing.joinTimeoutMs * 1_000_000
        var allStopped = true
        for (thread in listOf(receiver, deliverer, transmitter)) {
            if (thread == null || thread === Thread.currentThread()) continue
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs > 0) {
                try {
                    thread.join(remainingMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (thread.isAlive) allStopped = false
        }
        return allStopped
    }

    fun stats(): Stats = Stats(
        ntbFromPhone = ntbFromPhone.get(),
        bytesFromPhone = bytesFromPhone.get(),
        malformedNtb = malformedNtb.get(),
        arpReplies = arpReplies.get(),
        arpForOtherAddress = arpForOtherAddress.get(),
        packetsToTunnel = packetsToTunnel.get(),
        packetsToPhone = packetsToPhone.get(),
        bytesToPhone = bytesToPhone.get(),
        droppedFromPhone = droppedFromPhone.get(),
        droppedToPhone = droppedToPhone.get(),
        usbReceiveErrors = usbReceiveErrors.get(),
        usbSendFailures = usbSendFailures.get(),
    )

    // ---------------------------------------------------------------- 手机 → 车机（两级流水线：USB bulk-IN → 拆帧写 TUN）

    private fun receiveLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
            // 单元测试或受限环境下拿不到优先级时照常运行。
        }
        var consecutiveErrors = 0
        try {
            while (running.get()) {
                val slot = rxFree.take()
                if (slot === rxEndSentinel || !running.get()) {
                    if (slot === rxEndSentinel) rxFree.offer(rxEndSentinel)
                    return
                }
                val n = port.receive(slot.buffer, timing.receiveTimeoutMs)
                if (n < 0) {
                    rxFree.offer(slot)
                    usbReceiveErrors.incrementAndGet()
                    if (++consecutiveErrors >= timing.maxConsecutiveErrors) {
                        linkDown(LinkDown.USB_LINK_LOST)
                        return
                    }
                    Thread.sleep(timing.errorBackoffMs)
                    continue
                }
                consecutiveErrors = 0
                if (n == 0) {
                    rxFree.offer(slot)
                    continue
                }
                slot.length = n
                rxPending.put(slot)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            // 不让异常带出线程（安卓上未捕获的异常会结束整个应用），按链路断开收尾。
            linkDown(LinkDown.INTERNAL_ERROR)
        } finally {
            rxPending.offer(rxEndSentinel)
        }
    }

    private fun deliverLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
            // 同上。
        }
        val replyBuffer = ByteArray(Ntb16.blockLength(port.outLayout, MAX_REPLY_FRAME, port.zlpPacketSize))
        val output = object : EthernetEndpoint.Output {
            override fun sendToPhone(frame: ByteArray, offset: Int, length: Int) {
                if (length > MAX_REPLY_FRAME) {
                    droppedToPhone.incrementAndGet()
                    return
                }
                System.arraycopy(frame, offset, replyBuffer, port.outLayout.datagramOffset, length)
                sendFrame(replyBuffer, length)
            }

            override fun deliverToTunnel(packet: ByteArray, offset: Int, length: Int) {
                if (tunnel.write(packet, offset, length)) {
                    if (packetsToTunnel.incrementAndGet() == 1L) {
                        milestone(Milestone.FIRST_PACKET_TO_TUNNEL, "$length 字节")
                    }
                } else {
                    droppedFromPhone.incrementAndGet()
                }
            }
        }
        val sink = Ntb16.DatagramSink { frame, offset, length -> handleFrame(frame, offset, length, output) }
        try {
            while (running.get()) {
                val slot = rxPending.take()
                if (slot === rxEndSentinel || !running.get()) {
                    if (slot === rxEndSentinel) rxPending.offer(rxEndSentinel)
                    return
                }
                val n = slot.length
                try {
                    if (ntbFromPhone.incrementAndGet() == 1L) milestone(Milestone.FIRST_NTB_FROM_PHONE, "$n 字节")
                    bytesFromPhone.addAndGet(n.toLong())
                    Ntb16.parse(slot.buffer, 0, n, sink)
                } catch (e: MalformedMessageException) {
                    // 单块坏数据只丢这一块，线程必须活着，否则整条链路会静默失效。
                    if (malformedNtb.incrementAndGet() == 1L) {
                        milestone(Milestone.FIRST_MALFORMED_NTB, e.message ?: "")
                    }
                } finally {
                    rxFree.offer(slot)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            linkDown(LinkDown.INTERNAL_ERROR)
        }
    }

    private fun handleFrame(frame: ByteArray, offset: Int, length: Int, output: EthernetEndpoint.Output) {
        when (endpoint.handleFromPhone(frame, offset, length, output)) {
            EthernetEndpoint.Inbound.ARP_REPLIED -> {
                if (arpReplies.incrementAndGet() == 1L) {
                    milestone(Milestone.FIRST_ARP_REPLY, endpoint.phoneAddress?.let { Ipv4.format(it) } ?: "")
                }
            }
            EthernetEndpoint.Inbound.ARP_FOR_OTHER_ADDRESS -> {
                if (arpForOtherAddress.incrementAndGet() == 1L) {
                    val target = frame.copyOfRange(
                        offset + Ethernet.HEADER_LENGTH + 24,
                        offset + Ethernet.HEADER_LENGTH + 28,
                    )
                    milestone(Milestone.ARP_FOR_OTHER_ADDRESS, Ipv4.format(target))
                }
            }
            EthernetEndpoint.Inbound.DELIVERED, EthernetEndpoint.Inbound.ARP_IGNORED -> Unit
            EthernetEndpoint.Inbound.NOT_FOR_US,
            EthernetEndpoint.Inbound.UNSUPPORTED_TYPE,
            EthernetEndpoint.Inbound.MALFORMED,
            -> droppedFromPhone.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------- 车机 → 手机

    private fun transmitLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
            // 同上。
        }
        val layout = port.outLayout
        val maxBlock = port.maxOutBlockLength
        val maxBatch = if (maxBlock >= MIN_BATCH_BLOCK_BYTES) port.maxOutDatagrams.coerceIn(1, MAX_TX_BATCH) else 1
        if (maxBatch == 1) {
            transmitSingleLoop(layout)
            return
        }
        val firstOffset = layout.firstDatagramOffset(maxBatch)
        val buffer = ByteArray(maxOf(maxBlock, firstOffset + Ethernet.HEADER_LENGTH + MAX_PACKET) + 16)
        val offsets = IntArray(maxBatch)
        val lengths = IntArray(maxBatch)
        try {
            while (running.get()) {
                val packetOffset = firstOffset + Ethernet.HEADER_LENGTH
                val n = tunnel.read(buffer, packetOffset, MAX_PACKET)
                if (n < 0) {
                    linkDown(LinkDown.TUNNEL_CLOSED)
                    return
                }
                if (n == 0) continue
                if (endpoint.wrapForPhone(buffer, packetOffset, n) != EthernetEndpoint.Outbound.WRAPPED) {
                    droppedToPhone.incrementAndGet()
                    continue
                }
                val firstLen = endpoint.frameLength(buffer, packetOffset)
                var end = firstOffset + maxOf(firstLen, Ntb16.MIN_DATAGRAM_LENGTH)
                if (paddedBlockLength(end, port.zlpPacketSize) > maxBlock) {
                    droppedToPhone.incrementAndGet()
                    continue
                }
                offsets[0] = firstOffset
                lengths[0] = firstLen
                var count = 1
                var totalBytes = firstLen.toLong()
                var tunnelClosedAfterBatch = false

                // 零等待合并：仅顺带取出内核队列中已经就绪的后续 IP 包一起打进同一块 NTB16。
                while (count < maxBatch && running.get()) {
                    val nextOff = layout.alignDatagram(end)
                    val maxRoom = maxBlock - nextOff - Ethernet.HEADER_LENGTH - BLOCK_TAIL_MARGIN
                    if (maxRoom < MIN_COALESCE_PACKET_ROOM) break
                    val nextPacketOffset = nextOff + Ethernet.HEADER_LENGTH
                    val ni = tunnel.tryRead(buffer, nextPacketOffset, minOf(MAX_PACKET, maxRoom))
                    if (ni < 0) {
                        tunnelClosedAfterBatch = true
                        break
                    }
                    if (ni == 0) break
                    if (endpoint.wrapForPhone(buffer, nextPacketOffset, ni) != EthernetEndpoint.Outbound.WRAPPED) {
                        droppedToPhone.incrementAndGet()
                        continue
                    }
                    val lenI = endpoint.frameLength(buffer, nextPacketOffset)
                    val nextEnd = nextOff + maxOf(lenI, Ntb16.MIN_DATAGRAM_LENGTH)
                    if (paddedBlockLength(nextEnd, port.zlpPacketSize) > maxBlock) {
                        droppedToPhone.incrementAndGet()
                        break
                    }
                    offsets[count] = nextOff
                    lengths[count] = lenI
                    count++
                    totalBytes += lenI
                    end = nextEnd
                }
                if (sendBatch(buffer, offsets, lengths, count)) {
                    bytesToPhone.addAndGet(totalBytes)
                    val prev = packetsToPhone.getAndAdd(count.toLong())
                    if (prev == 0L) {
                        milestone(Milestone.FIRST_PACKET_TO_PHONE, "$firstLen 字节")
                    }
                }
                if (tunnelClosedAfterBatch) {
                    linkDown(LinkDown.TUNNEL_CLOSED)
                    return
                }
            }
        } catch (e: RuntimeException) {
            linkDown(LinkDown.INTERNAL_ERROR)
        }
    }

    private fun transmitSingleLoop(layout: NtbOutLayout) {
        val packetOffset = layout.datagramOffset + Ethernet.HEADER_LENGTH
        val buffer = ByteArray(Ntb16.blockLength(layout, Ethernet.HEADER_LENGTH + MAX_PACKET, port.zlpPacketSize))
        try {
            while (running.get()) {
                val n = tunnel.read(buffer, packetOffset, MAX_PACKET)
                if (n < 0) {
                    linkDown(LinkDown.TUNNEL_CLOSED)
                    return
                }
                if (n == 0) continue
                when (endpoint.wrapForPhone(buffer, packetOffset, n)) {
                    EthernetEndpoint.Outbound.WRAPPED -> {
                        val frameLength = endpoint.frameLength(buffer, packetOffset)
                        if (sendFrame(buffer, frameLength)) {
                            bytesToPhone.addAndGet(frameLength.toLong())
                            if (packetsToPhone.incrementAndGet() == 1L) {
                                milestone(Milestone.FIRST_PACKET_TO_PHONE, "$frameLength 字节")
                            }
                        }
                    }
                    EthernetEndpoint.Outbound.NO_PHONE_YET, EthernetEndpoint.Outbound.DROPPED ->
                        droppedToPhone.incrementAndGet()
                }
            }
        } catch (e: RuntimeException) {
            linkDown(LinkDown.INTERNAL_ERROR)
        }
    }

    private fun paddedBlockLength(rawEnd: Int, zlpPacketSize: Int): Int {
        var block = rawEnd + ((4 - (rawEnd and 3)) and 3)
        if (zlpPacketSize > 0 && block % zlpPacketSize == 0) block += 1
        return block
    }

    private fun sendBatch(buffer: ByteArray, offsets: IntArray, lengths: IntArray, count: Int): Boolean {
        val layout = port.outLayout
        val sent = synchronized(sendLock) {
            val block = Ntb16.frameBatchInPlace(
                buffer, layout, offsets, lengths, count, sequence.getAndIncrement(), port.zlpPacketSize,
            )
            port.send(buffer, block, timing.sendTimeoutMs)
        }
        if (!sent) usbSendFailures.incrementAndGet()
        return sent
    }

    /** 以太网帧已放在 `buffer[outLayout.datagramOffset, … + frameLength)`：组 NTB 并发出。 */
    private fun sendFrame(buffer: ByteArray, frameLength: Int): Boolean {
        val layout = port.outLayout
        val block = Ntb16.blockLength(layout, frameLength, port.zlpPacketSize)
        if (block > port.maxOutBlockLength || block > buffer.size) {
            droppedToPhone.incrementAndGet()
            return false
        }
        // 序号与发送放在同一把锁里，保证序号按实际发送顺序递增。
        val sent = synchronized(sendLock) {
            Ntb16.frameInPlace(buffer, layout, frameLength, sequence.getAndIncrement(), port.zlpPacketSize)
            port.send(buffer, block, timing.sendTimeoutMs)
        }
        if (!sent) usbSendFailures.incrementAndGet()
        return sent
    }

    private fun linkDown(reason: LinkDown) {
        if (running.compareAndSet(true, false)) {
            tunnel.wakeup()
            rxPending.offer(rxEndSentinel)
            rxFree.offer(rxEndSentinel)
            try {
                listener.onLinkDown(reason)
            } catch (e: RuntimeException) {
                // 同上：异常不能带出搬运线程。
            }
        }
    }

    private fun milestone(milestone: Milestone, detail: String) {
        // 每种只报一次：用位图记已报过的。
        val bit = 1 shl milestone.ordinal
        while (true) {
            val current = reported.get()
            if (current and bit != 0) return
            if (reported.compareAndSet(current, current or bit)) break
        }
        try {
            listener.onMilestone(milestone, detail)
        } catch (e: RuntimeException) {
            // 日志回调出错不能影响搬运。
        }
    }

    companion object {
        /** 从 TUN 读一个包的上限。TUN 的 MTU 是 1500，留余量是为了不静默截断异常大的包，超过手机接收上限的会被丢掉。 */
        const val MAX_PACKET = 4096

        /** 收方向线程自己发出的帧（ARP 应答）的上限。 */
        private const val MAX_REPLY_FRAME = Ethernet.HEADER_LENGTH + Arp.LENGTH
        private const val RX_POOL_SIZE = 4
        private const val MAX_TX_BATCH = 8
        private const val MIN_BATCH_BLOCK_BYTES = 2048
        private const val MIN_COALESCE_PACKET_ROOM = 1500
        private const val BLOCK_TAIL_MARGIN = 5
    }
}
