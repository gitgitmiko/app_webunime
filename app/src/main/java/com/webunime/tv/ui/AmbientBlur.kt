package com.webunime.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Backdrop "ambient": poster di-downscale lalu di-blur (box blur 3x ≈ gaussian).
 *
 * Katalog hampir tidak punya gambar landscape — poster ~300px yang di-stretch
 * full-screen terlihat pecah/burem. Blur yang disengaja + poster tajam di depan
 * terlihat jauh lebih rapi (gaya Google TV / Apple TV).
 */
class AmbientBlur(
    private val targetWidth: Int = 96,
    private val radius: Int = 6,
) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val scale = targetWidth.toFloat() / max(1, toTransform.width)
        val w = targetWidth.coerceAtLeast(8)
        val h = (toTransform.height * scale).roundToInt().coerceAtLeast(8)
        val small = pool.get(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(toTransform, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlurH(px, tmp, w, h, radius)
            boxBlurV(tmp, px, w, h, radius)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID:$targetWidth:$radius".toByteArray())
    }

    override fun equals(other: Any?): Boolean =
        other is AmbientBlur && other.targetWidth == targetWidth && other.radius == radius

    override fun hashCode(): Int = ID.hashCode() * 31 + targetWidth * 7 + radius

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            val row = y * w
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (pIn ushr 24) - (pOut ushr 24)
                rr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                g += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                b += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (pIn ushr 24) - (pOut ushr 24)
                rr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                g += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                b += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private companion object {
        const val ID = "com.webunime.tv.ui.AmbientBlur.v1"
    }
}
