package com.webunime.tv.data

import androidx.media3.common.C
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Buffer seperti YouTube: saat pause, unduhan tetap jalan sampai buffer jauh di depan.
 * Saat play, batas lebih ketat agar RAM TV (terutama 1080p) aman.
 */
class PauseAwareLoadControl(
    private val minBufferMs: Int,
    private val playingMaxBufferMs: Int,
    private val pausedMaxBufferMs: Int,
    private val bufferForPlaybackMs: Int,
    private val bufferForPlaybackAfterRebufferMs: Int,
    private val playingTargetBytes: Int,
    private val pausedTargetBytes: Int,
) : LoadControl {

    private val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    override fun getAllocator(): Allocator = allocator

    override fun onReleased() {
        allocator.reset()
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val maxMs = if (parameters.playWhenReady) playingMaxBufferMs else pausedMaxBufferMs
        val targetBytes = if (parameters.playWhenReady) playingTargetBytes else pausedTargetBytes
        val bufferedMs = parameters.bufferedDurationUs / 1000L
        if (bufferedMs >= maxMs) return false
        val allocated = allocator.totalBytesAllocated
        // Cap RAM: kalau sudah penuh byte tapi buffer waktu masih di bawah min, tetap isi.
        if (allocated >= targetBytes && bufferedMs >= minBufferMs) return false
        return true
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val needMs = if (parameters.rebuffering) {
            bufferForPlaybackAfterRebufferMs
        } else {
            bufferForPlaybackMs
        }
        val bufferedMs = parameters.bufferedDurationUs / 1000L
        if (bufferedMs >= needMs) return true
        // Target byte tercapai + ada sedikit buffer → boleh start (hindari stuck di jaringan lambat).
        return allocator.totalBytesAllocated >= playingTargetBytes &&
            bufferedMs >= needMs / 2
    }

    companion object {
        fun forWibufile(): PauseAwareLoadControl = PauseAwareLoadControl(
            minBufferMs = 22_000,
            playingMaxBufferMs = 70_000,
            pausedMaxBufferMs = 240_000, // ~4 menit ke depan saat pause
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 4_500,
            playingTargetBytes = 24 * 1024 * 1024,
            pausedTargetBytes = 40 * 1024 * 1024,
        )

        fun forDefault(): PauseAwareLoadControl = PauseAwareLoadControl(
            minBufferMs = 12_000,
            playingMaxBufferMs = 45_000,
            pausedMaxBufferMs = 180_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
            playingTargetBytes = 18 * 1024 * 1024,
            pausedTargetBytes = 32 * 1024 * 1024,
        )
    }
}
