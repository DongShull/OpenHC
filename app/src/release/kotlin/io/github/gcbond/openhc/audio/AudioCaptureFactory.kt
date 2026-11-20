package io.github.gcbond.openhc.audio

import android.content.Context

/** 发布构建不抓包。 */
internal object AudioCaptureFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, attemptId: Int): AudioCapture = AudioCapture.None
}
