package it.paolo.passwordsafe

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class SafeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opening = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height) * .78f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val safe = RectF(left, top, left + size, top + size)
        paint.color = Color.rgb(30, 28, 91)
        canvas.drawRoundRect(safe, size * .12f, size * .12f, paint)
        paint.color = Color.rgb(14, 13, 55)
        canvas.drawRoundRect(RectF(left + size*.07f, top + size*.08f, left + size*.93f, top + size*.92f), size*.08f, size*.08f, paint)
        if (opening > .08f) {
            paint.color = Color.argb((210 * opening).toInt(), 255, 205, 63)
            canvas.drawRoundRect(RectF(left + size*.13f, top + size*.14f, left + size*.87f, top + size*.86f), size*.05f, size*.05f, paint)
        }
        canvas.save()
        canvas.translate(-size*.58f*opening, 0f)
        val door = RectF(left + size*.10f, top + size*.11f, left + size*.90f, top + size*.89f)
        paint.color = Color.rgb(79, 68, 190)
        canvas.drawRoundRect(door, size*.07f, size*.07f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size*.035f
        paint.color = Color.rgb(139, 124, 239)
        canvas.drawRoundRect(RectF(door.left+size*.05f, door.top+size*.05f, door.right-size*.05f, door.bottom-size*.05f), size*.045f, size*.045f, paint)
        paint.style = Paint.Style.FILL
        val cx = door.centerX() + size*.10f
        val cy = door.centerY()
        paint.color = Color.rgb(232, 190, 62)
        canvas.drawCircle(cx, cy, size*.16f, paint)
        paint.color = Color.rgb(42, 36, 112)
        canvas.drawCircle(cx, cy, size*.105f, paint)
        paint.color = Color.rgb(232, 190, 62)
        paint.strokeWidth = size*.035f
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            val r = Math.toRadians(angle.toDouble())
            canvas.drawLine(cx, cy, cx + kotlin.math.cos(r).toFloat()*size*.15f, cy + kotlin.math.sin(r).toFloat()*size*.15f, paint)
        }
        canvas.restore()
    }

    fun open(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { opening = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }
}
