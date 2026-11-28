package io.github.gcbond.openhc.input

import io.github.gcbond.openhc.core.hisight.HiSightInput
import java.util.ArrayDeque

/**
 * 触摸移动事件合并器：在主线程高频（120～240Hz）派发触摸而协议线程或无线发送队列短暂卡顿期间，
 * 将队列尾部连续且触点集合完全一致的 [HiSightInput.Touch.Action.MOVE] 原地替换为最新坐标，
 * 避免无意义的历史中间点把回控队列打爆触发断连。
 *
 * 严格保留：
 * - 所有 [HiSightInput.Touch.Action.DOWN] / [HiSightInput.Touch.Action.UP]；
 * - 所有按键 [HiSightInput.Key] 与文本 [HiSightInput.Text]；
 * - 触点数量或触点 ID 集合发生变化的 [HiSightInput.Touch.Action.MOVE]（多指按压增减的边界事件）。
 */
internal class TouchInputCoalescer {
    private val pending = ArrayDeque<HiSightInput>()
    private var scheduled = false

    /**
     * 入队一条输入事件。返回 true 表示调用方需要向协议线程投递一次 [drain]。
     */
    @Synchronized
    fun offer(input: HiSightInput): Boolean {
        if (input is HiSightInput.Touch && input.action == HiSightInput.Touch.Action.MOVE) {
            val tail = pending.peekLast()
            if (tail is HiSightInput.Touch &&
                tail.action == HiSightInput.Touch.Action.MOVE &&
                samePointerSet(tail, input)
            ) {
                pending.removeLast()
                pending.addLast(input)
                return false
            }
        }
        pending.addLast(input)
        if (scheduled) return false
        scheduled = true
        return true
    }

    /**
     * 在协议线程上取出当前已合并的全部待发事件并逐条交给 [consume]。
     */
    fun drain(consume: (HiSightInput) -> Unit) {
        while (true) {
            val single: HiSightInput?
            val batch: ArrayList<HiSightInput>?
            synchronized(this) {
                when (pending.size) {
                    0 -> {
                        scheduled = false
                        return
                    }
                    1 -> {
                        single = pending.removeFirst()
                        batch = null
                    }
                    else -> {
                        single = null
                        batch = ArrayList<HiSightInput>(pending.size).also { list ->
                            while (pending.isNotEmpty()) list.add(pending.removeFirst())
                        }
                    }
                }
            }
            if (single != null) {
                consume(single)
            } else {
                for (item in batch!!) consume(item)
            }
        }
    }

    private companion object {
        fun samePointerSet(a: HiSightInput.Touch, b: HiSightInput.Touch): Boolean {
            if (a.points.size != b.points.size || a.width != b.width || a.height != b.height) return false
            var maskA = 0
            for (p in a.points) maskA = maskA or (1 shl (p.id and 31))
            var maskB = 0
            for (p in b.points) maskB = maskB or (1 shl (p.id and 31))
            return maskA == maskB
        }
    }
}
