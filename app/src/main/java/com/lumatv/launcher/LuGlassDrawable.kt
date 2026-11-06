package com.lumatv.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.max

/**
 * luGlass 2.0 ULTRA — an Android 5+ wallpaper-aware glass surface.
 *
 * Only the launcher wallpaper is sampled. This is a cached *static* frost blur, not
 * a claim of system-wide real-time iOS/tvOS refraction. There are no WebViews,
 * GPU shaders, RenderEffect calls (API 31+), or background animation loops.
 */
internal class LuGlassDrawable(
    private val owner: View,
    private val corner: Float,
    private val selected: Boolean,
    private val accent: Int,
    private val blurredWallpaper: Bitmap?,
    private val solid: Boolean = false,
    private val transparency: Int = 65,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val location = IntArray(2)
    private var overallAlpha = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val width = b.width().toFloat()
        val height = b.height().toFloat()
        val rect = RectF(0f, 0f, width, height)
        val round = corner.coerceAtMost(minOf(width, height) / 2f)
        val clip = Path().apply { addRoundRect(rect, round, round, Path.Direction.CW) }

        val layer = canvas.save()
        canvas.clipPath(clip)
        if (!solid) drawFrostedWallpaper(canvas, rect)

        // Layered transparent tint: legible in front of bright photos but still clear.
        paint.resetForGlass()
        paint.shader = LinearGradient(0f, 0f, width, height,
            if (solid) intArrayOf(0xCB11283D.toInt(), 0xE50B142B.toInt())
            else if (selected) intArrayOf(0x63FFFFFF, 0x280F3455, 0x6205132A)
            else intArrayOf(0x42FFFFFF, 0x0F233A55, 0x37061428),
            null, Shader.TileMode.CLAMP)
        if (!solid) paint.alpha = (235 - transparency.coerceIn(0,100) * 1.45).toInt().coerceIn(70,235)
        canvas.drawRoundRect(rect, round, round, paint)

        // An edge-lit, elliptical reflective highlight at the top-left.
        if (!solid) {
            paint.resetForGlass()
            paint.shader = RadialGradient(width * .24f, -height * .28f,
                max(width, height) * .96f,
                intArrayOf(0x58FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, .40f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, round, round, paint)
        }

        if (selected) {
            paint.resetForGlass()
            paint.shader = RadialGradient(width * .5f, height * 1.12f,
                max(width, height) * .90f,
                intArrayOf((accent and 0x00FFFFFF) or 0x56000000, 0x00000000),
                null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, round, round, paint)
        }
        canvas.restoreToCount(layer)

        // Two separate strokes create a curved, refracting-glass rim.
        val outer = RectF(rect).apply { inset(1.3f, 1.3f) }
        paint.resetForGlass()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) 3.1f else 1.8f
        paint.shader = LinearGradient(0f, 0f, width, height,
            if (selected) intArrayOf(0xFFFFFFFF.toInt(), accent, 0xD4FFFFFF.toInt(), accent)
            else intArrayOf(0xF1FFFFFF.toInt(), 0x77D1EFFF, 0x2AFFFFFF, 0xADFFFFFF.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, round - 1f, round - 1f, paint)

        if (width > 28f && height > 28f) {
            val inner = RectF(rect).apply { inset(4.6f, 4.6f) }
            paint.resetForGlass()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f
            paint.color = if (selected) 0x64FFFFFF else 0x2AFFFFFF
            canvas.drawRoundRect(inner, (round - 3f).coerceAtLeast(2f),
                (round - 3f).coerceAtLeast(2f), paint)
        }
        // The bevel receives a narrow white streak like real edge lighting.
        val streak = Path().apply {
            moveTo(round * .7f, 3.7f)
            quadTo(width * .25f, 1.3f, width * .56f, 3.3f)
        }
        paint.resetForGlass()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.color = if (selected) 0xD6FFFFFF.toInt() else 0x88FFFFFF.toInt()
        canvas.drawPath(streak, paint)
    }

    private fun drawFrostedWallpaper(canvas: Canvas, rect: RectF) {
        val blurred = blurredWallpaper ?: return
        if (blurred.isRecycled) return
        val root = owner.rootView
        val screenWidth = root.width.takeIf { it > 0 } ?: owner.resources.displayMetrics.widthPixels
        val screenHeight = root.height.takeIf { it > 0 } ?: owner.resources.displayMetrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) return
        owner.getLocationInWindow(location)
        // Match ImageView.ScaleType.CENTER_CROP, even on non-16:9 TV displays.
        val imageScale = max(screenWidth.toFloat() / blurred.width, screenHeight.toFloat() / blurred.height)
        val srcVisibleW = screenWidth / imageScale
        val srcVisibleH = screenHeight / imageScale
        val leftCrop = (blurred.width - srcVisibleW) * .5f
        val topCrop = (blurred.height - srcVisibleH) * .5f
        val x0 = leftCrop + location[0] / imageScale
        val y0 = topCrop + location[1] / imageScale
        val x1 = leftCrop + (location[0] + rect.width()) / imageScale
        val y1 = topCrop + (location[1] + rect.height()) / imageScale
        val src = Rect(x0.toInt().coerceIn(0, blurred.width - 1),
            y0.toInt().coerceIn(0, blurred.height - 1),
            x1.toInt().coerceIn(1, blurred.width),
            y1.toInt().coerceIn(1, blurred.height))
        if (src.width() <= 0 || src.height() <= 0) return
        paint.resetForGlass()
        paint.alpha = (if (selected) 225 else 210) - transparency.coerceIn(0,100) * 2
        canvas.drawBitmap(blurred, src, rect, paint)
    }

    private fun Paint.resetForGlass() {
        reset()
        isAntiAlias = true
        isFilterBitmap = true
        alpha = overallAlpha
    }

    override fun setAlpha(alpha: Int) { overallAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
