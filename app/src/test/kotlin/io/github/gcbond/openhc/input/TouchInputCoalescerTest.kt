package io.github.gcbond.openhc.input

import io.github.gcbond.openhc.core.hisight.HiSightInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputCoalescerTest {

    private fun move(vararg pts: Pair<Int, Int>): HiSightInput.Touch = HiSightInput.Touch(
        action = HiSightInput.Touch.Action.MOVE,
        points = pts.map { (id, x) -> HiSightInput.Point(id, x, 100) },
        width = 1920,
        height = 1080,
    )

    private fun down(id: Int, x: Int): HiSightInput.Touch = HiSightInput.Touch(
        action = HiSightInput.Touch.Action.DOWN,
        points = listOf(HiSightInput.Point(id, x, 100)),
        width = 1920,
        height = 1080,
    )

    private fun up(id: Int, x: Int): HiSightInput.Touch = HiSightInput.Touch(
        action = HiSightInput.Touch.Action.UP,
        points = listOf(HiSightInput.Point(id, x, 100)),
        width = 1920,
        height = 1080,
    )

    @Test
    fun coalescesConsecutiveSamePointerMoveEvents() {
        val coalescer = TouchInputCoalescer()
        assertTrue(coalescer.offer(down(0, 10)))
        assertFalse(coalescer.offer(move(0 to 20)))
        assertFalse(coalescer.offer(move(0 to 30)))
        assertFalse(coalescer.offer(move(0 to 40)))
        assertFalse(coalescer.offer(up(0, 50)))

        val drained = mutableListOf<HiSightInput>()
        coalescer.drain { drained += it }

        assertEquals(3, drained.size)
        assertEquals(HiSightInput.Touch.Action.DOWN, (drained[0] as HiSightInput.Touch).action)
        val mergedMove = drained[1] as HiSightInput.Touch
        assertEquals(HiSightInput.Touch.Action.MOVE, mergedMove.action)
        assertEquals(40, mergedMove.points.single().x)
        assertEquals(HiSightInput.Touch.Action.UP, (drained[2] as HiSightInput.Touch).action)
    }

    @Test
    fun preservesPointerSetTransitionsAndNonTouchEvents() {
        val coalescer = TouchInputCoalescer()
        assertTrue(coalescer.offer(move(0 to 10)))
        assertFalse(coalescer.offer(move(0 to 20)))
        // 第二根手指加入：触点集合变化，不能和单指 MOVE 合并。
        assertFalse(coalescer.offer(move(0 to 25, 1 to 200)))
        assertFalse(coalescer.offer(move(0 to 30, 1 to 210)))
        // 第二根手指抬起：触点集合再次变化。
        assertFalse(coalescer.offer(move(0 to 35)))
        // 按键事件穿插其中，不能跨越合并。
        val key = HiSightInput.Key(HiSightInput.Key.Action.DOWN, 4, 0)
        assertFalse(coalescer.offer(key))
        assertFalse(coalescer.offer(move(0 to 45)))

        val drained = mutableListOf<HiSightInput>()
        coalescer.drain { drained += it }

        assertEquals(5, drained.size)
        assertEquals(20, (drained[0] as HiSightInput.Touch).points.single().x)
        assertEquals(2, (drained[1] as HiSightInput.Touch).points.size)
        assertEquals(30, (drained[1] as HiSightInput.Touch).points[0].x)
        assertEquals(35, (drained[2] as HiSightInput.Touch).points.single().x)
        assertEquals(key, drained[3])
        assertEquals(45, (drained[4] as HiSightInput.Touch).points.single().x)

        // drain 完成后再入队会再次返回 true 请求调度。
        assertTrue(coalescer.offer(move(0 to 60)))
    }
}
