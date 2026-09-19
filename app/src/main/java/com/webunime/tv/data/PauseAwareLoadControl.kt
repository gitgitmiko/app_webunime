package com.webunime.tv.data

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Subclass [DefaultLoadControl] (bukan LoadControl dari nol) — implementasi kustom
 * sebelumnya crash di TV karena method default LoadControl melempar IllegalStateException
 * + buffer 1080p terlalu besar (OOM).
 *
 * Saat pause: izinkan unduh lebih jauh (mirip YouTube), dengan cap RAM ketat.
 */
class PauseAwareLoadControl private constructor(
    allocator: DefaultAllocator,
    private val minBufferMs: Int,
    playingMaxBufferMs: Int,
    private val pausedMaxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    playingTargetBytes: Int,
    private val pausedTargetBytes: Int,
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    playingMaxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    playingTargetBytes,
    /* prioritizeTimeOverSizeThresholds */ true,
    /* backBufferDurationMs */ 0,
    /* retainBackBufferFromKeyframe */ false,
) {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (!parameters.playWhenReady) {
            val bufferedMs = parameters.bufferedDurationUs / 1000L
            if (bufferedMs >= pausedMaxBufferMs) return false
            if (allocator.totalBytesAllocated >= pausedTargetBytes && bufferedMs >= minBufferMs) {
                return false
            }
            return true
        }
        return super.shouldContinueLoading(parameters)
    }

    companion object {
        fun forWibufile(): PauseAwareLoadControl = PauseAwareLoadControl(
            allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            minBufferMs = 15_000,
            playingMaxBufferMs = 40_000,
            pausedMaxBufferMs = 90_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_500,
            playingTargetBytes = 16 * 1024 * 1024,
            pausedTargetBytes = 24 * 1024 * 1024,
        )

        fun forDefault(): PauseAwareLoadControl = PauseAwareLoadControl(
            allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            minBufferMs = 12_000,
            playingMaxBufferMs = 35_000,
            pausedMaxBufferMs = 75_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
            playingTargetBytes = 14 * 1024 * 1024,
            pausedTargetBytes = 20 * 1024 * 1024,
        )
    }
}
