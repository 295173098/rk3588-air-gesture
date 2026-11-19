package com.rk3588.gesturelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameDetection 解析回归: 保证 JNI 输出数组 → 公开结果对象的字段映射正确。
 * 纯 Kotlin, 不需要真机 / NPU。
 */
class FrameDetectionTest {

    @Test
    fun `parses palm frame output`() {
        val out = FloatArray(74)
        out[RknnBridge.OUT_MS] = 18.5f
        out[RknnBridge.OUT_GESTURE] = GestureId.PALM.toFloat()
        out[RknnBridge.OUT_SCORE] = 0.93f
        out[RknnBridge.OUT_HAND] = 0.81f
        out[RknnBridge.OUT_PALM_VALID] = 1f
        out[RknnBridge.OUT_PALM_X] = 0.51f
        out[RknnBridge.OUT_PALM_Y] = 0.42f
        out[RknnBridge.OUT_PALM_W] = 0.20f
        out[RknnBridge.OUT_PALM_H] = 0.25f
        out[RknnBridge.OUT_OPEN] = 5f
        out[RknnBridge.OUT_LM] = 0.11f
        out[RknnBridge.OUT_LM + 1] = 0.22f
        out[RknnBridge.OUT_LM + 2] = -0.03f

        val d = FrameDetection.from(out)

        assertTrue(d.hasHand)
        assertEquals(GestureId.PALM, d.gesture)
        assertEquals("✋ 张掌", d.gestureName)
        assertEquals(0.93f, d.score, 1e-6f)
        assertEquals(0.81f, d.handedness, 1e-6f)
        assertEquals(5, d.openFingers)
        assertEquals(0.51f, d.palmX, 1e-6f)
        assertEquals(0.42f, d.palmY, 1e-6f)
        assertEquals(0.20f, d.palmW, 1e-6f)
        assertEquals(0.25f, d.palmH, 1e-6f)
        assertEquals(18.5f, d.inferenceMs, 1e-6f)
        assertEquals(63, d.landmarks.size)
        assertEquals(0.11f, d.landmark(0)[0], 1e-6f)
        assertEquals(0.22f, d.landmark(0)[1], 1e-6f)
        assertEquals(-0.03f, d.landmark(0)[2], 1e-6f)
    }

    @Test
    fun `empty frame reports no hand`() {
        val d = FrameDetection.from(FloatArray(74))

        assertFalse(d.hasHand)
        assertEquals(GestureId.NONE, d.gesture)
        assertEquals("无", d.gestureName)
        assertEquals(0, d.openFingers)
        assertEquals(63, d.landmarks.size)
    }

    @Test
    fun `palm box alone counts as hand even when landmark score is low`() {
        val out = FloatArray(74)
        out[RknnBridge.OUT_PALM_VALID] = 1f
        out[RknnBridge.OUT_SCORE] = 0.2f
        out[RknnBridge.OUT_GESTURE] = GestureId.NONE.toFloat()

        val d = FrameDetection.from(out)

        assertTrue(d.hasHand)
        assertEquals(GestureId.NONE, d.gesture)
        assertEquals("无", d.gestureName)
    }
}
